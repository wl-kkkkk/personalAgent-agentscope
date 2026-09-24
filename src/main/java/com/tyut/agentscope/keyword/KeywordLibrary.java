package com.tyut.agentscope.keyword;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>关键词词表</h2>
 *
 * <p>每个用户一张词表，是全系统里"知识库覆盖了哪些主题"的权威副本：查询侧用它做意图识别，
 * 入库侧用它判断新词还是旧词。
 *
 * <p><b>为什么要缓存</b>：查询侧每次提问都要读一次，直接打数据库没必要。这里用 60 秒 TTL
 * 的本地缓存顶着；写入时立即失效，所以读到的不会比写入旧超过一次 TTL。
 * 单实例部署下这样够用，多实例要换成 Redis（键 {@code keyword:{userId}}）。
 *
 * <p><b>边界处理</b>：userId 为空直接返回空词表；读库异常只记日志并降级成空词表——
 * 意图识别少了词表还能按问题本身判断，不该因为词表故障把整轮对话打断。
 */
@Service
public class KeywordLibrary {

    private static final Logger log = LoggerFactory.getLogger(KeywordLibrary.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final KeywordRepository repository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public KeywordLibrary(KeywordRepository repository) {
        this.repository = repository;
    }

    /** 该用户的词表；无词表或读库失败都返回空列表，不返回 null */
    public List<String> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        CacheEntry cached = cache.get(userId);
        if (cached != null && !cached.expired()) {
            return cached.keywords();
        }
        try {
            List<String> keywords = List.copyOf(repository.listByUser(userId));
            cache.put(userId, new CacheEntry(keywords, System.currentTimeMillis() + TTL.toMillis()));
            return keywords;
        } catch (Exception e) {
            log.warn("读取关键词词表失败，本轮按空词表处理: userId={}, error={}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 批量写入词表（归一化去重、已存在的忽略）。
     *
     * @return 真正新增的那部分；入参为空时返回空列表
     */
    public List<String> addAll(String userId, Collection<String> keywords, String source) {
        List<String> cleaned = Keywords.sanitize(keywords);
        if (userId == null || userId.isBlank() || cleaned.isEmpty()) {
            return List.of();
        }
        String from = (source == null || source.isBlank()) ? "manual" : source;
        List<String> added = new ArrayList<>();
        try {
            for (String keyword : cleaned) {
                String normalized = Keywords.normalize(keyword);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (repository.insertIgnore(userId, keyword, normalized, from) > 0) {
                    added.add(keyword);
                }
            }
            if (!added.isEmpty()) {
                log.info("词表新增 {} 个关键词: userId={}, source={}, keywords={}", added.size(), userId, from, added);
            }
        } catch (Exception e) {
            log.error("写入关键词词表失败: userId={}, keywords={}", userId, cleaned, e);
            throw new IllegalStateException("写入关键词词表失败: " + e.getMessage(), e);
        } finally {
            // 无论成功与否都清掉缓存：部分成功时缓存的旧值已经不准了
            invalidate(userId);
        }
        return List.copyOf(added);
    }

    /** 按归一化删词；返回是否真的删掉了 */
    public boolean delete(String userId, String keyword) {
        String normalized = Keywords.normalize(keyword);
        if (userId == null || userId.isBlank() || normalized.isEmpty()) {
            return false;
        }
        try {
            return repository.deleteByNormalized(userId, normalized) > 0;
        } finally {
            invalidate(userId);
        }
    }

    public void invalidate(String userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    private record CacheEntry(List<String> keywords, long expiresAt) {
        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}

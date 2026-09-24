package com.tyut.agentscope.web;

import com.tyut.agentscope.common.ApiResponse;
import com.tyut.agentscope.keyword.KeywordLibrary;
import com.tyut.agentscope.user.model.LoginUserVO;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <h2>关键词词表接口</h2>
 *
 * <p>词表是意图识别的输入：查询侧拿它做"问题落在哪个已有主题上"的匹配，入库侧拿它判断
 * "这个词是不是新的"。这个接口让用户能看见、也能手工维护自己的词表——不然自动沉淀之前，
 * 词表永远是空的，意图识别也就退化成只看问题本身。
 *
 * <p>用户身份一律从登录态取，接口不接受 userId 参数：否则任何人都能读写别人的词表。
 */
@RestController
@RequestMapping("/api/keywords")
public class KeywordController {

    private static final Logger log = LoggerFactory.getLogger(KeywordController.class);

    private final KeywordLibrary keywordLibrary;
    private final AuthService authService;

    public KeywordController(KeywordLibrary keywordLibrary, AuthService authService) {
        this.keywordLibrary = keywordLibrary;
        this.authService = authService;
    }

    /** 当前用户的词表 */
    @GetMapping
    public ApiResponse<List<String>> list() {
        try {
            LoginUserVO user = authService.currentUser();
            return ApiResponse.ok(keywordLibrary.list(user.userId()));
        } catch (Exception e) {
            log.error("查询词表失败", e);
            return ApiResponse.fail("查询词表失败：" + e.getMessage());
        }
    }

    /** 新增关键词；已存在的会被忽略，返回真正新增的那部分 */
    @PostMapping
    public ApiResponse<List<String>> add(@RequestBody KeywordRequest request) {
        try {
            LoginUserVO user = authService.currentUser();
            if (request == null || request.keywords() == null || request.keywords().isEmpty()) {
                return ApiResponse.fail("keywords 不能为空");
            }
            List<String> added = keywordLibrary.addAll(user.userId(), request.keywords(), "manual");
            return ApiResponse.ok(added);
        } catch (IllegalArgumentException e) {
            log.warn("新增关键词参数不合法: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("新增关键词失败", e);
            return ApiResponse.fail("新增关键词失败：" + e.getMessage());
        }
    }

    /** 删除关键词（按归一化匹配，大小写/全半角不同也能删掉） */
    @DeleteMapping
    public ApiResponse<Boolean> delete(@RequestParam("keyword") String keyword) {
        try {
            LoginUserVO user = authService.currentUser();
            return ApiResponse.ok(keywordLibrary.delete(user.userId(), keyword));
        } catch (Exception e) {
            log.error("删除关键词失败", e);
            return ApiResponse.fail("删除关键词失败：" + e.getMessage());
        }
    }

    /** 词表写入请求体 */
    public record KeywordRequest(List<String> keywords) {
    }
}

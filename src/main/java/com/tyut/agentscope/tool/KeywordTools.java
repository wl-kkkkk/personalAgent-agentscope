package com.tyut.agentscope.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.common.PromptLoader;
import com.tyut.agentscope.common.StructuredModelCaller;
import com.tyut.agentscope.keyword.KeywordExtraction;
import com.tyut.agentscope.keyword.KeywordLibrary;
import com.tyut.agentscope.keyword.KeywordSuggestion;
import com.tyut.agentscope.keyword.Keywords;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <h2>关键词提炼工具</h2>
 *
 * <p>{@code extract_keywords} 把"提炼"和"对比"一次做完：从文档正文里提炼候选词，再和该用户的
 * 关键词词表比一遍，直接告诉模型哪些是已有的、哪些是新的。让模型自己做集合运算是不现实的
 * （它会算错，也会自己造词），所以集合运算留在服务端。
 *
 * <p><b>只提议、不落库</b>：这个词表是全用户共享的长期资产，写进去必须有人点过头。
 * 所以这里只返回候选，真正入库发生在人工审批通过之后（见 {@code HitlService}）。
 *
 * <p>用主对话模型而不是轻量模型：提炼结果要沉淀成长期词表，质量比省那点 token 重要；
 * 而且一次上传只会跑一次。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>正文为空：直接返回空结果，不调模型；</li>
 *   <li>模型没返回可用 JSON、或返回的词全被清洗掉：返回空结果并带上说明，不打断上传流程；</li>
 *   <li>词表读不到（无 userId / 读库失败）：按空词表处理，全部算新增，由用户审批时决定。</li>
 * </ul>
 */
@Component
public class KeywordTools {

    private static final Logger log = LoggerFactory.getLogger(KeywordTools.class);
    private static final String PROMPT_PATH = "prompts/keyword-extract.st";

    /** 上下文里"当前用户 id"的 key，取词表要用 */
    public static final String USER_ID_KEY = "userId";

    private final Model chatModel;
    private final PromptLoader promptLoader;
    private final StructuredModelCaller structuredModelCaller;
    private final KeywordLibrary keywordLibrary;
    private final ObjectMapper objectMapper;

    public KeywordTools(@Qualifier("chatModel") Model chatModel,
                        PromptLoader promptLoader,
                        StructuredModelCaller structuredModelCaller,
                        KeywordLibrary keywordLibrary,
                        ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.promptLoader = promptLoader;
        this.structuredModelCaller = structuredModelCaller;
        this.keywordLibrary = keywordLibrary;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "extract_keywords",
            description = "从文档正文提炼 2-4 个候选关键词，并与该用户已有的关键词词表对比，"
                    + "返回 JSON：{candidates 候选词, existing 词表里已有的, new 需要新增的, "
                    + "keywordsText 顿号分隔串}。准备沉淀文档时先调它，再把 keywordsText 传给 "
                    + "write_markdown 和 upload_document；词表最终是否新增由用户在审批时决定")
    public String extractKeywords(
            @ToolParam(name = "text", description = "待提炼的文档正文（Markdown 全文）") String text,
            ToolExecutionContext context,
            ToolEmitter emitter) {
        if (text == null || text.isBlank()) {
            log.warn("关键词提炼被跳过：文本为空");
            return empty("文本为空，没有可提炼的内容");
        }
        String userId = context == null ? null : context.get(USER_ID_KEY, String.class);
        List<String> existing = keywordLibrary.list(userId);
        emitter.emit(ToolResultBlock.text("正在提炼关键词（现有词表 " + existing.size() + " 个词）"));

        String prompt = promptLoader.load(PROMPT_PATH, Map.of(
                "existingKeywords", Keywords.joinForPrompt(existing),
                "text", text));
        Optional<KeywordExtraction> parsed =
                structuredModelCaller.call(chatModel, prompt, KeywordExtraction.class);

        List<String> candidates = parsed
                .map(extraction -> Keywords.sanitize(extraction.keywords()))
                .orElse(List.of());
        if (candidates.isEmpty()) {
            log.warn("关键词提炼没有拿到可用结果: userId={}", userId);
            return empty("模型没有返回可用关键词；可以继续上传，词表不会被改动");
        }

        KeywordSuggestion suggestion = KeywordSuggestion.of(candidates, existing);
        log.info("关键词提炼完成: userId={}, 候选={}, 已有={}, 新增={}",
                userId, suggestion.candidates(), suggestion.existing(), suggestion.added());
        emitter.emit(ToolResultBlock.text("候选关键词：" + suggestion.keywordsText()
                + (suggestion.added().isEmpty() ? "（词表里都已有）" : "（新增 " + suggestion.added().size() + " 个）")));
        return toJson(suggestion);
    }

    private String empty(String note) {
        return "{\"candidates\":[],\"existing\":[],\"new\":[],\"keywordsText\":\"\",\"note\":\""
                + note + "\"}";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("关键词结果序列化失败: {}", value, e);
            return empty("结果序列化失败");
        }
    }
}

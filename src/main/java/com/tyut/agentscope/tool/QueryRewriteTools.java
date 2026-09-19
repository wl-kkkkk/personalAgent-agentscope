package com.tyut.agentscope.tool;

import com.tyut.agentscope.common.ModelCaller;
import com.tyut.agentscope.common.PromptLoader;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * <h2>查询改写工具</h2>
 * <p>
 * 结合历史对话把用户的口语化问题改写成更适合检索的查询语句，供知识库检索与联网检索共用。
 * 模型与 {@code answerByPersonalKnowledge} 一致，走 DashScope。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>原始查询为空：直接返回空串，不调用模型；</li>
 *   <li>历史对话为空：仍执行改写，只在提示词里说明"无历史对话"；</li>
 *   <li>模型返回为空：回退为原始查询，保证调用方拿到的永远是可用的查询文本。</li>
 * </ul>
 */
@Component
public class QueryRewriteTools {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteTools.class);

    private final Model queryRewriteModel;
    private final ModelCaller modelCaller;
    private final PromptLoader promptLoader;

    public QueryRewriteTools(@Qualifier("queryRewriteModel") Model queryRewriteModel,
                             ModelCaller modelCaller,
                             PromptLoader promptLoader) {
        this.queryRewriteModel = queryRewriteModel;
        this.modelCaller = modelCaller;
        this.promptLoader = promptLoader;
    }

    @Tool(name = "rewrite_query",
            description = "结合历史对话把用户的问题改写成更适合检索的查询语句，返回改写后的查询文本")
    public String rewriteQuery(
            @ToolParam(name = "query", description = "用户的原始问题") String query,
            @ToolParam(name = "history", required = false, description = "最近的对话历史，没有就留空") String history) {
        if (query == null || query.isBlank()) {
            log.warn("查询改写被跳过：原始查询为空");
            return "";
        }
        String chatMemory = (history == null || history.isBlank()) ? "（无历史对话）" : history;
        String prompt = promptLoader.load("prompts/query-rewrite.st",
                Map.of("chatMemory", chatMemory, "query", query));

        String rewritten = modelCaller.call(queryRewriteModel, prompt);
        if (rewritten.isBlank()) {
            log.warn("查询改写结果为空，回退原始查询: {}", query);
            return query;
        }
        log.info("查询改写完成: 「{}」 -> 「{}」", query, rewritten);
        return rewritten;
    }
}

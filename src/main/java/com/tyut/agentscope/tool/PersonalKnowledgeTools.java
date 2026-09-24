package com.tyut.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>个人知识库查询工具（MCP 包装）</h2>
 *
 * <p>底层调 RAG 暴露的 MCP 工具 {@code answerByPersonalKnowledge(nickname, query)}，
 * 它内部完成改写、双路召回、Rerank 与生成。
 *
 * <p><b>为什么要在本地再包一层</b>：MCP 工具是远端注册进来的，没法给它加参数，
 * 也就用不了框架的 {@link ToolEmitter}——而这个调用恰恰是整条链路里最慢的一步（RAG 检索 + 生成）。
 * 包一层之后就能在发起检索前推一条"正在检索个人知识库"，用户不用干看着转圈。
 * 注册时会用 {@code toolkit.removeTool} 把 MCP 直连的那个摘掉，避免模型看到两个功能相同的工具、
 * 随机挑一个（挑中直连的就又没有进度了）。
 *
 * <p>昵称从工具上下文取，不指望模型传对：模型没有"当前用户是谁"的概念，让它传只会传错或瞎编。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>问题为空、上下文里拿不到昵称：直接拒绝，不发起 MCP 调用；</li>
 *   <li>RAG 返回 error 或空结果：返回可读的失败原因；</li>
 *   <li>超时或抛异常：捕获后记录日志并返回失败原因，不让异常冒泡打断 agent 循环。</li>
 * </ul>
 */
@Component
public class PersonalKnowledgeTools {

    private static final Logger log = LoggerFactory.getLogger(PersonalKnowledgeTools.class);
    private static final String RAG_QUERY_TOOL = "answerByPersonalKnowledge";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /** 上下文里"当前用户昵称"的 key，与 KnowledgeAgentFactory 注册时保持一致 */
    public static final String NICKNAME_KEY = "nickname";

    private final McpClientWrapper ragMcpClient;

    public PersonalKnowledgeTools(McpClientWrapper ragMcpClient) {
        this.ragMcpClient = ragMcpClient;
    }

    @Tool(name = "query_personal_knowledge",
            description = "查询当前用户的个人知识库并生成回答（RAG 检索 + Rerank）。"
                    + "用户问自己的文档、笔记、以前记过的东西时用它，不需要传用户身份")
    public String queryPersonalKnowledge(
            @ToolParam(name = "query", description = "要拿去个人知识库检索的问题") String query,
            ToolExecutionContext context,
            ToolEmitter emitter) {
        if (query == null || query.isBlank()) {
            log.warn("知识库查询被拒绝：问题为空");
            return "查询失败：问题为空。";
        }
        String nickname = context == null ? null : context.get(NICKNAME_KEY, String.class);
        if (nickname == null || nickname.isBlank()) {
            log.warn("知识库查询被拒绝：工具上下文里没有昵称，无法确定查谁的资料");
            return "查询失败：拿不到当前用户身份，无法确定查谁的资料。";
        }

        log.info("知识库查询开始: nickname={}, query=「{}」", nickname, query);
        emitter.emit(ToolResultBlock.text("正在检索个人知识库：" + query));

        try {
            McpSchema.CallToolResult result = ragMcpClient
                    .callTool(RAG_QUERY_TOOL, Map.of("nickname", nickname, "query", query))
                    .block(TIMEOUT);
            if (result == null) {
                log.error("知识库查询失败：RAG 无返回, query=「{}」", query);
                return "查询失败：知识库没有返回结果。";
            }
            String text = textOf(result);
            if (Boolean.TRUE.equals(result.isError())) {
                log.error("知识库查询失败：RAG 返回错误, query=「{}」, result={}", query, text);
                return "查询失败：" + text;
            }
            if (text.isBlank()) {
                log.info("知识库查询完成但结果为空: query=「{}」", query);
                return "（知识库里没有检索到相关内容）";
            }
            log.info("知识库查询完成: query=「{}」, 返回 {} 字符", query, text.length());
            emitter.emit(ToolResultBlock.text("知识库检索完成，拿到 " + text.length() + " 字，正在组织回答"));
            return text;
        } catch (Exception e) {
            log.error("知识库查询异常: query=「{}」", query, e);
            return "查询失败：" + e.getMessage();
        }
    }

    private String textOf(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining());
    }
}

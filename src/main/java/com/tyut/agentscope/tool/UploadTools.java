package com.tyut.agentscope.tool;

import io.agentscope.core.tool.Tool;
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
 * <h2>知识上传工具</h2>
 *
 * <p>把整理好的 Markdown 内容上传到个人 RAG 知识库。底层调用 RAG 暴露的 MCP 工具
 * {@code upload2Rag(documentName, markdownContent)}，它内部会完成 MinIO 上传、文档落库、
 * 文档转换、分块与向量化。
 *
 * <p>该工具属于敏感操作，由 UploadApprovalHook 在 PostReasoning 阶段拦截，必须人工审批后才执行。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>标题或内容为空：直接拒绝，不发起 MCP 调用；</li>
 *   <li>RAG 返回 error 或空结果：返回可读的失败原因；</li>
 *   <li>调用超时或抛异常：捕获后记录 error 日志并返回失败原因，不让异常冒泡打断 agent 循环。</li>
 * </ul>
 */
@Component
public class UploadTools {

    private static final Logger log = LoggerFactory.getLogger(UploadTools.class);
    private static final String RAG_UPLOAD_TOOL = "upload2Rag";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final McpClientWrapper ragMcpClient;

    public UploadTools(McpClientWrapper ragMcpClient) {
        this.ragMcpClient = ragMcpClient;
    }

    @Tool(name = "upload_document",
            description = "把整理好的 Markdown 内容上传到个人知识库，需要人工审批后才能入库")
    public String uploadDocument(
            @ToolParam(name = "title", description = "文档标题，用于知识库里的文档名") String title,
            @ToolParam(name = "markdownContent", description = "要上传的 Markdown 正文全文") String markdownContent) {
        if (title == null || title.isBlank()) {
            log.warn("上传被拒绝：文档名称为空");
            return "上传失败：文档名称为空。";
        }
        if (markdownContent == null || markdownContent.isBlank()) {
            log.warn("上传被拒绝：文档内容为空, title={}", title);
            return "上传失败：文档内容为空。";
        }

        try {
            McpSchema.CallToolResult result = ragMcpClient
                    .callTool(RAG_UPLOAD_TOOL, Map.of(
                            "documentName", title,
                            "markdownContent", markdownContent))
                    .block(TIMEOUT);

            if (result == null) {
                log.error("上传失败：RAG 无返回, title={}", title);
                return "上传失败：知识库没有返回结果。";
            }
            String text = textOf(result);
            if (Boolean.TRUE.equals(result.isError())) {
                log.error("上传失败：RAG 返回错误, title={}, result={}", title, text);
                return "上传失败：" + text;
            }
            log.info("知识文档上传完成: title={}, result={}", title, text);
            return "上传完成：" + text;
        } catch (Exception e) {
            log.error("知识文档上传异常: title={}", title, e);
            return "上传失败：" + e.getMessage();
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

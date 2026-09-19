package com.tyut.agentscope.tool;

import com.tyut.agentscope.common.ModelCaller;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * <h2>文档工具</h2>
 *
 * <p>提供把知识写成 Markdown 文档、以及提炼关键词两个能力。保存目录由调用方（用户）决定，
 * 不传时回退到配置里的默认目录。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>内容为空：拒绝写入并返回原因，不产生空文件；</li>
 *   <li>标题为空：文件名回退为 article；</li>
 *   <li>目录不存在：自动创建；目录非法或没有写权限：捕获后返回失败原因；</li>
 *   <li>同名文件：追加时间戳后缀，不覆盖已有文档。</li>
 * </ul>
 */
@Component
public class DocumentTools {

    private static final Logger log = LoggerFactory.getLogger(DocumentTools.class);
    private static final int MAX_SLUG_LENGTH = 40;

    /** 上下文里"用户设置的保存目录"的 key，与 KnowledgeAgentFactory 注册时保持一致 */
    public static final String ROOT_FOLDER_KEY = "rootFolder";

    private final Path defaultDir;
    private final Model chatModel;
    private final ModelCaller modelCaller;

    public DocumentTools(@Value("${app.knowledge.dir:./knowledge/web}") String dir,
                         @Qualifier("chatModel") Model chatModel,
                         ModelCaller modelCaller) {
        this.defaultDir = Path.of(dir);
        this.chatModel = chatModel;
        this.modelCaller = modelCaller;
    }

    @Tool(name = "write_markdown",
            description = "把整理好的知识写成 Markdown 文档并保存到指定目录，返回文件绝对路径")
    public String writeMarkdown(
            @ToolParam(name = "title", description = "文档标题") String title,
            @ToolParam(name = "content", description = "Markdown 正文全文") String content,
            @ToolParam(name = "outputDir", required = false, description = "保存目录的绝对路径，通常不需要传，系统会自动使用用户设置的目录；仅当用户明确要求存到别处时才传") String outputDir,
            ToolExecutionContext context) {
        if (content == null || content.isBlank()) {
            log.warn("写入被拒绝：内容为空, title={}", title);
            return "未写入：文档内容为空。";
        }

        Path dir;
        try {
            String fromContext = context == null ? null : context.get(ROOT_FOLDER_KEY, String.class);
            if (fromContext != null && !fromContext.isBlank()) {
                dir = Path.of(fromContext.trim());
                log.debug("保存目录取自上下文(用户设置): {}", dir);
            } else if (outputDir != null && !outputDir.isBlank()) {
                dir = Path.of(outputDir.trim());
                log.debug("保存目录取自模型参数 outputDir: {}", dir);
            } else {
                dir = defaultDir;
                log.debug("保存目录回退到服务端默认: {}", dir);
            }
            dir = dir.toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            log.warn("写入被拒绝：保存目录不合法, outputDir={}, fromContext={}",
                    outputDir, context == null ? null : context.get(ROOT_FOLDER_KEY, String.class));
            return "未写入：保存目录不合法。";
        }

        try {
            Files.createDirectories(dir);
            Path target = resolveTarget(dir, title);
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("知识文档已写入: {}", target.toAbsolutePath());
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("写入知识文档失败: dir={}, title={}", dir, title, e);
            return "未写入：" + e.getMessage();
        }
    }

    @Tool(name = "extract_keywords",
            description = "从一段文本里提炼 1-3 个关键词，用于后续识别同类内容")
    public String extractKeywords(@ToolParam(name = "text", description = "待提炼的文本") String text) {
        if (text == null || text.isBlank()) {
            log.warn("关键词提炼被跳过：文本为空");
            return "";
        }
        String prompt = "从下面这段内容里提炼 1-3 个最能代表该主题的关键词，用中文输出，只输出关键词，用顿号分隔：\n\n"
                + text;
        String keywords = modelCaller.call(chatModel, prompt);
        log.info("关键词提炼完成: {}", keywords);
        return keywords;
    }

    private Path resolveTarget(Path dir, String title) {
        String slug = slugify(title);
        Path target = dir.resolve(LocalDate.now() + "-" + slug + ".md");
        if (Files.exists(target)) {
            target = dir.resolve(LocalDate.now() + "-" + slug + "-" + System.currentTimeMillis() + ".md");
            log.info("目标文件已存在，改用带时间戳的文件名: {}", target.getFileName());
        }
        return target;
    }

    private String slugify(String title) {
        if (title == null || title.isBlank()) {
            return "article";
        }
        String slug = title.trim().replaceAll("[^\\p{L}\\p{N}]+", "_");
        return slug.length() > MAX_SLUG_LENGTH ? slug.substring(0, MAX_SLUG_LENGTH) : slug;
    }
}
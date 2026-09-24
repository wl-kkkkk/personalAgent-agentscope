package com.tyut.agentscope.tool;

import com.tyut.agentscope.keyword.Frontmatter;
import com.tyut.agentscope.keyword.Keywords;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * <h2>文档工具</h2>
 *
 * <p>把知识写成 Markdown 文档。保存目录由调用方（用户）决定，不传时回退到配置里的默认目录。
 * 传了 {@code keywords} 就写进 Markdown 的 frontmatter，让文档自己带着标签
 * （提炼关键词是 {@code KeywordTools} 的事）。
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

    public DocumentTools(@Value("${app.knowledge.dir:./knowledge/web}") String dir) {
        this.defaultDir = Path.of(dir);
    }

    @Tool(name = "write_markdown",
            description = "把整理好的知识写成 Markdown 文档并保存到指定目录，返回文件绝对路径")
    public String writeMarkdown(
            @ToolParam(name = "title", description = "文档标题") String title,
            @ToolParam(name = "content", description = "Markdown 正文全文") String content,
            @ToolParam(name = "outputDir", required = false, description = "保存目录的绝对路径，通常不需要传，系统会自动使用用户设置的目录；仅当用户明确要求存到别处时才传") String outputDir,
            @ToolParam(name = "keywords", required = false, description = "这篇文档的关键词，用顿号分隔，一般取自 extract_keywords 的 keywordsText；会写进 Markdown 的 frontmatter，没有就不写") String keywords,
            ToolExecutionContext context) {
        if (content == null || content.isBlank()) {
            log.warn("写入被拒绝：内容为空, title={}", title);
            return "未写入：文档内容为空。";
        }

        List<String> keywordList = Keywords.split(keywords);
        String body = Frontmatter.apply(content, keywordList);

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
            Files.writeString(target, body, StandardCharsets.UTF_8);
            log.info("知识文档已写入: {}, 关键词={}", target.toAbsolutePath(), keywordList);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("写入知识文档失败: dir={}, title={}", dir, title, e);
            return "未写入：" + e.getMessage();
        }
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

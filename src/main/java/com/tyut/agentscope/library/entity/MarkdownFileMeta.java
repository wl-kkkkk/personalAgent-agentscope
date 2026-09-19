package com.tyut.agentscope.library.entity;

import java.time.LocalDateTime;

/**
 * <h2>Markdown 文件元信息</h2>
 *
 * <p>只存路径与大小等元信息，正文不落库：这一行指向本地文件，打开时按 absolutePath 读取。
 */
public record MarkdownFileMeta(
        Long id,
        String userId,
        String rootFolder,
        String relativePath,
        String absolutePath,
        String fileName,
        long fileSize,
        LocalDateTime lastModified) {
}

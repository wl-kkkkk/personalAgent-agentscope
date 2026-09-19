package com.tyut.agentscope.library.model;

public record FileContentVO(
        Long id,
        String fileName,
        String relativePath,
        String absolutePath,
        long fileSize,
        String lastModified,
        String content) {
}

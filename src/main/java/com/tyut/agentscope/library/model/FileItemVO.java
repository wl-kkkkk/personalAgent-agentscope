package com.tyut.agentscope.library.model;

public record FileItemVO(
        Long id,
        String fileName,
        String relativePath,
        long fileSize,
        String lastModified) {
}

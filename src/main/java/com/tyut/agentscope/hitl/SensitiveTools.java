package com.tyut.agentscope.hitl;

import java.util.Set;

/**
 * 需要人工审批的工具（HITL 的范围就限定在这几个工具上）。
 */
public final class SensitiveTools {

    /**
     * 本地包装工具 upload_document，以及 RAG 直接暴露的 MCP 工具 upload2Rag。
     * 后者也要拦，否则模型可能绕过包装工具直接调 MCP 上传。
     */
    public static final Set<String> NAMES = Set.of("upload_document", "upload2Rag");

    private SensitiveTools() {
    }
}

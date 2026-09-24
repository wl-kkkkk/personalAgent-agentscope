package com.tyut.agentscope.intent;

/**
 * <h2>检索路径常量</h2>
 *
 * <p>对外（前端、接口参数）用短名 {@code rag} / {@code web}，对内（提示词、决策结果、
 * 日志）用 {@code knowledge_base} / {@code web}——前者是给用户看的开关值，后者是给模型看的
 * 语义标签，两者在这里做一次映射，别在业务代码里到处写字符串。
 */
public final class Routes {

    /** 走个人知识库（向量检索 + 生成） */
    public static final String RAG = "knowledge_base";

    /** 走联网检索 */
    public static final String WEB = "web";

    /** 前端传的强制值：强制知识库 */
    public static final String FORCE_RAG = "rag";

    /** 前端传的强制值：强制联网 */
    public static final String FORCE_WEB = "web";

    /** 工具上下文里放 {@link RouteState} 的 key */
    public static final String STATE_KEY = "routeState";

    private Routes() {
    }

    public static boolean isValid(String route) {
        return RAG.equals(route) || WEB.equals(route);
    }

    /**
     * 把接口传进来的强制值翻译成内部路由。
     *
     * @return {@code rag} → {@link #RAG}，{@code web} → {@link #WEB}，
     *         空值或无法识别返回 null（表示"不强制，交给模型判断"）
     */
    public static String fromForcedValue(String forced) {
        if (forced == null || forced.isBlank()) {
            return null;
        }
        String value = forced.trim().toLowerCase();
        return switch (value) {
            case FORCE_RAG, "knowledge_base", "kb" -> RAG;
            case FORCE_WEB, "search", "net" -> WEB;
            default -> null;
        };
    }

    /** 中文标签，用于日志和注入给模型的指令 */
    public static String label(String route) {
        if (RAG.equals(route)) {
            return "个人知识库检索";
        }
        if (WEB.equals(route)) {
            return "联网检索";
        }
        return "未决";
    }
}

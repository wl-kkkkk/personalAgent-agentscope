package com.tyut.agentscope.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <h2>SSE 事件体</h2>
 *
 * <p>每个事件序列化成一行 JSON 再推给前端。用 JSON 而不是裸文本，是因为裸文本里带换行
 * 会把 SSE 的 {@code data:} 帧拆断（前端按行解析会丢内容），JSON 里的换行会被转义掉。
 *
 * <p>type 取值：{@code session} / {@code progress} / {@code thinking} / {@code tool} /
 * {@code answer} / {@code approval} / {@code done} / {@code error}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamEvent(String type,
                          String text,
                          String message,
                          String sessionId,
                          String title,
                          String taskId,
                          String toolName) {

    public static StreamEvent session(String sessionId, String title) {
        return new StreamEvent("session", null, null, sessionId, title, null, null);
    }

    public static StreamEvent progress(String message) {
        return new StreamEvent("progress", null, message, null, null, null, null);
    }

    /** 模型的思考/推理增量，只用于展示"正在思考"，答案一到就清掉 */
    public static StreamEvent thinking(String text) {
        return new StreamEvent("thinking", text, null, null, null, null, null);
    }

    /** 工具调用结果（含失败原因），前端按次要信息展示，方便排查 */
    public static StreamEvent tool(String note) {
        return new StreamEvent("tool", null, note, null, null, null, null);
    }

    /** 最终回答的增量文本 */
    public static StreamEvent answer(String text) {
        return new StreamEvent("answer", text, null, null, null, null, null);
    }

    public static StreamEvent approval(String taskId, String toolName) {
        return new StreamEvent("approval", null, null, null, null, taskId, toolName);
    }

    public static StreamEvent done() {
        return new StreamEvent("done", null, null, null, null, null, null);
    }

    public static StreamEvent error(String message) {
        return new StreamEvent("error", null, message, null, null, null, null);
    }
}

package com.tyut.agentscope.common;

/**
 * <h2>统一响应体</h2>
 *
 * <p>前端按 {@code {success, message, data}} 解析，与 personalrag 的返回结构保持一致。
 *
 * @param success 是否成功
 * @param message 提示信息，成功时可为空
 * @param data    业务数据
 */
public record ApiResponse<T>(boolean success, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, null, data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message, null);
    }
}

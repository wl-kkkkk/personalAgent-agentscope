package com.tyut.agentscope.common;

import cn.dev33.satoken.exception.NotLoginException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * <h2>全局异常处理</h2>
 *
 * <p>把异常统一转成 {@link ApiResponse} 的 JSON，而不是让它冒到容器层。
 * <p>这一点很关键：异常一旦没人处理，Tomcat 会转发到 {@code /error} 渲染错误页，
 * 途中又会被登录拦截器拦下抛 NotLoginException，最终报出来的错误和真实原因完全不相关。
 *
 * <p>注意这里用 {@code @ResponseStatus}（setStatus）而不是 {@code sendError}，
 * 前者只改状态码、写出响应体，不会触发错误页转发。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<Void> handleNotLogin(NotLoginException e) {
        log.warn("未登录访问被拒绝: {}", e.getMessage());
        return ApiResponse.fail("未登录或登录已过期，请先登录");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("请求参数不合法: {}", e.getMessage());
        return ApiResponse.fail(e.getMessage());
    }

    /**
     * 静态资源 404（浏览器自动请求的 favicon.ico 等）：按 404 静默处理，
     * 不要走下面的兜底分支记成 ERROR —— 否则日志里全是假报错。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.debug("静态资源不存在: {}", e.getMessage());
        return ApiResponse.fail("资源不存在");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("请求处理失败", e);
        return ApiResponse.fail("服务器处理失败：" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : " - " + e.getMessage()));
    }
}

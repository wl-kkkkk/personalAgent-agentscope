package com.tyut.agentscope.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Set;

/**
 * Sa-Token 拦截器：除登录相关接口与静态资源外，其余接口都要登录。
 * 规则与 personalrag 保持一致。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "html", "js", "css", "png", "jpg", "jpeg", "gif", "svg",
            "ico", "woff", "woff2", "ttf");

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> SaRouter.match("/**")
                        // /error 必须放行：否则真实异常转发到错误页时会被这里拦掉，
                        // 报出来的变成 NotLoginException，把原始错误盖住
                        .notMatch("/user/**", "/error", "/favicon.ico",
                                "/", "/index.html", "/static/**")
                        .check(r -> {
                            if (isStaticResource()) {
                                return;
                            }
                            StpUtil.checkLogin();
                        })))
                .addPathPatterns("/**");
    }

    private boolean isStaticResource() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return false;
        }
        HttpServletRequest request = attrs.getRequest();
        String uri = request.getRequestURI();
        int dotIndex = uri.lastIndexOf('.');
        if (dotIndex == -1) {
            return false;
        }
        return STATIC_EXTENSIONS.contains(uri.substring(dotIndex + 1).toLowerCase());
    }
}

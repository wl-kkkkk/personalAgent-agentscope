package com.tyut.agentscope.user.controller;

import com.tyut.agentscope.common.ApiResponse;
import com.tyut.agentscope.user.model.LoginDTO;
import com.tyut.agentscope.user.model.LoginStatus;
import com.tyut.agentscope.user.model.LoginUserVO;
import com.tyut.agentscope.user.model.RegisterDTO;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <h2>用户接口</h2>
 *
 * <p>路径与返回结构与 personalrag 保持一致，前端逻辑可以直接复用。
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final AuthService authService;

    public UserController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<LoginUserVO> register(@RequestBody RegisterDTO dto) {
        try {
            return ApiResponse.ok("注册成功", authService.register(dto));
        } catch (Exception e) {
            log.warn("注册失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<LoginUserVO> login(@RequestBody LoginDTO dto) {
        try {
            return ApiResponse.ok("登录成功", authService.login(dto));
        } catch (Exception e) {
            log.warn("登录失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        try {
            authService.logout();
            return ApiResponse.ok("登出成功", null);
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/current")
    public ApiResponse<LoginUserVO> current() {
        try {
            return ApiResponse.ok(authService.currentUser());
        } catch (Exception e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @GetMapping("/is-login")
    public ApiResponse<LoginStatus> isLogin() {
        try {
            return ApiResponse.ok(new LoginStatus(true, authService.currentUserId()));
        } catch (Exception e) {
            return ApiResponse.ok(new LoginStatus(false, null));
        }
    }
}

package com.tyut.agentscope.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.tyut.agentscope.user.entity.UserInfo;
import com.tyut.agentscope.user.model.LoginDTO;
import com.tyut.agentscope.user.model.LoginUserVO;
import com.tyut.agentscope.user.model.RegisterDTO;
import com.tyut.agentscope.user.repository.UserInfoRepository;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * <h2>登录注册</h2>
 *
 * <p>会话由 Sa-Token 维护（Cookie 名 satoken）；密码用 BCrypt 哈希后落库。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>手机号/密码为空、密码过短：直接拒绝并给出中文提示；</li>
 *   <li>手机号已注册：注册失败但不抛未捕获异常；</li>
 *   <li>账号被冻结、密码错误：统一提示"手机号或密码错误"，不泄露账号是否存在。</li>
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserInfoRepository userInfoRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthServiceImpl(UserInfoRepository userInfoRepository, BCryptPasswordEncoder passwordEncoder) {
        this.userInfoRepository = userInfoRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public LoginUserVO register(RegisterDTO dto) {
        if (dto == null || isBlank(dto.phone()) || isBlank(dto.password())) {
            throw new IllegalArgumentException("手机号和密码不能为空");
        }
        String phone = dto.phone().trim();
        if (dto.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (userInfoRepository.findByPhone(phone).isPresent()) {
            throw new IllegalArgumentException("该手机号已注册");
        }
        String nickname = isBlank(dto.nickname()) ? "用户" + phone.substring(Math.max(0, phone.length() - 4)) : dto.nickname().trim();
        long userId = userInfoRepository.insert(phone, passwordEncoder.encode(dto.password()), nickname);
        return new LoginUserVO(String.valueOf(userId), phone, nickname, null);
    }

    @Override
    public LoginUserVO login(LoginDTO dto) {
        if (dto == null || isBlank(dto.phone()) || isBlank(dto.password())) {
            throw new IllegalArgumentException("手机号和密码不能为空");
        }
        UserInfo user = userInfoRepository.findByPhone(dto.phone().trim())
                .orElseThrow(() -> new IllegalArgumentException("手机号或密码错误"));
        if (!passwordEncoder.matches(dto.password(), user.password())) {
            log.warn("登录失败（密码错误）: phone={}", dto.phone());
            throw new IllegalArgumentException("手机号或密码错误");
        }
        if (UserInfo.STATUS_FROZEN.equals(user.status())) {
            log.warn("登录失败（账号冻结）: userId={}", user.id());
            throw new IllegalArgumentException("账号已被冻结");
        }
        StpUtil.login(String.valueOf(user.id()));
        log.info("登录成功: userId={}, phone={}", user.id(), user.phone());
        return new LoginUserVO(String.valueOf(user.id()), user.phone(), user.nickname(), user.rootFolder());
    }

    @Override
    public void logout() {
        String userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        StpUtil.logout();
        log.info("登出: userId={}", userId);
    }

    @Override
    public LoginUserVO currentUser() {
        String userId = currentUserId();
        UserInfo user = userInfoRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("登录状态已失效，请重新登录"));
        return new LoginUserVO(userId, user.phone(), user.nickname(), user.rootFolder());
    }

    @Override
    public String currentUserId() {
        return StpUtil.getLoginIdAsString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

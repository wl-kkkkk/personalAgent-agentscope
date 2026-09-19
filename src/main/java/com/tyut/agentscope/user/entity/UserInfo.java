package com.tyut.agentscope.user.entity;

/**
 * <h2>用户</h2>
 *
 * <p>字段与 personalrag.user_info 对齐，额外带一个 {@code rootFolder}：
 * 用户在界面上选定的 Markdown 根目录。
 *
 * <p>密码字段存的是 BCrypt 哈希，不是明文。
 */
public record UserInfo(
        Long id,
        String phone,
        String email,
        String password,
        String nickname,
        String status,
        String rootFolder) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FROZEN = "FROZEN";
}

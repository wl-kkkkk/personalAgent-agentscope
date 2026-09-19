package com.tyut.agentscope.user.model;

/**
 * 返回给前端的登录用户信息，token 由 Sa-Token 通过 Cookie 维护，不在这里下发。
 */
public record LoginUserVO(String userId, String phone, String nickname, String rootFolder) {
}

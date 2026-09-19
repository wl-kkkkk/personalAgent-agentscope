package com.tyut.agentscope.web;

/**
 * sessionId 首轮可以不传（服务端生成并返回），之后每轮都要把返回的 sessionId 带回来。
 *
 * <p>userId 可以由前端带上（方便调试、也让老版本调用方无感），但**服务端只以登录态为准**，
 * 只做一致性校验：传了就必须和当前登录用户一致，否则直接拒绝——
 * 否则任何人都能拿别人的 userId 去查别人的知识库。
 */
public record AgentRequest(String userId, String sessionId, String query) {
}

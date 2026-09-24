package com.tyut.agentscope.web;

import com.tyut.agentscope.common.ApiResponse;
import com.tyut.agentscope.service.ChatService;
import com.tyut.agentscope.service.HitlService;
import com.tyut.agentscope.user.model.LoginUserVO;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * <h2>对话接口</h2>
 *
 * <p>用户身份**以登录态为准**。前端可以带 userId（方便调试与兼容老调用方），
 * 但服务端只做一致性校验，不一致直接拒绝，避免拿别人的昵称去查别人的知识库。
 * <p>HITL：web 路径上传前会返回 {@code status = PENDING_APPROVAL} 与 taskId，
 * 审批后调 {@code /api/agent/approve} 续跑。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final ChatService chatService;
    private final HitlService hitlService;
    private final AuthService authService;

    public AgentController(ChatService chatService, HitlService hitlService, AuthService authService) {
        this.chatService = chatService;
        this.hitlService = hitlService;
        this.authService = authService;
    }

    @PostMapping("/ask")
    public ApiResponse<AgentResponse> ask(@RequestBody AgentRequest request) {
        try {
            LoginUserVO user = authService.currentUser();
            verifyUser(request.userId(), user.userId());
            AgentResponse response = chatService.chat(
                    user.userId(), user.nickname(), user.rootFolder(),
                    request.sessionId(), request.query(), request.forceRoute());
            return ApiResponse.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("对话参数不合法: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("对话执行失败", e);
            return ApiResponse.fail("对话执行失败：" + e.getMessage());
        }
    }

    /**
     * 前端传了 userId 就和登录态比一下：一致放行，不一致拒绝；不传就用登录态。
     * 服务端始终以登录态为准，不接受"用别人的 userId 查别人知识库"。
     */
    private void verifyUser(String requestedUserId, String currentUserId) {
        if (requestedUserId == null || requestedUserId.isBlank()) {
            return;
        }
        if (!requestedUserId.equals(currentUserId)) {
            log.warn("请求体 userId({}) 与登录用户({}) 不一致，已拒绝", requestedUserId, currentUserId);
            throw new IllegalArgumentException("请求中的 userId 与当前登录用户不一致");
        }
    }

    @PostMapping("/approve")
    public ApiResponse<AgentResponse> approve(@RequestBody ApproveRequest request) {
        try {
            LoginUserVO user = authService.currentUser();
            AgentResponse response = hitlService.approve(
                    request.taskId(), request.approved(), request.note(),
                    request.keywords(), user.userId());
            return ApiResponse.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("审批失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("审批处理失败", e);
            return ApiResponse.fail("审批处理失败：" + e.getMessage());
        }
    }

    /**
     * 流式对话（SSE）：每个事件是一行 JSON，前端边收边渲染。
     * <p>用户校验在返回 Flux 之前完成，这样未登录/参数错误会走正常 JSON 错误响应，而不是半个流。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody AgentRequest request) {
        LoginUserVO user = authService.currentUser();
        verifyUser(request.userId(), user.userId());
        return chatService.streamChat(user.userId(), user.nickname(),
                user.rootFolder(), request.sessionId(), request.query(), request.forceRoute());
    }
}

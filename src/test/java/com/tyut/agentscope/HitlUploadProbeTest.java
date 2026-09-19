package com.tyut.agentscope;

import com.tyut.agentscope.hitl.PendingToolScan;
import com.tyut.agentscope.hitl.SensitiveTools;
import com.tyut.agentscope.hook.UploadApprovalHook;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>HITL 上传链路探针</h2>
 *
 * <p>用和线上一致的 hook 装配，加一个"只记录不真传"的桩工具，验证：
 * 模型要上传时，{@link UploadApprovalHook} 会不会拦住、停下之后那条 tool call
 * 还在不在 memory 里（审批卡片就靠它）。
 *
 * <p>跑法：{@code mvn test -DexcludedGroups= -Dgroups=probe -Dtest=HitlUploadProbeTest}</p>
 */
@Tag("probe")
class HitlUploadProbeTest {

    /** 桩上传工具：记录是否被真正执行 */
    public static class StubUploadTool {
        static boolean executed = false;

        @Tool(name = "upload_document", description = "把整理好的内容上传到个人知识库（需要人工审批）")
        public String uploadDocument(
                @ToolParam(name = "title", description = "文档标题") String title,
                @ToolParam(name = "markdownContent", description = "Markdown 正文全文") String markdownContent) {
            executed = true;
            System.out.println("!!! 桩工具被真正执行了（说明 HITL 没拦住）: title=" + title);
            return "已上传（stub）";
        }
    }

    @Test
    void probeHitlUpload() throws Exception {
        StubUploadTool.executed = false;

        Model chatModel = DashScopeChatModel.builder()
                .apiKey(readApiKey())
                .modelName("qwen-max")
                .baseUrl("https://dashscope.aliyuncs.com")
                .endpointType(EndpointType.AUTO)
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new StubUploadTool()).apply();
        System.out.println("=== 工具列表: " + toolkit.getToolNames());
        toolkit.getToolSchemas().forEach(s -> System.out.println("=== 工具 schema: name=" + s.getName()
                + ", description=" + s.getDescription()
                + ", parameters=" + s.getParameters()));

        ReActAgent agent = ReActAgent.builder()
                .name("hitlProbe")
                .sysPrompt("你是知识助手。用户要求把内容上传到知识库时，直接调用 upload_document 工具。")
                .model(chatModel)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .hook(new UploadApprovalHook())
                .maxIters(6)
                .build();

        Msg message = Msg.builder().role(MsgRole.USER)
                .textContent("请把「Redis 缓存配置要点：设置 maxmemory 与淘汰策略」整理成文档并上传到知识库。")
                .build();

        try {
            Msg reply = agent.call(message).block(Duration.ofMinutes(3));
            System.out.println("=== agent 回复: " + (reply == null ? "null" : reply.getTextContent()));
        } catch (Throwable t) {
            System.out.println("=== agent 调用异常: " + t.getClass().getName() + " : " + t.getMessage());
        }

        System.out.println("=== 桩工具是否被执行: " + StubUploadTool.executed);
        List<Msg> messages = agent.getMemory().getMessages();
        System.out.println("=== memory 消息数: " + (messages == null ? 0 : messages.size()));
        if (messages != null) {
            for (Msg m : messages) {
                List<ToolUseBlock> uses = m.getContentBlocks(ToolUseBlock.class);
                System.out.println("--- role=" + m.getRole()
                        + ", text=" + abbreviate(m.getTextContent())
                        + ", toolCalls=" + uses.stream().map(ToolUseBlock::getName).toList());
            }
        }
        List<ToolUseBlock> pending = PendingToolScan.findPending(agent.getMemory(), SensitiveTools.NAMES);
        System.out.println("=== 找到的挂起敏感调用数: " + pending.size()
                + (pending.isEmpty() ? "（审批卡片会拿不到，上传就永远走不到）" : " -> " + pending.get(0).getName()));
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > 60 ? one.substring(0, 60) + "…" : one;
    }

    /**
     * 只测"审批通过后的恢复续跑"这一段：构造与挂起时 id 相同的 ToolResultBlock 回填，
     * 再让 agent 继续，看它能不能正常收尾。HitlService 做的就是这件事。
     */
    @Test
    void probeHitlResume() throws Exception {
        StubUploadTool.executed = false;

        Model chatModel = DashScopeChatModel.builder()
                .apiKey(readApiKey())
                .modelName("qwen-max")
                .baseUrl("https://dashscope.aliyuncs.com")
                .endpointType(EndpointType.AUTO)
                .stream(true)
                .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(new StubUploadTool()).apply();

        ReActAgent agent = ReActAgent.builder()
                .name("hitlResumeProbe")
                .sysPrompt("你是知识助手。用户要求上传时调用 upload_document 工具；工具返回后，用一句话告诉用户结果。")
                .model(chatModel)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .hook(new UploadApprovalHook())
                .maxIters(6)
                .build();

        Msg request = Msg.builder().role(MsgRole.USER)
                .textContent("请把「Redis 缓存配置要点：设置 maxmemory 与淘汰策略」上传到知识库。")
                .build();
        agent.call(request).block(Duration.ofMinutes(3));

        List<ToolUseBlock> pending = PendingToolScan.findPending(agent.getMemory(), SensitiveTools.NAMES);
        if (pending.isEmpty()) {
            System.out.println("=== 没有找到挂起调用，无法继续测试恢复");
            return;
        }
        ToolUseBlock call = pending.get(0);
        System.out.println("=== 挂起调用 id=" + call.getId() + ", 入参=" + call.getInput());

        // 模拟"人工批准 + 执行完成"，回填同 id 的结果
        ToolResultBlock result = ToolResultBlock.builder()
                .id(call.getId())
                .name(call.getName())
                .output(TextBlock.builder().text("上传完成：已写入个人知识库（stub）").build())
                .build();
        Msg toolResultMessage = ToolResultMessageBuilder.buildToolResultMsg(result, call, call.getName());

        try {
            Msg reply = agent.call(toolResultMessage).block(Duration.ofMinutes(3));
            System.out.println("=== 恢复后的回复: " + (reply == null ? "null" : reply.getTextContent()));
        } catch (Throwable t) {
            System.out.println("=== 恢复失败: " + t.getClass().getName() + " : " + t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                System.out.println("--- caused by: " + cause.getClass().getName() + " : " + cause.getMessage());
                cause = cause.getCause();
            }
        }
        System.out.println("=== 桩工具是否被执行: " + StubUploadTool.executed);
    }

    private String readApiKey() throws Exception {
        String env = System.getenv("DASHSCOPE_API_KEY");
        if (env != null && !env.isBlank()) {
            return env;
        }
        String yml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("api-key:\\s*\\$\\{DASHSCOPE_API_KEY:([^}]+)}").matcher(yml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new IllegalStateException("application.yml 里没找到 api-key");
    }
}

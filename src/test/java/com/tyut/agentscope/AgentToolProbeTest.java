package com.tyut.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.common.JsonSupport;
import com.tyut.agentscope.common.ModelCaller;
import com.tyut.agentscope.common.PromptLoader;
import com.tyut.agentscope.common.StructuredModelCaller;
import com.tyut.agentscope.tool.DocumentTools;
import com.tyut.agentscope.tool.KeywordTools;
import com.tyut.agentscope.tool.QueryRewriteTools;
import com.tyut.agentscope.tool.UploadTools;
import com.tyut.agentscope.tool.WebSearchTools;
import com.tyut.agentscope.skill.SkillCatalog;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>Agent + 工具 探针</h2>
 *
 * <p>按和线上一致的方式装配 toolkit 与 ReActAgent，先打印工具列表，
 * 再真跑一次"需要联网"的问题，看模型到底调没调工具、结果是什么。
 *
 * <p>跑法：{@code mvn test -Dtest=AgentToolProbeTest}</p>
 */
@Tag("probe")
class AgentToolProbeTest {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com";

    @Test
    void probeAgentWithTools() throws Exception {
        String apiKey = readApiKey();
        ModelCaller modelCaller = new ModelCaller();
        PromptLoader promptLoader = new PromptLoader();

        Model chatModel = DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-max").baseUrl(BASE_URL)
                .endpointType(EndpointType.AUTO).stream(true).build();
        Model searchModel = DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen-max").baseUrl(BASE_URL)
                .endpointType(EndpointType.AUTO).enableSearch(true).stream(false).build();
        Model rewriteModel = DashScopeChatModel.builder()
                .apiKey(apiKey).modelName("qwen3.8-flash").baseUrl(BASE_URL)
                .endpointType(EndpointType.MULTIMODAL).enableThinking(false).stream(false).build();

        Toolkit toolkit = new Toolkit();
        // 与线上保持一致：每个工具单独注册（链式调用只会保留最后一个）
        toolkit.registration().tool(new QueryRewriteTools(rewriteModel, modelCaller, promptLoader)).apply();
        toolkit.registration().tool(new WebSearchTools(searchModel, modelCaller)).apply();
        toolkit.registration().tool(new DocumentTools("./knowledge/web")).apply();
        // 关键词工具（探针里不接词表，只为确认工具能注册上、schema 里参数是齐的）
        toolkit.registration().tool(new KeywordTools(chatModel, promptLoader,
                new StructuredModelCaller(modelCaller, new JsonSupport(new ObjectMapper()), false),
                null, new ObjectMapper())).apply();
        toolkit.registration().tool(new UploadTools(null)).apply();
        System.out.println("=== toolkit 工具列表: " + toolkit.getToolNames());
        toolkit.getToolSchemas().forEach(s -> System.out.println("=== schema: " + s.getName()
                + " -> " + s.getParameters()));

        SkillCatalog skillCatalog = new SkillCatalog();
        skillCatalog.newSkillBox(toolkit);
        System.out.println("=== 注册 skill 后的工具列表: " + toolkit.getToolNames());

        ReActAgent agent = ReActAgent.builder()
                .name("probeAgent")
                .sysPrompt("你是个人知识助手。需要外部信息时，调用 web_search 工具联网检索，然后回答用户。")
                .model(chatModel)
                .toolkit(toolkit)
                .memory(new InMemoryMemory())
                .maxIters(6)
                .build();

        Msg message = Msg.builder().role(MsgRole.USER).textContent("帮我查一下今天有什么科技新闻").build();
        try {
            Msg reply = agent.call(message).block(Duration.ofMinutes(3));
            System.out.println("=== agent 回复: " + (reply == null ? "null" : reply.getTextContent()));
        } catch (Throwable t) {
            System.out.println("=== agent 调用失败: " + t.getClass().getName() + " : " + t.getMessage());
            Throwable cause = t.getCause();
            while (cause != null) {
                System.out.println("--- caused by: " + cause.getClass().getName() + " : " + cause.getMessage());
                cause = cause.getCause();
            }
            t.printStackTrace(System.out);
        }
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

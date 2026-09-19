package com.tyut.agentscope;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.Model;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <h2>联网检索探针</h2>
 *
 * <p>用和 WebSearchTools 完全相同的模型配置打一次真实请求，
 * 把 DashScope 的原始返回或异常打出来，用于定位"联网查询失败"。
 *
 * <p>跑法：{@code mvn test -Dtest=WebSearchProbeTest}</p>
 */
@Tag("probe")
class WebSearchProbeTest {

@Test
    void probeWebSearchModel() throws Exception {
        Model model = DashScopeChatModel.builder()
                .apiKey(readApiKey())
                .modelName("qwen-max")
                .baseUrl("https://dashscope.aliyuncs.com")
                .endpointType(EndpointType.AUTO)
                .enableSearch(true)
                .stream(false)
                .build();

        Msg message = Msg.builder()
                .role(MsgRole.USER)
                .textContent("你是联网搜索助手。请基于最新联网检索结果，用中文整理要点。\n\n检索问题：今天有什么科技新闻")
                .build();

        try {
            List<ChatResponse> responses = model.stream(List.of(message), List.of(), null)
                    .collectList()
                    .block(Duration.ofSeconds(60));
            System.out.println("=== 探针成功，响应条数=" + (responses == null ? 0 : responses.size()));
            if (responses != null) {
                System.out.println("=== 文本: " + responses.stream()
                        .flatMap(r -> r.getContent().stream())
                        .filter(TextBlock.class::isInstance)
                        .map(b -> ((TextBlock) b).getText())
                        .collect(Collectors.joining()));
            }
        } catch (Throwable t) {
            System.out.println("=== 探针失败: " + t.getClass().getName() + " : " + t.getMessage());
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

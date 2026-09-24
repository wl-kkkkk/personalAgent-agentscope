package com.tyut.agentscope.config;

import com.tyut.agentscope.tool.DocumentTools;
import com.tyut.agentscope.tool.KeywordTools;
import com.tyut.agentscope.tool.PersonalKnowledgeTools;
import com.tyut.agentscope.tool.QueryRewriteTools;
import com.tyut.agentscope.tool.UploadTools;
import com.tyut.agentscope.tool.WebSearchTools;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.EndpointType;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可共享的单例组件：模型、MCP 客户端、工具集、Skill。
 * 注意 agent 与 memory 不在这里 —— 它们必须按请求创建，见 KnowledgeAgentFactory / AgentMemoryFactory。
 */
@Configuration
public class AgentScopeConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeConfig.class);

    @Value("${app.dashscope.api-key}")
    private String apiKey;

    @Value("${app.dashscope.model:qwen-max}")
    private String modelName;

    @Value("${app.dashscope.search-model:qwen-plus}")
    private String searchModelName;

    @Value("${app.dashscope.search-base-url:}")
    private String searchBaseUrl;

    @Value("${app.dashscope.rewrite-model:qwen3.8-flash}")
    private String rewriteModelName;

    @Value("${app.dashscope.title-model:qwen3.8-flash}")
    private String titleModelName;

    @Value("${app.rag.mcp-url}")
    private String ragMcpUrl;

    /**
     * DashScope 原生端点根地址。
     * 显式写死，不依赖框架的隐式兜底：AgentScope 会在它后面拼
     * /api/v1/services/aigc/text-generation/generation，
     * 若填成 OpenAI 兼容模式地址（.../compatible-mode/v1）会被判为 "url error"。
     */
    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com";

    private static final Pattern QWEN3_MINOR = Pattern.compile("^qwen3\\.(\\d+)");

    /** DashScope API Key 的最小长度校验 */
    private static final int API_KEY_MIN_LENGTH = 10;

    @PostConstruct
    public void validateConfig() {
        if (apiKey == null || apiKey.isBlank() || apiKey.length() < API_KEY_MIN_LENGTH) {
            log.warn("╔══════════════════════════════════════════════════════════════╗");
            log.warn("║  DASHSCOPE_API_KEY 未配置或无效！                           ║");
            log.warn("║  请在环境变量或 application.yml 中设置有效的 API Key。       ║");
            log.warn("║  联网搜索、对话等功能将无法正常使用。                       ║");
            log.warn("╚══════════════════════════════════════════════════════════════╝");
        } else {
            log.info("DashScope API Key 已配置，长度={}", apiKey.length());
        }

        log.info("联网搜索模型配置: search-model={}, search-base-url={}",
                searchModelName != null && !searchModelName.isBlank() ? searchModelName : modelName,
                searchBaseUrl != null && !searchBaseUrl.isBlank() ? searchBaseUrl : DASHSCOPE_BASE_URL);
    }

    /**
     * AgentScope 1.0.12 的端点自动判定只覆盖到 qwen3.5 / qwen3.6
     * （判定逻辑：qvq* / 含 -vl、-asr / qwen3.5* / qwen3.6* / kimi-k2.5、2.6）。
     * 而 qwen3.7、qwen3.8 这一代在 DashScope 上是挂在**多模态端点**的，
     * 发到文本端点会返回误导性的 "url error, please check url!"。
     * 这里按模型名补上，其余模型继续走 AUTO 自动判定。
     */
    private EndpointType endpointTypeFor(String model) {
        if (model == null) {
            return EndpointType.AUTO;
        }
        Matcher matcher = QWEN3_MINOR.matcher(model.toLowerCase().trim());
        if (matcher.find() && Integer.parseInt(matcher.group(1)) >= 7) {
            return EndpointType.MULTIMODAL;
        }
        return EndpointType.AUTO;
    }

    /** 通用对话模型（也用于记忆压缩） */
    @Bean
    public Model chatModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .baseUrl(DASHSCOPE_BASE_URL)
                .endpointType(endpointTypeFor(modelName))
                .stream(true)
                .build();
    }

    /** 联网搜索模型（开启 enable_search）
     * <p>关键：DashScope 联网搜索（enable_search=true）<b>必须使用流式调用</b>，
     * 否则会返回 "Non-streaming mode does not support Web Search" 报错。
     * ModelCaller 用 collectList().block() 收集流式结果，所以 stream=true 不影响调用方式。
     */
    @Bean
    public Model webSearchModel() {
        String url = (searchBaseUrl == null || searchBaseUrl.isBlank())
                ? DASHSCOPE_BASE_URL
                : searchBaseUrl;
        String model = (searchModelName == null || searchModelName.isBlank())
                ? modelName
                : searchModelName;

        log.info("联网搜索模型: model={}, baseUrl={}, stream=true (联网搜索必须流式)", model, url);

        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .baseUrl(url)
                .endpointType(endpointTypeFor(model))
                .enableSearch(true)
                .stream(true)
                .build();
    }

    /**
     * 查询改写专用模型：轻量、快、不开深度思考。
     * 每次用户提问都会走一次（QueryRewriteHook 保证必跑），所以单独配一个，换模型不用动业务代码。
     */
    @Bean
    public Model queryRewriteModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(rewriteModelName)
                .baseUrl(DASHSCOPE_BASE_URL)
                .endpointType(endpointTypeFor(rewriteModelName))
                .enableThinking(false)
                .stream(false)
                .build();
    }

    /** 会话标题生成专用模型：轻量、快、不开深度思考 */
    @Bean
    public Model titleModel() {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName(titleModelName)
                .baseUrl(DASHSCOPE_BASE_URL)
                .endpointType(endpointTypeFor(titleModelName))
                .enableThinking(false)
                .stream(false)
                .build();
    }

    /** 个人 RAG 的 MCP 客户端，注册进 Toolkit 后它的工具就能被 agent 调用 */
    @Bean
    public McpClientWrapper ragMcpClient() {
        return McpClientBuilder.create("rag")
                .streamableHttpTransport(ragMcpUrl)
                .buildSync();
    }

    @Bean
    public Toolkit toolkit(McpClientWrapper ragMcpClient,
                           WebSearchTools webSearchTools,
                           PersonalKnowledgeTools personalKnowledgeTools,
                           DocumentTools documentTools,
                           KeywordTools keywordTools,
                           UploadTools uploadTools,
                           QueryRewriteTools queryRewriteTools) {
        Toolkit toolkit = new Toolkit();
        // 注意：ToolRegistration 里只有一个 toolObject 字段，链式 .tool(a).tool(b)... 会互相覆盖，
        // 只有最后一个生效（表现为"工具莫名其妙不见了"）。必须逐个 apply()。
        toolkit.registration().tool(queryRewriteTools).apply();
        toolkit.registration().tool(webSearchTools).apply();
        toolkit.registration().tool(personalKnowledgeTools).apply();
        toolkit.registration().tool(documentTools).apply();
        toolkit.registration().tool(keywordTools).apply();
        toolkit.registration().tool(uploadTools).apply();
        toolkit.registration()
                .mcpClient(ragMcpClient)
                .apply();
        // 知识库查询改由本地包装工具承担（只有这样它才用得上 ToolEmitter 报进度），
        // 所以把 MCP 直连的那个摘掉——两个功能相同的工具同时存在，模型会随机挑一个，
        // 挑中直连的就又看不到进度了。上传工具保留：它走 HITL 拦截，且 upload2Rag 留作兜底。
        toolkit.removeTool("answerByPersonalKnowledge");
        log.info("基础 toolkit 注册完成, 可用工具: {}", toolkit.getToolNames());
        return toolkit;
    }
}

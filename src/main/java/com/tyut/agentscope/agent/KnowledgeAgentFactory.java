package com.tyut.agentscope.agent;

import com.tyut.agentscope.common.PromptLoader;
import com.tyut.agentscope.hook.QueryRewriteHook;
import com.tyut.agentscope.hook.UploadApprovalHook;
import com.tyut.agentscope.hook.IntentRecognitionHook;
import com.tyut.agentscope.intent.IntentClassifier;
import com.tyut.agentscope.intent.RouteState;
import com.tyut.agentscope.intent.Routes;
import com.tyut.agentscope.keyword.KeywordLibrary;
import com.tyut.agentscope.skill.SkillCatalog;
import com.tyut.agentscope.tool.DocumentTools;
import com.tyut.agentscope.tool.KeywordTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.SkillHook;
import io.agentscope.core.state.StatePersistence;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * <h2>知识智能体工厂</h2>
 *
 * <p>按请求构建 agent。每个 agent 拿到自己的 memory、以及一份从基础 toolkit 复制出来的 toolkit
 * 与 SkillBox —— skill 激活会改动工具组状态，共享的话并发会话会互相影响。
 * 系统提示词从 {@code prompts/agent-system.st} 加载，改提示词不用改代码。
 *
 * <p><b>意图识别 Hook 在这里创建</b>：它带着 userId 和"用户是否强制指定路由"，
 * 都是每请求不同的值，所以不能做成单例 bean。
 */
@Component
public class KnowledgeAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAgentFactory.class);
    private static final int MAX_ITERS = 12;

    private final Model chatModel;
    private final Toolkit baseToolkit;
    private final SkillCatalog skillCatalog;
    private final QueryRewriteHook queryRewriteHook;
    private final UploadApprovalHook uploadApprovalHook;
    private final PromptLoader promptLoader;
    private final IntentClassifier intentClassifier;
    private final KeywordLibrary keywordLibrary;

    public KnowledgeAgentFactory(@Qualifier("chatModel") Model chatModel,
                                 Toolkit baseToolkit,
                                 SkillCatalog skillCatalog,
                                 QueryRewriteHook queryRewriteHook,
                                 UploadApprovalHook uploadApprovalHook,
                                 PromptLoader promptLoader,
                                 IntentClassifier intentClassifier,
                                 KeywordLibrary keywordLibrary) {
        this.chatModel = chatModel;
        this.baseToolkit = baseToolkit;
        this.skillCatalog = skillCatalog;
        this.queryRewriteHook = queryRewriteHook;
        this.uploadApprovalHook = uploadApprovalHook;
        this.promptLoader = promptLoader;
        this.intentClassifier = intentClassifier;
        this.keywordLibrary = keywordLibrary;
    }

    /**
     * 构建一次请求专属的 agent。
     *
     * @param memory      本次会话的记忆
     * @param userId      当前用户 id，用于取该用户的词表做意图识别
     * @param nickname   当前用户昵称，写进系统提示词；调用 query_personal_knowledge 时由工具从上下文取
     * @param rootFolder 用户在界面上设置的 Markdown 保存目录，作为 write_markdown 的默认 outputDir
     * @param forcedRoute 用户强制指定的检索路径（{@code rag} / {@code web}），空表示不强制
     */
    public ReActAgent create(Memory memory, String userId, String nickname,
                             String rootFolder, String forcedRoute) {
        if (memory == null) {
            throw new IllegalArgumentException("memory 不能为空");
        }

        // 直接用共享的 toolkit：skill 绑定工具组那段目前是空操作、SkillHook 也不改工具组，
        // 复制一份没有收益，反而有丢掉 MCP 注册工具的风险。
        Toolkit toolkit = baseToolkit;
        SkillBox skillBox = skillCatalog.newSkillBox(toolkit);
        String systemPrompt = promptLoader.load("prompts/agent-system.st",
                Map.of(
                        "nickname", (nickname == null || nickname.isBlank()) ? "（未知）" : nickname,
                        "rootFolder", (rootFolder == null || rootFolder.isBlank())
                                ? "（未设置，可省略 outputDir，落到服务端默认目录）"
                                : rootFolder));

        // 路由状态：agent 构建时先放空壳，PreCall 阶段由意图识别 Hook 写入判定结果，
        // 工具层再通过 ToolExecutionContext 读到它做拦截。
        RouteState routeState = new RouteState();
        IntentRecognitionHook intentRecognitionHook = new IntentRecognitionHook(
                intentClassifier, keywordLibrary, routeState, userId,
                Routes.fromForcedValue(forcedRoute));

        ReActAgent agent = ReActAgent.builder()
                .name("personalKnowledgeAgent")
                .sysPrompt(systemPrompt)
                .model(chatModel)
                .toolkit(toolkit)
                .memory(memory)
                .skillBox(skillBox)
                // 只让 memory 参与会话持久化
                .statePersistence(StatePersistence.memoryOnly())
                .hook(new SkillHook(skillBox))
                .hook(queryRewriteHook)
                .hook(intentRecognitionHook)
                .hook(uploadApprovalHook)
                .maxIters(MAX_ITERS)
                .enablePendingToolRecovery(true)
                // 用户信息放进工具上下文：工具里能直接读到，不用指望模型把参数传对
                .toolExecutionContext(ToolExecutionContext.builder()
                       .register(DocumentTools.ROOT_FOLDER_KEY, rootFolder == null ? "" : rootFolder)
                        .register(KeywordTools.USER_ID_KEY, userId == null ? "" : userId)
                       .register("nickname", nickname == null ? "" : nickname)
                        .register(Routes.STATE_KEY, RouteState.class, routeState)
                        .build())
                .build();

        log.debug("agent 已构建, 可用工具={}", toolkit.getToolNames());
        return agent;
    }

}

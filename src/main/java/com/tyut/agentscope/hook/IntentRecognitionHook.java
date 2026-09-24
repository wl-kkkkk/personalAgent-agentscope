package com.tyut.agentscope.hook;

import com.tyut.agentscope.intent.IntentClassifier;
import com.tyut.agentscope.intent.IntentDecision;
import com.tyut.agentscope.intent.RouteState;
import com.tyut.agentscope.intent.Routes;
import com.tyut.agentscope.keyword.KeywordLibrary;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>意图识别 Hook</h2>
 *
 * <p>每次提问进入系统时判定"这一轮该查个人知识库还是联网检索"，做三件事：
 * <ol>
 *   <li>取该用户的词表，连同问题、最近对话一起交给 {@link IntentClassifier} 做结构化意图识别；</li>
 *   <li>把结论写进 {@link RouteState}（工具层据此硬拦截，见 {@code WebSearchTools}）；</li>
 *   <li>把结论作为一行路由指令附在用户消息末尾，让模型明确知道本轮该走哪条 skill 流程。</li>
 * </ol>
 *
 * <p><b>为什么是 Hook</b>：和查询改写同理——这件事每轮都必须做，交给模型决定它就一定会跳过。
 *
 * <p><b>优先级 60</b>：{@code QueryRewriteHook} 是 50，先跑。意图识别要拿改写后的查询去匹配
 * 词表，否则"那个东西怎么配"这类带指代的问题匹配不上任何关键词。
 *
 * <p><b>为什么不是 Spring 单例</b>：它带着 userId 和"用户是否强制指定路由"，都是每请求不同的值。
 * 做成单例就得用 ThreadLocal 或者可变字段，在响应式链路下会串。所以由
 * {@code KnowledgeAgentFactory} 每请求 new 一个。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>只处理 role = USER 且有文本的消息：HITL 恢复时进来的是工具结果消息，直接跳过；</li>
 *   <li>用户强制指定路由：不调模型，直接用用户的选择；</li>
 *   <li>词表为空（新用户）：照常判意图，提示词里标注"词表为空"；</li>
 *   <li>历史为空：照常判，只取最近若干条并截断，避免提示词膨胀。</li>
 * </ul>
 */
public class IntentRecognitionHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognitionHook.class);
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_HISTORY_CHARS = 2000;

    private final IntentClassifier classifier;
    private final KeywordLibrary keywordLibrary;
    private final RouteState routeState;
    private final String userId;

    /** 用户强制指定的路由（{@link Routes#RAG} / {@link Routes#WEB}），null 表示不强制 */
    private final String forcedRoute;

    public IntentRecognitionHook(IntentClassifier classifier,
                                 KeywordLibrary keywordLibrary,
                                 RouteState routeState,
                                 String userId,
                                 String forcedRoute) {
        this.classifier = classifier;
        this.keywordLibrary = keywordLibrary;
        this.routeState = routeState;
        this.userId = userId;
        this.forcedRoute = forcedRoute;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreCallEvent preCall)) {
            return Mono.just(event);
        }
        List<Msg> inputs = preCall.getInputMessages();
        if (inputs == null || inputs.isEmpty()) {
            return Mono.just(event);
        }

        String history = historyOf(preCall.getMemory());
        List<Msg> routedInputs = new ArrayList<>(inputs.size());
        boolean changed = false;

        for (Msg input : inputs) {
            if (input == null || input.getRole() != MsgRole.USER || !hasText(input)) {
                routedInputs.add(input);
                continue;
            }
            String query = input.getTextContent();
            IntentDecision decision = decide(query, history);
            routeState.apply(decision, forcedRoute != null);

            String routed = query + directive(decision);
            log.info("[意图识别] route={}, 强制={}, 命中词表={}, 注入指令: 「{}」",
                    decision.route(), forcedRoute != null, decision.matchedKeywords(),
                    oneLine(directive(decision)));
            routedInputs.add(Msg.builder().role(MsgRole.USER).textContent(routed).build());
            changed = true;
        }

        if (changed) {
            preCall.setInputMessages(routedInputs);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 60;
    }

    /** 用户强制指定时直接采用，否则交给模型判定 */
    private IntentDecision decide(String query, String history) {
        if (forcedRoute != null) {
            log.info("[意图识别] 用户强制路由: {}", forcedRoute);
            return IntentDecision.forced(forcedRoute, "用户在界面上强制指定");
        }
        return classifier.classify(query, history, keywordLibrary.list(userId));
    }

    /**
     * 拼一行路由指令附在用户消息后面。
     *
     * <p>代价是会跟用户消息一起进记忆：所以措辞上明确写"本轮"，并且只写一行。
     * 换成"按 skill 收窄工具集"的做法可以完全不碰消息内容，但当前 agent 复用共享 toolkit
     * （见 KnowledgeAgentFactory 的说明），工具集没法按请求改动，所以先走注入这条路。
     */
    private String directive(IntentDecision decision) {
        StringBuilder sb = new StringBuilder("\n\n【本轮路由】");
        if (forcedRoute != null) {
            sb.append("用户强制要求走").append(Routes.label(decision.route())).append("。");
        } else {
            sb.append("走").append(Routes.label(decision.route()));
            if (!decision.matchedKeywords().isEmpty()) {
                sb.append("（命中词表关键词：").append(String.join("、", decision.matchedKeywords())).append("）");
            }
            sb.append("。意图：").append(decision.intent());
            if (!decision.reasoning().isBlank()) {
                sb.append("；依据：").append(decision.reasoning());
            }
            sb.append("。");
        }
        sb.append(Routes.RAG.equals(decision.route())
                ? "请按 knowledge-base-qa 的流程执行，本轮不要联网检索。"
                : "请按 web-knowledge-capture 的流程执行。");
        return sb.toString();
    }

    private boolean hasText(Msg msg) {
        String text = msg.getTextContent();
        return text != null && !text.isBlank();
    }

    /** 取最近若干条对话拼成历史文本，并做长度截断 */
    private String historyOf(Memory memory) {
        if (memory == null) {
            return "";
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < messages.size(); i++) {
            Msg message = messages.get(i);
            if (message == null) {
                continue;
            }
            String text = message.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            builder.append(message.getRole()).append(": ").append(text).append('\n');
        }
        String history = builder.toString().trim();
        if (history.length() > MAX_HISTORY_CHARS) {
            history = history.substring(history.length() - MAX_HISTORY_CHARS);
        }
        return history;
    }

    private String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }
}

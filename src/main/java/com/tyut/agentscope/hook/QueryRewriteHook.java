package com.tyut.agentscope.hook;

import com.tyut.agentscope.tool.QueryRewriteTools;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>查询改写 Hook</h2>
 *
 * <p>在 {@link PreCallEvent} 阶段改写用户输入，保证"只要用户提问就一定先改写"——
 * 这是 Hook 相对 Tool 的关键差别：Tool 由模型决定调不调，Hook 由框架保证执行。
 *
 * <p>历史对话直接从 {@code event.getMemory()} 取，不需要模型把上下文当参数传进来。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>只改写 role = USER 且有文本的消息；HITL 恢复时输入是工具结果消息，会被跳过；</li>
 *   <li>改写结果为空、或与原问题相同：保留原消息不动，避免把内容改丢；</li>
 *   <li>历史为空：照常改写，只在提示词里说明"无历史对话"；</li>
 *   <li>历史过长：只取最近若干条并做长度截断，避免提示词膨胀。</li>
 * </ul>
 */
@Component
public class QueryRewriteHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteHook.class);
    private static final int MAX_HISTORY_MESSAGES = 10;
    private static final int MAX_HISTORY_CHARS = 2000;

    private final QueryRewriteTools queryRewriteTools;

    public QueryRewriteHook(QueryRewriteTools queryRewriteTools) {
        this.queryRewriteTools = queryRewriteTools;
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
        List<Msg> rewrittenInputs = new ArrayList<>(inputs.size());
        boolean changed = false;

        for (Msg input : inputs) {
            if (input == null || input.getRole() != MsgRole.USER || !hasText(input)) {
                rewrittenInputs.add(input);
                continue;
            }
            String original = input.getTextContent();
            String rewritten = queryRewriteTools.rewriteQuery(original, history);
            if (rewritten == null || rewritten.isBlank() || rewritten.equals(original)) {
                rewrittenInputs.add(input);
                continue;
            }
            log.info("[查询改写] 「{}」 -> 「{}」", original, rewritten);
            rewrittenInputs.add(Msg.builder().role(MsgRole.USER).textContent(rewritten).build());
            changed = true;
        }

        if (changed) {
            preCall.setInputMessages(rewrittenInputs);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 50;
    }

    private boolean hasText(Msg msg) {
        String text = msg.getTextContent();
        return text != null && !text.isBlank();
    }

    /** 取最近若干条对话拼成历史文本，并做长度截断。 */
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
}

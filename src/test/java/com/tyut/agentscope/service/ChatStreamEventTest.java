package com.tyut.agentscope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.common.StreamEvent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具进度与工具结果的分流（不依赖 DB / LLM）。
 *
 * <p>工具用 {@code ToolEmitter} 推的中间片段，框架会当成 {@code TOOL_RESULT} 事件发出来、
 * 但 {@code isLast=false}；工具真正返回的那条才是 {@code isLast=true}。
 * 前者只是给用户看的进度提示，后者才该留在工具结果区供排查——分错了会出现
 * "每一步进度都被当成结果显示"或者反过来"结果一闪而过"。
 *
 * <p><b>为什么事件消息要按框架的真实形状造</b>：框架给 TOOL 事件装的是
 * {@link ToolResultBlock}，而 {@code Msg.getTextContent()} 只认 {@link TextBlock}。
 * 早先这里用 {@code textContent(...)} 造消息，测试是绿的，线上工具内容却一个字都显示不出来——
 * 因为那条路径在真实事件上会拿到空串然后被整段丢掉。所以下面统一用框架的包法。
 */
class ChatStreamEventTest {

    private final ChatService chatService =
            new ChatService(null, null, null, null, null, null, new ObjectMapper());

    @Test
    void toolChunkBecomesProgressHint() {
        List<StreamEvent> events = collect(toolEvent("正在联网检索：xxx", false));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("progress");
        assertThat(events.get(0).message()).isEqualTo("web_search · 正在联网检索：xxx");
    }

    @Test
    void finalToolResultStaysInToolArea() {
        List<StreamEvent> events = collect(toolEvent("检索结果正文", true));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("tool");
        assertThat(events.get(0).message()).isEqualTo("web_search · 检索结果正文");
    }

    @Test
    void toolNameIsPrependedSoTheFrontendKnowsWhoIsRunning() {
        List<StreamEvent> events = collect(toolEvent("正在检索个人知识库：Redis 怎么配", false));

        assertThat(events.get(0).message()).startsWith("web_search · ");
    }

    @Test
    void plainTextToolMessageStillWorks() {
        // 兜底：即便内容块是 TextBlock（不是 ToolResultBlock），也要能取到文本
        Event event = new Event(EventType.TOOL_RESULT,
                Msg.builder().role(MsgRole.TOOL).textContent("没有工具名的结果").build(), true);

        List<StreamEvent> events = collect(event);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("tool");
        assertThat(events.get(0).message()).isEqualTo("没有工具名的结果");
    }

    @Test
    void multiLineChunkIsCompressedToOneLine() {
        List<StreamEvent> events = collect(toolEvent("第一行\n第二行", false));

        // SSE 的 data 帧不能带换行，压成一行是既有约定
        assertThat(events.get(0).message()).isEqualTo("web_search · 第一行 第二行");
    }

    @Test
    void blankAndUnknownEventsAreIgnored() {
        assertThat(collect(toolEvent("   ", false))).isEmpty();
        assertThat(collect(new Event(EventType.AGENT_RESULT, null, true))).isEmpty();
    }

    @Test
    void agentResultBecomesAnswer() {
        List<StreamEvent> events = collect(new Event(EventType.AGENT_RESULT,
                Msg.builder().role(MsgRole.ASSISTANT).textContent("最终回答").build(), true));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("answer");
        assertThat(events.get(0).text()).isEqualTo("最终回答");
    }

    /** 按框架的真实形状造 TOOL 事件：内容块是 ToolResultBlock，不是 TextBlock */
    private Event toolEvent(String text, boolean isLast) {
        ToolResultBlock result = ToolResultBlock.text(text).withIdAndName("call-1", "web_search");
        Msg msg = Msg.builder()
                .name("system")
                .role(MsgRole.TOOL)
                .content(List.of(result))
                .build();
        return new Event(EventType.TOOL_RESULT, msg, isLast);
    }

    private List<StreamEvent> collect(Event event) {
        List<StreamEvent> events = new ArrayList<>();
        chatService.emitEvent(event, events::add);
        return events;
    }
}

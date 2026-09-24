package com.tyut.agentscope.tool;

import com.tyut.agentscope.common.ModelCaller;
import com.tyut.agentscope.intent.RouteState;
import com.tyut.agentscope.intent.Routes;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolEmitter;
import io.agentscope.core.tool.ToolExecutionContext;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * <h2>联网检索工具</h2>
 *
 * <p>用开启了 {@code enable_search} 的 DashScope 模型检索最新信息，返回要点文本。
 *
 * <p><b>进度上报</b>：联网检索是整条链路里最慢的一步（几十秒），所以通过框架注入的
 * {@link ToolEmitter} 往前端推一条"正在检索"。emit 出去的片段只进 Hook 和流式事件、
 * **不进模型上下文**，只有方法返回值才作为 tool result 交给模型——这一点由框架保证。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>查询为空：不发起检索，直接返回提示文本（工具返回文本而不是抛异常，避免打断 agent 循环）；</li>
 *   <li>检索无结果：返回明确的"没有返回内容"，让模型据此走 missing 分支而不是编造；</li>
 *   <li>调用异常：捕获异常并返回友好提示，避免打断 agent 循环，同时记录详细诊断信息。</li>
 * </ul>
 */
@Component
public class WebSearchTools {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTools.class);

    private final Model webSearchModel;
    private final ModelCaller modelCaller;

    public WebSearchTools(@Qualifier("webSearchModel") Model webSearchModel, ModelCaller modelCaller) {
        this.webSearchModel = webSearchModel;
        this.modelCaller = modelCaller;
    }

    @Tool(name = "web_search", description = "联网检索，返回该问题最新的检索结果要点")
    public String webSearch(@ToolParam(name = "query", description = "要联网检索的问题") String query,
                            ToolExecutionContext context,
                            ToolEmitter emitter) {
        if (query == null || query.isBlank()) {
            log.warn("联网检索被跳过：查询为空");
            return "（查询为空，未执行联网检索）";
        }

        // 硬拦截：意图识别判定这一轮该查知识库时，联网这条路直接封掉。
        // 只注入提示词是不够的——ReAct 的模型有权自己决定调哪个工具，这里再兜一道。
        RouteState routeState = context == null
                ? null
                : context.get(Routes.STATE_KEY, RouteState.class);
        if (routeState != null && routeState.blocksWebSearch()) {
            log.info("[路由拦截] 本轮路由={}(强制={}), 拒绝联网检索: query=「{}」",
                    routeState.route(), routeState.forced(), query);
            return "（本轮已判定为用户个人知识库问题，联网检索未执行；请改用个人知识库检索工具回答。"
                    + "如果知识库确实查不到，再如实告诉用户。）";
        }

        log.info("联网检索开始: model={}, query=「{}」", webSearchModel.getModelName(), query);
        emitter.emit(ToolResultBlock.text("正在联网检索：" + query));

        String prompt = """
                你是联网搜索助手。请基于最新联网检索结果，用中文整理出该主题的关键要点，只输出正文，不要客套。

                检索问题：%s
                """.formatted(query);

        try {
            String result = modelCaller.call(webSearchModel, prompt);
            if (result == null || result.isBlank()) {
                log.warn("联网检索无结果: query=「{}」", query);
                return "（联网检索没有返回内容）";
            }
            log.info("联网检索完成: query=「{}」, 返回 {} 字符", query, result.length());
            emitter.emit(ToolResultBlock.text("联网检索完成，拿到 " + result.length() + " 字结果，正在整理"));
            return result;
        } catch (Exception e) {
            // 不要把原因吞掉：原样带出异常类型和消息，界面的工具结果行与日志里都能直接看到
            log.error("联网检索失败: query=「{}」", query, e);
            return "联网检索失败：" + e.getClass().getName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage());
        }
    }
}

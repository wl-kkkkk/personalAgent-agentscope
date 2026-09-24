package com.tyut.agentscope.intent;

import java.util.List;

/**
 * <h2>本轮路由状态（每次请求一份）</h2>
 *
 * <p>Hook 判定出来的路由结果放在这里，通过 {@code ToolExecutionContext} 共享给工具层，
 * 工具据此做硬拦截。这样路由就不只是"注入一句提示词、指望模型照做"，
 * 在工具这一层也拦得住。
 *
 * <p><b>为什么可变</b>：agent 是先构建、Hook 才在 PreCall 阶段跑，构建时还没有路由结果，
 * 所以先放一个空壳进去，等 Hook 判定完再写值。这个对象每请求一份，
 * 不存在跨会话串台的问题。
 */
public final class RouteState {

    private volatile String route = "";
    private volatile String intent = "";
    private volatile List<String> matchedKeywords = List.of();
    private volatile String reason = "";
    private volatile boolean forced;

    /** Hook 判定完成后写入 */
    public void apply(IntentDecision decision, boolean forcedByUser) {
        this.route = decision.route();
        this.intent = decision.intent();
        this.matchedKeywords = decision.matchedKeywords();
        this.reason = decision.reasoning();
        this.forced = forcedByUser;
    }

    /** 是否已经判定过（没判定时工具层不做拦截） */
    public boolean decided() {
        return Routes.isValid(route);
    }

    /**
     * 本轮是否禁止联网检索。
     *
     * <p>只拦"知识库路由下的联网"这一个方向：用户问自己的资料时联网既浪费又容易跑偏；
     * 反过来（路由为联网时仍允许查知识库）不拦，知识库命中反而是意外收获。
     */
    public boolean blocksWebSearch() {
        return Routes.RAG.equals(route);
    }

    public String route() {
        return route;
    }

    public String intent() {
        return intent;
    }

    public List<String> matchedKeywords() {
        return matchedKeywords;
    }

    public String reason() {
        return reason;
    }

    public boolean forced() {
        return forced;
    }
}

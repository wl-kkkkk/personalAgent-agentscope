package com.tyut.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.common.JsonSupport;
import com.tyut.agentscope.intent.IntentDecision;
import com.tyut.agentscope.intent.RouteState;
import com.tyut.agentscope.intent.Routes;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化意图识别的解析与降级策略：不依赖 DB / LLM。
 */
class IntentDecisionTest {

    private final JsonSupport jsonSupport = new JsonSupport(new ObjectMapper());

    @Test
    void parsesPlainJsonIntoTypedDecision() {
        String raw = """
                {"intent":"RAG 检索优化","route":"knowledge_base","matchedKeywords":["RAG"],"reasoning":"命中词表关键词","confidence":0.9}
                """;

        Optional<IntentDecision> parsed = jsonSupport.parse(raw, IntentDecision.class);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().route()).isEqualTo(Routes.RAG);
        assertThat(parsed.get().matchedKeywords()).containsExactly("RAG");
        assertThat(parsed.get().confidence()).isEqualTo(0.9);
    }

    @Test
    void parsesJsonWrappedInCodeFenceAndTrailingProse() {
        String raw = """
                好的，结果如下：
                ```json
                {"intent":"模型发布","route":"web","matchedKeywords":[],"reasoning":"时效性问题","confidence":0.8}
                ```
                以上。
                """;

        Optional<IntentDecision> parsed = jsonSupport.parse(raw, IntentDecision.class);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().route()).isEqualTo(Routes.WEB);
    }

    @Test
    void braceInsideStringDoesNotBreakExtraction() {
        String raw = """
                {"intent":"配置写法","route":"knowledge_base","matchedKeywords":["RAG"],
                 "reasoning":"用户提到「{token} 怎么配」","confidence":0.7}
                """;

        String json = jsonSupport.extractJsonObject(raw);

        assertThat(json).isNotNull();
        assertThat(jsonSupport.parse(raw, IntentDecision.class)).isPresent();
    }

    @Test
    void missingFieldsDoNotProduceNulls() {
        IntentDecision decision = jsonSupport
                .parse("{\"route\":\"web\"}", IntentDecision.class)
                .orElseThrow();

        assertThat(decision.intent()).isEqualTo("未识别");
        assertThat(decision.matchedKeywords()).isEmpty();
        assertThat(decision.reasoning()).isEmpty();
        assertThat(decision.confidence()).isZero();
    }

    @Test
    void unparsableOutputYieldsEmptyInsteadOfThrowing() {
        assertThat(jsonSupport.parse("我不知道该怎么判断", IntentDecision.class)).isEmpty();
        assertThat(jsonSupport.parse("", IntentDecision.class)).isEmpty();
        assertThat(jsonSupport.parse(null, IntentDecision.class)).isEmpty();
    }

    @Test
    void fallbackAndForcedRoutes() {
        assertThat(IntentDecision.fallback("模型没返回").route()).isEqualTo(Routes.RAG);
        assertThat(IntentDecision.forced(Routes.WEB, "用户强制").route()).isEqualTo(Routes.WEB);
        assertThat(IntentDecision.forced(Routes.WEB, "用户强制").confidence()).isEqualTo(1.0);

        IntentDecision downgraded = new IntentDecision("x", Routes.WEB, null, "r", 0.1)
                .withRoute(Routes.RAG, "置信度过低");
        assertThat(downgraded.route()).isEqualTo(Routes.RAG);
        assertThat(downgraded.matchedKeywords()).isEmpty();
    }

    @Test
    void invalidRouteIsDetected() {
        IntentDecision decision = jsonSupport
                .parse("{\"intent\":\"x\",\"route\":\"mysql\",\"confidence\":0.9}", IntentDecision.class)
                .orElseThrow();

        assertThat(decision.routeValid()).isFalse();
    }

    @Test
    void forcedValueMappingAndRouteState() {
        assertThat(Routes.fromForcedValue("rag")).isEqualTo(Routes.RAG);
        assertThat(Routes.fromForcedValue("WEB")).isEqualTo(Routes.WEB);
        assertThat(Routes.fromForcedValue("")).isNull();
        assertThat(Routes.fromForcedValue("随便")).isNull();

        RouteState state = new RouteState();
        assertThat(state.decided()).isFalse();
        assertThat(state.blocksWebSearch()).isFalse();

        state.apply(IntentDecision.forced(Routes.RAG, "用户强制"), true);
        assertThat(state.decided()).isTrue();
        assertThat(state.blocksWebSearch()).isTrue();

        state.apply(IntentDecision.forced(Routes.WEB, "用户强制"), true);
        assertThat(state.blocksWebSearch()).isFalse();
    }
}

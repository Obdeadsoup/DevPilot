package com.obdeadsoup.devpilot.knowledge.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAwareQueryRewriterTest {
    private final ConversationAwareQueryRewriter rewriter = new ConversationAwareQueryRewriter();

    @Test
    void rewritesContextDependentFollowUpWithLatestQuestion() {
        String rewritten = rewriter.rewrite("那一直失败怎么办？", List.of(
                "项目如何部署？",
                "DevPilot 为什么使用 Outbox？"
        ));

        assertThat(rewritten).isEqualTo("DevPilot 为什么使用 Outbox？\nFollow-up question: 那一直失败怎么办？");
    }

    @Test
    void leavesStandaloneQueryUntouched() {
        String query = "DevPilot 的 AgentRun 超时补偿与 Outbox 重试机制是什么？";
        assertThat(rewriter.rewrite(query, List.of("无关历史"))).isEqualTo(query);
    }
}

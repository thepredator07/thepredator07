package com.ticketfactory.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StreamJsonParserTest {

    private static StreamJsonParser.Outcome parse(String text, int chunk) {
        StreamJsonParser p = new StreamJsonParser();
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < b.length; i += chunk) {
            p.write(b, i, Math.min(chunk, b.length - i));
        }
        p.close();
        return p.outcome();
    }

    /** Real output of Claude Code 2.1.291 (one Bash step against the fake API), split at odd chunk sizes. */
    @Test
    void readsTheResultOfARealRun() throws IOException {
        String real;
        try (InputStream in = getClass().getResourceAsStream("/agent/claude-2.1.291-stream.jsonl")) {
            real = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (int chunk : new int[] {1, 7, 4096, real.length()}) {
            var o = parse(real, chunk);
            assertThat(o.completed()).isTrue();
            assertThat(o.subtype()).isEqualTo("success");
            assertThat(o.isError()).isFalse();
            assertThat(o.turns()).isEqualTo(2);
            assertThat(o.costUsd()).isEqualByComparingTo("0.0052");
            assertThat(o.inputTokens()).isEqualTo(3000);
            assertThat(o.outputTokens()).isEqualTo(100);
            assertThat(o.resultText()).isEqualTo("Scripted run 'edit' finished after 1 step(s).");
        }
    }

    @Test
    void readsTurnLimitAndBudgetOutcomes() {
        var turns = parse("""
                {"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":4,"total_cost_usd":0.31,\
                "usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":3}}
                """, 64);
        assertThat(turns.subtype()).isEqualTo("error_max_turns");
        assertThat(turns.isError()).isTrue();
        assertThat(turns.turns()).isEqualTo(4);
        assertThat(turns.inputTokens()).isEqualTo(15);
        assertThat(turns.costUsd()).isEqualByComparingTo("0.31");
    }

    @Test
    void whenCutOffItCountsEachAssistantMessageOnceAndTheCostIsUnknown() {
        var o = parse("""
                {"type":"system","subtype":"init"}
                {"type":"assistant","message":{"id":"msg_1","usage":{"input_tokens":100,"output_tokens":10}}}
                {"type":"assistant","message":{"id":"msg_1","usage":{"input_tokens":100,"output_tokens":10}}}
                {"type":"user","message":{"content":[]}}
                {"type":"assistant","message":{"id":"msg_2","usage":{"input_tokens":200,"cache_read_input_tokens":50,"output_tokens":20}}}
                {"type":"assistant","message":{"id":"msg_3","usage":{"input_to""", 5);
        assertThat(o.completed()).isFalse();
        assertThat(o.turns()).isEqualTo(2);
        assertThat(o.inputTokens()).isEqualTo(350);
        assertThat(o.outputTokens()).isEqualTo(30);
        assertThat(o.costUsd()).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void ignoresNonJsonLinesAndHugeLines() {
        String huge = "{\"type\":\"user\",\"x\":\"" + "a".repeat(5 * 1024 * 1024) + "\"}";
        var o = parse("Warning: something on stderr\n" + huge + "\nnot json {\n"
                + "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"num_turns\":1}\n", 65536);
        assertThat(o.completed()).isTrue();
        assertThat(o.turns()).isEqualTo(1);
        assertThat(o.costUsd()).isEqualTo(BigDecimal.ZERO);
    }
}

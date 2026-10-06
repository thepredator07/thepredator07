package com.ticketfactory.integration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Reads Claude Code's {@code --output-format stream-json} output as it arrives, one JSON object per line, in fixed
 * memory. Keeps the final {@code result} event (turns, usage, cost, outcome) and, in case the run is cut off before
 * it, a running count of assistant messages and their token usage. Anything that isn't JSON is ignored here; the
 * runner keeps a bounded copy of the raw output for error messages.
 */
public final class StreamJsonParser extends OutputStream {

    /** A single line longer than this (a huge tool result echoed back) is skipped rather than buffered. */
    private static final int MAX_LINE_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean skippingLongLine;
    private JsonNode result;
    private final java.util.Set<String> seenMessageIds = new java.util.HashSet<>();
    private int assistantMessages;
    private long inputTokens;
    private long outputTokens;

    /** What the run reported. {@code completed} is false when there was no result event (killed or crashed). */
    public record Outcome(boolean completed, String subtype, boolean isError, int turns, long inputTokens,
                          long outputTokens, BigDecimal costUsd, String resultText) {
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            endLine();
        } else if (!skippingLongLine) {
            line.write(b);
            if (line.size() > MAX_LINE_BYTES) {
                line.reset();
                skippingLongLine = true;
            }
        }
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            write(b[i]);
        }
    }

    @Override
    public synchronized void close() {
        endLine();
    }

    private void endLine() {
        if (!skippingLongLine && line.size() > 0) {
            handle(line.toString(StandardCharsets.UTF_8).strip());
        }
        line.reset();
        skippingLongLine = false;
    }

    private void handle(String text) {
        if (!text.startsWith("{")) {
            return;
        }
        JsonNode node;
        try {
            node = JSON.readTree(text);
        } catch (Exception e) {
            return;
        }
        switch (node.path("type").asText()) {
            case "result" -> result = node;
            case "assistant" -> {
                // One API message can arrive as several events (one per content block) with the same usage.
                String id = node.path("message").path("id").asText("");
                if (!id.isEmpty() && !seenMessageIds.add(id)) {
                    return;
                }
                assistantMessages++;
                JsonNode usage = node.path("message").path("usage");
                inputTokens += allInputTokens(usage);
                outputTokens += usage.path("output_tokens").asLong(0);
            }
            default -> {
                // init, user (tool results), system notices, stream events: not needed
            }
        }
    }

    /** Input includes cache reads and writes; they are all tokens sent to the model. */
    private static long allInputTokens(JsonNode usage) {
        return usage.path("input_tokens").asLong(0) + usage.path("cache_creation_input_tokens").asLong(0)
                + usage.path("cache_read_input_tokens").asLong(0);
    }

    public synchronized Outcome outcome() {
        if (result == null) {
            // Cut off: count what we saw. The CLI prices usage itself; without its result the cost is unknown.
            return new Outcome(false, "no_result", true, assistantMessages, inputTokens, outputTokens,
                    BigDecimal.ZERO, "");
        }
        JsonNode usage = result.path("usage");
        BigDecimal cost = result.hasNonNull("total_cost_usd")
                ? new BigDecimal(result.get("total_cost_usd").asText()) : BigDecimal.ZERO;
        return new Outcome(true, result.path("subtype").asText(""), result.path("is_error").asBoolean(true),
                result.path("num_turns").asInt(assistantMessages), allInputTokens(usage),
                usage.path("output_tokens").asLong(0), cost, result.path("result").asText(""));
    }
}

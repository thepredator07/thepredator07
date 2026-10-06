package com.ticketfactory.fake;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-ticket instructions for the fakes, written as lines in the issue body, e.g.
 *
 * <pre>
 * fake-sandbox: fail-then-succeed 1
 * fake-agent: fail
 * fake-agent-cost: 3.50       (dollars per agent run)
 * fake-agent-turns: 50        (turns per agent run)
 * fake-agent-delay: PT5S
 * fake-checks: fail-then-succeed 2
 * fake-github: fail
 * fake-approval: pending | approved | closed
 * </pre>
 *
 * Lets one running app (demo, tests) show every pipeline path without restarting with new config.
 */
public final class FakeScript {

    private static final Pattern LINE = Pattern.compile("(?im)^\\s*fake-([a-z-]+)\\s*:\\s*(.+?)\\s*$");

    private final Map<String, String> values;

    private FakeScript(Map<String, String> values) {
        this.values = values;
    }

    public static FakeScript parse(String body) {
        Map<String, String> values = new HashMap<>();
        if (body != null) {
            Matcher m = LINE.matcher(body);
            while (m.find()) {
                values.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
            }
        }
        return new FakeScript(values);
    }

    public FakeBehavior behavior(String step, FakeBehavior fallback) {
        return Optional.ofNullable(values.get(step)).map(FakeBehavior::parse).orElse(fallback);
    }

    public Optional<BigDecimal> decimal(String key) {
        return Optional.ofNullable(values.get(key)).map(BigDecimal::new);
    }

    public Optional<Integer> integer(String key) {
        return Optional.ofNullable(values.get(key)).map(Integer::parseInt);
    }

    public Optional<Duration> duration(String key) {
        return Optional.ofNullable(values.get(key)).map(Duration::parse);
    }

    public Optional<String> text(String key) {
        return Optional.ofNullable(values.get(key)).map(s -> s.toLowerCase(Locale.ROOT));
    }
}

package com.ticketfactory.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Keeps the live evaluation honest without spending anything: every fixture's tests fail on main as written, and pass
 * with a reference fix. Otherwise a "pass" or "fail" in the evaluation would say nothing about the agent.
 */
class EvalFixturesTest {

    /** Reference solutions, by fixture name: file to new content. */
    static final Map<String, Map<String, String>> SOLUTIONS = Map.of(
            "fizzbuzz", Map.of("fizz.py", """
                    def fizzbuzz(n):
                        return ["FizzBuzz" if i % 15 == 0 else "Fizz" if i % 3 == 0 else "Buzz" if i % 5 == 0
                                else str(i) for i in range(1, n + 1)]
                    """),
            "last-n", Map.of("lists.py", "def last_n(items, n):\n    return items[-n:] if n > 0 else []\n"),
            "slugify", Map.of("text.py", """
                    import re

                    def slugify(text):
                        return "-".join(re.findall(r"[a-z0-9]+", text.lower()))
                    """),
            "roman", Map.of("roman.py", """
                    NUMERALS = [(1000, "M"), (900, "CM"), (500, "D"), (400, "CD"), (100, "C"), (90, "XC"),
                                (50, "L"), (40, "XL"), (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I")]

                    def to_roman(n):
                        out = ""
                        for value, letters in NUMERALS:
                            while n >= value:
                                out += letters
                                n -= value
                        return out
                    """),
            "dedupe", Map.of("seq.py", "def dedupe(items):\n    return list(dict.fromkeys(items))\n"),
            "word-count", Map.of("words.py", """
                    import re

                    def word_count(text):
                        counts = {}
                        for word in re.findall(r"[a-z0-9']+", text.lower()):
                            counts[word] = counts.get(word, 0) + 1
                        return counts
                    """),
            "duration", Map.of("duration.py", """
                    import re

                    def parse_duration(text):
                        m = re.fullmatch(r"(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?", text)
                        if not text or not m:
                            raise ValueError(text)
                        h, mi, s = (int(g) if g else 0 for g in m.groups())
                        return h * 3600 + mi * 60 + s
                    """),
            "missing-user", Map.of("users.py",
                    "def get_email(users, user_id):\n    user = users.get(user_id)\n"
                            + "    return user[\"email\"] if user else None\n"),
            "median", Map.of("stats.py", """
                    def median(values):
                        o = sorted(values)
                        mid = len(o) // 2
                        return o[mid] if len(o) % 2 else (o[mid - 1] + o[mid]) / 2
                    """),
            "palindrome", Map.of("pal.py", """
                    def is_palindrome(text):
                        s = [c.lower() for c in text if c.isalnum()]
                        return s == s[::-1]
                    """));

    @Test
    void everyFixtureFailsAsWrittenAndPassesWithAReferenceFix() throws Exception {
        assumeTrue(python3Available(), "python3 not installed");
        assertThat(EvalFixtures.all()).hasSize(10);
        assertThat(SOLUTIONS.keySet()).containsExactlyInAnyOrderElementsOf(
                EvalFixtures.all().stream().map(EvalFixtures.Fixture::name).toList());
        for (EvalFixtures.Fixture f : EvalFixtures.all()) {
            Path dir = Files.createTempDirectory("eval-" + f.name());
            write(dir, f.files());
            assertThat(unittest(dir)).as(f.name() + " must fail on main").isNotZero();
            write(dir, SOLUTIONS.get(f.name()));
            assertThat(unittest(dir)).as(f.name() + " must pass with the reference fix").isZero();
        }
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        for (var e : files.entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static int unittest(Path dir) throws Exception {
        Process p = new ProcessBuilder("python3", "-m", "unittest", "-q").directory(dir.toFile())
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return p.exitValue();
    }

    private static boolean python3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}

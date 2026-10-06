package com.ticketfactory.integration.checks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BoundedOutputTest {

    private static void write(BoundedOutput out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.write(b, 0, b.length);
    }

    @Test
    void shortOutputIsKeptWhole() {
        BoundedOutput out = new BoundedOutput(8, 8);
        write(out, "hello");
        write(out, " world");
        assertThat(out).hasToString("hello world");
        assertThat(out.totalBytes()).isEqualTo(11);
    }

    @Test
    void outputThatExactlyFillsBothPartsHasNoMarker() {
        BoundedOutput out = new BoundedOutput(4, 4);
        write(out, "abcdefgh");
        assertThat(out).hasToString("abcdefgh");
    }

    @Test
    void longOutputKeepsTheStartAndTheEndAndSaysHowMuchWasDropped() {
        BoundedOutput out = new BoundedOutput(5, 5);
        write(out, "START");
        for (int i = 0; i < 1000; i++) {
            write(out, "middle-");
        }
        write(out, "ENDXY");
        assertThat(out.toString()).startsWith("START").endsWith("ENDXY").contains("[... 7000 bytes of output omitted ...]");
    }

    @Test
    void theEndIsCorrectWhateverTheChunkSizes() {
        BoundedOutput out = new BoundedOutput(3, 10);
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            String chunk = Integer.toString(i).repeat(i % 7 + 1);
            all.append(chunk);
            if (i % 3 == 0) {
                for (char c : chunk.toCharArray()) {
                    out.write(c);
                }
            } else {
                write(out, chunk);
            }
        }
        String s = out.toString();
        assertThat(s).startsWith(all.substring(0, 3)).endsWith(all.substring(all.length() - 10));
        assertThat(out.totalBytes()).isEqualTo(all.length());
    }

    @Test
    void oneWriteBiggerThanEverythingStillKeepsItsEnd() {
        BoundedOutput out = new BoundedOutput(2, 4);
        write(out, "0123456789");
        assertThat(out.toString()).startsWith("01").endsWith("6789").contains("[... 4 bytes");
    }

    @Test
    void textIsNeverLongerThanTheBuffersPlusTheMarker() {
        BoundedOutput out = new BoundedOutput(100, 100);
        for (int i = 0; i < 10_000; i++) {
            write(out, "é€😀"); // 2-, 3- and 4-byte characters, cut at arbitrary byte positions
        }
        assertThat(out.toString().length()).isLessThanOrEqualTo(200 + 64);
    }
}

package com.ticketfactory.integration.checks;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the start and the end of a stream and drops the middle, in fixed memory however much a build prints. The start
 * shows what ran; the end usually holds the failure summary.
 */
final class BoundedOutput extends OutputStream {

    private final byte[] head;
    private final byte[] tail; // ring buffer
    private int headLen;
    private long tailWritten;
    private long total;

    BoundedOutput(int headBytes, int tailBytes) {
        head = new byte[headBytes];
        tail = new byte[tailBytes];
    }

    @Override
    public synchronized void write(int b) {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        total += len;
        int toHead = Math.min(len, head.length - headLen);
        System.arraycopy(b, off, head, headLen, toHead);
        headLen += toHead;
        off += toHead;
        len -= toHead;
        if (len > tail.length) { // only the last tail.length bytes can survive
            off += len - tail.length;
            len = tail.length;
        }
        for (int i = 0; i < len; i++) {
            tail[(int) ((tailWritten + i) % tail.length)] = b[off + i];
        }
        tailWritten += len;
    }

    synchronized long totalBytes() {
        return total;
    }

    /** The kept text, with a marker where bytes were dropped. Never longer than head + tail + marker characters. */
    @Override
    public synchronized String toString() {
        StringBuilder s = new StringBuilder(new String(head, 0, headLen, StandardCharsets.UTF_8));
        int kept = (int) Math.min(tailWritten, tail.length);
        if (kept == 0) {
            return s.toString();
        }
        byte[] end = new byte[kept];
        int start = (int) (tailWritten % tail.length);
        for (int i = 0; i < kept; i++) {
            end[i] = tail[(kept < tail.length ? i : (start + i) % tail.length)];
        }
        long dropped = total - headLen - kept;
        if (dropped > 0) {
            s.append("\n\n[... ").append(dropped).append(" bytes of output omitted ...]\n\n");
        }
        return s.append(new String(end, StandardCharsets.UTF_8)).toString();
    }
}

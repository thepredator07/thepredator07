package com.ticketfactory.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Appends sections to {@code target/agent-eval/report.md}; the workflow shows it in the job summary. */
final class EvalReport {

    static final Path FILE = Path.of("target", "agent-eval", "report.md");

    static synchronized void append(String markdown) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, markdown.endsWith("\n") ? markdown : markdown + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private EvalReport() {
    }
}

package com.ticketfactory.integration.docker;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Docker sandbox settings ({@code factory.integrations=real}), bound from {@code factory.sandbox.*}.
 *
 * @param image              git, a shell and the Claude Code CLI: build {@code sandbox/Dockerfile}, adding the target
 *                           repo's toolchain
 * @param workspaceSize      tmpfs size for {@code /workspace} (counts against {@code memory})
 * @param hostWorkDir        where the host keeps bare clones of target repos
 * @param cloneUrlTemplate   {@code {repo}} is replaced with owner/name
 * @param janitorInterval    how often orphaned sandboxes are looked for
 */
@ConfigurationProperties("factory.sandbox")
public record SandboxProperties(
        @DefaultValue("factory-sandbox:latest") String image,
        @DefaultValue("4g") String memory,
        @DefaultValue("2.0") double cpus,
        @DefaultValue("512") long pidsLimit,
        @DefaultValue("2g") String workspaceSize,
        @DefaultValue("256m") String tmpSize,
        @DefaultValue("/var/lib/factory") Path hostWorkDir,
        @DefaultValue("https://github.com/{repo}.git") String cloneUrlTemplate,
        @DefaultValue("PT10M") Duration gitTimeout,
        @DefaultValue("PT10M") Duration janitorInterval) {

    /** Parses Docker-style sizes: {@code 512m}, {@code 4g}, {@code 1048576}. */
    public static long bytes(String size) {
        String s = size.trim().toLowerCase();
        long unit = switch (s.charAt(s.length() - 1)) {
            case 'k' -> 1024L;
            case 'm' -> 1024L * 1024;
            case 'g' -> 1024L * 1024 * 1024;
            default -> 1;
        };
        String digits = unit == 1 ? s : s.substring(0, s.length() - 1);
        return Long.parseLong(digits) * unit;
    }
}

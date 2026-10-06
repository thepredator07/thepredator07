package com.ticketfactory.integration.checks;

import com.ticketfactory.integration.UnrecoverableStepException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.convert.DurationStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * How to check one repo: the shell command and how long it may take. Comes from the repo's {@code .factory.yml}:
 *
 * <pre>
 * checks:
 *   command: ./gradlew --offline test
 *   timeout: 15m        # optional; 15m, 90s or PT15M
 * </pre>
 *
 * Any problem with the file is the repo's to fix, so it is reported as an {@link UnrecoverableStepException}.
 */
public record ChecksConfig(String command, Duration timeout) {

    public static ChecksConfig resolve(Optional<String> file, ChecksProperties props) {
        if (file.isEmpty()) {
            if (props.defaultCommand().isBlank()) {
                throw new UnrecoverableStepException("the repo has no " + props.configFile()
                        + " with a checks command, and no default is configured (factory.checks.default-command)");
            }
            return new ChecksConfig(props.defaultCommand(), props.defaultTimeout());
        }
        return parse(file.get(), props);
    }

    static ChecksConfig parse(String yaml, ChecksProperties props) {
        String where = props.configFile();
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(10);
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (YAMLException e) {
            throw new UnrecoverableStepException(where + " is not valid YAML: " + firstLine(e.getMessage()));
        }
        if (!(root instanceof Map<?, ?> top) || !(top.get("checks") instanceof Map<?, ?> checks)) {
            throw new UnrecoverableStepException(where + " has no 'checks' section");
        }
        if (!(checks.get("command") instanceof String command) || command.isBlank()) {
            throw new UnrecoverableStepException(where + ": checks.command must be a non-empty string");
        }
        Duration timeout = props.defaultTimeout();
        Object t = checks.get("timeout");
        if (t != null) {
            try {
                timeout = DurationStyle.detectAndParse(String.valueOf(t));
            } catch (IllegalArgumentException e) {
                throw new UnrecoverableStepException(where + ": checks.timeout '" + t
                        + "' is not a duration (examples: 15m, 90s, PT15M)");
            }
            if (timeout.isNegative() || timeout.isZero()) {
                throw new UnrecoverableStepException(where + ": checks.timeout must be positive");
            }
        }
        if (timeout.compareTo(props.maxTimeout()) > 0) {
            timeout = props.maxTimeout(); // the repo may not outlast the factory's limit
        }
        return new ChecksConfig(command.strip(), timeout);
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}

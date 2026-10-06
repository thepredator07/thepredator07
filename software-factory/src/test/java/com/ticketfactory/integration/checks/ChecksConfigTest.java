package com.ticketfactory.integration.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.integration.UnrecoverableStepException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChecksConfigTest {

    private final ChecksProperties props =
            new ChecksProperties(".factory.yml", "", Duration.ofMinutes(10), Duration.ofMinutes(30));

    private ChecksConfig parse(String yaml) {
        return ChecksConfig.parse(yaml, props);
    }

    @Test
    void readsCommandAndTimeout() {
        assertThat(parse("checks:\n  command: ./gradlew test\n  timeout: 15m\n"))
                .isEqualTo(new ChecksConfig("./gradlew test", Duration.ofMinutes(15)));
    }

    @Test
    void acceptsIsoAndSimpleDurations() {
        assertThat(parse("checks: {command: make, timeout: PT90S}").timeout()).isEqualTo(Duration.ofSeconds(90));
        assertThat(parse("checks: {command: make, timeout: 45s}").timeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void usesTheDefaultTimeoutWhenNoneIsGiven() {
        assertThat(parse("checks:\n  command: make test\n").timeout()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aRepoCannotAskForMoreThanTheMaximumTimeout() {
        assertThat(parse("checks: {command: make, timeout: 5h}").timeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void multiLineCommandsAreKept() {
        assertThat(parse("checks:\n  command: |\n    npm ci --offline\n    npm test\n").command())
                .isEqualTo("npm ci --offline\nnpm test");
    }

    @Test
    void otherSectionsAreIgnored() {
        assertThat(parse("agent: {model: x}\nchecks: {command: make}\n").command()).isEqualTo("make");
    }

    @Test
    void rejectsFilesWithoutAUsableCommand() {
        for (String bad : new String[] {"", "just a string", "checks: make", "checks: {}", "checks: {command: ''}",
                "checks: {command: [make, test]}", "other: {command: make}"}) {
            assertThatThrownBy(() -> parse(bad)).as(bad).isInstanceOf(UnrecoverableStepException.class)
                    .hasMessageStartingWith(".factory.yml");
        }
    }

    @Test
    void rejectsBadTimeouts() {
        assertThatThrownBy(() -> parse("checks: {command: make, timeout: soon}"))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("is not a duration");
        assertThatThrownBy(() -> parse("checks: {command: make, timeout: 0s}"))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("must be positive");
    }

    @Test
    void rejectsInvalidYamlAndDuplicateKeys() {
        assertThatThrownBy(() -> parse("checks: [unclosed"))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("not valid YAML");
        assertThatThrownBy(() -> parse("checks:\n  command: make\n  command: 'true'\n"))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("not valid YAML");
    }

    @Test
    void refusesToBuildJavaObjectsFromTags() {
        assertThatThrownBy(() -> parse("checks: !!java.io.File {}\n"))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("not valid YAML");
    }

    @Test
    void withoutAFileTheDefaultCommandIsUsedIfThereIsOne() {
        var withDefault = new ChecksProperties(".factory.yml", "make check", Duration.ofMinutes(7),
                Duration.ofMinutes(30));
        assertThat(ChecksConfig.resolve(Optional.empty(), withDefault))
                .isEqualTo(new ChecksConfig("make check", Duration.ofMinutes(7)));
        assertThatThrownBy(() -> ChecksConfig.resolve(Optional.empty(), props))
                .isInstanceOf(UnrecoverableStepException.class).hasMessageContaining("no .factory.yml");
    }
}

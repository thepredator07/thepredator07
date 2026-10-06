package com.ticketfactory.integration.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.integration.StepFailedException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link HostGit#readFile}, against a local bare repo (no Docker involved). */
class HostGitReadFileTest {

    private DockerSandboxFixture fx;
    private HostGit git;

    @BeforeEach
    void start() throws Exception {
        fx = new DockerSandboxFixture();
        git = new HostGit(fx.root.resolve("host"), "file://" + fx.root.resolve("remotes") + "/{repo}.git", null,
                Duration.ofSeconds(30));
    }

    @AfterEach
    void stop() throws Exception {
        fx.close();
    }

    @Test
    void readsAFileFromTheBranchAsTheRemoteHasItNow() {
        fx.commitToMain(Map.of(".factory.yml", "v1"), "v1");
        assertThat(git.readFile(DockerSandboxFixture.REPO, "main", ".factory.yml", 1024)).contains("v1");

        fx.commitToMain(Map.of(".factory.yml", "v2"), "v2");
        assertThat(git.readFile(DockerSandboxFixture.REPO, "main", ".factory.yml", 1024)).contains("v2");
    }

    @Test
    void aMissingFileIsEmptyNotAnError() {
        assertThat(git.readFile(DockerSandboxFixture.REPO, "main", ".factory.yml", 1024)).isEmpty();
    }

    @Test
    void aDirectoryIsAnError() {
        fx.commitToMain(Map.of(".factory.yml/x", "x"), "dir");
        assertThatThrownBy(() -> git.readFile(DockerSandboxFixture.REPO, "main", ".factory.yml", 1024))
                .isInstanceOf(StepFailedException.class).hasMessageContaining("is not a file");
    }

    @Test
    void aFileOverTheLimitIsRefused() {
        fx.commitToMain(Map.of("big.yml", "x".repeat(2000)), "big");
        assertThatThrownBy(() -> git.readFile(DockerSandboxFixture.REPO, "main", "big.yml", 1024))
                .isInstanceOf(StepFailedException.class).hasMessageContaining("2000 bytes, over the limit of 1024");
    }

    /** Bigger than a pipe buffer: reading only after git exits would block git until the timeout. */
    @Test
    void aFileBiggerThanThePipeBufferIsReadWithoutHanging() {
        String big = "line\n".repeat(60_000);
        fx.commitToMain(Map.of("big.txt", big), "big");
        long started = System.nanoTime();
        assertThat(git.readFile(DockerSandboxFixture.REPO, "main", "big.txt", 1_000_000)).contains(big);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }
}

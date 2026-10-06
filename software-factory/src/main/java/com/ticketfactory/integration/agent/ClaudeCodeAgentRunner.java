package com.ticketfactory.integration.agent;

import com.github.dockerjava.api.exception.DockerException;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.checks.BoundedOutput;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.DockerSandboxRunner.ExecResult;
import com.ticketfactory.integration.docker.ModelApiProxy;
import java.io.OutputStream;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs Claude Code headless in the ticket's sandbox.
 *
 * <ul>
 *   <li>The model proxy is started for the run and stopped afterwards; the sandbox holds only a placeholder
 *       credential ({@link ModelApiProxy}).</li>
 *   <li>Only the tools in {@code factory.agent.tools} (by default Bash, Read, Edit, Write) and no MCP servers.</li>
 *   <li>Limits are passed to the CLI: {@code --max-turns} and {@code --max-budget-usd} with what is left of the
 *       ticket's budget. The pipeline checks the reported usage against the guardrails afterwards as well.</li>
 *   <li>The agent runs in its own session ({@code setsid}). When the run ends, is cancelled or times out, the whole
 *       session is killed, including anything the agent started in the background.</li>
 *   <li>Afterwards everything in the working tree is committed on the ticket branch, so the pipeline can publish it.
 *       A run that changed nothing is reported as failed.</li>
 * </ul>
 */
public class ClaudeCodeAgentRunner implements AgentRunner {

    static final String AGENT_DIR = "/tmp/factory-agent";
    static final String PROMPT_FILE = AGENT_DIR + "/prompt.md";
    static final String PID_FILE = AGENT_DIR + "/pid";
    private static final Duration KILL_GRACE = Duration.ofSeconds(10);
    private static final int SUMMARY_CHARS = 1000;
    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeAgentRunner.class);

    private final DockerSandboxRunner sandbox;
    private final ModelApiProxy proxy;
    private final AgentProperties props;
    private final String baseBranch;

    public ClaudeCodeAgentRunner(DockerSandboxRunner sandbox, AgentProperties props, String baseBranch) {
        if (sandbox.proxy() == null) {
            throw new IllegalArgumentException("the agent needs a sandbox runner with a model proxy");
        }
        this.sandbox = sandbox;
        this.proxy = sandbox.proxy();
        this.props = props;
        this.baseBranch = baseBranch;
    }

    @Override
    public AgentResult run(AgentRequest request) {
        TicketContext t = request.ticket();
        String sbx = request.sandboxId();
        StreamJsonParser events = new StreamJsonParser();
        BoundedOutput raw = new BoundedOutput(4 * 1024, 12 * 1024);
        int code;
        try {
            killAgent(sbx); // leftovers of a run whose worker died
            proxy.start(t.ticketId());
            ExecResult mkdir = sandbox.exec(sbx, Duration.ofSeconds(30), "mkdir", "-p", AGENT_DIR);
            if (!mkdir.ok()) {
                throw new StepFailedException("sandbox " + sbx + ": " + mkdir.output());
            }
            sandbox.writeFile(sbx, PROMPT_FILE, AgentPrompt.build(request));
            code = sandbox.exec(sbx, props.runTimeout().plus(KILL_GRACE).plus(Duration.ofMinutes(1)), null,
                    environment(), new Tee(events, raw), command(request).toArray(String[]::new));
        } catch (DockerException e) {
            throw new StepFailedException("sandbox " + sbx + ": could not run the agent: " + e.getMessage(), e);
        } finally {
            // Always: a cancelled or timed-out run leaves the CLI and its tools running otherwise.
            boolean interrupted = Thread.interrupted(); // docker calls fail at once on an interrupted thread
            try {
                killAgent(sbx);
                proxy.stop(t.ticketId());
            } catch (RuntimeException e) {
                log.warn("Ticket {}: cleanup after the agent run failed: {}", t.ticketId(), e.toString());
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        events.close();
        StreamJsonParser.Outcome outcome = events.outcome();
        int commits = commitWork(t, sbx);
        AgentResult result = new AgentResult(succeeded(outcome, commits), summary(outcome, code, commits, raw),
                outcome.turns(), outcome.inputTokens(), outcome.outputTokens(), outcome.costUsd());
        log.info("Ticket {} agent: success={} turns={} cost=${} commits={} ({})", t.ticketId(), result.success(),
                result.turns(), result.costUsd(), commits, outcome.subtype());
        return result;
    }

    /** {@code setsid --wait sh -c '<record pid>; exec timeout ... claude ...' < prompt}, with the CLI arguments as $@. */
    List<String> command(AgentRequest request) {
        // --wait: setsid forks when run as a process-group leader (as docker exec runs it), and without --wait the
        // parent returns at once while the CLI carries on unobserved.
        List<String> cmd = new ArrayList<>(List.of("setsid", "--wait", "sh", "-c",
                "echo $$ > " + PID_FILE + " && exec timeout -k " + KILL_GRACE.toSeconds() + "s "
                        + Math.max(1, props.runTimeout().toSeconds()) + "s \"$@\" < " + PROMPT_FILE,
                "factory-agent",
                props.command(), "-p",
                "--output-format", "stream-json", "--verbose",
                "--max-turns", Integer.toString(Math.max(1, request.maxTurns())),
                "--max-budget-usd", request.maxCostUsd().setScale(2, RoundingMode.DOWN).toPlainString(),
                "--dangerously-skip-permissions",
                "--tools", props.tools(),
                "--strict-mcp-config", // no MCP servers from the repo's config
                "--no-session-persistence"));
        if (!props.model().isBlank()) {
            cmd.add("--model");
            cmd.add(props.model());
        }
        return cmd;
    }

    List<String> environment() {
        return List.of(
                "ANTHROPIC_BASE_URL=" + ModelApiProxy.sandboxBaseUrl(),
                proxy.mode().sandboxVariable + "=" + proxy.mode().placeholder,
                "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1",
                "DISABLE_AUTOUPDATER=1",
                "DISABLE_TELEMETRY=1",
                "DISABLE_ERROR_REPORTING=1",
                "CI=true");
    }

    /** Kills the agent's session and process group. Validates the pid file, which the agent could have changed. */
    private void killAgent(String sbx) {
        sandbox.exec(sbx, Duration.ofSeconds(30), "sh", "-c", """
                p=$(cat %1$s 2>/dev/null)
                case "$p" in ''|*[!0-9]*|1) exit 0 ;; esac
                kill -TERM -- "-$p" 2>/dev/null; pkill -TERM -s "$p" 2>/dev/null
                sleep 1
                kill -KILL -- "-$p" 2>/dev/null; pkill -KILL -s "$p" 2>/dev/null
                rm -f %1$s
                exit 0
                """.formatted(PID_FILE));
    }

    /** Commits whatever the agent left and returns how many commits the branch is ahead of the base. */
    private int commitWork(TicketContext t, String sbx) {
        String repo = DockerSandboxRunner.REPO_DIR;
        ExecResult head = git(sbx, "rev-parse", "--abbrev-ref", "HEAD");
        if (!head.ok() || !head.output().strip().equals(t.branchName())) {
            throw new StepFailedException("agent left the ticket branch (now on '" + head.output().strip() + "')");
        }
        ExecResult add = git(sbx, "add", "-A");
        if (!add.ok()) {
            throw new StepFailedException("could not stage the agent's changes: " + add.output());
        }
        if (!git(sbx, "diff", "--cached", "--quiet").ok()) {
            ExecResult commit = git(sbx, "commit", "-q", "-m",
                    "factory: " + t.title() + " (#" + t.issueNumber() + ")");
            if (!commit.ok()) {
                throw new StepFailedException("could not commit the agent's changes: " + commit.output());
            }
        }
        ExecResult ahead = git(sbx, "rev-list", "--count", baseBranch + "..HEAD");
        log.debug("Ticket {}: {} commit(s) ahead of {} in {}", t.ticketId(), ahead.output().strip(), baseBranch, repo);
        return ahead.ok() ? Integer.parseInt(ahead.output().strip()) : 0;
    }

    private ExecResult git(String sbx, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", DockerSandboxRunner.REPO_DIR));
        cmd.addAll(List.of(args));
        return sandbox.exec(sbx, Duration.ofMinutes(2), cmd.toArray(String[]::new));
    }

    private static boolean succeeded(StreamJsonParser.Outcome o, int commits) {
        return o.completed() && !o.isError() && "success".equals(o.subtype()) && commits > 0;
    }

    private String summary(StreamJsonParser.Outcome o, int exitCode, int commits, BoundedOutput raw) {
        String text;
        if (!o.completed()) {
            text = (exitCode == 124 || exitCode == 137
                    ? "agent run timed out after " + props.runTimeout()
                    : "agent stopped without a result (exit " + exitCode + ")") + ": " + tail(raw.toString());
        } else if ("error_max_turns".equals(o.subtype())) {
            text = "agent reached the turn limit (" + o.turns() + " turns) before finishing";
        } else if ("error_max_budget_usd".equals(o.subtype())) {
            text = "agent reached the cost limit ($" + o.costUsd().setScale(2, RoundingMode.HALF_UP) + ")";
        } else if (o.isError() || !"success".equals(o.subtype())) {
            text = "agent failed (" + o.subtype() + "): " + o.resultText();
        } else if (commits == 0) {
            text = "agent finished without changing anything: " + o.resultText();
        } else {
            text = o.resultText().isBlank() ? "agent finished" : o.resultText();
        }
        text = text.strip();
        return text.length() <= SUMMARY_CHARS ? text : text.substring(0, SUMMARY_CHARS - 3) + "...";
    }

    private static String tail(String s) {
        String t = s.strip();
        return t.length() <= 600 ? t : "..." + t.substring(t.length() - 600);
    }

    /** Copies the agent's output to both the event parser and the bounded raw copy. */
    private static final class Tee extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int x) throws java.io.IOException {
            a.write(x);
            b.write(x);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws java.io.IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }
    }
}

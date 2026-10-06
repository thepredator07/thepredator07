package com.ticketfactory.integration.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the Claude Code agent ({@code factory.integrations=real}), bound from {@code factory.agent.*}.
 *
 * <p>The credential lives only on the factory host and in the per-ticket model proxy; the sandbox gets a placeholder
 * (see {@link com.ticketfactory.integration.docker.ModelApiProxy}).
 *
 * @param apiKey        an Anthropic API key ({@code ANTHROPIC_API_KEY}); preferred when both are set
 * @param oauthToken    a Claude subscription token from {@code claude setup-token} ({@code CLAUDE_CODE_OAUTH_TOKEN})
 * @param command       the Claude Code executable inside the sandbox image
 * @param model         passed as {@code --model} when set; empty uses the CLI's default
 * @param tools         the only built-in tools the agent gets ({@code --tools}). Tools that act outside the sandbox
 *                      (web search and fetch, which run through the model API, remote triggers, scheduling) are left
 *                      out on purpose: an issue written to manipulate the agent could use them to leak code.
 * @param upstreamUrl   where the proxy forwards model API calls
 * @param proxyImage    nginx image for the per-ticket model proxy
 * @param egressNetwork Docker network the proxies use to reach the upstream (created if missing)
 * @param runTimeout    backstop for one agent run; the pipeline's hard timeout normally ends it first
 */
@ConfigurationProperties("factory.agent")
public record AgentProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("") String oauthToken,
        @DefaultValue("claude") String command,
        @DefaultValue("") String model,
        @DefaultValue("Bash,Read,Edit,Write") String tools,
        @DefaultValue("https://api.anthropic.com") String upstreamUrl,
        @DefaultValue("nginx:1.27-alpine") String proxyImage,
        @DefaultValue("factory-egress") String egressNetwork,
        @DefaultValue("PT30M") Duration runTimeout) {

    public boolean hasCredential() {
        return !apiKey.isBlank() || !oauthToken.isBlank();
    }
}

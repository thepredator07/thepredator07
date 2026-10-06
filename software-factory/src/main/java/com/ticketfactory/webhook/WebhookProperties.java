package com.ticketfactory.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GitHub webhooks, bound from {@code factory.webhook.*}. Optional: polling keeps working without them; with them,
 * labels, closes and reviews take effect within seconds instead of at the next poll.
 *
 * @param secret the webhook's secret ({@code GITHUB_WEBHOOK_SECRET}); empty disables the endpoint
 */
@ConfigurationProperties("factory.webhook")
public record WebhookProperties(@DefaultValue("") String secret) {

    public boolean enabled() {
        return !secret.isBlank();
    }
}

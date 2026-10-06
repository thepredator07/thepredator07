package com.ticketfactory.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Without a secret the endpoint doesn't exist, so it can't be used to trigger work. */
class WebhookDisabledTest {

    @Test
    void withoutASecretEveryDeliveryIsNotFound() {
        var controller = new GitHubWebhookController(new WebhookProperties(""), null, null, null, null);
        var body = "{}".getBytes();
        assertThat(controller.receive("issues", "sha256=00", body).getStatusCode().value())
                .isEqualTo(404);
        controller.destroy();
    }

    @Test
    void signaturesAreCheckedAgainstTheExactBody() {
        var controller = new GitHubWebhookController(new WebhookProperties("k"), null, null, null, null);
        byte[] body = "{\"a\":1}".getBytes();
        assertThat(controller.validSignature(body, GitHubWebhookTest.sign("k", "{\"a\":1}"))).isTrue();
        assertThat(controller.validSignature(body, GitHubWebhookTest.sign("k", "{\"a\":2}"))).isFalse();
        assertThat(controller.validSignature(body, GitHubWebhookTest.sign("k", "{\"a\":1}").toUpperCase()
                .replace("SHA256", "sha256"))).isTrue(); // hex digits in either case
        assertThat(controller.validSignature(body, "sha1=abc")).isFalse();
        assertThat(controller.validSignature(body, null)).isFalse();
        controller.destroy();
    }
}

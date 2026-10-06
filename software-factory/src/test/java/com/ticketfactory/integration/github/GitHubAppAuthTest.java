package com.ticketfactory.integration.github;

import static com.ticketfactory.integration.github.SimulatedGitHub.REPO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubAppAuthTest {

    private static KeyPair keys;
    private GitHubApiSimulator sim;

    @BeforeEach
    void start() throws Exception {
        if (keys == null) {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keys = gen.generateKeyPair();
        }
        sim = new GitHubApiSimulator(REPO, "unused-static-token");
        sim.setPermission("maintainer", "write");
        sim.enableAppAuth("12345", keys.getPublic(), 3600);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    /** What GitHub downloads: PKCS#1, "BEGIN RSA PRIVATE KEY". */
    private static String pkcs1Pem() {
        return pem("RSA PRIVATE KEY", pkcs1From(keys.getPrivate().getEncoded()));
    }

    private static String pkcs8Pem() {
        return pem("PRIVATE KEY", keys.getPrivate().getEncoded());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    /** PKCS#8 = SEQUENCE { INTEGER 0, AlgorithmIdentifier, OCTET STRING pkcs1 }: return the OCTET STRING's content. */
    private static byte[] pkcs1From(byte[] pkcs8) {
        int i = skipHeader(pkcs8, 0);          // outer SEQUENCE
        i += 3;                                 // INTEGER 0
        i = skipTlv(pkcs8, i);                  // AlgorithmIdentifier
        int contentStart = skipHeader(pkcs8, i); // OCTET STRING header
        return Arrays.copyOfRange(pkcs8, contentStart, pkcs8.length);
    }

    private static int skipHeader(byte[] d, int i) {
        int len = d[i + 1] & 0xff;
        return i + 2 + ((len & 0x80) != 0 ? (len & 0x7f) : 0);
    }

    private static int skipTlv(byte[] d, int i) {
        int len = d[i + 1] & 0xff;
        int header = 2;
        int length = len;
        if ((len & 0x80) != 0) {
            int n = len & 0x7f;
            length = 0;
            for (int k = 0; k < n; k++) {
                length = (length << 8) | (d[i + 2 + k] & 0xff);
            }
            header += n;
        }
        return i + header + length;
    }

    private GitHubAppAuth auth(String pem, Clock clock) {
        return new GitHubAppAuth(sim.url(), "12345", "678", pem, HttpClient.newHttpClient(), Duration.ofSeconds(5),
                clock);
    }

    @Test
    void readsBothPkcs1AndPkcs8Keys() {
        assertThat(GitHubAppAuth.parsePrivateKey(pkcs1Pem()).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
        assertThat(GitHubAppAuth.parsePrivateKey(pkcs8Pem()).getEncoded()).isEqualTo(keys.getPrivate().getEncoded());
    }

    @Test
    void jwtIsSignedWithTheAppKeyAndShortLived() throws Exception {
        String jwt = auth(pkcs1Pem(), Clock.systemUTC()).jwt();
        String[] parts = jwt.split("\\.");
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(keys.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        JsonNode claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("iss").asText()).isEqualTo("12345");
        long now = Instant.now().getEpochSecond();
        assertThat(claims.path("iat").asLong()).isBetween(now - 120, now);
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isLessThanOrEqualTo(600);
    }

    @Test
    void installationTokenIsCachedAndUsedForApiCalls() {
        GitHubAppAuth auth = auth(pkcs1Pem(), Clock.systemUTC());
        GitHubRestClient client = new GitHubRestClient(new GitHubHttp(sim.url(), auth, HttpClient.newHttpClient(),
                Duration.ofSeconds(5), Clock.systemUTC()), SimulatedGitHub.props(sim.url(), 100), Clock.systemUTC());
        sim.createIssue("x", "", "maintainer", "factory");

        client.listOpenIssues(REPO, "factory");
        client.listOpenIssues(REPO, "factory");

        assertThat(sim.issuedAppTokens()).as("one exchange, then cached").isEqualTo(1);
        assertThat(sim.requests()).filteredOn(r -> r.pathAndQuery().startsWith("/repos/"))
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer ghs_sim1"));
    }

    @Test
    void tokenIsRefreshedFiveMinutesBeforeItExpires() {
        // Tokens live 6 minutes here. The simulator checks JWTs against real time, so the test clock starts in the
        // past and never runs more than a minute ahead of real time.
        sim.enableAppAuth("12345", keys.getPublic(), 360);
        MutableClock clock = new MutableClock(Instant.now().minusSeconds(200));
        GitHubAppAuth auth = auth(pkcs1Pem(), clock);

        assertThat(auth.authorizationHeader()).isEqualTo("Bearer ghs_sim1");   // ~560 s left
        clock.advance(Duration.ofSeconds(200));
        assertThat(auth.authorizationHeader()).as("6 min left: cached").isEqualTo("Bearer ghs_sim1");
        clock.advance(Duration.ofSeconds(61));
        assertThat(auth.authorizationHeader()).as("under 5 min left: refreshed").isEqualTo("Bearer ghs_sim2");
        assertThat(sim.issuedAppTokens()).isEqualTo(2);
    }

    @Test
    void aKeyThatGitHubDoesNotAcceptFailsClearly() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        String otherKey = pem("PRIVATE KEY", gen.generateKeyPair().getPrivate().getEncoded());
        assertThatThrownBy(() -> auth(otherKey, Clock.systemUTC()).authorizationHeader())
                .hasMessageContaining("token exchange failed").hasMessageContaining("401");
    }

    @Test
    void configurationSelectsAppAuthAndReadsTheKeyFile(@TempDir Path dir) throws Exception {
        Path pem = dir.resolve("app.pem");
        Files.writeString(pem, pkcs1Pem());
        GitHubProperties props = new GitHubProperties(sim.url(), null, "12345", "678", pem.toString(),
                GitHubProperties.Permission.WRITE, 100, Duration.ofSeconds(5), Duration.ofMinutes(10));
        sim.createIssue("x", "", "maintainer", "factory");

        var client = com.ticketfactory.integration.real.RealIntegrationsConfig.createGitHubClient(props,
                Clock.systemUTC());

        assertThat(client.listOpenIssues(REPO, "factory")).hasSize(1);
        assertThat(sim.issuedAppTokens()).isEqualTo(1);
    }

    @Test
    void missingTokenAndAppSettingsFailAtStartupWithTheVariableName() {
        GitHubProperties none = new GitHubProperties(sim.url(), null, null, null, null,
                GitHubProperties.Permission.WRITE, 100, Duration.ofSeconds(5), Duration.ofMinutes(10));
        assertThatThrownBy(() -> com.ticketfactory.integration.real.RealIntegrationsConfig.createGitHubClient(none,
                Clock.systemUTC())).hasMessageContaining("GITHUB_TOKEN");
        GitHubProperties halfApp = new GitHubProperties(sim.url(), null, "12345", null, "/nope.pem",
                GitHubProperties.Permission.WRITE, 100, Duration.ofSeconds(5), Duration.ofMinutes(10));
        assertThatThrownBy(() -> com.ticketfactory.integration.real.RealIntegrationsConfig.createGitHubClient(halfApp,
                Clock.systemUTC())).hasMessageContaining("GITHUB_APP_PRIVATE_KEY_PATH");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}

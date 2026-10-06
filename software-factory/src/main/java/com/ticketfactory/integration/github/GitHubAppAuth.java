package com.ticketfactory.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * GitHub App authentication: signs a short-lived JWT with the App's private key, exchanges it for an installation
 * token, and caches that token until five minutes before it expires (GitHub issues them for one hour).
 */
public class GitHubAppAuth implements GitHubAuth {

    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiUrl;
    private final String appId;
    private final String installationId;
    private final PrivateKey key;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public GitHubAppAuth(String apiUrl, String appId, String installationId, String privateKeyPem, HttpClient http,
                         Duration timeout, Clock clock) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.appId = require(appId, "GITHUB_APP_ID");
        this.installationId = require(installationId, "GITHUB_APP_INSTALLATION_ID");
        this.key = parsePrivateKey(require(privateKeyPem, "GITHUB_APP_PRIVATE_KEY_PATH (file content)"));
        this.http = http;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public synchronized String authorizationHeader() {
        if (token == null || clock.instant().isAfter(expiresAt.minus(REFRESH_MARGIN))) {
            refresh();
        }
        return "Bearer " + token;
    }

    private void refresh() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        apiUrl + "/app/installations/" + installationId + "/access_tokens"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + jwt())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", GitHubHttp.API_VERSION)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IllegalStateException("GitHub App token exchange failed: HTTP " + response.statusCode()
                        + " " + response.body());
            }
            JsonNode body = JSON.readTree(response.body());
            token = body.path("token").asText();
            expiresAt = Instant.parse(body.path("expires_at").asText());
        } catch (IOException e) {
            throw new com.ticketfactory.integration.StepFailedException("GitHub App token exchange failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new com.ticketfactory.integration.StepFailedException("interrupted during token exchange", e);
        }
    }

    /** RS256 JWT: issued 60 s in the past to absorb clock drift, valid 9 minutes (GitHub allows at most 10). */
    String jwt() {
        long now = clock.instant().getEpochSecond();
        String header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"iat\":" + (now - 60) + ",\"exp\":" + (now + 540) + ",\"iss\":\"" + appId + "\"}");
        String signingInput = header + "." + payload;
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign GitHub App JWT", e);
        }
    }

    /**
     * Accepts PKCS#8 ({@code BEGIN PRIVATE KEY}) and PKCS#1 ({@code BEGIN RSA PRIVATE KEY}, the format GitHub
     * downloads). PKCS#1 is wrapped into PKCS#8, since the JDK only reads the latter.
     */
    static PrivateKey parsePrivateKey(String pem) {
        boolean pkcs1 = pem.contains("BEGIN RSA PRIVATE KEY");
        String base64 = pem.replaceAll("-----(BEGIN|END) (RSA )?PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        if (pkcs1) {
            der = wrapPkcs1(der);
        }
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("GitHub App private key is not a valid RSA key", e);
        }
    }

    /** PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier rsaEncryption, OCTET STRING pkcs1 }. */
    private static byte[] wrapPkcs1(byte[] pkcs1) {
        byte[] version = {0x02, 0x01, 0x00};
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01,
                0x01, 0x05, 0x00};
        byte[] octets = der((byte) 0x04, pkcs1);
        byte[] inner = concat(version, algorithm, octets);
        return der((byte) 0x30, inner);
    }

    private static byte[] der(byte tag, byte[] content) {
        int len = content.length;
        byte[] lenBytes;
        if (len < 0x80) {
            lenBytes = new byte[]{(byte) len};
        } else if (len <= 0xff) {
            lenBytes = new byte[]{(byte) 0x81, (byte) len};
        } else if (len <= 0xffff) {
            lenBytes = new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
        } else {
            lenBytes = new byte[]{(byte) 0x83, (byte) (len >> 16), (byte) (len >> 8), (byte) len};
        }
        return concat(new byte[]{tag}, lenBytes, content);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for GitHub App authentication");
        }
        return value.trim();
    }
}

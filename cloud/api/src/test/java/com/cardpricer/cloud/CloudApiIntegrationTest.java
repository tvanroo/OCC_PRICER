package com.cardpricer.cloud;

import com.cardpricer.cloud.catalog.CatalogImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudApiIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final String CLIENT_ID = "test-client";
    static final String OWNER_EMAIL = "platform-owner@example.com";
    /** A stand-in for the Auth0 tenant: serves its JWKS and answers the code exchange with a signed ID token. */
    static final HttpServer AUTH0;
    static final RSAKey KEY;
    /** Authorization code -> ID token claims the stand-in returns for it. */
    static final Map<String, Map<String, Object>> CODES = new ConcurrentHashMap<>();
    /** Authorization code -> the PKCE code_challenge it was issued for. */
    static final Map<String, String> CHALLENGES = new ConcurrentHashMap<>();

    static {
        // Started before the Spring context; Testcontainers' Ryuk removes it when the JVM exits.
        POSTGRES.start();
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            AUTH0 = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
            AUTH0.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            AUTH0.createContext("/oauth/token", exchange -> {
                var form = new java.util.HashMap<String, String>();
                for (String pair : new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("&")) {
                    String[] kv = pair.split("=", 2);
                    form.put(java.net.URLDecoder.decode(kv[0], java.nio.charset.StandardCharsets.UTF_8),
                            java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8));
                }
                var claims = CODES.remove(form.get("code"));
                byte[] body;
                int status = 200;
                try {
                    if (claims == null || !"test-secret".equals(form.get("client_secret"))
                            || !s256(form.get("code_verifier")).equals(CHALLENGES.remove(form.get("code")))) {
                        status = 403;
                        body = "{\"error\":\"invalid_grant\"}".getBytes();
                    } else {
                        var builder = new JWTClaimsSet.Builder().issuer(issuer()).audience(CLIENT_ID)
                                .issueTime(new java.util.Date()).expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000));
                        claims.forEach(builder::claim);
                        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), builder.build());
                        jwt.sign(new RSASSASigner(KEY));
                        body = ("{\"id_token\":\"" + jwt.serialize() + "\"}").getBytes();
                    }
                } catch (Exception e) {
                    throw new java.io.IOException(e);
                }
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            AUTH0.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static String s256(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String issuer() {
        return "http://localhost:" + AUTH0.getAddress().getPort() + "/";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.rate-limit.auth-per-minute", () -> "1000");
        registry.add("app.auth0.issuer", CloudApiIntegrationTest::issuer);
        registry.add("app.auth0.client-id", () -> CLIENT_ID);
        registry.add("app.auth0.client-secret", () -> "test-secret");
        registry.add("app.owner-email", () -> OWNER_EMAIL);
    }

    @LocalServerPort int port;
    @Autowired CatalogImporter importer;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw, String cookie, String location) {}

    @BeforeAll
    void loadCatalog() throws Exception {
        try (var in = getClass().getResourceAsStream("/cards-fixture.json")) {
            assertEquals(4, importer.importStream(in, "fixture"), "digital-only printing is skipped");
        }
    }

    Response call(String method, String path, String cookie, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        else builder.method(method, HttpRequest.BodyPublishers.noBody());
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // The cookie the response actually sets (sign-in responses also clear the finished transaction cookie).
        String setCookie = response.headers().allValues("Set-Cookie").stream().map(c -> c.split(";")[0])
                .filter(c -> !c.endsWith("=")).findFirst().orElse(null);
        JsonNode parsed = response.body().startsWith("{") || response.body().startsWith("[") ? json.readTree(response.body()) : null;
        return new Response(response.statusCode(), parsed, response.body(), setCookie,
                response.headers().firstValue("Location").orElse(null));
    }

    static String query(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /**
     * Runs Universal Login end to end against the stand-in: /login redirects to the authorize URL, the "user" signs in
     * as {@code sub}/{@code email}, and the callback answers. Returns the callback's response.
     */
    Response auth0SignIn(String sub, String email, boolean verified) throws Exception {
        var start = call("GET", "/api/auth/login", null, null);
        assertEquals(302, start.status(), start.raw());
        assertTrue(start.location().startsWith(issuer() + "authorize?"), start.location());
        assertEquals("S256", query(start.location(), "code_challenge_method"));
        assertNull(query(start.location(), "prompt"));
        String code = UUID.randomUUID().toString();
        CODES.put(code, Map.of("sub", sub, "email", email, "email_verified", verified, "name", email,
                "nonce", query(start.location(), "nonce")));
        CHALLENGES.put(code, query(start.location(), "code_challenge"));
        return call("GET", "/api/auth/callback?code=" + code + "&state=" + query(start.location(), "state"), start.cookie(), null);
    }

    String signup(String store, String email) throws Exception {
        var callback = auth0SignIn("auth0|" + UUID.randomUUID(), email, true);
        assertEquals(302, callback.status(), callback.raw());
        assertEquals("/signup", URI.create(callback.location()).getPath(), "no account yet, so name the store");
        assertEquals(email, call("GET", "/api/auth/pending", callback.cookie(), null).body().path("email").asText());
        var r = call("POST", "/api/auth/signup", callback.cookie(), Map.of("storeName", store, "name", "Owner"));
        assertEquals(200, r.status(), r.raw());
        assertTrue(r.body().path("entitled").asBoolean());
        return r.cookie();
    }

    @Test
    void importsGzippedJsonLines() throws Exception {
        // Scryfall's bulk files are now gzipped JSON Lines; re-importing the fixture that way upserts the same rows.
        JsonNode cards;
        try (var in = getClass().getResourceAsStream("/cards-fixture.json")) {
            cards = json.readTree(in);
        }
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            for (JsonNode card : cards) gzip.write((json.writeValueAsString(card) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(4, importer.importStream(new java.io.ByteArrayInputStream(bytes.toByteArray()), "fixture.jsonl.gz"));
    }

    @Test
    void freePriceCheckNeedsNoAccountAndShowsOnlyMarketPrices() throws Exception {
        var r = call("GET", "/api/public/cards?q=bolt", null, null);
        assertEquals(200, r.status());
        assertEquals("Scryfall", r.body().path("source").asText());
        var cards = r.body().path("cards");
        assertEquals(2, cards.size());
        assertEquals("2X2", cards.get(0).path("set").asText(), "newest printing first");
        assertEquals("1.37", cards.get(0).path("usd").asText());
        assertFalse(cards.get(0).has("credit"), "no store offers on the free page");
    }

    @Test
    void searchMatchesSetCodeAndCollectorNumber() throws Exception {
        for (String q : new String[]{"410", "CMM 410", "cmm 410", "cmm #410", "sol cmm", "ring 410"}) {
            var cards = call("GET", "/api/public/cards?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8), null, null)
                    .body().path("cards");
            assertEquals(1, cards.size(), q);
            assertEquals("Sol Ring", cards.get(0).path("name").asText(), q);
        }
        var bolt = call("GET", "/api/public/cards?q=bolt%202x2", null, null).body().path("cards");
        assertEquals(1, bolt.size());
        assertEquals("117", bolt.get(0).path("number").asText());
        assertEquals(0, call("GET", "/api/public/cards?q=cmm%20117", null, null).body().path("cards").size());
    }

    @Test
    void storeWorkflowRequiresSignIn() throws Exception {
        assertEquals(401, call("GET", "/api/app/trades", null, null).status());
        assertEquals(401, call("GET", "/api/app/rates", "occ_session=forged.123.abc", null).status());
    }

    @Test
    void quoteSaveHistoryAndPosExport() throws Exception {
        String owner = signup("OCC", "owner-" + UUID.randomUUID() + "@example.com");
        var lines = java.util.List.of(
                java.util.Map.of("cardId", "22222222-2222-2222-2222-222222222222", "finish", "normal", "condition", "NM", "quantity", 1),
                java.util.Map.of("cardId", "11111111-1111-1111-1111-111111111111", "finish", "foil", "condition", "LP", "quantity", 3));
        var quote = call("POST", "/api/app/trades/quote", owner, java.util.Map.of("lines", lines, "payment", "credit"));
        assertEquals(200, quote.status(), quote.raw());
        // Ragavan $48.20 -> $48 base; 50% credit = 24.00, 40% check = 19.20.
        // Foil bolt $3.12 -> $3.00 base, LP 0.8 -> $2.50; credit 1.25 x3, check 1.00 x3.
        assertEquals(0, new java.math.BigDecimal("27.75").compareTo(quote.body().path("creditOffer").decimalValue()));
        assertEquals(0, new java.math.BigDecimal("22.20").compareTo(quote.body().path("checkOffer").decimalValue()));

        var split = call("POST", "/api/app/trades/quote", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10));
        assertEquals(200, split.status(), split.raw());
        double check = split.body().path("settlement").path("check").asDouble();
        assertEquals(14.20, check, 0.001);

        var missingCheck = call("POST", "/api/app/trades", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10));
        assertEquals(400, missingCheck.status());

        var saved = call("POST", "/api/app/trades", owner, java.util.Map.of("lines", lines, "payment", "partial", "credit", 10,
                "customerPhone", "(555) 010-2030", "customerName", "Pat", "checkNumber", "1001"));
        assertEquals(200, saved.status(), saved.raw());
        assertEquals(1, saved.body().path("number").asInt());
        assertEquals("+15550102030", saved.body().path("customer_phone").asText());
        assertFalse(saved.raw().toLowerCase().contains("license"));

        var history = call("GET", "/api/app/trades?phone=555-010-2030", owner, null);
        assertEquals(1, history.body().size());

        String id = saved.body().path("id").asText();
        var csv = call("GET", "/api/app/trades/" + id + "/pos.csv", owner, null);
        assertEquals(200, csv.status());
        String[] rows = csv.raw().split("\r\n");
        assertEquals("LINE NO,DEPARTMENT,CATEGORY,TYPE,CODE,ITEM TYPE,ORDER NO,DESCRIPTION,UOM,QTY ON ORD,RESTOCK LEVEL,"
                + "REORDER POINT,QTY ON HAND,COST,DISCOUNT,BID,EXTENDED COST,TAX CODE,PRICE", rows[0]);
        assertTrue(rows[1].contains("MH2 138") && rows[1].contains("Ragavanɕ Nimble Pilferer"), rows[1]);
        assertTrue(rows[2].contains(" 117F,"), rows[2]);
        // Extended costs add up to the amount paid out.
        double extended = 0;
        for (int i = 1; i < rows.length; i++) extended += Double.parseDouble(rows[i].split(",")[16]);
        assertEquals(24.20, extended, 0.001);
    }

    @Test
    void storesCannotSeeEachOthersTrades() throws Exception {
        String a = signup("Store A", "a-" + UUID.randomUUID() + "@example.com");
        String b = signup("Store B", "b-" + UUID.randomUUID() + "@example.com");
        var saved = call("POST", "/api/app/trades", a, java.util.Map.of("payment", "credit", "lines", java.util.List.of(
                java.util.Map.of("cardId", "33333333-3333-3333-3333-333333333333", "finish", "etched", "condition", "NM", "quantity", 1))));
        assertEquals(200, saved.status(), saved.raw());
        String id = saved.body().path("id").asText();
        assertEquals(404, call("GET", "/api/app/trades/" + id, b, null).status());
        assertEquals(404, call("GET", "/api/app/trades/" + id + "/pos.csv", b, null).status());
        assertEquals(0, call("GET", "/api/app/trades", b, null).body().size());
    }

    @Test
    void onlyOwnersChangeRatesAndRatesDriveOffers() throws Exception {
        String owner = signup("Rates Store", "r-" + UUID.randomUUID() + "@example.com");
        String staffEmail = "s-" + UUID.randomUUID() + "@example.com";
        assertEquals(200, call("POST", "/api/app/staff", owner, java.util.Map.of("name", "Sam", "email", staffEmail)).status());
        // Staff join by signing in with the verified email the owner added.
        var staffSignIn = auth0SignIn("auth0|" + UUID.randomUUID(), staffEmail.toUpperCase(), true);
        assertEquals("/app", URI.create(staffSignIn.location()).getPath());
        String staff = staffSignIn.cookie();
        assertNotNull(staff);
        var rules = java.util.Map.of("rules", java.util.List.of(
                java.util.Map.of("thresholdMin", 0, "creditRate", 0.5, "checkRate", 0.4),
                java.util.Map.of("thresholdMin", 20, "creditRate", 0.7, "checkRate", 0.6)));
        assertEquals(403, call("PUT", "/api/app/rates", staff, rules).status());
        assertEquals(200, call("PUT", "/api/app/rates", owner, rules).status());
        var quote = call("POST", "/api/app/trades/quote", staff, java.util.Map.of("payment", "credit", "lines", java.util.List.of(
                java.util.Map.of("cardId", "22222222-2222-2222-2222-222222222222", "finish", "normal", "condition", "NM", "quantity", 1))));
        assertEquals(0, new java.math.BigDecimal("33.60").compareTo(quote.body().path("creditOffer").decimalValue()));
    }

    @Test
    void signInIsKeyedOnAuth0SubWithVerifiedEmailAsTheFallback() throws Exception {
        String email = "k-" + UUID.randomUUID() + "@example.com";
        String sub = "auth0|" + UUID.randomUUID();
        var first = auth0SignIn(sub, email, true);
        call("POST", "/api/auth/signup", first.cookie(), Map.of("storeName", "Keyed", "name", "Kim"));

        // Same sub with a changed email still finds the account, and picks up the new address.
        String newEmail = "k2-" + UUID.randomUUID() + "@example.com";
        var again = auth0SignIn(sub, newEmail, true);
        assertEquals("/app", URI.create(again.location()).getPath());
        assertEquals(newEmail, call("GET", "/api/auth/me", again.cookie(), null).body().path("email").asText());

        // A different sub with the same, already linked, email is not let in.
        var other = auth0SignIn("google-oauth2|" + UUID.randomUUID(), newEmail, true);
        assertEquals("/login", URI.create(other.location()).getPath());
        assertTrue(other.cookie() == null || !other.cookie().startsWith("occ_session="));
    }

    @Test
    void unverifiedEmailsAndForgedCallbacksAreRefused() throws Exception {
        var unverified = auth0SignIn("auth0|" + UUID.randomUUID(), "u-" + UUID.randomUUID() + "@example.com", false);
        assertEquals("/login", URI.create(unverified.location()).getPath());
        assertTrue(query(unverified.location(), "error").contains("verify"));

        var start = call("GET", "/api/auth/login", null, null);
        var wrongState = call("GET", "/api/auth/callback?code=x&state=not-the-state", start.cookie(), null);
        assertEquals("/login", URI.create(wrongState.location()).getPath());
        var noCookie = call("GET", "/api/auth/callback?code=x&state=" + query(start.location(), "state"), null, null);
        assertEquals("/login", URI.create(noCookie.location()).getPath());
        assertEquals(404, call("POST", "/api/auth/signup", null, Map.of("storeName", "X", "name", "Y")).status());
        // A sign-in transaction cookie is not a session.
        assertEquals(401, call("GET", "/api/app/rates", "occ_session=" + start.cookie().split("=", 2)[1], null).status());
    }

    @Test
    void chooseAccountAsksAuth0ForCredentialsAgain() throws Exception {
        var start = call("GET", "/api/auth/login?chooseAccount=true", null, null);
        assertEquals("login", query(start.location(), "prompt"));
    }

    @Test
    void platformOwnerIsTheVerifiedOwnerEmail() throws Exception {
        String owner = signup("Owner Store", OWNER_EMAIL);
        assertTrue(call("GET", "/api/auth/me", owner, null).body().path("admin").asBoolean());
        String other = signup("Other Store", "o-" + UUID.randomUUID() + "@example.com");
        assertFalse(call("GET", "/api/auth/me", other, null).body().path("admin").asBoolean());
        var out = call("POST", "/api/auth/logout", owner, Map.of());
        assertTrue(out.body().path("logoutUrl").asText().startsWith(issuer() + "v2/logout?client_id=" + CLIENT_ID));
    }

    @Test
    void mutatingRequestsMustBeJson() throws Exception {
        String owner = signup("Csrf Store", "c-" + UUID.randomUUID() + "@example.com");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/app/rates"))
                .header("Cookie", owner).header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(415, http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }
}

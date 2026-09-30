package com.cardpricer.cloud;

import com.cardpricer.cloud.catalog.CatalogImporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CloudApiIntegrationTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        // Started before the Spring context; Testcontainers' Ryuk removes it when the JVM exits.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.session-secret", () -> "test-secret-test-secret-test-secret-0123");
        registry.add("app.secure-cookie", () -> "false");
        registry.add("app.rate-limit.auth-per-minute", () -> "1000");
    }

    @LocalServerPort int port;
    @Autowired CatalogImporter importer;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    record Response(int status, JsonNode body, String raw, String cookie) {}

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
        String setCookie = response.headers().firstValue("Set-Cookie").map(c -> c.split(";")[0]).orElse(null);
        JsonNode parsed = response.body().startsWith("{") || response.body().startsWith("[") ? json.readTree(response.body()) : null;
        return new Response(response.statusCode(), parsed, response.body(), setCookie);
    }

    String signup(String store, String email) throws Exception {
        var r = call("POST", "/api/auth/signup", null, java.util.Map.of("storeName", store, "name", "Owner",
                "email", email, "password", "correct horse battery"));
        assertEquals(200, r.status(), r.raw());
        assertTrue(r.body().path("entitled").asBoolean());
        return r.cookie();
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
        assertEquals(200, call("POST", "/api/app/staff", owner, java.util.Map.of("name", "Sam", "email", staffEmail,
                "password", "another long password")).status());
        String staff = call("POST", "/api/auth/login", null, java.util.Map.of("email", staffEmail, "password", "another long password")).cookie();
        assertNotNull(staff);
        var rules = java.util.Map.of("rules", java.util.List.of(
                java.util.Map.of("thresholdMin", 0, "creditRate", 0.5, "checkRate", 0.4),
                java.util.Map.of("thresholdMin", 20, "creditRate", 0.7, "checkRate", 0.6)));
        assertEquals(403, call("PUT", "/api/app/rates", staff, rules).status());
        assertEquals(200, call("PUT", "/api/app/rates", owner, rules).status());
        var quote = call("POST", "/api/app/trades/quote", staff, java.util.Map.of("payment", "credit", "lines", java.util.List.of(
                java.util.Map.of("cardId", "22222222-2222-2222-2222-222222222222", "finish", "normal", "condition", "NM", "quantity", 1))));
        assertEquals(0, new java.math.BigDecimal("33.60").compareTo(quote.body().path("creditOffer").decimalValue()));
        var badLogin = call("POST", "/api/auth/login", null, java.util.Map.of("email", staffEmail, "password", "wrong password here"));
        assertEquals(401, badLogin.status());
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

package com.cardpricer.cloud.cardbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls cardbox.club's JSON API from Trading's server, as the signed-in person: every request carries their Auth0
 * access token for the CardBox API audience, and CardBox decides what they may see and change.
 * Switched off unless {@code app.cardbox.enabled} is true.
 */
@Component
public class CardBoxClient {
    /** CardBox's answer. Errors come back as {@code {"detail": "..."}} with 401, 403, 404, 409 or 422. */
    public record Result(int status, JsonNode body) {
        public boolean ok() { return status >= 200 && status < 300; }

        public String detail() {
            String detail = body.path("detail").asText("");
            return detail.isBlank() ? "CardBox answered " + status : detail;
        }
    }

    public static class Unavailable extends Exception {
        public Unavailable(String message) { super(message); }
    }

    private final boolean enabled;
    private final String baseUrl;
    private final String audience;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    public CardBoxClient(@Value("${app.cardbox.enabled:false}") boolean enabled,
                         @Value("${app.cardbox.base-url:https://cardbox.club}") String baseUrl,
                         @Value("${app.cardbox.audience:https://cardbox.club/api}") String audience) {
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.audience = audience;
    }

    public boolean enabled() { return enabled; }

    /** The Auth0 API audience Trading asks a token for at sign-in, or null while the link is off. */
    public String audience() { return enabled ? audience : null; }

    /** {@code path} starts with /api/ and may carry a query string; {@code body} is sent as JSON when not null. */
    public Result call(String accessToken, String method, String path, JsonNode body) throws Unavailable {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode parsed;
            try {
                parsed = text == null || text.isBlank() ? NullNode.getInstance() : json.readTree(text);
            } catch (IOException e) {
                // A proxy or error page in front of CardBox; there is no detail to show.
                parsed = NullNode.getInstance();
            }
            return new Result(response.statusCode(), parsed);
        } catch (IOException e) {
            throw new Unavailable("CardBox could not be reached. Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
    }
}

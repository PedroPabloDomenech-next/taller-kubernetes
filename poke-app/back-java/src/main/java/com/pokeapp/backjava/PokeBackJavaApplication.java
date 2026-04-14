package com.pokeapp.backjava;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class PokeBackJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PokeBackJavaApplication.class, args);
    }

    @Bean
    WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                    .allowedOrigins("*")
                    .allowedHeaders("*")
                    .allowedMethods("*");
            }
        };
    }

    @Bean
    HttpClient httpClient() {
        return HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @RestController
    static class ProxyController {

        private static final String POKE_API_BASE_URL = "https://pokeapi.co";
        private static final String API_PREFIX = "/api/v2";

        private final HttpClient httpClient;
        private final String authBaseUrl;

        ProxyController(HttpClient httpClient, @Value("${auth.base-url}") String authBaseUrl) {
            this.httpClient = httpClient;
            this.authBaseUrl = normalizeBaseUrl(authBaseUrl);
        }

        @GetMapping("/")
        Map<String, Object> root() {
            return Map.of(
                "name", "PokeBackJava",
                "description", "Proxy local en Java 21 para la PokeAPI v2",
                "upstream", "https://pokeapi.co/api/v2",
                "endpoints", List.of(
                    "/api/v2/{endpoint}",
                    "/api/v2/{endpoint}/{id-or-name}"
                )
            );
        }

        @GetMapping("/health")
        Map<String, Object> health() {
            return Map.of("status", "ok");
        }

        @GetMapping({"/api/v2", "/api/v2/", "/api/v2/**"})
        ResponseEntity<?> proxy(
            HttpServletRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader
        ) throws IOException, InterruptedException {
            var bearerToken = extractBearerToken(authorizationHeader);

            if (bearerToken == null || bearerToken.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var authResponse = validateUser(bearerToken);
            if (authResponse.statusCode() == 401) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            if (authResponse.statusCode() < 200 || authResponse.statusCode() >= 300) {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                        "title", "No se pudo validar el usuario autenticado.",
                        "status", 502
                    ));
            }

            var pokePath = extractPokePath(request);
            if (pokePath.isBlank()) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Debes indicar una ruta de la PokeAPI v2."));
            }

            var upstreamUri = buildUpstreamUri(request, pokePath);
            var upstreamRequest = HttpRequest.newBuilder(upstreamUri)
                .GET()
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

            var upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofByteArray());
            return buildProxyResponse(upstreamResponse);
        }

        private HttpResponse<byte[]> validateUser(String bearerToken) throws IOException, InterruptedException {
            var request = HttpRequest.newBuilder(URI.create(authBaseUrl + "/auth/userinfo"))
                .GET()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }

        private ResponseEntity<byte[]> buildProxyResponse(HttpResponse<byte[]> upstreamResponse) {
            var headers = new HttpHeaders();

            upstreamResponse.headers().map().forEach((name, values) -> {
                if (!HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)
                    && !HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)) {
                    headers.put(name, values);
                }
            });

            return ResponseEntity.status(upstreamResponse.statusCode())
                .headers(headers)
                .body(upstreamResponse.body());
        }

        private String extractPokePath(HttpServletRequest request) {
            var requestUri = request.getRequestURI();
            if (!requestUri.startsWith(API_PREFIX)) {
                return "";
            }

            return requestUri.substring(API_PREFIX.length()).replaceFirst("^/+", "");
        }

        private URI buildUpstreamUri(HttpServletRequest request, String pokePath) {
            var queryString = request.getQueryString();
            var builder = new StringBuilder(POKE_API_BASE_URL)
                .append(API_PREFIX)
                .append("/")
                .append(encodePath(pokePath));

            if (queryString != null && !queryString.isBlank()) {
                builder.append("?").append(queryString);
            }

            return URI.create(builder.toString());
        }

        private String encodePath(String pokePath) {
            var segments = pokePath.split("/");
            var encodedSegments = new String[segments.length];

            for (var index = 0; index < segments.length; index++) {
                encodedSegments[index] = URLEncoder.encode(segments[index], StandardCharsets.UTF_8)
                    .replace("+", "%20");
            }

            return String.join("/", encodedSegments);
        }

        private String extractBearerToken(String authorizationHeader) {
            if (authorizationHeader == null) {
                return null;
            }

            var bearerPrefix = "Bearer ";
            return authorizationHeader.regionMatches(true, 0, bearerPrefix, 0, bearerPrefix.length())
                ? authorizationHeader.substring(bearerPrefix.length()).trim()
                : null;
        }

        private static String normalizeBaseUrl(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}

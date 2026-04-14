package com.pokeapp.authjava;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@SpringBootApplication
public class PokeAuthJavaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PokeAuthJavaApplication.class, args);
    }

    @Bean
    DataSource dataSource(@Value("${auth.db.connection-string}") String rawConnectionString) {
        var dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");

        if (rawConnectionString.startsWith("jdbc:")) {
            dataSource.setUrl(rawConnectionString);
            return dataSource;
        }

        var segments = rawConnectionString.split(";");
        var values = new HashMap<String, String>();

        for (var segment : segments) {
            var trimmed = segment.trim();

            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }

            var separatorIndex = trimmed.indexOf('=');
            var key = trimmed.substring(0, separatorIndex).trim().toLowerCase();
            var value = trimmed.substring(separatorIndex + 1).trim();
            values.put(key, value);
        }

        var host = values.getOrDefault("host", "localhost");
        var port = values.getOrDefault("port", "5432");
        var database = values.getOrDefault("database", "poke_auth");
        dataSource.setUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        dataSource.setUsername(values.getOrDefault("username", "poke"));
        dataSource.setPassword(values.getOrDefault("password", "poke"));
        return dataSource;
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
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CommandLineRunner initializeDatabase(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        return args -> {
            var attempts = 0;

            while (true) {
                try (var connection = dataSource.getConnection()) {
                    connection.prepareStatement("SELECT 1").execute();
                    break;
                } catch (Exception exception) {
                    attempts++;

                    if (attempts >= 30) {
                        throw exception;
                    }

                    Thread.sleep(2_000);
                }
            }

            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS auth_users (
                    id UUID PRIMARY KEY,
                    email VARCHAR(320) NOT NULL,
                    normalized_email VARCHAR(320) NOT NULL UNIQUE,
                    first_name VARCHAR(200) NOT NULL,
                    last_name VARCHAR(200) NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at_utc TIMESTAMPTZ NOT NULL
                )
                """);

            jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_auth_users_normalized_email
                ON auth_users (normalized_email)
                """);
        };
    }

    @RestController
    static class AuthController {

        private final JdbcTemplate jdbcTemplate;
        private final BCryptPasswordEncoder passwordEncoder;
        private final JWTVerifier jwtVerifier;
        private final Algorithm jwtAlgorithm;
        private final String jwtIssuer;
        private final String jwtAudience;
        private final long jwtExpirationHours;

        AuthController(
            JdbcTemplate jdbcTemplate,
            BCryptPasswordEncoder passwordEncoder,
            @Value("${auth.jwt.issuer}") String jwtIssuer,
            @Value("${auth.jwt.audience}") String jwtAudience,
            @Value("${auth.jwt.key}") String jwtKey,
            @Value("${auth.jwt.expiration-hours}") long jwtExpirationHours
        ) {
            this.jdbcTemplate = jdbcTemplate;
            this.passwordEncoder = passwordEncoder;
            this.jwtIssuer = jwtIssuer;
            this.jwtAudience = jwtAudience;
            this.jwtExpirationHours = jwtExpirationHours;
            this.jwtAlgorithm = Algorithm.HMAC256(jwtKey);
            this.jwtVerifier = JWT.require(this.jwtAlgorithm)
                .withIssuer(jwtIssuer)
                .withAudience(jwtAudience)
                .acceptLeeway(30)
                .build();
        }

        @GetMapping("/")
        Map<String, Object> root() {
            return Map.of(
                "name", "PokeAuthJava",
                "description", "Servicio de autenticacion local para la poke-app en Java 21",
                "endpoints", List.of(
                    "POST /auth/register",
                    "POST /auth/login",
                    "GET /auth/userinfo",
                    "GET /health"
                )
            );
        }

        @GetMapping("/health")
        ResponseEntity<?> health() {
            try {
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);
                return ResponseEntity.ok(Map.of("status", "ok"));
            } catch (Exception exception) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("title", "Database unavailable", "status", 503));
            }
        }

        @PostMapping({"/register", "/auth/register"})
        ResponseEntity<?> register(@RequestBody RegisterRequest request) {
            var errors = validateRegisterRequest(request);

            if (!errors.isEmpty()) {
                return validationProblem(errors);
            }

            var normalizedEmail = request.email().trim().toUpperCase();
            var emailInUse = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM auth_users WHERE normalized_email = ?)",
                Boolean.class,
                normalizedEmail
            ));

            if (emailInUse) {
                return validationProblem(Map.of("email", List.of("Ya existe un usuario registrado con ese email.")));
            }

            var user = new AuthUser(
                UUID.randomUUID(),
                request.email().trim(),
                normalizedEmail,
                request.firstName().trim(),
                request.lastName().trim(),
                passwordEncoder.encode(request.password()),
                Instant.now().truncatedTo(ChronoUnit.MILLIS)
            );

            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO auth_users (id, email, normalized_email, first_name, last_name, password_hash, created_at_utc)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """);
                statement.setObject(1, user.id());
                statement.setString(2, user.email());
                statement.setString(3, user.normalizedEmail());
                statement.setString(4, user.firstName());
                statement.setString(5, user.lastName());
                statement.setString(6, user.passwordHash());
                statement.setObject(
                    7,
                    OffsetDateTime.ofInstant(user.createdAtUtc(), ZoneOffset.UTC),
                    Types.TIMESTAMP_WITH_TIMEZONE
                );
                return statement;
            });

            return ResponseEntity.created(java.net.URI.create("/auth/users/" + user.id()))
                .body(toUserInfoResponse(user));
        }

        @PostMapping({"/login", "/auth/login"})
        ResponseEntity<?> login(@RequestBody LoginRequest request) {
            if (isBlank(request.email()) || isBlank(request.password())) {
                return validationProblem(Map.of("credentials", List.of("Debes indicar email y contraseña.")));
            }

            var normalizedEmail = request.email().trim().toUpperCase();
            var user = findUserByNormalizedEmail(normalizedEmail);

            if (user.isEmpty() || !passwordEncoder.matches(request.password(), user.get().passwordHash())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var expiresAtUtc = Instant.now().plus(jwtExpirationHours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
            var token = JWT.create()
                .withIssuer(jwtIssuer)
                .withAudience(jwtAudience)
                .withSubject(user.get().id().toString())
                .withClaim("email", user.get().email())
                .withClaim("given_name", user.get().firstName())
                .withClaim("family_name", user.get().lastName())
                .withClaim("unique_name", user.get().firstName() + " " + user.get().lastName())
                .withClaim("nameid", user.get().id().toString())
                .withExpiresAt(expiresAtUtc)
                .sign(jwtAlgorithm);

            return ResponseEntity.ok(new LoginResponse(
                token,
                expiresAtUtc,
                toUserInfoResponse(user.get())
            ));
        }

        @GetMapping({"/userinfo", "/auth/userinfo"})
        ResponseEntity<?> userInfo(@RequestHeader HttpHeaders headers) {
            var token = extractBearerToken(headers);

            if (token.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            final DecodedJWT decodedToken;

            try {
                decodedToken = jwtVerifier.verify(token.get());
            } catch (JWTVerificationException exception) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            var subject = decodedToken.getSubject();
            UUID userId;

            try {
                userId = UUID.fromString(subject);
            } catch (Exception exception) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }

            return findUserById(userId)
                .<ResponseEntity<?>>map(user -> ResponseEntity.ok(toUserInfoResponse(user)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        private Optional<String> extractBearerToken(HttpHeaders headers) {
            var authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);

            if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                return Optional.empty();
            }

            var token = authorization.substring(7).trim();
            return token.isEmpty() ? Optional.empty() : Optional.of(token);
        }

        private Optional<AuthUser> findUserByNormalizedEmail(String normalizedEmail) {
            return jdbcTemplate.query(
                "SELECT * FROM auth_users WHERE normalized_email = ?",
                authUserRowMapper(),
                normalizedEmail
            ).stream().findFirst();
        }

        private Optional<AuthUser> findUserById(UUID id) {
            return jdbcTemplate.query(
                "SELECT * FROM auth_users WHERE id = ?",
                authUserRowMapper(),
                id
            ).stream().findFirst();
        }

        private RowMapper<AuthUser> authUserRowMapper() {
            return (resultSet, rowNum) -> mapUser(resultSet);
        }

        private AuthUser mapUser(ResultSet resultSet) throws SQLException {
            return new AuthUser(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("email"),
                resultSet.getString("normalized_email"),
                resultSet.getString("first_name"),
                resultSet.getString("last_name"),
                resultSet.getString("password_hash"),
                resultSet.getObject("created_at_utc", OffsetDateTime.class).toInstant()
            );
        }

        private UserInfoResponse toUserInfoResponse(AuthUser user) {
            return new UserInfoResponse(
                user.id(),
                user.email(),
                user.firstName(),
                user.lastName(),
                user.createdAtUtc()
            );
        }

        private Map<String, List<String>> validateRegisterRequest(RegisterRequest request) {
            var errors = new LinkedHashMap<String, List<String>>();

            if (isBlank(request.email()) || !request.email().contains("@")) {
                errors.put("email", List.of("Debes indicar un email válido."));
            }

            if (isBlank(request.firstName())) {
                errors.put("firstName", List.of("Debes indicar el nombre."));
            }

            if (isBlank(request.lastName())) {
                errors.put("lastName", List.of("Debes indicar los apellidos."));
            }

            if (isBlank(request.password()) || request.password().length() < 8) {
                errors.put("password", List.of("La contraseña debe tener al menos 8 caracteres."));
            }

            return errors;
        }

        private ResponseEntity<Map<String, Object>> validationProblem(Map<String, List<String>> errors) {
            return ResponseEntity.badRequest().body(Map.of(
                "title", "One or more validation errors occurred.",
                "status", 400,
                "errors", errors
            ));
        }

        private boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }

    record RegisterRequest(String email, String firstName, String lastName, String password) {
    }

    record LoginRequest(String email, String password) {
    }

    record UserInfoResponse(UUID id, String email, String firstName, String lastName, Instant createdAtUtc) {
    }

    record LoginResponse(String accessToken, Instant expiresAtUtc, UserInfoResponse user) {
    }

    record AuthUser(
        UUID id,
        String email,
        String normalizedEmail,
        String firstName,
        String lastName,
        String passwordHash,
        Instant createdAtUtc
    ) {
    }
}

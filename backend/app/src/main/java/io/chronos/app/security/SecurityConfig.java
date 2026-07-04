package io.chronos.app.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.chronos.app.persistence.AppUserRepository;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * REST API security (§11): stateless JWT bearer auth + role-based access (ADMIN/OPERATOR/VIEWER).
 *
 * <p>Login at {@code POST /api/auth/login} issues an HS256 JWT (roles claim). Reads (GET) need any
 * authenticated role; operational actions (run task, ack alarm, device validate) need OPERATOR+;
 * all config mutations and script execution need ADMIN. Health/info/prometheus and the gateway WS
 * (own token) are open. Users live in the {@code app_user} table (BCrypt), seeded by
 * {@link DefaultUserInitializer}.
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    private static final String DEV_JWT_SECRET = "chronos-dev-jwt-secret-change-me-please-0123456789";
    // the well-known AES key baked into docker-compose as a dev default (decrypts device credentials)
    private static final String DEV_SECRET_KEY = "Y2hyb25vcy1kZXYtc2VjcmV0LWtleS0zMmJ5dGVzISE=";

    /**
     * Guard the JWT signing secret. In a {@code prod} profile a blank/known-dev secret is fatal (anyone
     * with the repo default could forge ADMIN tokens); elsewhere it's a loud warning so dev/test still run.
     */
    @Bean
    ApplicationRunner jwtSecretCheck(
            @Value("${chronos.security.jwt-secret}") String secret,
            @Value("${chronos.secret-key:}") String aesKey,
            org.springframework.core.env.Environment env) {
        return args -> {
            boolean prod = java.util.List.of(env.getActiveProfiles()).contains("prod");
            boolean weakJwt = DEV_JWT_SECRET.equals(secret) || secret == null || secret.isBlank();
            boolean weakAes = DEV_SECRET_KEY.equals(aesKey) || aesKey == null || aesKey.isBlank();
            if (prod && (weakJwt || weakAes)) {
                throw new IllegalStateException(
                        "Refusing to start under the 'prod' profile with a built-in/blank secret — set "
                                + "CHRONOS_JWT_SECRET and CHRONOS_SECRET_KEY to strong random values.");
            }
            if (weakJwt) {
                log.warn("⚠ Using the built-in DEV JWT secret — set CHRONOS_JWT_SECRET to a strong "
                        + "random value (≥32 bytes) in production; tokens are otherwise forgeable.");
            }
            if (DEV_SECRET_KEY.equals(aesKey)) {
                log.warn("⚠ Using the built-in DEV AES key — set CHRONOS_SECRET_KEY in production; "
                        + "stored device credentials are otherwise decryptable by anyone with the repo.");
            }
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthenticationConverter jwtConverter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless API; no cookies/sessions
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/ws/gateway/**", // gateway authenticates via its own token (§9)
                                "/api/flows/in/**", // public webhook ingress for http-in flow nodes
                                "/api/auth/login")
                        .permitAll()
                        // live flow introspection carries decrypted message payloads / context vars
                        // (potential secrets) across ALL running flows — keep it off VIEWER.
                        .requestMatchers(HttpMethod.GET, "/api/flows/debug", "/api/flows/context")
                        .hasAnyRole("OPERATOR", "ADMIN")
                        // operational actions — OPERATOR or ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/tasks/*/run").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/devices/*/validate").hasAnyRole("OPERATOR", "ADMIN")
                        // all other writes + script execution — ADMIN only
                        .requestMatchers(HttpMethod.POST, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/**").hasRole("ADMIN")
                        // reads — any authenticated user (incl. VIEWER)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)));
        return http.build();
    }

    /** Map the token's {@code roles} claim (e.g. ["ADMIN"]) to ROLE_ authorities. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppUserRepository users) {
        return username -> users.findByUsername(username)
                .map(u -> User.withUsername(u.getUsername())
                        .password(u.getPasswordHash())
                        .roles(u.getRole())
                        .disabled(!u.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("unknown user: " + username));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService uds, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(uds);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    private static SecretKeySpec key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(@Value("${chronos.security.jwt-secret}") String secret) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(secret)));
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${chronos.security.jwt-secret}") String secret) {
        return NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
    }
}

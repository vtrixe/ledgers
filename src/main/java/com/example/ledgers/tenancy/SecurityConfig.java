package com.example.ledgers.tenancy;

import com.example.ledgers.shared.CommonResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final JsonMapper jsonMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority(ApiKeyAuthenticationFilter.ROLE_ROOT)
                        // First match wins: reads need ledger:read, account writes need accounts:manage.
                        .requestMatchers(HttpMethod.GET, "/v1/**").hasAuthority("SCOPE_" + Scopes.LEDGER_READ)
                        .requestMatchers("/v1/accounts/**").hasAuthority("SCOPE_" + Scopes.ACCOUNTS_MANAGE)
                        .requestMatchers(HttpMethod.POST, "/v1/transactions").hasAuthority("SCOPE_" + Scopes.LEDGER_WRITE)
                        .anyRequest().denyAll()
                )
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) -> write(response,
                                HttpStatus.UNAUTHORIZED, "ERR_UNAUTHENTICATED", "Missing, invalid or revoked API key"))
                        .accessDeniedHandler((request, response, ex) -> write(response,
                                HttpStatus.FORBIDDEN, "ERR_FORBIDDEN", "This key lacks the required scope"))
                )
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), CommonResponse.failure(status, code, message));
    }
}

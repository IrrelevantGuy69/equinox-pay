package com.equinox.core.config;

import com.equinox.core.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v2/auth/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()

                        // 1. ANYONE can view the invoice details
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v2/invoices/**").permitAll()

                        // 2. ONLY Merchants can generate new invoices
                        .requestMatchers("/api/v2/invoices/generate").hasAnyAuthority("ROLE_MERCHANT", "MERCHANT")

                        // 3. ANY logged-in user can pay an invoice (Customer)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v2/invoices/*/pay").authenticated()

                        .requestMatchers("/api/v2/merchants/**").hasAnyAuthority("ROLE_MERCHANT", "MERCHANT")
                        // Add this line with your other invoice rules:
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v2/invoices/*/refund").hasAnyAuthority("ROLE_MERCHANT", "MERCHANT")
                        .anyRequest().authenticated()
                )
                .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

}

package com.dts.media.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class MediaSecurityConfiguration {
    @Bean FilterRegistrationBean<MediaJwtFilter> jwtServletRegistration(MediaJwtFilter filter) {
        var registration = new FilterRegistrationBean<>(filter); registration.setEnabled(false); return registration;
    }
    @Bean SecurityFilterChain mediaSecurity(HttpSecurity http, MediaJwtFilter filter, ObjectMapper json) throws Exception {
        return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error", "/actuator/health", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/media/uploads/initialize", "/api/v1/media/uploads/*/confirm").hasAuthority("PERM_media:upload")
                        .requestMatchers(HttpMethod.POST, "/api/v1/media/simulation-playback", "/api/v1/media/simulation-catalog-thumbnail").hasAuthority("PERM_media:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/media/published-question-image").hasAuthority("PERM_media:read")
                        .requestMatchers(HttpMethod.POST, "/api/v1/media/simulation-exam/eligibility", "/api/v1/media/simulation-exam/playback", "/api/v1/media/simulation-exam/review-playback").hasAuthority("PERM_media:read")
                        .requestMatchers("/api/v1/media/admin", "/api/v1/media/admin/**").hasAuthority("PERM_media:manage")
                        .requestMatchers(HttpMethod.GET, "/api/v1/media", "/api/v1/media/").hasAuthority("PERM_media:read")
                        .requestMatchers(HttpMethod.GET, "/api/v1/media/*").permitAll()
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/media/*").hasAuthority("PERM_media:update")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/media/*").hasAuthority("PERM_media:delete")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> MediaJwtFilter.writeError(res, 401, "Unauthorized", "Authentication required", json))
                        .accessDeniedHandler((req, res, ex) -> MediaJwtFilter.writeError(res, 403, "Forbidden", "Permission required", json)))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }
}

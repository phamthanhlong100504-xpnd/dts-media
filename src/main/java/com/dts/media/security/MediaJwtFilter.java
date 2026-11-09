package com.dts.media.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class MediaJwtFilter extends OncePerRequestFilter {
    private final MediaJwtVerifier verifier;
    private final ObjectMapper json;

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/media")) response.setHeader("Cache-Control", "no-store");
        List<String> headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.isEmpty()) { chain.doFilter(request, response); return; }
        MediaActor actor;
        try {
            String header = headers.getFirst();
            if (headers.size() != 1 || !header.startsWith("Bearer ") || header.length() > 8192) throw new IllegalArgumentException();
            actor = verifier.verify(header.substring(7));
        } catch (Exception failure) {
            SecurityContextHolder.clearContext();
            writeError(response, 401, "Unauthorized", "Invalid or expired access token", json);
            return;
        }
        var authorities = Stream.concat(actor.roles().stream(), actor.permissions().stream().map(p -> "PERM_" + p))
                .map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, null, authorities));
        chain.doFilter(request, response);
    }
    static void writeError(HttpServletResponse response, int status, String error, String message, ObjectMapper json) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        json.writeValue(response.getOutputStream(), Map.of("timestamp", Instant.now().toString(), "status", status, "error", error, "message", message));
    }
}

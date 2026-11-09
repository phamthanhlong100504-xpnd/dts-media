package com.dts.media.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.stream.Stream;

public final class MediaTestActors {
    private MediaTestActors() {}
    public static void set(UUID user, Set<String> roles, String... rights) {
        MediaActor actor = new MediaActor(user, roles, Set.of(rights));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(actor, null,
                Stream.concat(roles.stream(), actor.permissions().stream().map(p -> "PERM_" + p)).map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
    }
}

package com.dts.media.security;

import com.dts.media.domain.entity.Media;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

public final class MediaAccess {
    private MediaAccess() {}
    public static MediaActor optionalActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof MediaActor actor ? actor : null;
    }
    public static MediaActor require(String permission) {
        MediaActor actor = optionalActor();
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        if (!actor.permissions().contains(permission)) throw new SecurityException("Permission required");
        return actor;
    }
    public static void ownerOrManager(Media media, MediaActor actor) {
        if (!actor.userId().equals(media.getCreatedBy()) && !actor.manager())
            throw new SecurityException("Media access denied");
    }
    public static void actorHint(UUID hint, MediaActor actor) {
        if (hint != null && !hint.equals(actor.userId())) throw new SecurityException("User hint does not match authenticated actor");
    }
    public static boolean simulation(String target) {
        return "SIMULATION_VIDEO".equals(target) || "SIMULATION_THUMBNAIL".equals(target);
    }
}

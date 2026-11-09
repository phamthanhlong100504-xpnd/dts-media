package com.dts.media.security;

import java.util.Set;
import java.util.UUID;

public record MediaActor(UUID userId, Set<String> roles, Set<String> permissions) {
    public MediaActor {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }
    public boolean manager() { return roles.contains("ROLE_ADMIN") && permissions.contains("media:manage"); }
}

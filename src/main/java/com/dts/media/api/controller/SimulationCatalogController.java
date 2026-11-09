package com.dts.media.api.controller;

import com.dts.media.api.form.SimulationPlaybackForm;
import com.dts.media.api.response.SimulationCatalogThumbnailResponse;
import com.dts.media.application.service.SimulationCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media")
public class SimulationCatalogController {
    private final SimulationCatalogService catalog;

    @PostMapping("/simulation-catalog-thumbnail")
    @PreAuthorize("hasAuthority('PERM_media:read') and hasAuthority('PERM_simulations:read')")
    public ResponseEntity<SimulationCatalogThumbnailResponse> thumbnail(@Valid @RequestBody SimulationPlaybackForm form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.resolve(form.getTicket()));
    }
}

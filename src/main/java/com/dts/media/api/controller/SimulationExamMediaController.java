package com.dts.media.api.controller;

import com.dts.media.api.response.SimulationExamMediaModel.*;
import com.dts.media.application.service.SimulationExamMediaService;
import com.dts.media.security.SimulationExamTicketVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media/simulation-exam")
@PreAuthorize("hasAuthority('PERM_media:read') and hasAuthority('PERM_simulations:read') and hasAuthority('PERM_simulations:exam')")
public class SimulationExamMediaController {
    private final SimulationExamMediaService service;
    @PostMapping("/eligibility") public ResponseEntity<EligibilityResponse> eligibility(@RequestBody JsonNode body){return response(service.eligibility(ticket(body)));}
    @PostMapping("/playback") public ResponseEntity<PlaybackResponse> playback(@RequestBody JsonNode body){return response(service.playback(ticket(body),false));}
    @PostMapping("/review-playback") public ResponseEntity<PlaybackResponse> review(@RequestBody JsonNode body){return response(service.playback(ticket(body),true));}
    private String ticket(JsonNode body) {
        if(body==null||!body.isObject()||body.size()!=1||!body.path("ticket").isTextual()||body.path("ticket").asText().isBlank()
                ||body.path("ticket").asText().length()>SimulationExamTicketVerifier.MAX_TICKET)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected only a bounded ticket field");
        return body.path("ticket").asText();
    }
    private<T>ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}

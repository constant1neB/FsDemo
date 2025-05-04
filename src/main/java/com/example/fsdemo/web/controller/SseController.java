package com.example.fsdemo.web.controller;

import com.example.fsdemo.service.SseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;


import java.io.IOException;

@RestController
@RequestMapping("/api/sse")
public class SseController {

    @Value("${sse.emitter.timeout.ms}")
    private long sseEmitterTimeout;

    private static final Logger log = LoggerFactory.getLogger(SseController.class);
    private final SseService sseService;

    public SseController(SseService sseService) {
        this.sseService = sseService;
    }

    @GetMapping(path = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> subscribe(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Attempt to subscribe to SSE without authentication.");
            return ResponseEntity.status(401).build();
        }

        String username = authentication.getName();
        log.info("SSE subscription request received for user: {}", username);

        SseEmitter emitter = new SseEmitter(sseEmitterTimeout);

        try {
            emitter.send(SseEmitter.event().comment("SSE connection established"));
            log.debug("Sent initial connection confirmation to user: {}", username);
        } catch (IOException e) {
            log.error("Failed to send initial SSE comment to user: {}. Error: {}", username, e.getMessage(), e);
            emitter.completeWithError(e);
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("Unexpected error during initial SSE send for user: {}", username, e);
            emitter.completeWithError(e);
            return ResponseEntity.internalServerError().build();
        }

        sseService.addEmitter(username, emitter);

        log.info("SSE emitter created and registered successfully for user: {}", username);
        return ResponseEntity.ok(emitter);
    }
}
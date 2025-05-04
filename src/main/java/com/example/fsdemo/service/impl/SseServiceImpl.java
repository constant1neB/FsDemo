package com.example.fsdemo.service.impl;

import com.example.fsdemo.service.SseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class SseServiceImpl implements SseService {

    private static final Logger log = LoggerFactory.getLogger(SseServiceImpl.class);
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    @Override
    public void addEmitter(String username, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = this.userEmitters.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);
        log.info("Added SSE emitter for user: {}. Total emitters for user: {}", username, emitters.size());

        Runnable cleanup = () -> {
            log.debug("SSE emitter cleanup triggered for user: {}", username);
            removeEmitter(username, emitter);
        };

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.error("SSE emitter error for user: {}. Removing emitter.", username, e);
            cleanup.run();
        });
    }

    @Override
    public void removeEmitter(String username, SseEmitter emitter) {
        if (username == null || emitter == null) {
            log.warn("Attempted to remove null username or emitter.");
            return;
        }
        CopyOnWriteArrayList<SseEmitter> emitters = this.userEmitters.get(username);
        if (emitters != null) {
            boolean removed = emitters.remove(emitter);
            if (removed) {
                log.info("Removed SSE emitter for user: {}. Remaining emitters: {}", username, emitters.size());
                if (emitters.isEmpty()) {
                    this.userEmitters.remove(username);
                    log.info("Removed user entry from SSE map as no emitters remain: {}", username);
                }
            } else {
                log.warn("Attempted to remove emitter for user {}, but it was not found in the list.", username);
            }
        } else {
            log.warn("Attempted to remove emitter for user {}, but the user was not found in the map.", username);
        }
    }

    @Override
    public void sendEventToUser(String username, Object eventData, String eventName) {
        List<SseEmitter> emitters = this.userEmitters.get(username);
        if (emitters == null || emitters.isEmpty()) {
            log.debug("No active SSE emitters found for user: {} when trying to send event: {}", username, eventName);
            return;
        }

        log.info("Sending SSE event '{}' to user: {}", eventName, username);
        List<SseEmitter> emittersToSend = List.copyOf(emitters);

        for (SseEmitter emitter : emittersToSend) {
            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event()
                        .name(eventName)
                        .data(eventData);
                emitter.send(event);
                log.debug("Successfully sent event '{}' to an emitter for user: {}", eventName, username);
            } catch (IOException e) {
                log.error("Failed to send SSE event '{}' to an emitter for user: {}. Removing emitter. Error: {}",
                        eventName, username, e.getMessage());
                removeEmitter(username, emitter);
            } catch (Exception e) {
                log.error("Unexpected error sending SSE event '{}' to an emitter for user: {}. Removing emitter.",
                        eventName, username, e);
                removeEmitter(username, emitter);
            }
        }
    }

    @Scheduled(fixedRate = 20000)
    public void sendHeartbeat() {
        if (userEmitters.isEmpty()) {
            return;
        }
        log.trace("Sending SSE heartbeats to {} users.", userEmitters.size());
        int totalEmitters = 0;
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : userEmitters.entrySet()) {
            String username = entry.getKey();
            List<SseEmitter> emitters = entry.getValue();
            totalEmitters += emitters.size();
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException e) {
                    log.warn("Failed to send heartbeat to user: {}. Removing emitter. Error: {}", username, e.getMessage());
                    removeEmitter(username, emitter);
                } catch (Exception e) {
                    log.error("Unexpected error sending heartbeat to user: {}. Removing emitter.", username, e);
                    removeEmitter(username, emitter);
                }
            }
        }
        if (totalEmitters > 0) {
            log.trace("Sent heartbeats to {} active emitters across {} users.", totalEmitters, userEmitters.size());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down SseService. Completing all active emitters...");
        int completedCount = 0;
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : new ConcurrentHashMap<>(userEmitters).entrySet()) {
            List<SseEmitter> emitters = entry.getValue();
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    emitter.complete();
                    completedCount++;
                } catch (Exception e) {
                    log.warn("Error completing emitter for user {} during shutdown: {}", entry.getKey(), e.getMessage());
                }
            }
        }
        userEmitters.clear();
        log.info("SseService shutdown complete. Completed {} emitters.", completedCount);
    }
}
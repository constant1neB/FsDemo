package com.example.fsdemo.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Interface for managing Server-Sent Event emitters and sending events to users.
 */
public interface SseService {

    /**
     * Adds a new SseEmitter for a specific user.
     *
     * @param username The username of the connected user.
     * @param emitter  The SseEmitter instance.
     */
    void addEmitter(String username, SseEmitter emitter);

    /**
     * Removes a specific SseEmitter for a user.
     *
     * @param username The username.
     * @param emitter  The emitter to remove.
     */
    void removeEmitter(String username, SseEmitter emitter);

    /**
     * Sends an event to all active SseEmitters for a specific user.
     *
     * @param username  The target username.
     * @param eventData The data object to send (will be serialized).
     * @param eventName The name of the SSE event.
     */
    void sendEventToUser(String username, Object eventData, String eventName);

}
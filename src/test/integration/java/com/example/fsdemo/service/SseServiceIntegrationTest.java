package com.example.fsdemo.service;

import com.example.fsdemo.service.impl.SseServiceImpl;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.lang.NonNull;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SseServiceImpl Integration Tests (Realistic Emitter Observation)")
class SseServiceIntegrationTest {

    @Autowired
    private SseServiceImpl sseService;

    private static final long EMITTER_TEST_TIMEOUT_MS = 500L;
    private static final long CALLBACK_AWAIT_TIMEOUT_S = 5L;
    private static final long SERVICE_OPERATION_AWAIT_TIMEOUT_S = 3L;
    private static final String TEST_USER_1 = "user1";
    private static final String TEST_USER_2 = "user2";

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:ssedb;DB_CLOSE_DELAY=-1;MODE=LEGACY;NON_KEYWORDS=USER");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "password");
        registry.add("sse.heartbeat.interval.ms", () -> "500");
        registry.add("sse.emitter.timeout.ms", () -> "60000");
    }

    enum SimulatedEmitterEvent {
        COMPLETION("onCompletion"),
        TIMEOUT("onTimeout"),
        ERROR("onError");

        private final String eventName;

        SimulatedEmitterEvent(String eventName) {
            this.eventName = eventName;
        }

        @Override
        public String toString() {
            return eventName;
        }
    }

    record CapturedSseEvent(String id, String name, List<String> dataLines, List<String> comments, Long reconnectTime) {
        CapturedSseEvent(String id, String name, List<String> dataLines, List<String> comments, Long reconnectTime) {
            this.id = id;
            this.name = name;
            this.dataLines = dataLines != null ? List.copyOf(dataLines) : List.of();
            this.comments = comments != null ? List.copyOf(comments) : List.of();
            this.reconnectTime = reconnectTime;
        }

        public String getFirstDataLine() {
            return dataLines.isEmpty() ? null : dataLines.getFirst();
        }

        public String getFirstComment() {
            return comments.isEmpty() ? null : comments.getFirst();
        }

        public static List<CapturedSseEvent> parseSseString(String sseString) {
            List<CapturedSseEvent> events = new ArrayList<>();
            if (sseString == null || sseString.isBlank()) {
                return events;
            }
            String[] messageBlocks = sseString.split("\n\n");
            for (String block : messageBlocks) {
                if (block.trim().isEmpty()) continue;
                String currentId = null;
                String currentName = null;
                List<String> currentData = new ArrayList<>();
                List<String> currentComments = new ArrayList<>();
                Long currentReconnectTime = null;
                String[] lines = block.split("\n");
                for (String line : lines) {
                    if (line.startsWith("id:")) {
                        currentId = line.substring(3).trim();
                    } else if (line.startsWith("event:")) {
                        currentName = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        currentData.add(line.substring(5).trim());
                    } else if (line.startsWith(":")) {
                        currentComments.add(line.substring(1).trim());
                    } else if (line.startsWith("retry:")) {
                        try {
                            currentReconnectTime = Long.parseLong(line.substring(6).trim());
                        } catch (NumberFormatException e) { /* ignore */ }
                    }
                }
                if (currentId != null || currentName != null || !currentData.isEmpty() || !currentComments.isEmpty() || currentReconnectTime != null) {
                    events.add(new CapturedSseEvent(currentId, currentName, currentData, currentComments, currentReconnectTime));
                }
            }
            return events;
        }
    }

    static class TestSseEmitter extends SseEmitter {
        private final AtomicBoolean throwOnSend = new AtomicBoolean(false);
        private final List<String> rawSentPayloads = new CopyOnWriteArrayList<>();
        private final String emitterId;

        public TestSseEmitter(String emitterId, long timeout) {
            super(timeout);
            this.emitterId = emitterId;
        }

        public void setThrowOnSend(boolean shouldThrow) {
            this.throwOnSend.set(shouldThrow);
        }

        @Override
        public void send(@NonNull SseEventBuilder builder) throws IOException {
            if (throwOnSend.get()) {
                throw new IOException("Simulated send error from " + emitterId);
            }

            StringBuilder sseString = new StringBuilder();
            Set<ResponseBodyEmitter.DataWithMediaType> dataToSendSet = builder.build();
            for (ResponseBodyEmitter.DataWithMediaType dwm : dataToSendSet) {
                Object dataObject = dwm.getData();
                sseString.append(dataObject);
            }
            rawSentPayloads.add(sseString.toString());
            super.send(builder);
        }

        public List<CapturedSseEvent> getCapturedEvents() {
            List<CapturedSseEvent> allEvents = new ArrayList<>();
            for (String payload : rawSentPayloads) {
                allEvents.addAll(CapturedSseEvent.parseSseString(payload));
            }
            return allEvents;
        }
    }

    @BeforeEach
    void setUp() {
        System.out.println("SETUP: Ensuring SseService is clean before test.");
        sseService.shutdown();
        clearUserEmittersMap();
    }

    @AfterEach
    void tearDown() {
        System.out.println("TEARDOWN: Cleaning up SseService after test.");
        sseService.shutdown();
        clearUserEmittersMap();
    }

    @SuppressWarnings("unchecked")
    private void clearUserEmittersMap() {
        try {
            Field emittersMapField = SseServiceImpl.class.getDeclaredField("userEmitters");
            emittersMapField.setAccessible(true);
            Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters =
                    (Map<String, CopyOnWriteArrayList<SseEmitter>>) emittersMapField.get(sseService);
            userEmitters.clear();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to clear userEmitters map via reflection: " + e.getMessage(), e);
        }
    }

    private TestSseEmitter createAndRegisterTestEmitter(String emitterId, String username) {
        TestSseEmitter emitter = new TestSseEmitter(emitterId, EMITTER_TEST_TIMEOUT_MS);
        sseService.addEmitter(username, emitter);
        return emitter;
    }

    @Test
    @DisplayName("addEmitter stores emitter, removeEmitter removes it")
    void addAndRemoveEmitter() {
        TestSseEmitter emitter = createAndRegisterTestEmitter("e1", TEST_USER_1);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1);
        sseService.removeEmitter(TEST_USER_1, emitter);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 0);
    }

    @Test
    @DisplayName("addEmitter stores multiple emitters for the same user")
    void addMultipleEmittersForSameUser() {
        createAndRegisterTestEmitter("e1", TEST_USER_1);
        createAndRegisterTestEmitter("e2", TEST_USER_1);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 2);
    }

    @Test
    @DisplayName("removeEmitter on non-existent emitter does not throw error and map is correct")
    void removeNonExistentEmitter() {
        createAndRegisterTestEmitter("e1", TEST_USER_1);
        TestSseEmitter emitterNotInMap = new TestSseEmitter("e2", EMITTER_TEST_TIMEOUT_MS);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1);
        sseService.removeEmitter(TEST_USER_1, emitterNotInMap);
        assertThat(getEmitterCountForUser(TEST_USER_1)).isEqualTo(1);
    }

    @Test
    @DisplayName("removeEmitter for non-existent user does not throw error")
    void removeEmitterForNonExistentUser() {
        TestSseEmitter emitter = new TestSseEmitter("e1", EMITTER_TEST_TIMEOUT_MS);
        sseService.removeEmitter("nonExistentUser", emitter);
        assertThat(getEmitterCountForUser("nonExistentUser")).isZero();
    }

    @ParameterizedTest
    @EnumSource(SimulatedEmitterEvent.class)
    @DisplayName("SIMULATED emitter lifecycle events lead to service removing emitter")
    void simulatedEmitterLifecycleEvent_TriggersRemoval(SimulatedEmitterEvent eventType) {
        String emitterId = "e1-sim-" + eventType.name().toLowerCase();
        TestSseEmitter emitter = createAndRegisterTestEmitter(emitterId, TEST_USER_1);

        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1);

        System.out.println("SIMULATING SseEmitter." + eventType + " callback effect for: " + emitter);
        sseService.removeEmitter(TEST_USER_1, emitter);

        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 0);
    }

    @Test
    @DisplayName("sendEventToUser sends data to a single emitter")
    void sendEventToUser_SingleEmitter() {
        TestSseEmitter emitter = createAndRegisterTestEmitter("e1", TEST_USER_1);
        String eventName = "testEvent";
        String eventData = "testData";
        sseService.sendEventToUser(TEST_USER_1, eventData, eventName);
        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS).untilAsserted(() -> {
            List<CapturedSseEvent> events = emitter.getCapturedEvents();
            assertThat(events).isNotEmpty();
            CapturedSseEvent captured = events.stream()
                    .filter(e -> eventName.equals(e.name))
                    .findFirst().orElse(null);
            assertThat(captured).isNotNull();
            assertThat(captured.name).isEqualTo(eventName);
            assertThat(captured.getFirstDataLine()).isEqualTo(eventData);
        });
    }

    @Test
    @DisplayName("sendEventToUser sends data to multiple emitters for the same user")
    void sendEventToUser_MultipleEmitters() {
        TestSseEmitter emitter1 = createAndRegisterTestEmitter("e1", TEST_USER_1);
        TestSseEmitter emitter2 = createAndRegisterTestEmitter("e2", TEST_USER_1);
        String eventName = "multiEvent";
        String eventData = "multiData";
        sseService.sendEventToUser(TEST_USER_1, eventData, eventName);
        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emitter1.getCapturedEvents().stream()
                    .anyMatch(e -> eventName.equals(e.name) && Objects.equals(e.getFirstDataLine(), eventData))).isTrue();
            assertThat(emitter2.getCapturedEvents().stream()
                    .anyMatch(e -> eventName.equals(e.name) && Objects.equals(e.getFirstDataLine(), eventData))).isTrue();
        });
    }

    @Test
    @DisplayName("sendEventToUser does nothing if user has no emitters")
    void sendEventToUser_NoEmitters() {
        TestSseEmitter emitterUser2 = createAndRegisterTestEmitter("e-other", TEST_USER_2);
        sseService.sendEventToUser(TEST_USER_1, "someData", "someEvent");
        Awaitility.await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        assertThat(getEmitterCountForUser(TEST_USER_1)).isZero();
        assertThat(emitterUser2.getCapturedEvents()).isEmpty();
    }

    @Test
    @DisplayName("sendEventToUser removes emitter if send causes IOException")
    void sendEventToUser_IOExceptionRemovesEmitter() {
        TestSseEmitter faultyEmitter = createAndRegisterTestEmitter("faulty", TEST_USER_1);
        TestSseEmitter goodEmitter = createAndRegisterTestEmitter("good", TEST_USER_1);
        faultyEmitter.setThrowOnSend(true);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 2);
        sseService.sendEventToUser(TEST_USER_1, "errorData", "errorEvent");
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1);
        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(goodEmitter.getCapturedEvents().stream()
                        .anyMatch(e -> "errorEvent".equals(e.name) && Objects.equals(e.getFirstDataLine(), "errorData")))
                        .as("Good emitter should have received the specific event").isTrue());
    }

    @Test
    @DisplayName("sendHeartbeat sends comment to active emitters")
    void sendHeartbeat_SendsComment() {
        TestSseEmitter emitter1 = createAndRegisterTestEmitter("e1-hb", TEST_USER_1);
        TestSseEmitter emitter2 = createAndRegisterTestEmitter("e2-hb", TEST_USER_2);
        sseService.sendHeartbeat();
        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(emitter1.getCapturedEvents().stream()
                    .anyMatch(e -> e.getFirstComment() != null && e.getFirstComment().equals("keep-alive"))).isTrue();
            assertThat(emitter2.getCapturedEvents().stream()
                    .anyMatch(e -> e.getFirstComment() != null && e.getFirstComment().equals("keep-alive"))).isTrue();
        });
    }

    @Test
    @DisplayName("sendHeartbeat does nothing if no emitters are present")
    void sendHeartbeat_NoEmitters_DoesNothing() {
        sseService.sendHeartbeat();
        assertThat(getEmitterCountForUser(TEST_USER_1)).isZero();
        assertThat(getEmitterCountForUser(TEST_USER_2)).isZero();
    }

    @Test
    @DisplayName("sendHeartbeat removes emitter if send causes IOException")
    void sendHeartbeat_IOExceptionRemovesEmitter() {
        TestSseEmitter faultyEmitter = createAndRegisterTestEmitter("faulty-hb", TEST_USER_1);
        TestSseEmitter goodEmitter = createAndRegisterTestEmitter("good-hb", TEST_USER_1);
        faultyEmitter.setThrowOnSend(true);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 2);
        sseService.sendHeartbeat();
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1);
        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(goodEmitter.getCapturedEvents().stream()
                        .anyMatch(e -> e.getFirstComment() != null && e.getFirstComment().equals("keep-alive")))
                        .as("Good emitter should have received keep-alive comment").isTrue());
    }

    @Test
    @DisplayName("shutdown completes all active emitters and removes them")
    void shutdown_CompletesEmitters() {
        createAndRegisterTestEmitter("e1-shutdown", TEST_USER_1);
        createAndRegisterTestEmitter("e2-shutdown", TEST_USER_2);
        Awaitility.await().atMost(SERVICE_OPERATION_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .until(() -> getEmitterCountForUser(TEST_USER_1) == 1 && getEmitterCountForUser(TEST_USER_2) == 1);

        sseService.shutdown();

        Awaitility.await().atMost(CALLBACK_AWAIT_TIMEOUT_S, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(getEmitterCountForUser(TEST_USER_1)).isZero();
                    assertThat(getEmitterCountForUser(TEST_USER_2)).isZero();
                });
    }

    @SuppressWarnings("unchecked")
    private int getEmitterCountForUser(String username) {
        try {
            Field emittersMapField = SseServiceImpl.class.getDeclaredField("userEmitters");
            emittersMapField.setAccessible(true);
            Map<String, CopyOnWriteArrayList<SseEmitter>> userEmitters =
                    (Map<String, CopyOnWriteArrayList<SseEmitter>>) emittersMapField.get(sseService);
            return userEmitters.getOrDefault(username, new CopyOnWriteArrayList<>()).size();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("Failed to access userEmitters map via reflection: " + e.getMessage());
            return -1;
        }
    }
}
package com.example.fsdemo.service;

import com.example.fsdemo.service.impl.SseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SseServiceImpl Unit Tests")
class SseServiceImplTest {

    @Spy
    private SseServiceImpl sseService;

    @Captor
    private ArgumentCaptor<Runnable> runnableCaptor;
    @Captor
    private ArgumentCaptor<Consumer<Throwable>> errorConsumerCaptor;

    private Map<String, CopyOnWriteArrayList<SseEmitter>> userEmittersMap;

    private final String testUser1 = "user1";
    private final String testUser2 = "user2";
    private final String eventName = "testEvent";
    private final Object eventData = "TestData";

    @BeforeEach
    void setUp() {
        Object field = ReflectionTestUtils.getField(sseService, "userEmitters");

        if (!(field instanceof Map)) {
            throw new IllegalStateException(
                    "SseServiceImpl.userEmitters is not an instance of Map. Actual type: " +
                            (field == null ? "null" : field.getClass().getName())
            );
        }

        @SuppressWarnings("unchecked")
        Map<String, CopyOnWriteArrayList<SseEmitter>> typedMap =
                (Map<String, CopyOnWriteArrayList<SseEmitter>>) field;

        typedMap.clear();
        this.userEmittersMap = typedMap;
    }

    private SseEmitter createMockEmitter() {
        SseEmitter emitter = mock(SseEmitter.class);
        lenient().doNothing().when(emitter).onCompletion(any(Runnable.class));
        lenient().doNothing().when(emitter).onTimeout(any(Runnable.class));
        lenient().doNothing().when(emitter).onError(ArgumentMatchers.any());
        return emitter;
    }

    @Nested
    @DisplayName("addEmitter Tests")
    class AddEmitterTests {

        @Test
        @DisplayName("✅ Should add emitter for a new user")
        void addEmitter_NewUser_ShouldAdd() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            assertThat(userEmittersMap).containsKey(testUser1);
            assertThat(userEmittersMap.get(testUser1)).containsExactly(emitter);
            verify(emitter).onCompletion(any(Runnable.class));
            verify(emitter).onTimeout(any(Runnable.class));
            verify(emitter).onError(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("✅ Should add multiple emitters for the same user")
        void addEmitter_ExistingUser_ShouldAddToList() {
            SseEmitter emitter1 = createMockEmitter();
            SseEmitter emitter2 = createMockEmitter();

            sseService.addEmitter(testUser1, emitter1);
            sseService.addEmitter(testUser1, emitter2);

            assertThat(userEmittersMap.get(testUser1)).containsExactlyInAnyOrder(emitter1, emitter2);
        }

        @Test
        @DisplayName("✅ onCompletion callback should remove emitter")
        void addEmitter_OnCompletion_ShouldRemoveEmitter() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            verify(emitter).onCompletion(runnableCaptor.capture());
            Runnable onCompletionCallback = runnableCaptor.getValue();
            onCompletionCallback.run();

            assertThat(userEmittersMap).doesNotContainKey(testUser1);
        }

        @Test
        @DisplayName("✅ onTimeout callback should remove emitter")
        void addEmitter_OnTimeout_ShouldRemoveEmitter() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            verify(emitter).onTimeout(runnableCaptor.capture());
            Runnable onTimeoutCallback = runnableCaptor.getValue();
            onTimeoutCallback.run();

            assertThat(userEmittersMap).doesNotContainKey(testUser1);
        }

        @Test
        @DisplayName("✅ onError callback should remove emitter")
        void addEmitter_OnError_ShouldRemoveEmitter() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            verify(emitter).onError(errorConsumerCaptor.capture());
            Consumer<Throwable> onErrorCallback = errorConsumerCaptor.getValue();
            onErrorCallback.accept(new IOException("Test error"));

            assertThat(userEmittersMap).doesNotContainKey(testUser1);
        }
    }

    @Nested
    @DisplayName("removeEmitter Tests")
    class RemoveEmitterTests {

        @Test
        @DisplayName("✅ Should remove an existing emitter")
        void removeEmitter_ExistingEmitter_ShouldRemove() {
            SseEmitter emitter1 = createMockEmitter();
            SseEmitter emitter2 = createMockEmitter();
            sseService.addEmitter(testUser1, emitter1);
            sseService.addEmitter(testUser1, emitter2);

            sseService.removeEmitter(testUser1, emitter1);

            assertThat(userEmittersMap.get(testUser1)).containsExactly(emitter2);
        }

        @Test
        @DisplayName("✅ Should remove user from map if last emitter is removed")
        void removeEmitter_LastEmitter_ShouldRemoveUserFromMap() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            sseService.removeEmitter(testUser1, emitter);

            assertThat(userEmittersMap).doesNotContainKey(testUser1);
        }

        @Test
        @DisplayName("✅ Should not throw error if removing non-existent emitter for existing user")
        void removeEmitter_NonExistentEmitter_ShouldNotThrow() {
            SseEmitter emitter1 = createMockEmitter();
            SseEmitter nonExistentEmitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter1);

            assertThatCode(() -> sseService.removeEmitter(testUser1, nonExistentEmitter))
                    .doesNotThrowAnyException();
            assertThat(userEmittersMap.get(testUser1)).containsExactly(emitter1);
        }

        @Test
        @DisplayName("✅ Should not throw error if removing emitter for non-existent user")
        void removeEmitter_NonExistentUser_ShouldNotThrow() {
            SseEmitter emitter = createMockEmitter();
            assertThatCode(() -> sseService.removeEmitter("nonExistentUser", emitter))
                    .doesNotThrowAnyException();
            assertThat(userEmittersMap).isEmpty();
        }

        @Test
        @DisplayName("✅ Should handle null username gracefully")
        void removeEmitter_NullUsername_ShouldNotThrow() {
            SseEmitter emitter = createMockEmitter();
            assertThatCode(() -> sseService.removeEmitter(null, emitter))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ Should handle null emitter gracefully")
        void removeEmitter_NullEmitter_ShouldNotThrow() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);
            assertThatCode(() -> sseService.removeEmitter(testUser1, null))
                    .doesNotThrowAnyException();
            assertThat(userEmittersMap.get(testUser1)).containsExactly(emitter);
        }
    }

    @Nested
    @DisplayName("sendEventToUser Tests")
    class SendEventToUserTests {

        @Test
        @DisplayName("✅ Should send event to user with one emitter")
        void sendEvent_OneEmitter_ShouldSend() throws IOException {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);

            sseService.sendEventToUser(testUser1, eventData, eventName);

            verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        }

        @Test
        @DisplayName("✅ Should send event to user with multiple emitters")
        void sendEvent_MultipleEmitters_ShouldSendToAll() throws IOException {
            SseEmitter emitter1 = createMockEmitter();
            SseEmitter emitter2 = createMockEmitter();
            sseService.addEmitter(testUser1, emitter1);
            sseService.addEmitter(testUser1, emitter2);

            sseService.sendEventToUser(testUser1, eventData, eventName);

            verify(emitter1).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter2).send(any(SseEmitter.SseEventBuilder.class));
        }

        @Test
        @DisplayName("✅ Should not throw if user has no active emitters")
        void sendEvent_NoEmitters_ShouldNotThrow() {
            SseEmitter emitter = createMockEmitter();
            sseService.addEmitter(testUser1, emitter);
            sseService.removeEmitter(testUser1, emitter);


            assertThatCode(() -> sseService.sendEventToUser(testUser1, eventData, eventName))
                    .doesNotThrowAnyException();

            assertThat(userEmittersMap).doesNotContainKey(testUser1);
        }

        @Test
        @DisplayName("✅ Should not throw if user does not exist")
        void sendEvent_NonExistentUser_ShouldNotThrow() {
            assertThatCode(() -> sseService.sendEventToUser("nonExistentUser", eventData, eventName))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("❌ Should remove emitter if IOException occurs during send")
        void sendEvent_IOException_ShouldRemoveEmitter() throws IOException {
            SseEmitter faultyEmitter = createMockEmitter();
            SseEmitter goodEmitter = createMockEmitter();
            sseService.addEmitter(testUser1, faultyEmitter);
            sseService.addEmitter(testUser1, goodEmitter);

            doThrow(new IOException("Send failed")).when(faultyEmitter).send(any(SseEmitter.SseEventBuilder.class));

            sseService.sendEventToUser(testUser1, eventData, eventName);

            verify(faultyEmitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(goodEmitter).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(userEmittersMap.get(testUser1)).containsExactly(goodEmitter);
        }

        @Test
        @DisplayName("❌ Should remove emitter if generic Exception occurs during send")
        void sendEvent_GenericException_ShouldRemoveEmitter() throws IOException {
            SseEmitter faultyEmitter = createMockEmitter();
            SseEmitter goodEmitter = createMockEmitter();
            sseService.addEmitter(testUser1, faultyEmitter);
            sseService.addEmitter(testUser1, goodEmitter);

            doThrow(new RuntimeException("Unexpected send error")).when(faultyEmitter).send(any(SseEmitter.SseEventBuilder.class));

            sseService.sendEventToUser(testUser1, eventData, eventName);

            verify(faultyEmitter).send(any(SseEmitter.SseEventBuilder.class));
            verify(goodEmitter).send(any(SseEmitter.SseEventBuilder.class));
            assertThat(userEmittersMap.get(testUser1)).containsExactly(goodEmitter);
        }
    }

    @Nested
    @DisplayName("sendHeartbeat Tests")
    class SendHeartbeatTests {

        @Test
        @DisplayName("✅ Should send heartbeat to all active emitters")
        void sendHeartbeat_MultipleUsersAndEmitters_ShouldSendToAll() throws IOException {
            SseEmitter emitter1User1 = createMockEmitter();
            SseEmitter emitter2User1 = createMockEmitter();
            SseEmitter emitter1User2 = createMockEmitter();

            sseService.addEmitter(testUser1, emitter1User1);
            sseService.addEmitter(testUser1, emitter2User1);
            sseService.addEmitter(testUser2, emitter1User2);

            try (MockedStatic<SseEmitter> mockedSseEmitter = mockStatic(SseEmitter.class)) {
                SseEmitter.SseEventBuilder mockBuilderInstance = mock(SseEmitter.SseEventBuilder.class);
                mockedSseEmitter.when(SseEmitter::event).thenReturn(mockBuilderInstance);
                when(mockBuilderInstance.comment(anyString())).thenReturn(mockBuilderInstance);

                sseService.sendHeartbeat();

                verify(emitter1User1).send(mockBuilderInstance);
                verify(emitter2User1).send(mockBuilderInstance);
                verify(emitter1User2).send(mockBuilderInstance);

                verify(mockBuilderInstance, times(3)).comment("keep-alive");
            }
        }

        @Test
        @DisplayName("✅ Should do nothing if no emitters exist")
        void sendHeartbeat_NoEmitters_ShouldDoNothing() {
            assertThat(userEmittersMap).isEmpty();
            try (MockedStatic<SseEmitter> mockedSseEmitter = mockStatic(SseEmitter.class)) {
                assertThatCode(() -> sseService.sendHeartbeat()).doesNotThrowAnyException();
                mockedSseEmitter.verify(SseEmitter::event, never());
            }
        }

        @Test
        @DisplayName("❌ Should remove emitter if IOException occurs during heartbeat")
        void sendHeartbeat_IOException_ShouldRemoveEmitter() throws IOException {
            SseEmitter faultyEmitter = createMockEmitter();
            SseEmitter goodEmitter = createMockEmitter();
            sseService.addEmitter(testUser1, faultyEmitter);
            sseService.addEmitter(testUser1, goodEmitter);

            try (MockedStatic<SseEmitter> mockedSseEmitter = mockStatic(SseEmitter.class)) {
                SseEmitter.SseEventBuilder mockBuilderInstance = mock(SseEmitter.SseEventBuilder.class);
                mockedSseEmitter.when(SseEmitter::event).thenReturn(mockBuilderInstance);
                when(mockBuilderInstance.comment(anyString())).thenReturn(mockBuilderInstance);

                doThrow(new IOException("Heartbeat failed")).when(faultyEmitter).send(mockBuilderInstance);
                // goodEmitter should send normally
                lenient().doNothing().when(goodEmitter).send(mockBuilderInstance);


                sseService.sendHeartbeat();

                verify(faultyEmitter).send(mockBuilderInstance);
                verify(goodEmitter).send(mockBuilderInstance);
                assertThat(userEmittersMap.get(testUser1)).containsExactly(goodEmitter);
            }
        }

        @Test
        @DisplayName("❌ Should remove emitter if generic Exception occurs during heartbeat")
        void sendHeartbeat_GenericException_ShouldRemoveEmitter() throws IOException {
            SseEmitter faultyEmitter = createMockEmitter();
            SseEmitter goodEmitter = createMockEmitter();
            sseService.addEmitter(testUser1, faultyEmitter);
            sseService.addEmitter(testUser1, goodEmitter);

            try (MockedStatic<SseEmitter> mockedSseEmitter = mockStatic(SseEmitter.class)) {
                SseEmitter.SseEventBuilder mockBuilderInstance = mock(SseEmitter.SseEventBuilder.class);
                mockedSseEmitter.when(SseEmitter::event).thenReturn(mockBuilderInstance);
                when(mockBuilderInstance.comment(anyString())).thenReturn(mockBuilderInstance);

                doThrow(new RuntimeException("Unexpected heartbeat error")).when(faultyEmitter).send(mockBuilderInstance);
                lenient().doNothing().when(goodEmitter).send(mockBuilderInstance);

                sseService.sendHeartbeat();

                verify(faultyEmitter).send(mockBuilderInstance);
                verify(goodEmitter).send(mockBuilderInstance);
                assertThat(userEmittersMap.get(testUser1)).containsExactly(goodEmitter);
            }
        }
    }

    @Nested
    @DisplayName("shutdown Tests (@PreDestroy)")
    class ShutdownTests {

        @Test
        @DisplayName("✅ Should complete all active emitters and clear map")
        void shutdown_CompletesAllEmittersAndClearsMap() {
            SseEmitter emitter1User1 = createMockEmitter();
            SseEmitter emitter2User1 = createMockEmitter();
            SseEmitter emitter1User2 = createMockEmitter();

            sseService.addEmitter(testUser1, emitter1User1);
            sseService.addEmitter(testUser1, emitter2User1);
            sseService.addEmitter(testUser2, emitter1User2);

            sseService.shutdown();

            verify(emitter1User1).complete();
            verify(emitter2User1).complete();
            verify(emitter1User2).complete();
            assertThat(userEmittersMap).isEmpty();
        }

        @Test
        @DisplayName("✅ Should handle exceptions during emitter.complete() gracefully")
        void shutdown_HandlesEmitterCompleteException() {
            SseEmitter faultyEmitter = createMockEmitter();
            SseEmitter goodEmitter = createMockEmitter();

            sseService.addEmitter(testUser1, faultyEmitter);
            sseService.addEmitter(testUser1, goodEmitter);

            doThrow(new RuntimeException("Complete failed")).when(faultyEmitter).complete();

            assertThatCode(() -> sseService.shutdown()).doesNotThrowAnyException();

            verify(faultyEmitter).complete();
            verify(goodEmitter).complete();
            assertThat(userEmittersMap).isEmpty();
        }

        @Test
        @DisplayName("✅ Should do nothing if no emitters on shutdown")
        void shutdown_NoEmitters_ShouldDoNothing() {
            assertThat(userEmittersMap).isEmpty();
            assertThatCode(() -> sseService.shutdown()).doesNotThrowAnyException();
            assertThat(userEmittersMap).isEmpty();
        }
    }
}
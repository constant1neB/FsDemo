package com.example.fsdemo.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncConfig Tests")
class AsyncConfigTest {

    @InjectMocks
    private AsyncConfig asyncConfig;

    public void dummyAsyncMethod(String arg1, int arg2) {
        throw new RuntimeException("Async Test Error");
    }

    @Test
    @DisplayName("asyncTaskExecutor should return TaskExecutorAdapter")
    void asyncTaskExecutor_ReturnsCorrectType() {
        AsyncTaskExecutor executor = asyncConfig.asyncTaskExecutor();
        assertThat(executor).isInstanceOf(TaskExecutorAdapter.class);
    }

    @Test
    @DisplayName("getAsyncUncaughtExceptionHandler should return a non-null handler")
    void getAsyncUncaughtExceptionHandler_ReturnsHandler() {
        AsyncUncaughtExceptionHandler handler = asyncConfig.getAsyncUncaughtExceptionHandler();
        assertThat(handler).isNotNull();
    }

    @Test
    @DisplayName("AsyncUncaughtExceptionHandler should run without error")
    void asyncUncaughtExceptionHandler_RunsWithoutError() throws NoSuchMethodException {
        AsyncUncaughtExceptionHandler handler = asyncConfig.getAsyncUncaughtExceptionHandler();
        assertThat(handler).isNotNull();

        Throwable testException = new RuntimeException("Async Test Error");
        Method testMethod = AsyncConfigTest.class.getDeclaredMethod("dummyAsyncMethod", String.class, int.class);
        Object[] testParams = {"param1", 123};

        assertThatCode(() -> {
            handler.handleUncaughtException(testException, testMethod, testParams);
        }).doesNotThrowAnyException();
    }
}
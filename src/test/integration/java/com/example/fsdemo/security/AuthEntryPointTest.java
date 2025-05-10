package com.example.fsdemo.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
@DisplayName("AuthEntryPoint Tests")
class AuthEntryPointTest {

    @Mock
    private HttpServletRequest request;

    @Spy
    private MockHttpServletResponse responseSpy = new MockHttpServletResponse();

    @Mock
    private HttpServletResponse errorResponseMock;

    @Mock
    private PrintWriter writerMock;

    @Autowired
    private ObjectMapper objectMapper;

    private AuthEntryPoint authEntryPoint;

    @BeforeEach
    void setUp() {
        authEntryPoint = new AuthEntryPoint(objectMapper);
    }

    @Test
    @DisplayName("commence should set 401 status, correct headers, and write ProblemDetail JSON")
    void commence_setsCorrectStatusHeadersAndBody() throws IOException {
        String requestUri = "/api/protected/resource";
        AuthenticationException authException = new BadCredentialsException("Invalid credentials provided");
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        when(request.getRequestURI()).thenReturn(requestUri);
        doReturn(printWriter).when(responseSpy).getWriter();

        authEntryPoint.commence(request, responseSpy, authException);

        verify(responseSpy).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(responseSpy).setCharacterEncoding(StandardCharsets.UTF_8.name());
        verify(responseSpy).setContentType(startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));

        String jsonResponse = stringWriter.toString();
        assertThat(jsonResponse).isNotBlank();
        ProblemDetail problemDetail = objectMapper.readValue(jsonResponse, ProblemDetail.class);

        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Unauthorized");
        assertThat(problemDetail.getDetail()).isEqualTo("Authentication failed or is required to access this resource.");
        assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
        assertThat(problemDetail.getProperties()).isNotNull().containsKey("timestamp");

        Object timestampValue = problemDetail.getProperties().get("timestamp");
        assertThat(timestampValue).isNotNull();

        Instant timestamp;
        if (timestampValue instanceof String) {
            timestamp = Instant.parse((String) timestampValue);
        } else if (timestampValue instanceof Number) {
            long epochMillis = ((Number) timestampValue).longValue();
            if (String.valueOf(epochMillis).length() > 10) {
                timestamp = Instant.ofEpochMilli(epochMillis);
            } else {
                timestamp = Instant.ofEpochSecond(epochMillis);
            }
        } else {
            fail("Timestamp property was not a String or Number: " + timestampValue.getClass());
            return;
        }
        assertThat(timestamp).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    @DisplayName("commence should handle IOException during response writing gracefully (logs error)")
    void commence_handlesIOExceptionDuringWrite() throws IOException {
        String requestUri = "/api/error/resource";
        AuthenticationException authException = new BadCredentialsException("Auth error");

        when(request.getRequestURI()).thenReturn(requestUri);
        when(errorResponseMock.getWriter()).thenReturn(writerMock);


        doThrow(new IOException("Simulated write error"))
                .when(writerMock).write(any(char[].class), anyInt(), anyInt());

        assertThatCode(() -> authEntryPoint.commence(request, errorResponseMock, authException))
                .doesNotThrowAnyException();

        verify(errorResponseMock).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(errorResponseMock).setCharacterEncoding(StandardCharsets.UTF_8.name());
        verify(errorResponseMock).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        verify(errorResponseMock).getWriter();

        verify(writerMock).write(any(char[].class), anyInt(), anyInt());
    }
}
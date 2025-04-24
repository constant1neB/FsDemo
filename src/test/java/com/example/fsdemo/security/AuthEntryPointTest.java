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
    private PrintWriter writerMock; // Mock the writer for the error case

    @Autowired
    private ObjectMapper objectMapper; // Inject the real ObjectMapper

    private AuthEntryPoint authEntryPoint;

    @BeforeEach
    void setUp() {
        // Manually instantiate with the autowired ObjectMapper
        authEntryPoint = new AuthEntryPoint(objectMapper);
    }

    @Test
    @DisplayName("commence should set 401 status, correct headers, and write ProblemDetail JSON")
    void commence_setsCorrectStatusHeadersAndBody() throws IOException {
        // --- Arrange ---
        String requestUri = "/api/protected/resource";
        AuthenticationException authException = new BadCredentialsException("Invalid credentials provided");
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);

        when(request.getRequestURI()).thenReturn(requestUri);
        // Stub the getWriter() method on the spy to return our PrintWriter
        doReturn(printWriter).when(responseSpy).getWriter();

        // --- Act ---
        authEntryPoint.commence(request, responseSpy, authException);

        // --- Assert ---
        verify(responseSpy).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(responseSpy).setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Use startsWith for Content-Type assertion
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
            if (String.valueOf(epochMillis).length() > 10) { // Simple check for millis vs seconds
                timestamp = Instant.ofEpochMilli(epochMillis);
            } else { // Assume seconds
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
        // --- Arrange ---
        String requestUri = "/api/error/resource";
        AuthenticationException authException = new BadCredentialsException("Auth error");

        // Use the mock response and mock writer
        when(request.getRequestURI()).thenReturn(requestUri);
        when(errorResponseMock.getWriter()).thenReturn(writerMock);


        doThrow(new IOException("Simulated write error"))
                .when(writerMock).write(any(char[].class), anyInt(), anyInt());

        // --- Act & Assert ---
        // Use the authEntryPoint instance with the real ObjectMapper
        assertThatCode(() -> authEntryPoint.commence(request, errorResponseMock, authException))
                .doesNotThrowAnyException(); // Verify the exception is caught internally

        // Verify that the response setters were still called
        verify(errorResponseMock).setStatus(HttpStatus.UNAUTHORIZED.value());
        verify(errorResponseMock).setCharacterEncoding(StandardCharsets.UTF_8.name());
        verify(errorResponseMock).setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        // Verify that getWriter was called to get the writer
        verify(errorResponseMock).getWriter();

        // Verify that the writer's write method was called (which triggered the exception)
        verify(writerMock).write(any(char[].class), anyInt(), anyInt());
    }
}
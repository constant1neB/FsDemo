package com.example.fsdemo.exceptions;

import jakarta.validation.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.MethodParameter;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GlobalExceptionHandler Tests")
@MockitoSettings(strictness = Strictness.LENIENT)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler globalExceptionHandler;

    @Mock
    private WebRequest webRequest;

    private final String requestUri = "/api/test/resource";
    private HttpHeaders defaultHeaders;

    @BeforeEach
    void setUp() {
        when(webRequest.getDescription(false)).thenReturn(requestUri);
        defaultHeaders = new HttpHeaders();
    }

    @Nested
    @DisplayName("VideoStorageException Handling")
    class VideoStorageExceptionTests {
        @Test
        @DisplayName("Should return 500 Internal Server Error with correct ProblemDetail")
        void handleVideoStorageException() {
            VideoStorageException ex = new VideoStorageException("Failed to store video");

            ProblemDetail problemDetail = globalExceptionHandler.handleVideoStorageException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Failed to process video storage operation. Please contact support if the problem persists.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties())
                    .containsKey("timestamp")
                    .extracting("timestamp").isInstanceOf(Instant.class);
        }
    }

    @Nested
    @DisplayName("AccessDeniedException Handling")
    class AccessDeniedExceptionTests {
        @Test
        @DisplayName("Should return 403 Forbidden with correct ProblemDetail")
        void handleAccessDeniedException() {
            AccessDeniedException ex = new AccessDeniedException("Permission denied");

            ProblemDetail problemDetail = globalExceptionHandler.handleAccessDeniedException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.FORBIDDEN.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Access Denied. You do not have sufficient permissions to access this resource.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("AuthenticationException Handling")
    class AuthenticationExceptionTests {
        @Test
        @DisplayName("Should return 401 Unauthorized with correct ProblemDetail")
        void handleAuthenticationException() {
            AuthenticationException ex = new BadCredentialsException("Bad credentials");

            ProblemDetail problemDetail = globalExceptionHandler.handleAuthenticationException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.UNAUTHORIZED.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Authentication failed. Please check your credentials or log in.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    private record TestDto(@NotBlank String name, @Size(min = 5) String value) {
    }

    @Nested
    @DisplayName("ConstraintViolationException Handling")
    class ConstraintViolationExceptionTests {
        @Test
        @DisplayName("Should return 400 Bad Request with validation errors")
        void handleConstraintViolationException() {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                Set<ConstraintViolation<TestDto>> violations = validator.validate(new TestDto("Test", "123"));
                ConstraintViolationException ex = new ConstraintViolationException("Validation failed", violations);

                ProblemDetail problemDetail = globalExceptionHandler.handleConstraintViolationException(ex, webRequest);

                assertThat(problemDetail).isNotNull();
                assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
                assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
                assertThat(problemDetail.getDetail()).isEqualTo("Input validation failed. Check the 'errors' field for details.");
                assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
                assertThat(problemDetail.getProperties()).containsKey("timestamp");
                assertThat(problemDetail.getProperties()).containsKey("errors");

                @SuppressWarnings("unchecked")
                Map<String, String> errors = (Map<String, String>) Objects.requireNonNull(problemDetail.getProperties()).get("errors");
                assertThat(errors).hasSize(1);
                assertThat(errors.get("value"))
                        .isIn(
                                "size must be between 5 and 2147483647",
                                "размер должен находиться в диапазоне от 5 до 2147483647"
                        );
            }
        }
    }

    @Nested
    @DisplayName("MethodArgumentNotValidException Handling")
    class MethodArgumentNotValidExceptionTests {
        @Test
        @DisplayName("Should return 400 Bad Request with validation errors from @Valid")
        void handleMethodArgumentNotValid() throws NoSuchMethodException {
            BindingResult bindingResult = mock(BindingResult.class);
            FieldError fieldError = new FieldError("testDto", "name", null,
                    false, null, null, "must not be blank");
            when(bindingResult.getAllErrors()).thenReturn(List.of(fieldError));

            MethodParameter parameter = new MethodParameter(this.getClass().getDeclaredMethod("dummyMethod",
                    TestDto.class), 0);
            MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleMethodArgumentNotValid(
                    ex, defaultHeaders, HttpStatus.BAD_REQUEST, webRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Request body validation failed. Check the 'errors' field for details.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
            assertThat(problemDetail.getProperties()).containsKey("errors");

            @SuppressWarnings("unchecked")
            Map<String, String> errors = (Map<String, String>) Objects.requireNonNull(problemDetail.getProperties()).get("errors");
            assertThat(errors)
                    .hasSize(1)
                    .containsEntry("name", "must not be blank");
        }

        void dummyMethod(TestDto dto) {
            // Dummy method needed for MethodParameter creation
        }
    }

    @Nested
    @DisplayName("HttpRequestMethodNotSupportedException Handling")
    class HttpRequestMethodNotSupportedExceptionTests {
        @Test
        @DisplayName("Should return 405 Method Not Allowed with Allow header")
        void handleHttpRequestMethodNotSupported() {
            HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException(
                    HttpMethod.POST.name(), List.of(HttpMethod.GET.name(), HttpMethod.PUT.name()));

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleHttpRequestMethodNotSupported(
                    ex, defaultHeaders, HttpStatus.METHOD_NOT_ALLOWED, webRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(responseEntity.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PUT);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo(ex.getMessage());
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("HttpMediaTypeNotSupportedException Handling")
    class HttpMediaTypeNotSupportedExceptionTests {
        @Test
        @DisplayName("Should return 415 Unsupported Media Type with Accept header")
        void handleHttpMediaTypeNotSupported() {
            HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                    MediaType.APPLICATION_XML, List.of(MediaType.APPLICATION_JSON));

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleHttpMediaTypeNotSupported(
                    ex, defaultHeaders, HttpStatus.UNSUPPORTED_MEDIA_TYPE, webRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(responseEntity.getHeaders().getAccept()).containsExactly(MediaType.APPLICATION_JSON);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo(ex.getMessage());
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("MissingServletRequestParameterException Handling")
    class MissingServletRequestParameterExceptionTests {
        @Test
        @DisplayName("Should return 400 Bad Request")
        void handleMissingServletRequestParameter() {
            MissingServletRequestParameterException ex = new MissingServletRequestParameterException(
                    "paramName", "String");

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleMissingServletRequestParameter(
                    ex, defaultHeaders, HttpStatus.BAD_REQUEST, webRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo(ex.getMessage());
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("ResponseStatusException Handling")
    class ResponseStatusExceptionTests {
        @Test
        @DisplayName("Should return ProblemDetail matching the ResponseStatusException")
        void handleResponseStatusException_NotFound() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource Not Found");

            ProblemDetail problemDetail = globalExceptionHandler.handleResponseStatusException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Resource Not Found");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("Should use standard title for non-error statuses")
        void handleResponseStatusException_NotModified() {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_MODIFIED, "Resource Not Modified");

            ProblemDetail problemDetail = globalExceptionHandler.handleResponseStatusException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_MODIFIED.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.NOT_MODIFIED.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("Resource Not Modified");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("Generic Exception Handling")
    class GenericExceptionTests {
        @Test
        @DisplayName("Should return 500 Internal Server Error with generic message for unhandled Exception")
        void handleGenericException() {
            Exception ex = new IOException("Disk read error");

            ProblemDetail problemDetail = globalExceptionHandler.handleGenericException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("An unexpected internal error occurred. Please try again later or contact support.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("Should return 500 Internal Server Error for NullPointerException")
        void handleNullPointerException() {
            Exception ex = new NullPointerException("Something was null unexpectedly");

            ProblemDetail problemDetail = globalExceptionHandler.handleGenericException(ex, webRequest);

            assertThat(problemDetail).isNotNull();
            assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo("An unexpected internal error occurred. Please try again later or contact support.");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create(requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("handleExceptionInternal Override")
    class HandleExceptionInternalTests {

        @Test
        @DisplayName("Should handle MaxUploadSizeExceededException correctly")
        void handleMaxUploadSizeExceededException() {
            MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(1024L);
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", requestUri);
            ServletWebRequest servletWebRequest = new ServletWebRequest(servletRequest);

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleExceptionInternal(
                    ex, null, defaultHeaders, HttpStatus.PAYLOAD_TOO_LARGE, servletWebRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase());
            assertThat(problemDetail.getDetail()).contains("Maximum upload size exceeded");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create("uri=" + requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("Should handle other exceptions passed to handleExceptionInternal with basic ProblemDetail")
        void handleOtherExceptionInternal() {
            IllegalStateException ex = new IllegalStateException("Some internal state error");
            HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", requestUri);
            ServletWebRequest servletWebRequest = new ServletWebRequest(servletRequest);


            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleExceptionInternal(
                    ex, null, defaultHeaders, status, servletWebRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(status);
            assertThat(responseEntity.getBody()).isInstanceOf(ProblemDetail.class);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
            assertThat(problemDetail.getDetail()).isEqualTo(ex.getMessage());
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create("uri=" + requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }

        @Test
        @DisplayName("Should use existing ProblemDetail body if provided")
        void handleExceptionInternalWithProblemDetailBody() {
            IllegalStateException ex = new IllegalStateException("Some internal state error");
            HttpStatus status = HttpStatus.BAD_GATEWAY;
            MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", requestUri);
            ServletWebRequest servletWebRequest = new ServletWebRequest(servletRequest);

            ProblemDetail existingProblemDetail = ProblemDetail.forStatusAndDetail(status, "Pre-existing detail");
            existingProblemDetail.setTitle("Custom Title");

            ResponseEntity<Object> responseEntity = globalExceptionHandler.handleExceptionInternal(
                    ex, existingProblemDetail, defaultHeaders, status, servletWebRequest);

            assertThat(responseEntity).isNotNull();
            assertThat(responseEntity.getStatusCode()).isEqualTo(status);
            assertThat(responseEntity.getBody()).isSameAs(existingProblemDetail);

            ProblemDetail problemDetail = (ProblemDetail) responseEntity.getBody();
            assert problemDetail != null;
            assertThat(problemDetail.getTitle()).isEqualTo("Custom Title");
            assertThat(problemDetail.getDetail()).isEqualTo("Pre-existing detail");
            assertThat(problemDetail.getInstance()).isEqualTo(URI.create("uri=" + requestUri));
            assertThat(problemDetail.getProperties()).containsKey("timestamp");
        }
    }

    @Nested
    @DisplayName("Helper Method Tests")
    class HelperMethodTests {

        @Test
        @DisplayName("getReasonPhrase should return standard phrase for HttpStatus")
        void getReasonPhrase_HttpStatus() {
            String phrase = ReflectionTestUtils.invokeMethod(globalExceptionHandler, "getReasonPhrase", HttpStatus.NOT_FOUND);
            assertThat(phrase).isEqualTo(HttpStatus.NOT_FOUND.getReasonPhrase());
        }

        @ParameterizedTest
        @ValueSource(strings = {"field", "object.field", "list[0].field", "map[key]", "complex.list[1].map[key].finalField"})
        @DisplayName("getPropertyName should extract last part correctly")
        void getPropertyName_ValidCases(String input) {
            String actual = ReflectionTestUtils.invokeMethod(globalExceptionHandler, "getPropertyName", input);
            switch (input) {
                case "field", "object.field", "list[0].field" -> assertThat(actual).isEqualTo("field");
                case "map[key]" ->
                        assertThat(actual).isEqualTo("key]");
                case "complex.list[1].map[key].finalField" ->
                        assertThat(actual).isEqualTo("finalField");
                default ->
                        assertThat(actual).isEqualTo(input.substring(Math.max(input.lastIndexOf('.'), input.lastIndexOf('[')) + 1));
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {""})
        @NullSource
        @DisplayName("getPropertyName should return 'unknown' for null or empty input")
        void getPropertyName_NullOrEmpty(String input) {
            String actual = ReflectionTestUtils.invokeMethod(globalExceptionHandler, "getPropertyName", input);
            assertThat(actual).isEqualTo("unknown");
        }

        @Test
        @DisplayName("getPropertyName should return blank string for blank input (current behavior)")
        void getPropertyName_Blank() {
            String input = " ";
            String actual = ReflectionTestUtils.invokeMethod(globalExceptionHandler, "getPropertyName", input);
            assertThat(actual).isEqualTo(" ");
        }
    }
}
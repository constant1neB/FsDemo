package com.example.fsdemo.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global Exception Handler using @RestControllerAdvice.
 * Provides centralized exception handling across all @RequestMapping methods.
 * Uses RFC 7807 Problem Details for structured error responses.
 * Extends ResponseEntityExceptionHandler to leverage Spring's handling of common web exceptions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String TIMESTAMP_PROPERTY = "timestamp";
    private static final String ERRORS_PROPERTY = "errors"; // For validation errors

    // --- Custom Application Specific Exceptions ---

    @ExceptionHandler(VideoStorageException.class)
    public ProblemDetail handleVideoStorageException(VideoStorageException ex, WebRequest request) {
        log.error("Video storage operation failed: {}", ex.getMessage(), ex);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to process video storage operation. Please contact support if the problem persists."
        );
        problemDetail.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()); // Use standard phrase
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(FfmpegProcessingException.class)
    public ProblemDetail handleFfmpegProcessingException(FfmpegProcessingException ex, WebRequest request) {
        // Log the detailed error internally, including stderr if available
        if (ex.getStderrOutput() != null && !ex.getStderrOutput().isBlank()) {
            log.error("FFmpeg processing failed for request: {} - FFmpeg stderr:\n{}",
                    ex.getMessage(), ex.getStderrOutput(), ex);
        } else {
            log.error("FFmpeg processing failed for request: {}", ex.getMessage(), ex);
        }

        // Return a generic error to the client
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Video processing failed. Please check the video format or contact support if the problem persists."
        );
        problemDetail.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase()); // Use standard phrase
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Spring Security Exceptions ---

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Access Denied for request {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access Denied. You do not have sufficient permissions to access this resource."
        );
        problemDetail.setTitle(HttpStatus.FORBIDDEN.getReasonPhrase()); // Use standard phrase
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Authentication failure for request {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication failed. Please check your credentials or log in."
        );
        problemDetail.setTitle(HttpStatus.UNAUTHORIZED.getReasonPhrase()); // Use standard phrase
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Bean Validation Exceptions ---

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex, WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Constraint violation for request {}: {}",
                    request.getDescription(false), ex.getMessage());
        }

        Map<String, String> errors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> getPropertyName(violation.getPropertyPath().toString()),
                        ConstraintViolation::getMessage
                ));

        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase()); // Use standard phrase
        problemDetail.setDetail("Input validation failed. Check the 'errors' field for details.");
        problemDetail.setProperty(ERRORS_PROPERTY, errors);
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- General Spring Web Exceptions (Overrides from ResponseEntityExceptionHandler) ---

    // Override for @Valid on @RequestBody
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Method argument validation failed for request {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        // Use standard reason phrase if available, fallback otherwise
        problemDetail.setTitle(getReasonPhrase(status, "Validation Failed"));
        problemDetail.setDetail("Request body validation failed. Check the 'errors' field for details.");
        problemDetail.setProperty(ERRORS_PROPERTY, errors);
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            @NonNull HttpRequestMethodNotSupportedException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("HTTP method not supported for {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(getReasonPhrase(status, "Method Not Allowed")); // Use standard phrase
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        String[] supportedMethodsArray = ex.getSupportedMethods();
        if (supportedMethodsArray != null && supportedMethodsArray.length > 0) {
            try {
                Set<HttpMethod> allowedMethods = Arrays.stream(supportedMethodsArray)
                        .map(HttpMethod::valueOf)
                        .collect(Collectors.toSet());
                headers.setAllow(allowedMethods);
            } catch (IllegalArgumentException illegalArgEx) {
                log.error("Could not parse supported HTTP methods provided by exception: {}",
                        Arrays.toString(supportedMethodsArray), illegalArgEx);
            }
        }
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            @NonNull HttpMediaTypeNotSupportedException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("HTTP media type not supported for {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(getReasonPhrase(status, "Unsupported Media Type")); // Use standard phrase
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        if (!ex.getSupportedMediaTypes().isEmpty()) {
            headers.setAccept(ex.getSupportedMediaTypes());
        }

        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            @NonNull MissingServletRequestParameterException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        if (log.isWarnEnabled()) {
            log.warn("Missing request parameter for {}: {}",
                    request.getDescription(false), ex.getMessage());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle(getReasonPhrase(status, "Missing Request Parameter")); // Use standard phrase
        problemDetail.setDetail(ex.getMessage());
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex, WebRequest request) {
        if (log.isInfoEnabled()) {
            log.info("Handling ResponseStatusException for {}: Status={}, Reason={}",
                    request.getDescription(false), ex.getStatusCode(), ex.getReason());
        }
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        // CORRECTED: Use standard HTTP reason phrase for the title if possible
        problemDetail.setTitle(getReasonPhrase(ex.getStatusCode()));
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Generic Fallback Handler and Override for Internal Exceptions ---

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, WebRequest request) {
        // Log ALL unhandled exceptions with full stack trace for internal debugging
        if (log.isErrorEnabled()) {
            log.error("Unhandled exception caught by @ExceptionHandler(Exception.class) for request {}:",
                    request.getDescription(false), ex);
        }
        // Return a generic, non-revealing error message to the client
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please try again later or contact support."
        );
        problemDetail.setTitle(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    /**
     * Override handleExceptionInternal to customize handling for specific exceptions
     * caught by the base class, like MaxUploadSizeExceededException.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            @NonNull Exception ex, @Nullable Object body, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode statusCode, @NonNull WebRequest request) {

        if (ex instanceof MaxUploadSizeExceededException maxEx) {
            log.warn("Max upload size exceeded (handled internally): {}", maxEx.getMessage());
            ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Maximum upload size exceeded. " + maxEx.getLocalizedMessage()
            );
            // CORRECTED: HttpStatus.PAYLOAD_TOO_LARGE is an HttpStatus enum
            problemDetail.setTitle(HttpStatus.PAYLOAD_TOO_LARGE.getReasonPhrase());
            problemDetail.setInstance(URI.create(request.getDescription(false)));
            problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
            return new ResponseEntity<>(problemDetail, headers, HttpStatus.PAYLOAD_TOO_LARGE);
        }

        ProblemDetail problemDetailToReturn;

        if (body instanceof ProblemDetail pdBody) {
            problemDetailToReturn = pdBody;
            Map<String, Object> properties = problemDetailToReturn.getProperties();
            if (properties == null || !properties.containsKey(TIMESTAMP_PROPERTY)) {
                problemDetailToReturn.setProperty(TIMESTAMP_PROPERTY, Instant.now());
            }
            if (problemDetailToReturn.getInstance() == null) {
                problemDetailToReturn.setInstance(URI.create(request.getDescription(false)));
            }
            // Ensure title is set if missing, using standard phrase if possible
            if (problemDetailToReturn.getTitle() == null) {
                problemDetailToReturn.setTitle(getReasonPhrase(statusCode));
            }
        } else {
            // Create a new basic ProblemDetail
            log.warn("Creating basic ProblemDetail in handleExceptionInternal for exception type {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            problemDetailToReturn = ProblemDetail.forStatus(statusCode);
            // CORRECTED: Use standard reason phrase for title if possible
            problemDetailToReturn.setTitle(getReasonPhrase(statusCode));
            String detail = (ex.getCause() != null) ? ex.getCause().getMessage() : ex.getMessage();
            problemDetailToReturn.setDetail(detail);
            problemDetailToReturn.setInstance(URI.create(request.getDescription(false)));
            problemDetailToReturn.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        }

        return new ResponseEntity<>(problemDetailToReturn, headers, statusCode);
    }


    // --- Helper Methods ---

    private String getPropertyName(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "unknown";
        }
        int lastDot = propertyPath.lastIndexOf('.');
        int lastBracket = propertyPath.lastIndexOf('[');
        int lastSeparator = Math.max(lastDot, lastBracket);
        return (lastSeparator == -1) ? propertyPath : propertyPath.substring(lastSeparator + 1);
    }

    /**
     * Helper to safely get the standard reason phrase or a fallback.
     */
    private String getReasonPhrase(HttpStatusCode statusCode) {
        return getReasonPhrase(statusCode, "Status"); // Default fallback prefix
    }

    /**
     * Helper to safely get the standard reason phrase or a specific fallback title.
     */
    private String getReasonPhrase(HttpStatusCode statusCode, String fallbackTitle) {
        if (statusCode instanceof HttpStatus httpStatus) {
            return httpStatus.getReasonPhrase();
        } else {
            // Fallback for non-standard HttpStatusCode implementations
            return fallbackTitle;
        }
    }
}
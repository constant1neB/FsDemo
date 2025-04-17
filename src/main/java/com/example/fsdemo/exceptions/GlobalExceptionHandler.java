package com.example.fsdemo.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.lang.NonNull;
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
        // Log the full exception internally
        log.error("Video storage operation failed: {}", ex.getMessage(), ex);

        // Return a generic error to the client, avoid leaking storage details
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to process video storage operation. Please contact support if the problem persists."
        );
        problemDetail.setTitle("Video Storage Error");
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        // Could potentially map certain storage errors (e.g., invalid filename format passed)
        // to 400 Bad Request if distinguishable and safe. For now, 500 is safer.
        return problemDetail;
    }

    // Consider adding a specific VideoProcessingException if needed
    // @ExceptionHandler(VideoProcessingException.class)
    // public ProblemDetail handleVideoProcessingException(...) { ... }

    // --- Spring Security Exceptions ---

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDeniedException(AccessDeniedException ex, WebRequest request) {
        // Logged by Spring Security's filter chain usually, but can add specific logging here if needed
        log.warn("Access Denied for request {}: {}", request.getDescription(false), ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "Access Denied. You do not have sufficient permissions to access this resource."
        );
        problemDetail.setTitle("Forbidden");
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // NOTE: AuthenticationException is typically handled by the AuthenticationEntryPoint (AuthEntryPoint.java)
    // before it reaches the controller advice. However, adding a handler here can act as a fallback
    // or catch specific instances if they somehow bypass the entry point.
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthenticationException(AuthenticationException ex, WebRequest request) {
        log.warn("Authentication failure for request {}: {}", request.getDescription(false), ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED,
                "Authentication failed. Please check your credentials or log in."
        );
        problemDetail.setTitle("Unauthorized");
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Bean Validation Exceptions ---

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex, WebRequest request) {
        log.warn("Constraint violation for request {}: {}", request.getDescription(false), ex.getMessage());

        Map<String, String> errors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        violation -> getPropertyName(violation.getPropertyPath().toString()),
                        ConstraintViolation::getMessage
                ));

        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problemDetail.setTitle("Validation Failed");
        problemDetail.setDetail("Input validation failed. Check the 'errors' field for details.");
        problemDetail.setProperty(ERRORS_PROPERTY, errors);
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // Override handler for @Valid on @RequestBody
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            @NonNull WebRequest request) {
        log.warn("Method argument validation failed for request {}: {}", request.getDescription(false), ex.getMessage());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach((error) -> {
            String fieldName = (error instanceof FieldError) ? ((FieldError) error).getField() : error.getObjectName();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        ProblemDetail problemDetail = ProblemDetail.forStatus(status); // Use status provided (usually 400)
        problemDetail.setTitle("Validation Failed");
        problemDetail.setDetail("Request body validation failed. Check the 'errors' field for details.");
        problemDetail.setProperty(ERRORS_PROPERTY, errors);
        problemDetail.setInstance(URI.create(request.getDescription(false))); // Get request path
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        return new ResponseEntity<>(problemDetail, headers, status);
    }

    // --- General Spring Web Exceptions (Overrides from ResponseEntityExceptionHandler) ---

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            WebRequest request) {
        log.warn("HTTP method not supported for {}: {}", request.getDescription(false), ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle("Method Not Allowed");
        problemDetail.setDetail(ex.getMessage()); // Safe to expose supported/unsupported methods
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        // --- Correction Start ---
        String[] supportedMethodsArray = ex.getSupportedMethods();

        // Check if the array is not null and not empty
        if (supportedMethodsArray != null && supportedMethodsArray.length > 0) {
            try {
                // Convert String array to Set<HttpMethod>
                Set<HttpMethod> allowedMethods = Arrays.stream(supportedMethodsArray)
                        .map(HttpMethod::valueOf) // Convert String ("GET") to HttpMethod.GET enum
                        .collect(Collectors.toSet());

                // Set the Allow header with the correct type
                headers.setAllow(allowedMethods);
            } catch (IllegalArgumentException illegalArgEx) {
                // Log if an unexpected method string is encountered from the exception
                log.error("Could not parse supported HTTP methods provided by exception: {}", Arrays.toString(supportedMethodsArray), illegalArgEx);
                // Optionally decide not to set the Allow header if parsing fails
            }
        }

        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            WebRequest request) {
        log.warn("HTTP media type not supported for {}: {}", request.getDescription(false), ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle("Unsupported Media Type");
        problemDetail.setDetail(ex.getMessage()); // Safe to expose supported types
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());

        // Add Accept header if available
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            headers.setAccept(ex.getSupportedMediaTypes());
        }

        return new ResponseEntity<>(problemDetail, headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException ex,
            @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status,
            WebRequest request) {
        log.warn("Missing request parameter for {}: {}", request.getDescription(false), ex.getMessage());

        ProblemDetail problemDetail = ProblemDetail.forStatus(status);
        problemDetail.setTitle("Missing Request Parameter");
        problemDetail.setDetail(ex.getMessage()); // Parameter name is usually safe
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return new ResponseEntity<>(problemDetail, headers, status);
    }

    // --- File Upload Specific Exceptions ---

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex, WebRequest request) {
        log.warn("Upload size limit exceeded for {}: {}", request.getDescription(false), ex.getMessage());
        // Note: The actual limit might be exposed in ex.getMessage(), which is acceptable here.
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Maximum upload size exceeded. " + ex.getLocalizedMessage() // Provide detail from exception
        );
        problemDetail.setTitle("File Too Large");
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Handling ResponseStatusException ---

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail handleResponseStatusException(ResponseStatusException ex, WebRequest request) {
        // These are often thrown deliberately from controllers. Log as info or warn.
        log.info("Handling ResponseStatusException for {}: Status={}, Reason={}",
                request.getDescription(false), ex.getStatusCode(), ex.getReason());

        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(ex.getStatusCode(), ex.getReason());
        problemDetail.setTitle(ex.getStatusCode().isError() ? "Request Error" : "Request Information"); // Generic title
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }


    // --- Generic Fallback Handler ---

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, WebRequest request) {
        // Log ALL unhandled exceptions with full stack trace for internal debugging
        log.error("Unhandled exception occurred processing request {}:", request.getDescription(false), ex);

        // Return a generic, non-revealing error message to the client
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected internal error occurred. Please try again later or contact support."
        );
        problemDetail.setTitle("Internal Server Error");
        problemDetail.setInstance(URI.create(request.getDescription(false)));
        problemDetail.setProperty(TIMESTAMP_PROPERTY, Instant.now());
        return problemDetail;
    }

    // --- Helper Methods ---

    /**
     * Extracts the property name from a ConstraintViolation path.
     * Handles nested properties if necessary.
     */
    private String getPropertyName(String propertyPath) {
        if (propertyPath == null || propertyPath.isEmpty()) {
            return "unknown";
        }
        // Example: "field", "object.field", "list[0].field"
        // Return the part after the last dot or bracket, if any
        int lastDot = propertyPath.lastIndexOf('.');
        int lastBracket = propertyPath.lastIndexOf('[');
        int lastSeparator = Math.max(lastDot, lastBracket);
        return (lastSeparator == -1) ? propertyPath : propertyPath.substring(lastSeparator + 1);
    }
}
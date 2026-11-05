package com.germanreader.exception;

import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public record FieldError(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return problem(ErrorCode.VALIDATION_ERROR, errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().getLast().getPropertyName();
            return problem(ErrorCode.VALIDATION_ERROR, List.of(new FieldError(field, describe(mismatch))));
        }
        return problem(ErrorCode.MALFORMED_REQUEST, null);
    }

    @ExceptionHandler(TranslationException.class)
    ResponseEntity<Object> handleTranslationException(TranslationException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.status().is5xxServerError()) {
            log.warn("Translation failed with {}: {}", code, ex.getMessage(), ex);
        } else {
            log.debug("Translation rejected with {}: {}", code, ex.getMessage());
        }
        return problem(code, null);
    }

    @ExceptionHandler(DocumentException.class)
    ResponseEntity<Object> handleDocumentException(DocumentException ex) {
        // The message holds ids and reasons only, never document text.
        log.debug("Document request rejected with {}: {}", ex.getErrorCode(), ex.getMessage());
        return problem(ex.getErrorCode(), null);
    }

    /** Thrown while the multipart request is parsed, before the controller runs. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        return problem(ErrorCode.UPLOAD_TOO_LARGE, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected error while handling request", ex);
        return problem(ErrorCode.INTERNAL_ERROR, null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {

        // The base class creates the ProblemDetail body for framework exceptions inside this call,
        // so the code can only be added to the response it returns.
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ErrorCode code = statusCode.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.REQUEST_ERROR;
            problem.setProperty("code", code.name());
        }
        return response;
    }

    private static ResponseEntity<Object> problem(ErrorCode code, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.message());
        problem.setProperty("code", code.name());
        if (errors != null) {
            problem.setProperty("errors", errors);
        }
        return ResponseEntity.status(code.status()).body(problem);
    }

    private static String describe(MismatchedInputException ex) {
        Class<?> target = ex.getTargetType();
        if (ex instanceof InvalidFormatException && target != null && target.isEnum()) {
            return "must be one of " + Arrays.toString(target.getEnumConstants());
        }
        return "has an invalid format";
    }
}

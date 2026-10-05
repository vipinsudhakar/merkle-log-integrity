package com.merklelog.api;

import com.merklelog.core.EmptyForestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;

/**
 * Maps engine and validation exceptions to HTTP responses in one place (RFC 9457 problem
 * details), so controllers stay free of try/catch.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Unknown dataset or anchor, or a proof requested from an empty forest. */
    @ExceptionHandler({NoSuchElementException.class, EmptyForestException.class})
    public ProblemDetail notFound(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** Bad strategy name or parameter, out-of-range index, malformed request. */
    @ExceptionHandler({IllegalArgumentException.class, IndexOutOfBoundsException.class})
    public ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    }
}

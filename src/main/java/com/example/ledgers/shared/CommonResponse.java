package com.example.ledgers.shared;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Envelope for every API response.
 * {@code code} is the outcome (HTTP status name, e.g. CREATED); on failure {@code error.code} is the stable reason
 * (e.g. ERR_UNBALANCED) clients should branch on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommonResponse<T>(boolean success, String code, String message, T data, Error error) {

    public record Error(String code, String message) {
    }

    public static <T> CommonResponse<T> of(HttpStatus status, String message, T data) {
        return new CommonResponse<>(true, status.name(), message, data, null);
    }

    public static <T> CommonResponse<T> ok(String message, T data) {
        return of(HttpStatus.OK, message, data);
    }

    public static <T> CommonResponse<T> created(String message, T data) {
        return of(HttpStatus.CREATED, message, data);
    }

    public static CommonResponse<Void> failure(HttpStatusCode status, String errorCode, String message) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String code = resolved != null ? resolved.name() : String.valueOf(status.value());
        return new CommonResponse<>(false, code, message, null, new Error(errorCode, message));
    }
}

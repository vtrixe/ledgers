package com.example.ledgers.shared;

import jakarta.persistence.PersistenceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Maps every failure to {@link CommonResponse}.
 * Ledger invariants are enforced by Postgres and raised as {@code LEDGER_<REASON>: <message>}; those become
 * {@code ERR_<REASON>} with 422. Errors raised at COMMIT (deferred triggers) arrive wrapped in a
 * {@link TransactionException}, so both paths unwrap to the underlying {@link SQLException}.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final Pattern LEDGER_ERROR = Pattern.compile("LEDGER_([A-Z_]+): (.*)");
    private static final Pattern CONSTRAINT = Pattern.compile("constraint \"([a-z0-9_]+)\"");

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<CommonResponse<Void>> handleApi(ApiException ex) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CommonResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return respond(HttpStatus.BAD_REQUEST, "ERR_VALIDATION", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CommonResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, "ERR_MALFORMED_REQUEST",
                "Request body is missing or malformed (timestamps need an explicit offset, e.g. 2026-09-14T19:02:11+05:30)");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<CommonResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(HttpStatus.BAD_REQUEST, "ERR_MISSING_HEADER", "Missing header " + ex.getHeaderName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CommonResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, "ERR_INVALID_PARAMETER", "Invalid value for " + ex.getName());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<CommonResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, "ERR_NOT_FOUND", "No such endpoint");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class, PersistenceException.class})
    public ResponseEntity<CommonResponse<Void>> handleDatabase(Exception ex) {
        SQLException sql = findSqlException(ex);
        if (sql == null) {
            return handleGeneral(ex);
        }

        String message = serverMessage(sql);
        Matcher ledger = LEDGER_ERROR.matcher(message);
        if (ledger.find()) {
            return respond(HttpStatus.valueOf(422), "ERR_" + ledger.group(1), ledger.group(2));
        }

        String state = sql.getSQLState() == null ? "" : sql.getSQLState();
        return switch (state) {
            case "23505" -> respond(HttpStatus.CONFLICT, "ERR_DUPLICATE", "Already exists (" + constraint(message) + ")");
            case "23503" -> respond(HttpStatus.valueOf(422), "ERR_REFERENCE", "Unknown reference (" + constraint(message) + ")");
            case "23514", "23502" -> respond(HttpStatus.valueOf(422), "ERR_CONSTRAINT", "Rejected by " + constraint(message));
            default -> handleGeneral(ex);
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommonResponse<Void>> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "ERR_INTERNAL", "An unexpected error occurred");
    }

    private static ResponseEntity<CommonResponse<Void>> respond(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(CommonResponse.failure(status, code, message));
    }

    private static SQLException findSqlException(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    // First line of the Postgres message, without "ERROR: " and the PL/pgSQL context.
    private static String serverMessage(SQLException sql) {
        String message = sql.getMessage() == null ? "" : sql.getMessage();
        int newline = message.indexOf('\n');
        if (newline >= 0) {
            message = message.substring(0, newline);
        }
        return message.startsWith("ERROR: ") ? message.substring("ERROR: ".length()) : message;
    }

    private static String constraint(String message) {
        Matcher matcher = CONSTRAINT.matcher(message);
        return matcher.find() ? matcher.group(1) : "database constraint";
    }
}

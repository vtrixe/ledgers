package com.example.ledgers.accounts.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Only non-null fields change. A rename cannot change the first segment (the account type). */
@Data
@NoArgsConstructor
public class UpdateAccountRequest {

    @Size(max = 255)
    @Pattern(regexp = CreateAccountRequest.PATH_REGEX, message = CreateAccountRequest.PATH_MESSAGE)
    private String path;

    private Boolean allowNegative;

    private Map<String, Object> metadata;
}

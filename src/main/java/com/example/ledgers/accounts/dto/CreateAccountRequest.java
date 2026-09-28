package com.example.ledgers.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
public class CreateAccountRequest {

    /** Same rule as the accounts_path_check constraint; checked here only to give a friendlier error. */
    public static final String PATH_REGEX = "(assets|liabilities|equity|revenue|expenses)(:[a-z0-9_]+)+";
    public static final String PATH_MESSAGE =
            "must be lowercase segments separated by ':', starting with assets|liabilities|equity|revenue|expenses";

    @NotBlank
    @Size(max = 255)
    @Pattern(regexp = PATH_REGEX, message = PATH_MESSAGE)
    private String path;

    private boolean allowNegative;

    private Map<String, Object> metadata;
}

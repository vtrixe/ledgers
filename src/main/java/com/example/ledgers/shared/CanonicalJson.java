package com.example.ledgers.shared;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 of an object's canonical JSON (sorted keys at every level), for request fingerprints. */
@UtilityClass
public class CanonicalJson {

    private final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @SneakyThrows
    public String sha256Hex(Object value) {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(MAPPER.writeValueAsBytes(value)));
    }
}

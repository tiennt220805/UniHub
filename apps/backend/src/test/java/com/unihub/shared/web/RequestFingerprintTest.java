package com.unihub.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String of(String json) throws Exception {
        return RequestFingerprint.of(JSON, JSON.readTree(json));
    }

    @Test
    @DisplayName("is 64 lowercase hex characters (idempotency_keys.request_fingerprint)")
    void isLowercaseHexSha256() throws Exception {
        assertThat(of("{\"a\":1}")).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("key order and whitespace do not change the fingerprint")
    void keyOrderAndWhitespace_areIgnored() throws Exception {
        assertThat(of("{\"workshopId\":\"w1\",\"seats\":2}"))
                .isEqualTo(of("{ \"seats\" : 2,\n  \"workshopId\" : \"w1\" }"));
    }

    @Test
    @DisplayName("key order is ignored at every depth, including inside array elements")
    void nestedKeyOrder_isIgnored() throws Exception {
        assertThat(of("{\"a\":{\"x\":1,\"y\":[{\"p\":1,\"q\":2}]}}"))
                .isEqualTo(of("{\"a\":{\"y\":[{\"q\":2,\"p\":1}],\"x\":1}}"));
    }

    @Test
    @DisplayName("changing a value changes the fingerprint")
    void changedValue_changesFingerprint() throws Exception {
        assertThat(of("{\"seats\":2}")).isNotEqualTo(of("{\"seats\":3}"));
        assertThat(of("{\"a\":{\"b\":\"x\"}}")).isNotEqualTo(of("{\"a\":{\"b\":\"y\"}}"));
    }

    @Test
    @DisplayName("changing a key, a type or an array's order changes the fingerprint")
    void changedShape_changesFingerprint() throws Exception {
        assertThat(of("{\"a\":1}")).isNotEqualTo(of("{\"b\":1}"));
        assertThat(of("{\"a\":1}")).isNotEqualTo(of("{\"a\":\"1\"}"));
        assertThat(of("{\"a\":[1,2]}")).isNotEqualTo(of("{\"a\":[2,1]}"));
        assertThat(of("{\"a\":null}")).isNotEqualTo(of("{}"));
    }

    @Test
    @DisplayName("a string that looks like JSON structure cannot collide with real structure")
    void embeddedStructure_isEscaped() throws Exception {
        assertThat(of("{\"a\":\"b\\\",\\\"c\\\":\\\"d\"}"))
                .isNotEqualTo(of("{\"a\":\"b\",\"c\":\"d\"}"));
    }

    @Test
    @DisplayName("a DTO and the equivalent JSON tree have the same fingerprint")
    void dto_matchesTree() throws Exception {
        record Body(String workshopId, int seats) {}
        JsonNode tree = JSON.readTree("{\"seats\":2,\"workshopId\":\"w1\"}");

        assertThat(RequestFingerprint.of(JSON, new Body("w1", 2)))
                .isEqualTo(RequestFingerprint.of(JSON, tree));
    }
}

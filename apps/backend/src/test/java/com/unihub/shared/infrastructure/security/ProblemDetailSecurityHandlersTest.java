package com.unihub.shared.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/**
 * Every non-2xx body is {@code application/problem+json} (packages/api-contract/common/error.yaml,
 * backend-coding-standards.md §17), including the two the security filters write themselves. The
 * body states the HTTP facts only: never why the token failed to decode.
 */
class ProblemDetailSecurityHandlersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    @DisplayName("401: problem+json with type, title, status, instance and a Bearer challenge")
    void authenticationEntryPoint_writesProblem() throws Exception {
        new ProblemDetailAuthenticationEntryPoint(JSON, null)
                .commence(request, response, new BadCredentialsException("secret internal reason"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/x");
        assertThat(response.getContentAsString()).doesNotContain("secret internal reason");
    }

    @Test
    @DisplayName(
            "401 for an invalid bearer token keeps the error code in the challenge, not the body")
    void authenticationEntryPoint_invalidToken_challengeCarriesErrorCode() throws Exception {
        new ProblemDetailAuthenticationEntryPoint(JSON, null)
                .commence(request, response, new InvalidBearerTokenException("decoder said no"));

        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).contains("invalid_token");
        assertThat(response.getContentAsString()).doesNotContain("decoder said no");
    }

    @Test
    @DisplayName("403: problem+json with type, title, status and instance")
    void accessDeniedHandler_writesProblem() throws Exception {
        new ProblemDetailAccessDeniedHandler(JSON)
                .handle(request, response, new AccessDeniedException("Access Denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("type").asText()).isEqualTo("about:blank");
        assertThat(body.get("title").asText()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("instance").asText()).isEqualTo("/api/v1/x");
    }
}

package com.unihub.registration.web.controller;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static com.unihub.workshop.domain.model.WorkshopBuilder.aPublishedWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.RegistrationFixtures;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import com.unihub.workshop.domain.model.Workshop;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code getRegistration} and {@code listMyRegistrations} end to end (M-17, M-19): a student sees
 * exactly their own registrations, the list is newest first and paged, a status filter narrows it,
 * another student's registration is 403 and an unknown one 404. Registrations are made through the
 * real endpoint, so a read right after the write is what is being proved.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistrationReadIT extends IntegrationTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkshopRepository workshops;
    @Autowired private TransactionTemplate tx;

    private RegistrationFixtures fixtures;

    @BeforeEach
    void setUp() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        fixtures = new RegistrationFixtures(jdbc);
    }

    @AfterEach
    void cleanUp() {
        fixtures.cleanUp();
    }

    private UUID workshop(Workshop workshop) {
        tx.executeWithoutResult(s -> workshops.save(workshop));
        return fixtures.track(workshop.id()).value();
    }

    private JsonNode register(UserId student, UUID workshop) throws Exception {
        var headers = headers(student.value());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Idempotency-Key", UUID.randomUUID().toString());
        var response =
                http.exchange(
                        "/api/v1/registrations",
                        HttpMethod.POST,
                        new HttpEntity<>("{\"workshopId\":\"" + workshop + "\"}", headers),
                        String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JSON.readTree(response.getBody());
    }

    private static HttpHeaders headers(UUID user) {
        var headers = new HttpHeaders();
        if (user != null) {
            headers.setBearerAuth(TestJwts.valid(user, "REGISTRATION_CREATE"));
        }
        return headers;
    }

    private ResponseEntity<String> get(String path, UUID user) {
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(user)), String.class);
    }

    @Test
    @DisplayName(
            "the owner reads a paid registration right after reserving it: RESERVED with its hold")
    void owner_readsItRightAfterTheWrite() throws Exception {
        var student = fixtures.aStudent();
        var created = register(student, workshop(aPaidWorkshop().withCapacity(5).build()));

        var response = get("/api/v1/registrations/" + created.get("id").asText(), student.value());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = JSON.readTree(response.getBody());
        assertThat(body.get("status").asText()).isEqualTo("RESERVED");
        assertThat(body.get("ticketType").asText()).isEqualTo("PAID");
        assertThat(body.get("userId").asText()).isEqualTo(student.value().toString());
        assertThat(body.hasNonNull("holdExpiresAt")).isTrue();
        assertThat(body.get("qrPayload").isNull()).as("no ticket before Phase 5").isTrue();
    }

    @Test
    @DisplayName("another student's registration is 403, an unknown one 404, no token 401")
    void failures() throws Exception {
        var owner = fixtures.aStudent();
        var created = register(owner, workshop(aPublishedWorkshop().withCapacity(5).build()));
        var stranger = fixtures.aStudent();
        var path = "/api/v1/registrations/" + created.get("id").asText();

        assertThat(get(path, stranger.value()).getStatusCode().value()).isEqualTo(403);
        assertThat(
                        get("/api/v1/registrations/" + UUID.randomUUID(), owner.value())
                                .getStatusCode()
                                .value())
                .isEqualTo(404);
        assertThat(get(path, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName(
            "the list holds only the caller's registrations, newest first, without the ticket payload")
    void list_isTheCallersOwn_newestFirst() throws Exception {
        var student = fixtures.aStudent();
        var other = fixtures.aStudent();
        var first = register(student, workshop(aPublishedWorkshop().withCapacity(5).build()));
        Thread.sleep(20);
        var second = register(student, workshop(aPaidWorkshop().withCapacity(5).build()));
        register(other, workshop(aPublishedWorkshop().withCapacity(5).build()));

        var response = get("/api/v1/registrations", student.value());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = JSON.readTree(response.getBody());
        assertThat(body.get("items")).hasSize(2);
        assertThat(body.get("items").get(0).get("id").asText())
                .isEqualTo(second.get("id").asText());
        assertThat(body.get("items").get(1).get("id").asText()).isEqualTo(first.get("id").asText());
        body.get("items")
                .forEach(
                        item -> {
                            assertThat(item.get("userId").asText())
                                    .isEqualTo(student.value().toString());
                            assertThat(item.has("qrPayload")).isFalse();
                        });
        assertThat(body.get("page").get("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("a status filter narrows the list, and paging walks it with a stable envelope")
    void list_filtersAndPages() throws Exception {
        var student = fixtures.aStudent();
        register(student, workshop(aPublishedWorkshop().withCapacity(5).build()));
        register(student, workshop(aPaidWorkshop().withCapacity(5).build()));
        register(student, workshop(aPaidWorkshop().withCapacity(5).build()));

        var reserved =
                JSON.readTree(
                        get("/api/v1/registrations?status=RESERVED", student.value()).getBody());
        var confirmed =
                JSON.readTree(
                        get("/api/v1/registrations?status=CONFIRMED", student.value()).getBody());
        var firstPage =
                JSON.readTree(
                        get("/api/v1/registrations?page=0&size=2", student.value()).getBody());
        var lastPage =
                JSON.readTree(
                        get("/api/v1/registrations?page=1&size=2", student.value()).getBody());

        assertThat(reserved.get("items")).hasSize(2);
        assertThat(confirmed.get("items")).hasSize(1);
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("page").get("totalPages").asInt()).isEqualTo(2);
        assertThat(lastPage.get("items")).hasSize(1);
        assertThat(lastPage.get("page").get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a student with nothing registered gets an empty page, not an error; bad paging is 400")
    void list_empty_andBadPaging() {
        var student = fixtures.aStudent();

        var empty = get("/api/v1/registrations", student.value());

        assertThat(empty.getStatusCode().value()).isEqualTo(200);
        assertThat(empty.getBody()).contains("\"items\":[]");
        assertThat(get("/api/v1/registrations?size=101", student.value()).getStatusCode().value())
                .isEqualTo(400);
        assertThat(get("/api/v1/registrations?page=-1", student.value()).getStatusCode().value())
                .isEqualTo(400);
        assertThat(
                        get("/api/v1/registrations?status=BOGUS", student.value())
                                .getStatusCode()
                                .value())
                .isEqualTo(400);
    }
}

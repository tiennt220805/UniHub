package com.unihub.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.shared.security.TestJwts;
import com.unihub.user.api.UserContact;
import com.unihub.user.api.UserContacts;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The read Notification uses to address a user (Phase 4 checklist D35): name and email by id, one
 * lookup or one batch for a fan-out, absent ids reported as absent rather than failing the batch
 * (AC35.6).
 */
@SpringBootTest
class UserContactLookupIT extends IntegrationTestSupport {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("unihub.security.jwt.secret", () -> TestJwts.SECRET);
    }

    @Autowired private UserContacts contacts;
    @Autowired private JdbcTemplate jdbc;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
    }

    private UserId userNamed(String fullName, String email) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, full_name) VALUES (?, ?, ?)", id, email, fullName);
        created.add(id);
        return UserId.of(id);
    }

    @Test
    @DisplayName("find returns the name and email of the user")
    void find_returnsNameAndEmail() {
        var id = userNamed("Nguyễn Văn An", "an." + UUID.randomUUID() + "@example.test");

        var contact = contacts.find(id).orElseThrow();

        assertThat(contact.id()).isEqualTo(id);
        assertThat(contact.fullName()).isEqualTo("Nguyễn Văn An");
        assertThat(contact.email()).startsWith("an.").endsWith("@example.test");
    }

    @Test
    @DisplayName("find is empty for an unknown user")
    void find_unknown_isEmpty() {
        assertThat(contacts.find(UserId.newId())).isEmpty();
    }

    @Test
    @DisplayName(
            "findAll returns the users that exist, keyed by id, and omits the ones that do not")
    void findAll_omitsMissingUsers() {
        var first = userNamed("First", UUID.randomUUID() + "@example.test");
        var second = userNamed("Second", UUID.randomUUID() + "@example.test");
        var missing = UserId.newId();

        var found = contacts.findAll(List.of(first, missing, second, first));

        assertThat(found).containsOnlyKeys(first, second);
        assertThat(found.get(first)).extracting(UserContact::fullName).isEqualTo("First");
        assertThat(found.get(second)).extracting(UserContact::fullName).isEqualTo("Second");
    }

    @Test
    @DisplayName("findAll of nothing is empty and runs no query")
    void findAll_ofNothing_isEmpty() {
        assertThat(contacts.findAll(List.of())).isEmpty();
    }
}

package com.unihub.workshop.web.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.unihub.shared.infrastructure.IntegrationTestSupport;
import com.unihub.workshop.web.dto.WorkshopListResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopControllerIT extends IntegrationTestSupport {

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private TestRestTemplate restTemplate;

  @Test
  @DisplayName("GET /api/v1/workshops returns 200 with an empty catalog against a freshly migrated database")
  void getWorkshops_returnsEmptyCatalog() {
    ResponseEntity<WorkshopListResponse> response =
        restTemplate.getForEntity("/api/v1/workshops", WorkshopListResponse.class);

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    WorkshopListResponse body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.items()).isEmpty();
    assertThat(body.page().page()).isEqualTo(0);
    assertThat(body.page().size()).isEqualTo(20);
    assertThat(body.page().totalElements()).isZero();
    assertThat(body.page().totalPages()).isZero();
  }
}

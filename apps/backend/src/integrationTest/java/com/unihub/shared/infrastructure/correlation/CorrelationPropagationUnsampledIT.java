package com.unihub.shared.infrastructure.correlation;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link CorrelationPropagationITBase} with sampling 0.0 (Phase 6 checklist D66, 3.4, AC66.1): no
 * trace is recorded, and the header, the MDC, the outbox column and the message header must all
 * still carry a 32-hex id. If the OpenTelemetry bridge gave an unsampled span no usable trace id,
 * these tests fail and the work stops for a decision; it does not fall back to a UUID.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class CorrelationPropagationUnsampledIT extends CorrelationPropagationITBase {

    @DynamicPropertySource
    static void sampling(DynamicPropertyRegistry registry) {
        registry.add("management.tracing.sampling.probability", () -> "0.0");
    }
}

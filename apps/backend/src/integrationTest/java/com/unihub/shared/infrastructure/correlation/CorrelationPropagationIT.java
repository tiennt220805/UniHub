package com.unihub.shared.infrastructure.correlation;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link CorrelationPropagationITBase} with every trace recorded (probability 1.0). */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
class CorrelationPropagationIT extends CorrelationPropagationITBase {

    @DynamicPropertySource
    static void sampling(DynamicPropertyRegistry registry) {
        registry.add("management.tracing.sampling.probability", () -> "1.0");
    }
}

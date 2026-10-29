package com.unihub.checkin.archfixture;

import org.springframework.amqp.rabbit.annotation.RabbitListener;

/**
 * A listener on a queue nobody wrote a redelivery test for: what {@code
 * ConsumerRedeliveryCoverageTest} must turn red on (Phase 4 D41, AC41.2). A plain class, never a
 * bean: test sources under {@code com.unihub} are component-scanned into every Spring context, and
 * Spring only registers a {@code @RabbitListener} found on a bean.
 */
public final class UncoveredConsumer {

    @RabbitListener(queues = "unihub.fixture.uncovered.queue")
    void onMessage(String body) {
        // never called
    }
}

package com.unihub.registration;

import static com.unihub.workshop.domain.model.WorkshopBuilder.aPaidWorkshop;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.registration.application.command.ReserveSeatCommand;
import com.unihub.registration.application.result.ReserveSeatResult;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.shared.infrastructure.out.messaging.Topology;
import com.unihub.shared.security.Actor;
import com.unihub.shared.web.RequestFingerprint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Queue;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * What the ITs that run the real lifecycle of a seat hold on the real broker share (checklist C2,
 * D19): a RabbitMQ of their own whose delay queue and retry chain are seconds long instead of 15
 * minutes and 5m35s. Queue arguments cannot change once a queue exists, so the shortened topology
 * cannot be declared on the broker the other classes share; and the application declares it from
 * the very properties it reads in production ({@code unihub.registration.seat-hold}, {@code
 * unihub.messaging.retry-delays}), so the one source of the hold length is what is under test.
 *
 * <p>The relay stays off, so the test is the relay (see {@link HandOffITSupport}): it publishes the
 * outbox row it reads. Subclasses close their context afterwards ({@code @DirtiesContext}), because
 * the listeners run.
 */
public abstract class ShortChainITSupport extends HandOffITSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Long enough to charge inside it, short enough to wait for. */
    protected static final Duration HOLD = Duration.ofSeconds(4);

    protected static final String HOLD_RESERVED = Topology.HOLD_RESERVED;
    protected static final String HOLD_EXPIRED = Topology.HOLD_EXPIRED;

    private static final RabbitMQContainer SHORT_BROKER =
            new RabbitMQContainer("rabbitmq:3-management");

    static {
        SHORT_BROKER.start();
    }

    @DynamicPropertySource
    static void shortChainProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", SHORT_BROKER::getHost);
        registry.add("spring.rabbitmq.port", SHORT_BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", SHORT_BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", SHORT_BROKER::getAdminPassword);
        registry.add("unihub.registration.seat-hold", HOLD::toString);
        registry.add("unihub.messaging.retry-delays", () -> "PT0.3S,PT0.3S,PT0.3S");
    }

    @BeforeEach
    void emptyTheShortBroker() {
        // Declared by the application on first use; purging a queue it has not declared yet fails.
        rabbit.execute(
                channel -> {
                    channel.queueDeclarePassive(Topology.PARKING_LOT);
                    return null;
                });
        Topology.declarables()
                .getDeclarablesByType(Queue.class)
                .forEach(q -> amqpAdmin.purgeQueue(q.getName()));
    }

    private final List<WorkshopId> created = new ArrayList<>();

    /** The hold jobs these tests leave pending would be the next relay's to publish. */
    @AfterEach
    void forgetTheHoldJobs() {
        created.forEach(this::forgetOutbox);
    }

    /** A published paid workshop with this capacity, tracked for clean-up. */
    protected WorkshopId aWorkshop(int capacity) {
        var workshop = aPaidWorkshop().withCapacity(capacity).build();
        tx.executeWithoutResult(s -> workshops.save(workshop));
        created.add(workshop.id());
        return fixtures.track(workshop.id());
    }

    /** A new student holds a seat of the workshop; the hold lasts {@link #HOLD}. */
    protected ReserveSeatResult.Reserved reserveFor(UserId student, WorkshopId workshop) {
        var result =
                reserveSeat.reserve(
                        new ReserveSeatCommand(
                                new Actor.UserActor(student, Set.of()),
                                workshop,
                                UUID.randomUUID().toString(),
                                RequestFingerprint.of(
                                        JSON, Map.of("workshopId", workshop.value()))));
        assertThat(result).isInstanceOf(ReserveSeatResult.Reserved.class);
        return (ReserveSeatResult.Reserved) result;
    }

    protected ReserveSeatResult.Reserved reserveForNewStudent(WorkshopId workshop) {
        return reserveFor(fixtures.aStudent(), workshop);
    }

    /** What the relay would publish for a hold: the job body, to the delay queue. */
    protected void relayHold(UUID registration) {
        var payloads = outboxPayloads(registration, HOLD_RESERVED);
        assertThat(payloads).as("hold.reserved outbox rows").hasSize(1);
        publish(HOLD_RESERVED, payloads.getFirst());
    }

    /**
     * What the relay would publish: every outbox row of this aggregate with this routing key goes
     * to the exchange once, in the order it was written. Returns how many there were.
     */
    protected int relay(String routingKey, UUID aggregate) {
        var payloads = outboxPayloads(aggregate, routingKey);
        payloads.forEach(payload -> publish(routingKey, payload));
        return payloads.size();
    }

    /** The same job body, as the broker delivers it once the delay queue has expired it. */
    protected void deliverHoldExpired(UUID registration) {
        var payloads = outboxPayloads(registration, HOLD_RESERVED);
        assertThat(payloads).as("hold.reserved outbox rows").hasSize(1);
        publish(HOLD_EXPIRED, payloads.getFirst());
    }

    /** Registrations of the workshop that hold a seat: what {@code seats_taken} must equal. */
    protected int seatHolders(WorkshopId workshop) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM registrations WHERE workshop_id = ? AND status IN"
                        + " ('RESERVED', 'CONFIRMED')",
                Integer.class,
                workshop.value());
    }

    /** INV-06 for the first code that gives seats back: the counter is exactly the holders. */
    protected void assertSeatsMatchHolders(WorkshopId workshop) {
        assertThat(fixtures.seatsTaken(workshop))
                .as("seats_taken equals the RESERVED + CONFIRMED registrations")
                .isEqualTo(seatHolders(workshop));
    }

    protected int parkedMessages() {
        return amqpAdmin.getQueueInfo(Topology.PARKING_LOT).getMessageCount();
    }

    protected int waiting(String queue) {
        return amqpAdmin.getQueueInfo(queue).getMessageCount();
    }

    private void forgetOutbox(WorkshopId workshop) {
        jdbc.update(
                "DELETE FROM event_publications WHERE aggregate_type = 'REGISTRATION' AND"
                        + " payload ->> 'workshopId' = ?",
                workshop.value().toString());
    }
}

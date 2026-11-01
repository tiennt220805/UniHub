package com.unihub.checkin.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.unihub.shared.domain.id.WorkshopId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The decision table and the ordering of earliest-timestamp-wins (checkin.md §7; Phase 5 checklist
 * D51, D52; test-design-techniques.md §2). Pure logic, so every arrival order can be tried here for
 * free; the integration tests only have to prove the database wiring.
 */
class ReconciliationPolicyTest {

    private static final Instant T0 = Instant.parse("2026-12-01T09:00:00Z");

    private static final WorkshopId TICKET_WORKSHOP = WorkshopId.newId();

    // ---------- validity (D51) ----------

    @Test
    @DisplayName("a verified scan of a CONFIRMED ticket, in its own room, is valid")
    void confirmedTicketInItsOwnRoom_isValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.VERIFIED,
                                TICKET_WORKSHOP,
                                Optional.of(standing(true, TICKET_WORKSHOP, false))))
                .isTrue();
    }

    @Test
    @DisplayName("a scan the device rejected is never valid, whatever the ticket looks like")
    void rejectedSignature_isNeverValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.REJECTED_SIGNATURE,
                                TICKET_WORKSHOP,
                                Optional.of(standing(true, TICKET_WORKSHOP, false))))
                .isFalse();
    }

    @Test
    @DisplayName("a code that resolves to no registration is not valid")
    void unresolvedCode_isNotValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.VERIFIED, TICKET_WORKSHOP, Optional.empty()))
                .isFalse();
    }

    @Test
    @DisplayName("a registration that is not CONFIRMED (reserved, expired, cancelled) is not valid")
    void unconfirmedRegistration_isNotValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.VERIFIED,
                                TICKET_WORKSHOP,
                                Optional.of(standing(false, TICKET_WORKSHOP, false))))
                .isFalse();
    }

    @Test
    @DisplayName("a ticket of a cancelled workshop is not valid even though it is CONFIRMED")
    void cancelledWorkshop_isNotValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.VERIFIED,
                                TICKET_WORKSHOP,
                                Optional.of(standing(true, TICKET_WORKSHOP, true))))
                .isFalse();
    }

    @Test
    @DisplayName("a valid ticket scanned in another workshop's room is not valid")
    void wrongRoom_isNotValid() {
        assertThat(
                        ReconciliationPolicy.isValid(
                                DeviceVerdict.VERIFIED,
                                WorkshopId.newId(),
                                Optional.of(standing(true, TICKET_WORKSHOP, false))))
                .isFalse();
    }

    // ---------- the order (D52) ----------

    @Test
    @DisplayName("the earlier scanned_at wins, whichever scan is listed first")
    void earlierScannedAt_wins() {
        var early = scan(uuid(0x2000_0000_0000_0000L, 1), T0);
        var late = scan(uuid(0x1000_0000_0000_0000L, 1), T0.plusSeconds(5));

        assertThat(ReconciliationPolicy.resolve(List.of(late, early)).winner())
                .isEqualTo(early.id());
        assertThat(ReconciliationPolicy.resolve(List.of(early, late)).winner())
                .isEqualTo(early.id());
    }

    @Test
    @DisplayName("a tie on scanned_at goes to the smaller id, not to the first arrival")
    void tie_goesToTheSmallerId() {
        var smaller = scan(uuid(0x1000_0000_0000_0000L, 0), T0);
        var larger = scan(uuid(0x2000_0000_0000_0000L, 0), T0);

        assertThat(ReconciliationPolicy.resolve(List.of(larger, smaller)).winner())
                .isEqualTo(smaller.id());
        assertThat(ReconciliationPolicy.resolve(List.of(smaller, larger)).winner())
                .isEqualTo(smaller.id());
    }

    @Test
    @DisplayName(
            "ids compare as unsigned bytes, the way Postgres orders uuid: 7fff… comes before 8000…")
    void ids_compareAsUnsignedBytes() {
        // java.util.UUID.compareTo reads the halves as signed longs and would put 8000… first.
        var low = scan(UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff"), T0);
        var high = scan(UUID.fromString("80000000-0000-0000-0000-000000000000"), T0);

        assertThat(ReconciliationPolicy.resolve(List.of(high, low)).winner()).isEqualTo(low.id());
        assertThat(ReconciliationPolicy.resolve(List.of(low, high)).winner()).isEqualTo(low.id());
    }

    @Test
    @DisplayName("when the high halves are equal the low halves decide, also unsigned")
    void ids_lowHalfDecidesUnsigned() {
        var low = scan(new UUID(5L, 0x7fff_ffff_ffff_ffffL), T0);
        var high = scan(new UUID(5L, 0x8000_0000_0000_0000L), T0);

        assertThat(ReconciliationPolicy.resolve(List.of(high, low)).winner()).isEqualTo(low.id());
    }

    @Test
    @DisplayName("scanned_at is compared at microseconds, which is all TIMESTAMPTZ keeps")
    void scannedAt_isTruncatedToMicroseconds() {
        // 900 ns apart inside the same microsecond: the database sees one instant, so a tie.
        var smallerId = scan(uuid(0x1000_0000_0000_0000L, 0), T0.plusNanos(1_900));
        var largerIdButEarlierByNanos = scan(uuid(0x2000_0000_0000_0000L, 0), T0.plusNanos(1_000));

        assertThat(
                        ReconciliationPolicy.resolve(List.of(largerIdButEarlierByNanos, smallerId))
                                .winner())
                .isEqualTo(smallerId.id());
    }

    @Test
    @DisplayName("one microsecond earlier still wins")
    void oneMicrosecondEarlier_wins() {
        var earlier = scan(uuid(0x2000_0000_0000_0000L, 0), T0.plusNanos(1_000));
        var later = scan(uuid(0x1000_0000_0000_0000L, 0), T0.plusNanos(2_000));

        assertThat(ReconciliationPolicy.resolve(List.of(later, earlier)).winner())
                .isEqualTo(earlier.id());
    }

    // ---------- the outcomes ----------

    @Test
    @DisplayName("a single valid scan is ACCEPTED")
    void singleScan_isAccepted() {
        var only = scan(UUID.randomUUID(), T0);

        var resolution = ReconciliationPolicy.resolve(List.of(only));

        assertThat(resolution.winner()).isEqualTo(only.id());
        assertThat(resolution.outcomes())
                .containsExactly(Map.entry(only.id(), ScanOutcome.ACCEPTED));
    }

    @Test
    @DisplayName("exactly one scan is ACCEPTED and every other one is REJECTED_DUPLICATE")
    void oneAccepted_restDuplicates() {
        var scans = fiveScansWithATie();

        var resolution = ReconciliationPolicy.resolve(scans);

        assertThat(resolution.outcomes()).hasSize(5);
        assertThat(resolution.outcomes().values().stream().filter(ScanOutcome.ACCEPTED::equals))
                .hasSize(1);
        assertThat(resolution.outcomes().get(resolution.winner())).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(
                        resolution.outcomes().entrySet().stream()
                                .filter(e -> !e.getKey().equals(resolution.winner()))
                                .map(Map.Entry::getValue))
                .containsOnly(ScanOutcome.REJECTED_DUPLICATE);
    }

    @Test
    @DisplayName("there is nothing to resolve without a valid scan")
    void noValidScan_isRefused() {
        assertThatThrownBy(() -> ReconciliationPolicy.resolve(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the same scan listed twice is refused, not counted as a duplicate of itself")
    void sameScanTwice_isRefused() {
        var scan = scan(UUID.randomUUID(), T0);

        assertThatThrownBy(() -> ReconciliationPolicy.resolve(List.of(scan, scan)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- arrival order (INV-12) ----------

    @Test
    @DisplayName("every arrival order ends with the same winner and the same outcomes")
    void everyArrivalOrder_endsTheSame() {
        var scans = fiveScansWithATie();
        var expected = ReconciliationPolicy.resolve(scans);

        for (var order : permutations(scans)) {
            Resolution last = null;
            var arrived = new ArrayList<ScanKey>();
            for (var next : order) {
                arrived.add(next);
                last = ReconciliationPolicy.resolve(arrived);
            }
            assertThat(last).as("arrival order %s", order).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName(
            "while scans arrive, ACCEPTED can only turn into REJECTED_DUPLICATE, never the reverse")
    void whileArriving_theReverseIsForbidden() {
        for (var order : permutations(fiveScansWithATie())) {
            Map<ScanId, ScanOutcome> before = new HashMap<>();
            var arrived = new ArrayList<ScanKey>();
            for (var next : order) {
                arrived.add(next);
                var after = ReconciliationPolicy.resolve(arrived).outcomes();

                assertThat(after.values().stream().filter(ScanOutcome.ACCEPTED::equals))
                        .as("one ACCEPTED after %d arrivals of %s", arrived.size(), order)
                        .hasSize(1);
                before.forEach(
                        (id, was) -> {
                            if (was == ScanOutcome.REJECTED_DUPLICATE) {
                                assertThat(after.get(id))
                                        .as("a duplicate stays a duplicate, order %s", order)
                                        .isEqualTo(ScanOutcome.REJECTED_DUPLICATE);
                            }
                        });
                before = new HashMap<>(after);
            }
        }
    }

    @Test
    @DisplayName("a later scan that is earlier in time takes the win from the recorded winner")
    void overtake_flipsTheRecordedWinner() {
        var recorded = scan(UUID.randomUUID(), T0.plusSeconds(60));
        var overtaking = scan(UUID.randomUUID(), T0);

        var before = ReconciliationPolicy.resolve(List.of(recorded));
        var after = ReconciliationPolicy.resolve(List.of(recorded, overtaking));

        assertThat(before.outcomes().get(recorded.id())).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(after.outcomes().get(recorded.id())).isEqualTo(ScanOutcome.REJECTED_DUPLICATE);
        assertThat(after.outcomes().get(overtaking.id())).isEqualTo(ScanOutcome.ACCEPTED);
    }

    @Test
    @DisplayName("a later scan that is also later in time is itself the duplicate")
    void laterInTime_isTheDuplicate() {
        var recorded = scan(UUID.randomUUID(), T0);
        var late = scan(UUID.randomUUID(), T0.plusSeconds(60));

        var after = ReconciliationPolicy.resolve(List.of(recorded, late));

        assertThat(after.outcomes().get(recorded.id())).isEqualTo(ScanOutcome.ACCEPTED);
        assertThat(after.outcomes().get(late.id())).isEqualTo(ScanOutcome.REJECTED_DUPLICATE);
    }

    @ParameterizedTest
    @EnumSource(ScanOutcome.class)
    @DisplayName("only ACCEPTED and REJECTED_DUPLICATE can come out of a resolution")
    void resolution_neverProducesInvalid(ScanOutcome outcome) {
        var produced =
                ReconciliationPolicy.resolve(fiveScansWithATie()).outcomes().values().stream()
                        .anyMatch(outcome::equals);

        assertThat(produced).isEqualTo(outcome != ScanOutcome.REJECTED_INVALID);
    }

    // ---------- helpers ----------

    /**
     * Five scans from two imagined devices: a tie on {@code scanned_at} whose ids straddle the
     * signed/unsigned boundary, one scan far later, one inside the same microsecond as the tie.
     */
    private static List<ScanKey> fiveScansWithATie() {
        return List.of(
                scan(UUID.fromString("80000000-0000-0000-0000-000000000001"), T0),
                scan(UUID.fromString("7fffffff-ffff-ffff-ffff-fffffffffffe"), T0),
                scan(UUID.fromString("30000000-0000-0000-0000-000000000000"), T0.minusSeconds(3)),
                scan(UUID.fromString("a0000000-0000-0000-0000-000000000000"), T0.plusSeconds(90)),
                scan(UUID.fromString("00000000-0000-0000-0000-000000000009"), T0.plusNanos(400)));
    }

    private static ScanKey scan(UUID id, Instant scannedAt) {
        return new ScanKey(new ScanId(id), scannedAt);
    }

    private static UUID uuid(long high, long low) {
        return new UUID(high, low);
    }

    private static TicketStanding standing(
            boolean confirmed, WorkshopId workshop, boolean workshopCancelled) {
        return new TicketStanding(confirmed, workshop, workshopCancelled);
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.size() <= 1) {
            return List.of(items);
        }
        var result = new ArrayList<List<T>>();
        for (var i = 0; i < items.size(); i++) {
            var rest = new ArrayList<>(items);
            var head = rest.remove(i);
            for (var tail : permutations(rest)) {
                var order = new ArrayList<T>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }
}

package com.unihub.checkin.domain.model;

import com.unihub.shared.domain.id.WorkshopId;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

/**
 * The rules of reconciliation as plain functions (checkin.md §7; Phase 5 checklist D51, D52).
 *
 * <p>Two questions, kept apart. {@link #isValid} judges one scan once, when it is reconciled, and
 * that judgement is final: an attendance already recorded is never undone by a later change of the
 * registration or workshop. {@link #resolve} then picks the winner among the registration's valid
 * scans, so the valid set only ever grows and a scan can only move from {@code ACCEPTED} to {@code
 * REJECTED_DUPLICATE}, never back.
 */
public final class ReconciliationPolicy {

    private ReconciliationPolicy() {}

    /**
     * A scan is valid when the device verified the signature, its code resolves to a registration
     * that is {@code CONFIRMED}, the registration's workshop is not {@code CANCELLED}, and the room
     * the staff member selected is the ticket's workshop.
     *
     * @param standing empty when the code resolved to no registration
     */
    public static boolean isValid(
            DeviceVerdict verdict, WorkshopId venue, Optional<TicketStanding> standing) {
        if (verdict != DeviceVerdict.VERIFIED || standing.isEmpty()) {
            return false;
        }
        var ticket = standing.get();
        return ticket.confirmed()
                && !ticket.workshopCancelled()
                && ticket.workshopId().equals(venue);
    }

    /**
     * The smallest scan by {@link ScanKey#ORDER} is {@code ACCEPTED}, every other one {@code
     * REJECTED_DUPLICATE}. The result does not depend on the order of {@code validScans}.
     *
     * @throws IllegalArgumentException when empty, or when a scan appears twice
     */
    public static Resolution resolve(Collection<ScanKey> validScans) {
        if (validScans.isEmpty()) {
            throw new IllegalArgumentException("nothing to resolve: no valid scan");
        }
        var seen = new HashSet<ScanId>();
        ScanKey winner = null;
        for (var scan : validScans) {
            if (!seen.add(scan.id())) {
                throw new IllegalArgumentException("scan listed twice: " + scan.id().value());
            }
            if (winner == null || ScanKey.ORDER.compare(scan, winner) < 0) {
                winner = scan;
            }
        }
        var outcomes = new HashMap<ScanId, ScanOutcome>();
        for (var scan : validScans) {
            outcomes.put(
                    scan.id(),
                    scan.equals(winner) ? ScanOutcome.ACCEPTED : ScanOutcome.REJECTED_DUPLICATE);
        }
        return new Resolution(winner.id(), outcomes);
    }
}

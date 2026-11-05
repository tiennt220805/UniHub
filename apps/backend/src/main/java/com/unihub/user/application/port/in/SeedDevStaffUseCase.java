package com.unihub.user.application.port.in;

import com.unihub.user.application.command.SeedDevStaffCommand;

/**
 * Gives a development or cluster environment accounts that hold the Organizer, Admin and Check-in
 * Staff roles, so that the flows behind those roles can be run (user.md §10, Phase 7 checklist
 * D86). Nothing triggers it except the {@code dev}-profile runner; no production path does.
 */
public interface SeedDevStaffUseCase {

    /** How many accounts this call created; accounts that already exist are left untouched. */
    int seed(SeedDevStaffCommand command);
}

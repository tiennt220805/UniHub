package com.unihub.user.application.port.in;

import com.unihub.user.application.command.SeedDevStudentsCommand;

public interface SeedDevStudentsUseCase {

    /** How many accounts this call created; accounts that already exist are left untouched. */
    int seed(SeedDevStudentsCommand command);
}

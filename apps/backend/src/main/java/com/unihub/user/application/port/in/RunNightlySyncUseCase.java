package com.unihub.user.application.port.in;

import com.unihub.user.application.result.RunNightlySyncResult;

/** Ingests tonight's legacy student export (requirement.md §1.3.6, §2.10; user.md §2–§8). */
public interface RunNightlySyncUseCase {

    RunNightlySyncResult run();
}

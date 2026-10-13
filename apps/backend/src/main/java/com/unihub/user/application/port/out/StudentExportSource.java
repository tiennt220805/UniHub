package com.unihub.user.application.port.out;

import java.time.LocalDate;
import java.util.Optional;

/** Where the legacy system drops its nightly student export (user.md §2). */
public interface StudentExportSource {

    /** The export for {@code exportDate}; empty when no file exists for that date. */
    Optional<StudentExport> find(LocalDate exportDate);
}

package com.unihub.registration.application.port.out;

import com.unihub.registration.domain.model.Registration;
import java.util.List;

/** One page of registrations and where it sits in the whole result (offset/limit, zero-based). */
public record RegistrationPage(
        List<Registration> items, int page, int size, long totalElements, int totalPages) {}

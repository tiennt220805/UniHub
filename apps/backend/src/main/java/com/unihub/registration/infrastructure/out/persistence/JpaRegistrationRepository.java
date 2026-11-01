package com.unihub.registration.infrastructure.out.persistence;

import com.unihub.registration.application.port.out.RegistrationPage;
import com.unihub.registration.application.port.out.RegistrationRepository;
import com.unihub.registration.domain.exception.AlreadyRegisteredException;
import com.unihub.registration.domain.model.Registration;
import com.unihub.registration.domain.model.RegistrationStatus;
import com.unihub.shared.domain.OptimisticConflictException;
import com.unihub.shared.domain.id.RegistrationId;
import com.unihub.shared.domain.id.UserId;
import com.unihub.shared.domain.id.WorkshopId;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class JpaRegistrationRepository implements RegistrationRepository {
    private static final String AGGREGATE = "REGISTRATION";
    private static final String ACTIVE_PER_USER_INDEX = "uq_registrations_active_per_user";

    private final SpringDataRegistrationRepository springData;
    private final RegistrationPersistenceMapper mapper;
    private final Clock clock;

    JpaRegistrationRepository(
            SpringDataRegistrationRepository springData,
            RegistrationPersistenceMapper mapper,
            Clock clock) {
        this.springData = springData;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Optional<Registration> findById(RegistrationId id) {
        return springData.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public List<Registration> findHoldingSeats(WorkshopId workshopId) {
        return springData.findHoldingSeats(workshopId.value()).stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    public List<Registration> findByTicketCodes(Collection<String> ticketCodes) {
        return springData.findByTicketCodeIn(ticketCodes).stream().map(mapper::toDomain).toList();
    }

    @Override
    public RegistrationPage findByUser(
            UserId userId, RegistrationStatus status, int page, int size) {
        // newest first; the id breaks ties so a page boundary is stable
        var paging =
                PageRequest.of(
                        page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        var found =
                status == null
                        ? springData.findByUserId(userId.value(), paging)
                        : springData.findByUserIdAndStatus(userId.value(), status.name(), paging);
        return new RegistrationPage(
                found.getContent().stream().map(mapper::toDomain).toList(),
                found.getNumber(),
                found.getSize(),
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Override
    public boolean existsActive(WorkshopId workshopId, UserId userId) {
        return springData.existsActive(workshopId.value(), userId.value());
    }

    @Override
    public Registration save(Registration registration) {
        var entity = springData.findById(registration.id().value()).orElse(null);
        if (entity == null) {
            return insert(registration);
        }
        // Hibernate ignores a version assigned to a managed entity (§9.2): compare explicitly.
        if (entity.version() != registration.version().value()) {
            throw new OptimisticConflictException(AGGREGATE, registration.id().value());
        }
        mapper.copyState(registration, entity);
        try {
            return mapper.toDomain(springData.saveAndFlush(entity));
        } catch (ObjectOptimisticLockingFailureException lostRace) {
            throw new OptimisticConflictException(AGGREGATE, registration.id().value());
        }
    }

    @Override
    public Registration transition(Registration registration, RegistrationStatus from) {
        var changed =
                springData.transition(
                        registration.id().value(),
                        registration.version().value(),
                        from.name(),
                        registration.status().name(),
                        registration.holdExpiresAt(),
                        registration.ticketCode(),
                        registration.qrPayload(),
                        clock.instant());
        if (changed != 1) {
            throw new OptimisticConflictException(AGGREGATE, registration.id().value());
        }
        return springData.findById(registration.id().value()).map(mapper::toDomain).orElseThrow();
    }

    private Registration insert(Registration registration) {
        try {
            return mapper.toDomain(springData.saveAndFlush(mapper.toNewEntity(registration)));
        } catch (DataIntegrityViolationException violation) {
            // The use case checks first; this is a request that raced past the check. It must be
            // a 409, never a 500, and the transaction is already doomed (the claim rolls back too).
            if (isActivePerUserViolation(violation)) {
                throw new AlreadyRegisteredException(
                        registration.userId(), registration.workshopId());
            }
            throw violation;
        }
    }

    private static boolean isActivePerUserViolation(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message != null && message.contains(ACTIVE_PER_USER_INDEX)) {
                return true;
            }
        }
        return false;
    }
}

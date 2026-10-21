package com.unihub.registration.infrastructure.out.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataRegistrationRepository extends JpaRepository<RegistrationJpaEntity, UUID> {

    Page<RegistrationJpaEntity> findByUserId(UUID userId, Pageable pageable);

    Page<RegistrationJpaEntity> findByUserIdAndStatus(
            UUID userId, String status, Pageable pageable);

    @Query(
            "select count(r) > 0 from RegistrationJpaEntity r "
                    + "where r.workshopId = :workshopId and r.userId = :userId "
                    + "and r.status in ('RESERVED', 'CONFIRMED')")
    boolean existsActive(@Param("workshopId") UUID workshopId, @Param("userId") UUID userId);

    /**
     * The status-guarded transition (backend-coding-standards.md §9.2, Phase 2 checklist D2): the
     * expected status is part of the WHERE, and the version is bumped here because a bulk update
     * bypasses Hibernate's own increment. Returns the rows changed: 1, or 0 when it lost.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "update RegistrationJpaEntity r set r.status = :status, r.holdExpiresAt = :holdExpiresAt, "
                    + "r.ticketCode = :ticketCode, r.qrPayload = :qrPayload, "
                    + "r.version = r.version + 1, r.updatedAt = :now "
                    + "where r.id = :id and r.version = :version and r.status = :from")
    int transition(
            @Param("id") UUID id,
            @Param("version") long version,
            @Param("from") String from,
            @Param("status") String status,
            @Param("holdExpiresAt") Instant holdExpiresAt,
            @Param("ticketCode") String ticketCode,
            @Param("qrPayload") String qrPayload,
            @Param("now") Instant now);
}

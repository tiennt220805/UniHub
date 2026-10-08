package com.unihub.workshop.infrastructure.out.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataWorkshopRepository extends JpaRepository<WorkshopJpaEntity, UUID> {}

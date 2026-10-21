package com.unihub.workshop.application.usecase;

import com.unihub.shared.domain.id.WorkshopId;
import com.unihub.workshop.api.WorkshopPricing;
import com.unihub.workshop.application.port.out.WorkshopRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkshopPriceLookup implements WorkshopPricing {
    private final WorkshopRepository workshops;

    WorkshopPriceLookup(WorkshopRepository workshops) {
        this.workshops = workshops;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> priceOf(WorkshopId workshopId) {
        return workshops.findById(workshopId).map(workshop -> workshop.price());
    }
}

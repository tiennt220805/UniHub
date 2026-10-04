package com.unihub.workshop.application.port.out;

import com.unihub.workshop.application.command.SearchWorkshopsCriteria;

public interface WorkshopCatalogQueries {
    WorkshopCatalogPage search(SearchWorkshopsCriteria criteria);
}

package com.unihub.workshop.application.port.out;

import com.unihub.workshop.application.command.ListManagedWorkshopsCriteria;

/**
 * Read model of the management list (backend-coding-standards.md §20): every status, newest start
 * first. Never cached; the caller reads the Master.
 */
public interface ManagedWorkshopQueries {
    WorkshopCatalogPage search(ListManagedWorkshopsCriteria criteria);
}

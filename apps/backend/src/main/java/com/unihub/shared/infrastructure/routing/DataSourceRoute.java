package com.unihub.shared.infrastructure.routing;

/** The two connection targets of the read/write split (architecture.md §4.1). */
enum DataSourceRoute {
    MASTER,
    REPLICA
}

package com.unihub.shared.infrastructure.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Replica connection (architecture.md §4.1), bound from the environment ({@code
 * UNIHUB_DATASOURCE_REPLICA_URL}, {@code _USERNAME}, {@code _PASSWORD}), never from committed
 * config. With no {@code url}, read-only transactions use the Master pool: local dev and {@code
 * overlays/dev} run no Replica (deployment-and-cicd.md §3). A null {@code username}/{@code
 * password} falls back to the Master's, since a streaming replica carries the Master's roles.
 */
@ConfigurationProperties(prefix = "unihub.datasource.replica")
public record ReplicaDataSourceProperties(String url, String username, String password) {

    public ReplicaDataSourceProperties {
        if (url != null && url.isBlank()) {
            throw new IllegalArgumentException(
                    "unihub.datasource.replica.url must be a JDBC URL or left unset");
        }
    }

    /** Keeps the password out of logs and exception messages. */
    @Override
    public String toString() {
        return "ReplicaDataSourceProperties[url="
                + url
                + ", username="
                + username
                + ", password=****]";
    }
}

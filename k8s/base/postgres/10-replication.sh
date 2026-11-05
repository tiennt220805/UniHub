#!/bin/sh
# Run once by the postgres image's entrypoint when it creates the data directory (/docker-entrypoint-initdb.d).
# The image's default pg_hba.conf has no remote `replication` entry (`all` in the database column does not match
# replication connections), so a standby could not clone this Master. The same line ReplicaIntegrationTestSupport
# appends. The password check stays scram-sha-256: a standby presents the Master's own user and password.
set -e
echo "host replication all all scram-sha-256" >> "$PGDATA/pg_hba.conf"

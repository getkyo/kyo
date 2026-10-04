package kyo.internal

import kyo.SqlConformanceBackend
import kyo.internal.sqlite.SqliteBackendFactory

/** The conformance descriptor for SQLite, which is what puts it in front of the cross-engine battery. */
final class SqliteConformanceBackend extends SqliteLineageConformanceBackend(new SqliteBackendFactory(), "sqlite"):

    def label: SqlConformanceBackend.Label = SqlConformanceBackend.Label("SQLite")

end SqliteConformanceBackend

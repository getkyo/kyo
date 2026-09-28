package kyo.internal

import kyo.db.Backend
import kyo.internal.sqlite.SqliteBackendFactory

/** The conformance descriptor for SQLite, which is what puts it in front of the cross-engine battery. */
final class SqliteConformanceBackend extends SqliteLineageConformanceBackend:

    def id: String        = "sqlite"
    def label: String     = "SQLite"
    def urlScheme: String = "sqlite"

    def backend: Backend = new SqliteBackendFactory()

end SqliteConformanceBackend

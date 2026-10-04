package kyo.internal.doltlite

import kyo.SqlConformanceBackend
import kyo.internal.SqliteLineageConformanceBackend

/** The conformance descriptor for DoltLite, a SQLite fork that keeps the SQL surface and replaces the storage engine. */
final class DoltLiteConformanceBackend extends SqliteLineageConformanceBackend(new DoltLiteBackendFactory(), "doltlite"):

    def label: SqlConformanceBackend.Label = SqlConformanceBackend.Label("DoltLite")

    /** Reachable where the engine is published for this platform. */
    override def reachable: Boolean = DoltLiteEngineProbe.available

end DoltLiteConformanceBackend

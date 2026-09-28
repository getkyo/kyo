package kyo.internal.doltlite

import kyo.db.Backend
import kyo.internal.SqliteLineageConformanceBackend

/** The conformance descriptor for DoltLite, a SQLite fork that keeps the SQL surface and replaces the storage engine. */
final class DoltLiteConformanceBackend extends SqliteLineageConformanceBackend:

    def id: String        = "doltlite"
    def label: String     = "DoltLite"
    def urlScheme: String = "doltlite"

    def backend: Backend = new DoltLiteBackendFactory()

    /** Reachable where the engine is published for this platform. */
    override def reachable: Boolean = DoltLiteEngineProbe.available

end DoltLiteConformanceBackend

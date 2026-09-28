package kyo

import kyo.internal.doltlite.DoltLiteEngineProbe

/** Every leaf drives ONE connection over `:memory:`, which needs no filesystem. The single connection is what makes that name a single
  * database, since each connection opening it gets its own.
  */
class DoltLiteConformanceTest extends DoltConformanceTest:

    def withDolt[A](config: SqlConfig)(
        f: Dolt => A < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): A < (Async & Abort[SqlException] & Scope) =
        // Cancelled where the engine is not published, which the unavailability leaf in DoltLiteClientTest claims.
        assume(DoltLiteEngineProbe.available, "the DoltLite engine is not published for this platform")
        SqlClient.init("doltlite://:memory:", config.copy(maxConnections = 1)).map(client => DB.run(client)(Dolt.use(f)))
    end withDolt

    def recordsAuthor: Boolean           = false
    def recordsCommitOrder: Boolean      = false
    def keepsConflictsPastMerge: Boolean = false

    // The engine runs in this process, so the remote lives on this host, inside a directory removed with the leaf. A
    // path not yet created, since the first push initializes the store and an existing empty directory is refused.
    def fileRemoteUrl(using Frame): String < (Sync & Scope) =
        Abort.recover[FileSystemException](e => Abort.panic(e)) {
            Path.run(Path.tempDir("kyo-dolt-remote")).map(dir => s"file://${(dir / "remote").unsafe.show}")
        }

end DoltLiteConformanceTest

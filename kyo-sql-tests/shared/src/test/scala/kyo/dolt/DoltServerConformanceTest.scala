package kyo.dolt

import kyo.*
import kyo.internal.SqlConformanceBackends

class DoltServerConformanceTest extends SqlContainerTest with DoltConformanceTest:

    override def timeout: Duration = 5.minutes

    private val descriptor = SqlConformanceBackends.dolt

    def withDolt[A](config: SqlConfig)(
        f: Dolt => A < (Async & Abort[SqlException] & Scope & DB)
    )(using Frame): A < (Async & Abort[SqlException] & Scope) =
        descriptor.withFreshSchema { schema =>
            descriptor.open(schema.url, config).flatMap(client => DB.run(client)(Dolt.use(f)))
        }

    def recordsAuthor: Boolean           = true
    def recordsCommitOrder: Boolean      = true
    def keepsConflictsPastMerge: Boolean = true

    // The server resolves the path inside its container, which goes with the container.
    def fileRemoteUrl(using Frame): String < (Sync & Scope) =
        Random.nextLong.map(v => s"file:///tmp/kyo-dolt-remote-${(v & Long.MaxValue).toHexString}")

end DoltServerConformanceTest

package kyo

import kyo.internal.Platform
import kyo.internal.SqlConformanceBackends

/** The battery over every engine kyo ships, compiled in this module so each query runs the SQL `.run` rendered at compile time. The
  * published classes render at run time; [[com.example.sql.SqlExternalPackageTest]] and DoltLite's run exercise that path.
  */
class SqlConformanceKyoBackendsTest extends folded.SqlConformanceTest(SqlConformanceBackends.all):

    // The battery cancels a comparison it has only one engine for, so a host where the containers cannot start would
    // run SQLite alone and report nothing missing. This leaf is what keeps that host red.
    "every backend kyo ships is reachable" in {
        assume(!Platform.isWindows, "the container engines are declared unreachable on Windows")
        val unreachable = backends.filterNot(_.reachable).map(_.label)
        assert(unreachable.isEmpty, s"these backends cannot be exercised on this host, so their leaves did not run: $unreachable")
    }

end SqlConformanceKyoBackendsTest

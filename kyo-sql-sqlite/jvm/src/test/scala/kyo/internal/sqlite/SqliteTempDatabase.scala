package kyo.internal.sqlite

import java.nio.file.Files

/** A throwaway database FILE for the conformance descriptor, on the platforms that have a filesystem.
  *
  * A file rather than `:memory:` because the battery opens a SECOND client on the same URL to test concurrency, and every connection opening
  * `:memory:` gets its own private database, so that client would see an empty one and every such leaf would fail for a reason that has
  * nothing to do with what it tests.
  *
  * The unit suites in this module need none of this: each drives one client, so `:memory:` serves them and keeps them running everywhere.
  */
private[sqlite] object SqliteTempDatabase:

    /** Whether this platform can supply a file at all, which is what the descriptor reports as its reachability. */
    val available: Boolean = true

    def create(): String = Files.createTempFile("kyo-sqlite-conformance-", ".db").toAbsolutePath.toString

    /** Deletes the database and the two sidecars WAL leaves beside it, so the descriptor is not the suite's own leak. */
    def delete(path: String): Unit =
        val p = java.nio.file.Path.of(path)
        val _ = Files.deleteIfExists(p)
        val _ = Files.deleteIfExists(p.resolveSibling(p.getFileName.toString + "-wal"))
        val _ = Files.deleteIfExists(p.resolveSibling(p.getFileName.toString + "-shm"))
    end delete

end SqliteTempDatabase

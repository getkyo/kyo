package kyo.internal

import java.nio.file.Files

/** A throwaway database FILE for the conformance descriptor, on the platforms that have a filesystem.
  *
  * A file rather than `:memory:` because the battery opens a SECOND client on the same URL to test concurrency, and every connection opening
  * `:memory:` gets its own private database, so that client would see an empty one.
  */
private[kyo] object SqliteTempDatabase:

    val available: Boolean = true

    def create(): String = Files.createTempFile("kyo-sqlite-conformance-", ".db").toAbsolutePath.toString

    /** Deletes the database and the two sidecars WAL leaves beside it. */
    def delete(path: String): Unit =
        val p = java.nio.file.Path.of(path)
        val _ = Files.deleteIfExists(p)
        val _ = Files.deleteIfExists(p.resolveSibling(p.getFileName.toString + "-wal"))
        val _ = Files.deleteIfExists(p.resolveSibling(p.getFileName.toString + "-shm"))
    end delete

end SqliteTempDatabase

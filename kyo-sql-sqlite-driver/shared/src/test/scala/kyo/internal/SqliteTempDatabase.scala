package kyo.internal

import kyo.*

/** A throwaway database FILE, in a temporary directory removed with its WAL sidecars when the enclosing scope ends.
  *
  * A file rather than `:memory:` because the conformance battery opens a SECOND client on the same URL, and every connection opening
  * `:memory:` gets its own private database, so that client would see an empty one. A directory rather than a file because the engine
  * writes `-wal` and `-shm` beside the database. A failure to make it is a panic: the leaf reports red with the cause.
  */
private[kyo] object SqliteTempDatabase:

    def create(using Frame): String < (Sync & Scope) =
        Abort.recover[FileSystemException](e => Abort.panic(e)) {
            Path.run(Path.tempDir("kyo-sqlite")).map(dir => (dir / "db.sqlite").unsafe.show)
        }

end SqliteTempDatabase

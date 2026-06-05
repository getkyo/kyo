package kyo.internal.tasty.query

import kyo.*

/** A handle to an opened zip/jar root for reading individual entries.
  *
  * Obtained from `FileSource.openZip(root)`. The handle is Scope-bound: the backing resources are released when the enclosing Scope exits.
  * Callers should not cache ZipHandle instances beyond the Scope they were opened in.
  *
  * Each call to `readEntry` reads one named entry from the zip. Missing entries return `Maybe.Absent` rather than raising an error, so
  * callers can probe for optional entries (e.g. `META-INF/kyo-tasty/snapshot.krfl`) without branching on Abort.
  *
  * Scaladoc: 8-35 lines.
  */
trait ZipHandle:

    /** Read the bytes of one zip entry by its internal path (e.g. `"META-INF/kyo-tasty/snapshot.krfl"`).
      *
      * Returns `Maybe.Absent` when the entry does not exist in the zip. Raises `Abort[TastyError]` only on genuine I/O failures (corrupt
      * zip, missing backing store, etc.).
      */
    def readEntry(internalPath: String)(using Frame): Maybe[Array[Byte]] < (Sync & Abort[TastyError])

end ZipHandle

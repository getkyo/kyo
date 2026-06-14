package kyo.internal.tasty.query

import kyo.*

/** A handle to an opened zip/jar root for reading individual entries.
  *
  * Obtained from `ZipHandle.open(root)`. The handle is Scope-bound: the backing resources are released when the enclosing Scope exits.
  * Callers should not cache ZipHandle instances beyond the Scope they were opened in.
  *
  * Each call to `readEntry` reads one named entry from the zip. Missing entries return `Maybe.Absent` rather than raising an error, so
  * callers can probe for optional entries (e.g. `META-INF/kyo-tasty/snapshot.krfl`) without branching on Abort.
  *
  * `listEntries` enumerates all entry names whose names end with any of the given suffixes. Returns `Chunk.empty` when no entries match.
  */
trait ZipHandle:

    /** Read the bytes of one zip entry by its internal path (e.g. `"META-INF/kyo-tasty/snapshot.krfl"`).
      *
      * Returns `Maybe.Absent` when the entry does not exist in the zip. Raises `Abort[TastyError]` only on genuine I/O failures (corrupt
      * zip, missing backing store, etc.).
      */
    def readEntry(internalPath: String)(using Frame): Maybe[Array[Byte]] < (Sync & Abort[TastyError])

    /** List all entry names in the zip whose names end with any of the given suffixes.
      *
      * Returns `Chunk.empty` when `suffixes` is empty or when no entries match. Raises `Abort[TastyError]` only on genuine I/O failures.
      */
    def listEntries(suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError])

end ZipHandle

object ZipHandle:

    /** Open a zip or jar root for entry-level reads.
      *
      * Returns `Maybe.Absent` when the platform cannot open the zip (browser JS, Scala Native) or when the root is not a regular jar file
      * (a directory, a jrt:/ URI, a non-existent path). The returned handle is Scope-bound: its backing resources are released when the
      * enclosing Scope exits.
      */
    def open(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
        ZipHandlePlatform.open(root)

    /** Execute `body` inside a read-batch context.
      *
      * On JVM, installs a `JarMappedReaderPool` for the duration of `body` so that repeated jar reads within one classpath-init call share
      * memory-mapped buffers instead of constructing a new mmap reader per read. On JS and Native, this is a no-op (jar reads are not
      * supported and no pooling is needed).
      *
      * Called by `ClasspathOrchestrator.init` to wrap the entire scan-and-decode pipeline.
      */
    def withPool[A, S](body: A < S)(using Frame): A < (S & Sync & Scope) =
        ZipHandlePlatform.withPool(body)

    /** Read one entry from a jar file by its internal path, using the active `JarMappedReaderPool` when available.
      *
      * On JVM, checks the thread-local pool installed by `withPool`; falls back to a one-shot mmap reader when no pool is active.
      * On JS and Native, always raises `Abort[TastyError.FileNotFound]` because jar reading is not supported on those platforms.
      *
      * `jarPath` is the filesystem path to the jar file. `entryName` is the entry's internal name (forward-slash separated, no leading
      * slash). Used by `ClasspathOrchestrator.decodeOneEntry` for entries whose path is in the `"jar!/entry"` format produced by
      * `walkRoot`.
      */
    def readJarEntry(jarPath: String, entryName: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        ZipHandlePlatform.readJarEntry(jarPath, entryName)

    /** Read the raw bytes of a single `jrt:/` class file.
      *
      * On JVM, resolves the path in the JRT filesystem and reads all bytes. On JS and Native, always raises
      * `Abort[TastyError.FileNotFound]` because the JRT filesystem is JVM-only.
      *
      * Used by `ClasspathOrchestrator.readEntryBytes` for `jrt:/` entry paths emitted by `PlatformModuleOps.listJdkClassFiles`.
      */
    def readJrtEntry(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        ZipHandlePlatform.readJrtEntry(path)

    /** List all entries under a jrt:/ path whose names end with any of the given suffixes.
      *
      * On JVM, walks the JRT filesystem. On JS and Native, always returns `Chunk.empty` (jrt:/ is JVM-only).
      *
      * Used by `ClasspathOrchestrator.walkRoot` for jrt:/ roots.
      */
    def listJrtEntries(root: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        ZipHandlePlatform.listJrtEntries(root, suffixes)

end ZipHandle

package kyo

import java.nio.charset.Charset

/** The backend every safe `Path` operation dispatches through. Effect-polymorphic in `S`
  * (the backend's own effect, exposed on a runner residual, the Journal `Backend[S]`
  * precedent); methods take no `(using Frame)` (a service captures its `Frame` at
  * construction). The umbrella `Abort[FileException]` is the uniform error row. Implement
  * this to provide a virtual filesystem (in-memory, overlay); [[FileSystem.host]] is the
  * default host-filesystem backend.
  *
  * `FileSystem[S]` is one flat effect-polymorphic contract: there is no read-only
  * sub-trait, because the read runners ([[Path.runReadOnly]], [[Path.runReadOnlyWith]])
  * restrict the program capability at the call site rather than the service type.
  *
  * @tparam S the backend's own effect, propagated to the runner residual
  * @see [[Path.run]] and [[Path.runWith]] for the runners that install a service
  */
trait FileSystem[S]:
    def commitStrategy: FileSystem.CommitStrategy

    // inspection
    def exists(path: Path): Boolean < (S & Abort[FileException])
    def exists(path: Path, followLinks: Boolean): Boolean < (S & Abort[FileException])
    def isDirectory(path: Path): Boolean < (S & Abort[FileException])
    def isRegularFile(path: Path): Boolean < (S & Abort[FileException])
    def isSymbolicLink(path: Path): Boolean < (S & Abort[FileException])
    def realPath(path: Path): Path < (S & Abort[FileException])

    // read
    def read(path: Path): String < (S & Abort[FileException])
    def read(path: Path, charset: Charset): String < (S & Abort[FileException])
    def readBytes(path: Path): Span[Byte] < (S & Abort[FileException])
    def readLines(path: Path): Chunk[String] < (S & Abort[FileException])
    def readLines(path: Path, charset: Charset): Chunk[String] < (S & Abort[FileException])
    def size(path: Path): Long < (S & Abort[FileException])
    def stat(path: Path): Path.PathStat < (S & Abort[FileException])

    // read handles (internal handle types; back the streaming reads and walk)
    def openRead(path: Path): Path.ReadHandle < (S & Abort[FileException])
    def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (S & Abort[FileException])
    def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (S & Abort[FileException])

    // write
    def write(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException])
    def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException])
    def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException])
    def append(path: Path, value: String, createFolders: Boolean): Unit < (S & Abort[FileException])
    def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (S & Abort[FileException])
    def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (S & Abort[FileException])
    def truncate(path: Path, size: Long): Unit < (S & Abort[FileException])
    def setLastModified(path: Path, epochMs: Long): Unit < (S & Abort[FileException])

    // write handle
    def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (S & Abort[FileException])
    def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (S & Abort[FileException])
    def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (S & Abort[FileException])

    // directory / structure
    def mkDir(path: Path): Unit < (S & Abort[FileException])
    def mkFile(path: Path): Unit < (S & Abort[FileException])
    def list(path: Path): Chunk[Path] < (S & Abort[FileException])
    def list(path: Path, glob: String): Chunk[Path] < (S & Abort[FileException])
    def move(
        from: Path,
        to: Path,
        replaceExisting: Boolean,
        atomicMove: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException])
    def copy(
        from: Path,
        to: Path,
        followLinks: Boolean,
        replaceExisting: Boolean,
        copyAttributes: Boolean,
        createFolders: Boolean
    ): Unit < (S & Abort[FileException])
    def remove(path: Path): Boolean < (S & Abort[FileException])
    def removeExisting(path: Path): Unit < (S & Abort[FileException])
    def removeAll(path: Path): Unit < (S & Abort[FileException])

    // scoped temp: vends a service-correct removal handle so cleanup runs through the creating service
    def tempDir(prefix: String): Path.TempDirHandle < (S & Abort[FileException])

    // channel tier: service-level members only, reached by a caller holding this
    // value directly; never a Path.Op ArrowEffect case, never suspended through Path.run.
    def openChannel(path: Path, mode: FileSystem.ChannelMode): Path.Channel[S] < (S & Scope & Abort[FileException])
    def syncDir(path: Path): Unit < (S & Abort[FileException])

    /** Acquires an advisory lock on `path`, Scope-released. `exclusive = true` excludes every
      * other holder; `false` requests a shared lock admitting other concurrent shared holders
      * while still excluding an exclusive holder. Non-blocking: fails
      * `FileLockUnavailableException` immediately if the lock is held incompatibly, never waits.
      */
    def lock(path: Path, exclusive: Boolean): Path.FileLock < (S & Scope & Abort[FileException])
end FileSystem

object FileSystem:

    /** Write-durability contract a [[FileSystem]] declares. Governs when staged bytes
      * become visible to other readers of the same backend: `Auto` (each write durable
      * immediately, [[host]] and [[inMemory]]), `OnSuccess` (staged for the enclosing
      * run), `Manual` (staged until an explicit [[CommitHandle.commit]], [[overlay]]).
      */
    enum CommitStrategy derives CanEqual:
        case Auto
        case OnSuccess
        case Manual
    end CommitStrategy

    /** Capability gate for [[FileSystem.openChannel]], chosen at open time. `Read`
      * vends a channel whose `writeAt`/`truncate` calls fail with `Abort[FileException]` at
      * the call site. `ReadWrite` and `ReadWriteCreate` vend a fully read/write channel,
      * differing only in whether `openChannel` itself fails or creates the target file when
      * it is absent.
      */
    enum ChannelMode derives CanEqual:
        case Read
        case ReadWrite
        case ReadWriteCreate
    end ChannelMode

    /** Default host backend: delegates every op to [[Path.Unsafe]], translating the concrete
      * `Result[File*Exception, A]` into `Abort[FileException]`, so it preserves current
      * `Path` behavior exactly. Its commit strategy is `Auto`.
      */
    def host(using Frame): FileSystem[Sync] = HostFileSystem()

    /** Root-confined host backend: resolves `root.realPath` at construction and rejects any
      * op whose canonical path (following every symlink) escapes it; writes to missing
      * entries validate the nearest existing parent. Prefix-only checking without realpath is
      * a security defect.
      */
    def host(root: Path)(using Frame): FileSystem[Sync] < (Sync & Abort[FileException]) =
        HostFileSystem.rootConfined(root)

    /** In-memory backend: an immutable node tree keyed by `Path.parts` behind one
      * `AtomicRef`, advanced by an optimistic CAS loop. `isSymbolicLink` always returns
      * `false`. Its commit strategy is `Auto`.
      */
    def inMemory(using Frame): FileSystem[Sync] < Sync = InMemoryFileSystem.init

    /** Copy-on-write overlay over `lower`: reads fall through, writes stage in an upper
      * layer, and an explicit commit replays the staged journal onto `lower`. Scope-managed;
      * `Manual`. Produces a [[CommitHandle]].
      */
    def overlay[S](lower: FileSystem[S])(using Frame): CommitHandle[S] < (Sync & Scope) =
        OverlayFileSystem.init(lower)

    /** A [[FileSystem]] extension whose writes stage locally until an explicit commit validates
      * them against the underlying live service. [[FileSystem.overlay]] is the built-in
      * manual-commit factory; its [[FileSystem.commitStrategy]] is `CommitStrategy.Manual`.
      *
      * Three commit strategies are available:
      *   - [[commit]]: validates the read-set against the live lower; aborts `CommitConflict` if
      *     any observed entry has diverged, leaving the lower untouched.
      *   - [[commitOverwrite]]: replays unconditionally (last-writer-wins) with no conflict check.
      *   - [[commitWith]]: validates, then calls a caller-supplied `resolve` function for each
      *     conflict to obtain a [[Resolution]] before replaying the resolved entries.
      *
      * [[rollback]] discards all staged writes without touching the lower service.
      *
      * @tparam S the lower service's own effect, propagated to each commit operation's residual
      */
    trait CommitHandle[S] extends FileSystem[S]:
        /** Validates the read-set against the live lower; replays if every observed entry
          * matches, else aborts `CommitConflict` and leaves the lower service untouched.
          */
        def commit(using Frame): Unit < (S & Abort[FileException] & Abort[CommitConflict])

        /** Replays every staged write unconditionally (last-writer-wins). No `CommitConflict` in
          * the row.
          */
        def commitOverwrite(using Frame): Unit < (S & Abort[FileException])

        /** Validates, then resolves each [[Conflict]] through `resolve` before replaying. */
        def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (S & Abort[FileException] & S2)

        /** Discards all staged writes without touching the lower service. */
        def rollback(using Frame): Unit < S
    end CommitHandle
end FileSystem

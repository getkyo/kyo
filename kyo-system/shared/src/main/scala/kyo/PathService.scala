package kyo

import java.nio.charset.Charset

/** The backend every safe `Path` operation dispatches through. Effect-polymorphic in `S`
  * (the backend's own effect, exposed on a runner residual, the Journal `Backend[S]`
  * precedent); methods take no `(using Frame)` (a service captures its `Frame` at
  * construction). The umbrella `Abort[FileException]` is the uniform error row. Implement
  * this to provide a virtual filesystem (in-memory, overlay); [[PathService.host]] is the
  * default host-filesystem backend.
  *
  * `PathService[S]` is one flat effect-polymorphic contract: there is no read-only
  * sub-trait, because the read runners ([[Path.runReadOnly]], [[Path.runReadOnlyWith]])
  * restrict the program capability at the call site rather than the service type.
  *
  * @tparam S the backend's own effect, propagated to the runner residual
  * @see [[Path.run]] and [[Path.runWith]] for the runners that install a service
  */
trait PathService[S]:
    def disposition: PathService.Disposition

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
end PathService

object PathService:

    /** Write-durability contract a [[PathService]] declares. Governs when staged bytes
      * become visible to other readers of the same backend: `AutoCommit` (each write durable
      * immediately, [[host]] and [[inMemory]]), `CommitOnSuccess` (staged for the enclosing
      * run), `ManualCommit` (staged until an explicit [[CommitHandle.commit]], [[overlay]]).
      */
    enum Disposition derives CanEqual:
        case AutoCommit
        case CommitOnSuccess
        case ManualCommit
    end Disposition

    /** Default host backend: delegates every op to [[Path.Unsafe]], translating the concrete
      * `Result[File*Exception, A]` into `Abort[FileException]`, so it preserves current
      * `Path` behavior exactly. Its disposition is `AutoCommit`.
      */
    def host(using Frame): PathService[Sync] = HostService()

    /** Root-confined host backend: resolves `root.realPath` at construction and rejects any
      * op whose canonical path (following every symlink) escapes it; writes to missing
      * entries validate the nearest existing parent. Prefix-only checking without realpath is
      * a security defect.
      */
    def host(root: Path)(using Frame): PathService[Sync] < (Sync & Abort[FileException]) =
        HostService.rootConfined(root)

    /** In-memory backend: an immutable node tree keyed by `Path.parts` behind one
      * `AtomicRef`, advanced by an optimistic CAS loop. `isSymbolicLink` always returns
      * `false`. Its disposition is `AutoCommit`.
      */
    def inMemory(using Frame): PathService[Sync] < Sync = InMemoryService.init

    /** Copy-on-write overlay over `lower`: reads fall through, writes stage in an upper
      * layer, and an explicit commit replays the staged journal onto `lower`. Scope-managed;
      * `ManualCommit`. Produces a [[CommitHandle]].
      */
    def overlay[S](lower: PathService[S])(using Frame): CommitHandle[S] < (Sync & Scope) =
        OverlayService.init(lower)
end PathService

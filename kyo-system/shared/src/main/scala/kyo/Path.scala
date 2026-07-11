package kyo

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.PathPlatformSpecific

/** A cross-platform, immutable file-system path with effect-tracked I/O.
  *
  * Path provides a unified API for file operations across JVM, Scala.js (Node.js), and Scala Native. Every I/O operation is tracked in the
  * type system via capability effects: reads carry `PathRead` and writes carry `PathWrite`. A runner (`Path.run`, `Path.runReadOnly`,
  * `Path.runWith`, or `Path.runReadOnlyWith`) discharges the capability and leaves `Sync & Abort[FileException]` as the residual.
  *
  * Paths are constructed via the `/` operator or the `apply` factory:
  *
  * {{{
  * val config = Path / "etc" / "app" / "config.toml"
  * val data   = Path("var", "data", "app")
  *
  * // Read with capability effect
  * val content: String < PathRead = config.read
  *
  * // Discharge with the host runner
  * val result: String < (Sync & Abort[FileException]) = Path.runReadOnly(content)
  *
  * // Streaming reads are Scope-managed (file handle auto-closed)
  * val lines: Stream[String, PathRead & Scope & Sync] = config.readLinesStream
  * }}}
  *
  * Inspection methods (`exists`, `isDirectory`, `isRegularFile`, `isSymbolicLink`) return `false` for inaccessible paths rather than
  * failing -- they require only `PathRead`, not explicit `Abort`.
  *
  * **Streaming operations** (`readStream`, `readBytesStream`, `readLinesStream`, `walk`, `tail`) return `Stream` values that carry
  * `Scope` in their effect type. The underlying OS resource (file handle, directory handle) is acquired when the stream starts and released
  * when the enclosing `Scope` closes -- whether by normal completion, error, or cancellation.
  *
  * @see
  *   [[FileException]] for the typed error hierarchy
  * @see
  *   [[kyo.Path.Unsafe]] for the abstract platform-specific implementation class
  * @see
  *   [[PathRead]] for the read capability
  * @see
  *   [[PathWrite]] for the write capability (extends PathRead)
  */
opaque type Path = Path.Unsafe

import kyo.kernel.ArrowEffect

/** Read capability for the file system: existence queries, reads, `list`, `walk`, `realPath`,
  * `confinedTo`, `stat`, `size`. A computation that only reads carries `< PathRead` in its row;
  * `Sync` and the `Abort[FileException]` umbrella are folded into the capability and become visible
  * only after a runner discharges it. Discharge with [[Path.runReadOnly]] (read-only) or
  * [[Path.run]] (read and write). `PathWrite <: PathRead`: a write-capable context also satisfies
  * reads, and a read-only runner rejects write programs at the call site.
  *
  * @see
  *   [[Path.run]], [[Path.runReadOnly]], [[Path.runWith]], [[Path.runReadOnlyWith]] for the runners
  * @see
  *   [[Path.Service]] for the pluggable backend the runners install
  */
sealed trait PathRead extends ArrowEffect[[A] =>> Path.Op[A], Id]

/** Write capability for the file system: writes, appends, `truncate`, `mkDir`, `mkFile`, `move`,
  * `copy`, `remove`, and the scoped `tempDir`. Because `PathWrite <: PathRead`, a write-capable
  * context also satisfies read operations, a mixed read plus write program's row collapses to
  * `PathWrite`, and [[Path.runReadOnly]] rejects a program containing a write at the call site.
  *
  * @see
  *   [[PathRead]] for the read capability this extends
  */
sealed trait PathWrite extends PathRead

object Path extends PathPlatformSpecific:

    given CanEqual[Path, Path] = CanEqual.derived

    /** A path segment, either a literal string or another Path whose parts are spliced in. */
    type Part = String | Path

    /** Combined file-attribute snapshot returned by [[Path.stat]].
      *
      * `lastModifiedMs` is the file's last-modified time in milliseconds since the Unix epoch.
      * `sizeBytes` is the file's size in bytes for regular files; the value for directories
      * and special files is platform-defined (typically 0 or the directory entry size).
      *
      * Returning both fields from a single underlying syscall guarantees the two values
      * reflect a consistent measurement of the file at one instant.
      */
    final case class PathStat(lastModifiedMs: Long, sizeBytes: Long) derives CanEqual

    /** Platform separator between path entries in classpath-style joined strings.
      *
      * Returns `":"` on Unix-family systems and `";"` on Windows. On Scala.js, forwards Node's `path.delimiter`.
      * Runtime-invariant; computed once at companion init.
      */
    val pathSeparator: String = platformPathSeparator

    /** Platform separator between segments of a single path.
      *
      * Returns `"/"` on Unix-family systems and `"\\"` on Windows. On Scala.js, forwards Node's `path.sep`.
      * Runtime-invariant; computed once at companion init.
      */
    val fileSeparator: String = platformFileSeparator

    // --- Construction ---

    /** Creates a Path from zero or more string-or-Path segments.
      *
      * Empty strings are dropped and `.`/`..` components are normalised by the platform implementation.
      *
      * {{{
      * val p = Path("usr", "local", "bin")
      * }}}
      */
    def apply(parts: Part*): Path =
        make(flattenParts(parts))

    /** Creates a path from a single segment (enables `Path / "a" / "b"` syntax starting from the companion). */
    infix def /(part: Path.Part)(using Frame): Path =
        make(flattenParts(Seq(part)))

    // --- Shared op family ---

    /** Reified filesystem operations. Read-group cases suspend under `Tag[PathRead]`, write-group
      * cases under `Tag[PathWrite]`; `Output = Id` (each case resumes with its raw `A`, no `Result`
      * wrapper, so a failing op short-circuits the runner through the residual `Abort[FileException]`).
      * One shared op family serves both capabilities: a class cannot extend `ArrowEffect` twice with
      * different inputs, and `PathWrite <: PathRead` inherits `PathRead`'s input constructor, so the read
      * operations are the read-group cases and the mutations are the write-group cases of this one enum.
      */
    private[kyo] enum Op[A]:
        // read-group (suspend under Tag[PathRead])
        case Exists(path: Path)                                        extends Op[Boolean]
        case ExistsFollow(path: Path, followLinks: Boolean)            extends Op[Boolean]
        case IsDirectory(path: Path)                                   extends Op[Boolean]
        case IsRegularFile(path: Path)                                 extends Op[Boolean]
        case IsSymbolicLink(path: Path)                                extends Op[Boolean]
        case RealPath(path: Path)                                      extends Op[Path]
        case Read(path: Path)                                          extends Op[String]
        case ReadCharset(path: Path, charset: Charset)                 extends Op[String]
        case ReadBytes(path: Path)                                     extends Op[Span[Byte]]
        case ReadLines(path: Path)                                     extends Op[Chunk[String]]
        case ReadLinesCharset(path: Path, charset: Charset)            extends Op[Chunk[String]]
        case Size(path: Path)                                          extends Op[Long]
        case Stat(path: Path)                                          extends Op[Path.PathStat]
        case ListDir(path: Path)                                       extends Op[Chunk[Path]]
        case ListGlob(path: Path, glob: String)                        extends Op[Chunk[Path]]
        case OpenRead(path: Path)                                      extends Op[Path.ReadHandle]
        case OpenReadLines(path: Path, charset: Charset)               extends Op[Path.LineReadHandle]
        case OpenWalk(path: Path, maxDepth: Int, followLinks: Boolean) extends Op[Path.WalkHandle]
        // write-group (suspend under Tag[PathWrite])
        case Write(path: Path, value: String, createFolders: Boolean)                                          extends Op[Unit]
        case WriteBytes(path: Path, value: Span[Byte], createFolders: Boolean)                                 extends Op[Unit]
        case WriteLines(path: Path, value: Chunk[String], createFolders: Boolean)                              extends Op[Unit]
        case Append(path: Path, value: String, createFolders: Boolean)                                         extends Op[Unit]
        case AppendBytes(path: Path, value: Span[Byte], createFolders: Boolean)                                extends Op[Unit]
        case AppendLines(path: Path, value: Chunk[String], createFolders: Boolean)                             extends Op[Unit]
        case Truncate(path: Path, size: Long)                                                                  extends Op[Unit]
        case SetLastModified(path: Path, epochMs: Long)                                                        extends Op[Unit]
        case MkDir(path: Path)                                                                                 extends Op[Unit]
        case MkFile(path: Path)                                                                                extends Op[Unit]
        case Move(from: Path, to: Path, replaceExisting: Boolean, atomicMove: Boolean, createFolders: Boolean) extends Op[Unit]
        case Copy(from: Path, to: Path, followLinks: Boolean, replaceExisting: Boolean, copyAttributes: Boolean, createFolders: Boolean)
            extends Op[Unit]
        case Remove(path: Path)                                                     extends Op[Boolean]
        case RemoveExisting(path: Path)                                             extends Op[Unit]
        case RemoveAll(path: Path)                                                  extends Op[Unit]
        case OpenWrite(path: Path, append: Boolean, createFolders: Boolean)         extends Op[Path.WriteHandle]
        case TempDir(prefix: String)                                                extends Op[Path.TempDirHandle]
        case WriteChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])               extends Op[Unit]
        case WriteString(handle: Path.WriteHandle, value: String, charset: Charset) extends Op[Unit]
    end Op

    // --- Disposition ---

    /** Write-durability contract a service declares. `AutoCommit`: a successful write is durable per
      * the backend's normal persistence contract (host, default in-memory). `CommitOnSuccess`: writes
      * stage during the enclosing run and commit on success (no in-scope backend in this release; the
      * case ships as an extension point). `ManualCommit`: writes stage until an explicit commit handle
      * call (overlay default). Adding a case is non-breaking; exhaustive match sites gain a compile
      * error rather than silently defaulting.
      */
    enum Disposition derives CanEqual:
        case AutoCommit
        case CommitOnSuccess
        case ManualCommit
    end Disposition

    // --- Service SPI ---

    /** The backend every safe `Path` operation dispatches through. Effect-polymorphic in `S` (the
      * backend's own effect, exposed on a runner residual, the Journal `Backend[S]` precedent);
      * methods take no `(using Frame)` (a service captures its `Frame` at construction). The umbrella
      * `Abort[FileException]` is the uniform error row. Implement this to provide a virtual filesystem
      * (in-memory, overlay); [[Service.host]] is the default host-filesystem backend.
      *
      * @tparam S
      *   the backend's own effect, propagated to the runner residual
      * @see
      *   [[Path.run]] and [[Path.runWith]] for the runners that install a service
      */
    trait Service[S]:
        def disposition: Disposition

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
    end Service

    object Service:
        /** Default host backend: delegates every op to [[Path.Unsafe]], translating the concrete
          * `Result[File*Exception, A]` into `Abort[FileException]`, so it preserves current `Path`
          * behavior exactly. Its disposition is `AutoCommit`.
          */
        def host(using Frame): Service[Sync] = new HostService

        /** Root-confined host backend: resolves `root.realPath` at construction and rejects any op whose
          * canonical path (following every symlink) escapes it; writes to missing entries validate the
          * nearest existing parent. Prefix-only checking without realpath is a security defect.
          */
        def host(root: Path)(using Frame): Service[Sync] < (Sync & Abort[FileException]) =
            // Unsafe: resolves the confinement root's real path once at construction
            Sync.Unsafe.defer(Abort.get(root.unsafe.realPath())).map(rootReal => new RootConfinedHostService(rootReal))

        /** In-memory backend: an immutable node tree keyed by `Path.parts` behind one `AtomicRef`,
          * advanced by an optimistic CAS loop. `isSymbolicLink` always returns `false`. Its disposition
          * is `AutoCommit`.
          */
        def inMemory(using Frame): Service[Sync] < Sync = InMemoryService.init

        /** Copy-on-write overlay over `lower`: reads fall through, writes stage in an upper layer, and
          * an explicit commit replays the staged journal onto `lower`. Scope-managed; `ManualCommit`.
          */
        def overlay[S](lower: Service[S])(using Frame): Overlay[S] < (Sync & Scope) =
            OverlayService.init(lower)

        /** An overlay's public face: a [[CommitHandle]] whose disposition is `ManualCommit`. */
        trait Overlay[S] extends CommitHandle[S]
    end Service

    final private class HostService(using Frame) extends Service[Sync]:
        val disposition: Path.Disposition = Path.Disposition.AutoCommit

        def exists(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.exists into the safe tier
            Sync.Unsafe.defer(path.unsafe.exists())
        def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.exists(followLinks) into the safe tier
            Sync.Unsafe.defer(path.unsafe.exists(followLinks))
        def isDirectory(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isDirectory into the safe tier
            Sync.Unsafe.defer(path.unsafe.isDirectory())
        def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isRegularFile into the safe tier
            Sync.Unsafe.defer(path.unsafe.isRegularFile())
        def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.isSymbolicLink into the safe tier
            Sync.Unsafe.defer(path.unsafe.isSymbolicLink())
        def realPath(path: Path): Path < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.realPath; the Result maps to Abort[FileException]
            Sync.Unsafe.defer(Abort.get(path.unsafe.realPath()))
        def read(path: Path): String < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.read into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read()))
        def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.read(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.read(charset)))
        def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readBytes()))
        def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines()))
        def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.readLines(charset) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.readLines(charset)))
        def size(path: Path): Long < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.size into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.size()))
        def stat(path: Path): Path.PathStat < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.stat into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.stat()))
        def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openRead into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openRead()))
        def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openReadLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openReadLines(charset)))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openWalk into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWalk(maxDepth, followLinks)))
        def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.write into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.write(value, createFolders)))
        def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.writeBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeBytes(value, createFolders)))
        def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.writeLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.writeLines(value, createFolders)))
        def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.append into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.append(value, createFolders)))
        def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.appendBytes into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendBytes(value, createFolders)))
        def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.appendLines into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.appendLines(value, createFolders)))
        def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.truncate into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.truncate(size)))
        def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.setLastModified into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.setLastModified(epochMs)))
        def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.openWrite into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(append, createFolders)))
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileException](handle.writeBytes(chunk)))
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
            // Unsafe: pumps a vended write handle into the safe tier
            Sync.Unsafe.defer(Abort.get[FileException](handle.writeString(value, charset)))
        def mkDir(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.mkDir into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkDir()))
        def mkFile(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.mkFile into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.mkFile()))
        def list(path: Path): Chunk[Path] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.list into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list()))
        def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.list(glob) into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.list(glob)))
        def move(
            from: Path,
            to: Path,
            replaceExisting: Boolean,
            atomicMove: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.move into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.move(to, replaceExisting, atomicMove, createFolders)))
        def copy(
            from: Path,
            to: Path,
            followLinks: Boolean,
            replaceExisting: Boolean,
            copyAttributes: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.copy into the safe tier
            Sync.Unsafe.defer(Abort.get(from.unsafe.copy(to, followLinks, replaceExisting, copyAttributes, createFolders)))
        def remove(path: Path): Boolean < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.remove into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.remove()))
        def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.removeExisting into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeExisting()))
        def removeAll(path: Path): Unit < (Sync & Abort[FileException]) =
            // Unsafe: bridges Path.Unsafe.removeAll into the safe tier
            Sync.Unsafe.defer(Abort.get(path.unsafe.removeAll()))
        def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) =
            tempDirUnscoped(prefix).map { dir =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the created temp dir at Scope exit
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
    end HostService

    final private class RootConfinedHostService(rootReal: Path)(using Frame) extends Service[Sync]:
        private val host                  = new HostService
        val disposition: Path.Disposition = Path.Disposition.AutoCommit

        private def confined(path: Path): Path < (Sync & Abort[FileException]) =
            // Unsafe: probes target existence to choose between realpath and nearest-parent checks
            Sync.Unsafe.defer(path.unsafe.exists()).map {
                case true  => Sync.Unsafe.defer(Abort.get(path.unsafe.realPath())).map(check)
                case false => nearestExistingParent(path).map(check).andThen(path)
            }
        private def check(real: Path): Path < Abort[FileException] =
            if real.parts.take(rootReal.parts.size) == rootReal.parts then real
            else Abort.fail(FileAccessDeniedException(real))
        private def nearestExistingParent(path: Path): Path < (Sync & Abort[FileException]) =
            path.parent match
                case Absent     => Abort.fail(FileNotFoundException(path))
                case Present(p) =>
                    // Unsafe: probes parent existence to walk to the nearest real ancestor
                    Sync.Unsafe.defer(p.unsafe.exists()).map {
                        case true  => Sync.Unsafe.defer(Abort.get(p.unsafe.realPath()))
                        case false => nearestExistingParent(p)
                    }

        def exists(path: Path): Boolean < (Sync & Abort[FileException]) = confined(path).andThen(host.exists(path))
        def exists(path: Path, followLinks: Boolean): Boolean < (Sync & Abort[FileException]) =
            confined(path).andThen(host.exists(path, followLinks))
        def isDirectory(path: Path): Boolean < (Sync & Abort[FileException])           = confined(path).andThen(host.isDirectory(path))
        def isRegularFile(path: Path): Boolean < (Sync & Abort[FileException])         = confined(path).andThen(host.isRegularFile(path))
        def isSymbolicLink(path: Path): Boolean < (Sync & Abort[FileException])        = confined(path).andThen(host.isSymbolicLink(path))
        def realPath(path: Path): Path < (Sync & Abort[FileException])                 = confined(path).andThen(host.realPath(path))
        def read(path: Path): String < (Sync & Abort[FileException])                   = confined(path).andThen(host.read(path))
        def read(path: Path, charset: Charset): String < (Sync & Abort[FileException]) = confined(path).andThen(host.read(path, charset))
        def readBytes(path: Path): Span[Byte] < (Sync & Abort[FileException])          = confined(path).andThen(host.readBytes(path))
        def readLines(path: Path): Chunk[String] < (Sync & Abort[FileException])       = confined(path).andThen(host.readLines(path))
        def readLines(path: Path, charset: Charset): Chunk[String] < (Sync & Abort[FileException]) =
            confined(path).andThen(host.readLines(path, charset))
        def size(path: Path): Long < (Sync & Abort[FileException])                = confined(path).andThen(host.size(path))
        def stat(path: Path): Path.PathStat < (Sync & Abort[FileException])       = confined(path).andThen(host.stat(path))
        def openRead(path: Path): Path.ReadHandle < (Sync & Abort[FileException]) = confined(path).andThen(host.openRead(path))
        def openReadLines(path: Path, charset: Charset): Path.LineReadHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openReadLines(path, charset))
        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean): Path.WalkHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openWalk(path, maxDepth, followLinks))
        def write(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.write(path, value, createFolders))
        def writeBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.writeBytes(path, value, createFolders))
        def writeLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.writeLines(path, value, createFolders))
        def append(path: Path, value: String, createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.append(path, value, createFolders))
        def appendBytes(path: Path, value: Span[Byte], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.appendBytes(path, value, createFolders))
        def appendLines(path: Path, value: Chunk[String], createFolders: Boolean): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.appendLines(path, value, createFolders))
        def truncate(path: Path, size: Long): Unit < (Sync & Abort[FileException]) = confined(path).andThen(host.truncate(path, size))
        def setLastModified(path: Path, epochMs: Long): Unit < (Sync & Abort[FileException]) =
            confined(path).andThen(host.setLastModified(path, epochMs))
        def openWrite(path: Path, append: Boolean, createFolders: Boolean): Path.WriteHandle < (Sync & Abort[FileException]) =
            confined(path).andThen(host.openWrite(path, append, createFolders))
        // writeChunk/writeString carry only a handle (no path), so confinement is not applicable; forward directly.
        def writeChunk(handle: Path.WriteHandle, chunk: Chunk[Byte]): Unit < (Sync & Abort[FileException]) = host.writeChunk(handle, chunk)
        def writeString(handle: Path.WriteHandle, value: String, charset: Charset): Unit < (Sync & Abort[FileException]) =
            host.writeString(handle, value, charset)
        def mkDir(path: Path): Unit < (Sync & Abort[FileException])                     = confined(path).andThen(host.mkDir(path))
        def mkFile(path: Path): Unit < (Sync & Abort[FileException])                    = confined(path).andThen(host.mkFile(path))
        def list(path: Path): Chunk[Path] < (Sync & Abort[FileException])               = confined(path).andThen(host.list(path))
        def list(path: Path, glob: String): Chunk[Path] < (Sync & Abort[FileException]) = confined(path).andThen(host.list(path, glob))
        def move(
            from: Path,
            to: Path,
            replaceExisting: Boolean,
            atomicMove: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            confined(from).andThen(confined(to)).andThen(host.move(from, to, replaceExisting, atomicMove, createFolders))
        def copy(
            from: Path,
            to: Path,
            followLinks: Boolean,
            replaceExisting: Boolean,
            copyAttributes: Boolean,
            createFolders: Boolean
        ): Unit < (Sync & Abort[FileException]) =
            confined(from).andThen(confined(to)).andThen(host.copy(from, to, followLinks, replaceExisting, copyAttributes, createFolders))
        def remove(path: Path): Boolean < (Sync & Abort[FileException])      = confined(path).andThen(host.remove(path))
        def removeExisting(path: Path): Unit < (Sync & Abort[FileException]) = confined(path).andThen(host.removeExisting(path))
        def removeAll(path: Path): Unit < (Sync & Abort[FileException])      = confined(path).andThen(host.removeAll(path))
        // tempDir creates inside rootReal, not in the OS temp dir. Creating within root keeps
        // staged paths confined: the overlay's commit protocol calls lower.move(stagingDir/eN.dat,
        // target) through this service; if stagingDir were in OS temp the confinement check on
        // the source path would fail. Creating in root also lets recoverFromDisk(root) scan for
        // kyo-commit-* dirs without cross-filesystem access. Uniqueness: nanoTime XOR identityHash
        // provides negligible collision probability for the expected number of concurrent commits.
        def tempDir(prefix: String): Path.TempDirHandle < (Sync & Abort[FileException]) =
            val uniqueSuffix =
                java.lang.Long.toHexString(java.lang.System.nanoTime() ^ java.lang.System.identityHashCode(this).toLong)
            val dir = rootReal / s"$prefix-$uniqueSuffix"
            host.mkDir(dir).map { _ =>
                new Path.TempDirHandle:
                    def path: Path = dir
                    // Unsafe: recursive host delete of the temp dir created within rootReal
                    def remove()(using AllowUnsafe): Unit = discard(dir.unsafe.removeAll())
            }
        end tempDir
    end RootConfinedHostService

    // --- Runners ---

    /** Runs `program`, discharging both write and read capabilities against the default host service.
      * Residual: `Sync & Abort[FileException] & S` (the caller's tail `S` rides through).
      */
    def run[A, S](program: A < (PathWrite & S))(using Frame): A < (Sync & Abort[FileException] & S) =
        runWith(Service.host)(program)

    /** Runs `program`, discharging the read capability only against the default host service. A write
      * op left in the program keeps `PathWrite` undischarged, so the ascribed read-only residual does
      * not compile (the negative capability law).
      */
    def runReadOnly[A, S](program: A < (PathRead & S))(using Frame): A < (Sync & Abort[FileException] & S) =
        runReadOnlyWith(Service.host)(program)

    /** Runs `program` against an explicit `service`, discharging write and read; the backend's own
      * effect `S` rides the residual (the Journal `Backend[S]` mapping).
      */
    def runWith[S, A, S2](service: Service[S])(program: A < (PathWrite & S2))(using Frame): A < (S & Abort[FileException] & S2) =
        ArrowEffect.handle(Tag[PathWrite], program)(
            [C] => (op, cont) => dispatch(service, op).map(cont)
        )

    /** Runs `program` against an explicit `service`, discharging the read capability only. */
    def runReadOnlyWith[S, A, S2](service: Service[S])(program: A < (PathRead & S2))(using Frame): A < (S & Abort[FileException] & S2) =
        ArrowEffect.handle(Tag[PathRead], program)(
            [C] => (op, cont) => dispatch(service, op).map(cont)
        )

    private def dispatch[S, C](service: Service[S], op: Op[C])(using Frame): C < (S & Abort[FileException]) =
        op match
            case Op.Exists(p)                  => service.exists(p)
            case Op.ExistsFollow(p, f)         => service.exists(p, f)
            case Op.IsDirectory(p)             => service.isDirectory(p)
            case Op.IsRegularFile(p)           => service.isRegularFile(p)
            case Op.IsSymbolicLink(p)          => service.isSymbolicLink(p)
            case Op.RealPath(p)                => service.realPath(p)
            case Op.Read(p)                    => service.read(p)
            case Op.ReadCharset(p, c)          => service.read(p, c)
            case Op.ReadBytes(p)               => service.readBytes(p)
            case Op.ReadLines(p)               => service.readLines(p)
            case Op.ReadLinesCharset(p, c)     => service.readLines(p, c)
            case Op.Size(p)                    => service.size(p)
            case Op.Stat(p)                    => service.stat(p)
            case Op.ListDir(p)                 => service.list(p)
            case Op.ListGlob(p, g)             => service.list(p, g)
            case Op.OpenRead(p)                => service.openRead(p)
            case Op.OpenReadLines(p, c)        => service.openReadLines(p, c)
            case Op.OpenWalk(p, d, f)          => service.openWalk(p, d, f)
            case Op.Write(p, v, cf)            => service.write(p, v, cf)
            case Op.WriteBytes(p, v, cf)       => service.writeBytes(p, v, cf)
            case Op.WriteLines(p, v, cf)       => service.writeLines(p, v, cf)
            case Op.Append(p, v, cf)           => service.append(p, v, cf)
            case Op.AppendBytes(p, v, cf)      => service.appendBytes(p, v, cf)
            case Op.AppendLines(p, v, cf)      => service.appendLines(p, v, cf)
            case Op.Truncate(p, s)             => service.truncate(p, s)
            case Op.SetLastModified(p, e)      => service.setLastModified(p, e)
            case Op.MkDir(p)                   => service.mkDir(p)
            case Op.MkFile(p)                  => service.mkFile(p)
            case Op.Move(f, t, re, am, cf)     => service.move(f, t, re, am, cf)
            case Op.Copy(f, t, fl, re, ca, cf) => service.copy(f, t, fl, re, ca, cf)
            case Op.Remove(p)                  => service.remove(p)
            case Op.RemoveExisting(p)          => service.removeExisting(p)
            case Op.RemoveAll(p)               => service.removeAll(p)
            case Op.OpenWrite(p, a, cf)        => service.openWrite(p, a, cf)
            case Op.TempDir(prefix)            => service.tempDir(prefix)
            case Op.WriteChunk(h, ch)          => service.writeChunk(h, ch)
            case Op.WriteString(h, s, c)       => service.writeString(h, s, c)
    end dispatch

    // --- Scoped tempDir ---

    /** Creates a temporary directory in the active service and registers its recursive removal with
      * the enclosing `Scope`. The removal runs through the service that created the directory (host:
      * real recursive delete; in-memory: map-subtree removal; overlay: upper-entry discard), so a temp
      * dir made by a virtual service is never deleted by a host-tier `removeAll`. There is no unscoped
      * public temp-directory primitive. The location of the created directory is service-defined:
      * unconfined host services use the OS temporary directory; root-confined host services create the
      * directory inside their root.
      */
    def tempDir(prefix: String = "kyo")(using Frame): Path < (PathWrite & Sync & Scope) =
        Scope.acquireRelease(
            ArrowEffect.suspend(Tag[PathWrite], Op.TempDir(prefix))
        )(handle => Sync.Unsafe.defer(handle.remove())) // Unsafe: service-vended recursive cleanup at Scope exit
            .map(_.path)

    /** A handle to a service-created temporary directory. Vended by [[Service.tempDir]] so the scoped
      * [[Path.tempDir]] finalizer removes through the creating service. Internal.
      */
    abstract private[kyo] class TempDirHandle:
        def path: Path
        def remove()(using AllowUnsafe): Unit
    end TempDirHandle

    /** A committed filesystem entry surfaced at commit time by [[Conflict]] (the live lower view and
      * the staged overlay view) and accepted as input by [[Resolution.Write]] (a caller-supplied
      * replacement entry for the conflicting path).
      *
      * Two cases: `File(bytes, stat)` carries the full byte content and stat metadata for a regular
      * file; `Directory(stat)` carries only the stat for a directory. Symlink entries are excluded
      * until `Path` grows public symlink operations.
      *
      * `Path.Entry` derives `CanEqual`. File content (a `Span[Byte]`) does not derive `CanEqual`,
      * so equality on `File` entries compares the `Span` reference, not the content. To compare
      * file bytes structurally use `bytes.toArrayUnsafe sameElements other.toArrayUnsafe`.
      */
    enum Entry derives CanEqual:
        case File(bytes: Span[Byte], stat: Path.PathStat)
        case Directory(stat: Path.PathStat)

    /** The base observation the overlay records for a lower entry at first sight, and the value
      * carried by [[Conflict.ancestor]]. It is exactly what the read-set stores so that a commit
      * can surface it without re-reading the lower: the observed entry kind, the size for a regular
      * file, the last-modified time where available, and a content hash only when the backend can
      * supply one cheaply.
      *
      * No bytes are retained. A `Stamp` is cheaper than a [[Path.Entry]] because it omits file
      * content; the read-set records one stamp per observed path, not the bytes, keeping overlay
      * memory cost proportional to the number of distinct paths touched rather than their content
      * size. A stamp is also the unit of divergence detection at commit: the commit compares each
      * stamped path against the live lower to decide whether a conflict exists.
      *
      * `contentHash` is an optional hook for backend-supplied fingerprints (for example, a block
      * hash from a content-addressed store); the base host backend leaves it `Absent`.
      */
    final case class Stamp(
        entryType: Stamp.Kind,
        size: Maybe[FileSize],
        lastModifiedMs: Maybe[Long],
        contentHash: Maybe[Span[Byte]]
    ) derives CanEqual

    object Stamp:
        /** The entry kind recorded by a [[Path.Stamp]] at the time the overlay first observed a
          * lower-layer path.
          *
          * Three cases: `File` means the path existed as a regular file when observed; `Directory`
          * means it existed as a directory; `Absent` means the path did not exist at observation time.
          *
          * Note the distinction between `Kind.Absent` and a `Maybe.Absent` [[Conflict.ancestor]]:
          * `Kind.Absent` stamps a path that WAS observed but happened to not exist at that moment;
          * `Maybe.Absent` ancestor means the path was NEVER read through the overlay at all, so no
          * stamp was ever recorded for it. Both can appear on a [[Conflict]], but their semantics
          * differ: `Kind.Absent` is a confirmed observation of absence; `Maybe.Absent` is a gap in
          * the read-set.
          */
        enum Kind derives CanEqual:
            case File, Directory, Absent
    end Stamp

    /** A [[Service]] extension whose writes stage locally until an explicit commit validates them
      * against the underlying live service. The overlay ([[Service.overlay]]) is the in-scope
      * implementation; its [[Service.disposition]] is `Disposition.ManualCommit`.
      *
      * Three commit strategies are available:
      *   - [[commit]]: validates the read-set against the live lower; aborts `CommitConflict` if
      *     any stamp has diverged, leaving the lower untouched.
      *   - [[commitOverwrite]]: replays unconditionally (last-writer-wins) with no conflict check.
      *   - [[commitWith]]: validates, then calls a caller-supplied `resolve` function for each
      *     conflict to obtain a [[Resolution]] before replaying the resolved entries.
      *
      * [[rollback]] discards all staged writes without touching the lower service. Calling any
      * write method after a commit or rollback is undefined behavior on the current implementation.
      */
    trait CommitHandle[S] extends Service[S]:
        /** Validates the read-set against the live lower service; replays if every stamp matches, else
          * aborts `CommitConflict` and leaves the lower service untouched.
          */
        def commit(using Frame): Unit < (S & Abort[FileException] & Abort[CommitConflict])

        /** Replays unconditionally (last-writer-wins); no `CommitConflict` in the row. */
        def commitOverwrite(using Frame): Unit < (S & Abort[FileException])

        /** Validates, calls `resolve` per conflict, replays the resolved journal. */
        def commitWith[S2](resolve: Conflict => Resolution < S2)(using Frame): Unit < (S & Abort[FileException] & S2)

        /** Discards staged writes; no error surface. */
        def rollback(using Frame): Unit < S
    end CommitHandle

    // --- Safe extension methods ---

    extension (self: Path)

        /** Returns the individual string components that make up this path. */
        def parts: Chunk[String] = self.parts

        /** Returns the final component of this path (the file or directory name).
          *
          * Returns `Absent` for a root or empty path.
          */
        def name: Maybe[String] =
            self.parts.lastMaybe match
                case Present(s) if s.nonEmpty => Present(s)
                case _                        => Absent

        /** Returns the parent path, or `Absent` if this is a root or single-component path. */
        def parent: Maybe[Path] =
            val ps = self.parts
            if ps.isEmpty || ps.size == 1 then Absent
            else Present(Path(ps.init*))
        end parent

        /** Lazily yields self, its parent, its grandparent, ..., up to and including the filesystem root.
          *
          * Use with `Stream.find` for "first ancestor where X" lookups (e.g., finding a project root
          * marker like `.git` or `build.sbt`). The stream is pure: it does not stat anything on disk.
          */
        def ancestors(using tag: Tag[Emit[Chunk[Path]]], frame: Frame): Stream[Path, Any] =
            @scala.annotation.tailrec
            def loop(cur: Path, acc: Chunk[Path]): Chunk[Path] =
                val next = acc.append(cur)
                cur.parent match
                    case Maybe.Present(parent) => loop(parent, next)
                    case Maybe.Absent          => next
            end loop
            Stream.init(loop(self, Chunk.empty))
        end ancestors

        /** Returns `true` if this path is absolute (begins at a filesystem root).
          *
          * Absolute paths are normalised to start with a leading `""` segment.
          */
        def isAbsolute: Boolean = self.isAbsolute

        /** Returns the file extension including the leading dot (e.g. `".gz"`), or `Absent` if none.
          *
          * A leading dot in the filename (dotfiles like `.gitignore`) is not treated as an extension.
          */
        def extName: Maybe[String] =
            self.parts.lastMaybe match
                case Absent => Absent
                case Present(name) =>
                    val dot = name.lastIndexOf('.')
                    if dot <= 0 then Absent else Present(name.substring(dot))

        /** Appends a single segment to this path. */
        infix def /(part: Path.Part)(using Frame): Path =
            make(self.parts ++ flattenParts(Seq(part)))

        // --- Inspection ---

        /** Returns `true` if this path exists in the file system (following symbolic links). */
        inline def exists(using inline frame: Frame): Boolean < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.Exists(self))

        /** Returns `true` if this path exists, optionally following symbolic links. */
        inline def exists(followLinks: Boolean)(using inline frame: Frame): Boolean < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ExistsFollow(self, followLinks))

        /** Returns `true` if this path is a directory. */
        inline def isDirectory(using inline frame: Frame): Boolean < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.IsDirectory(self))

        /** Returns `true` if this path is a regular file. */
        inline def isRegularFile(using inline frame: Frame): Boolean < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.IsRegularFile(self))

        /** Returns `true` if this path is a symbolic link. */
        inline def isSymbolicLink(using inline frame: Frame): Boolean < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.IsSymbolicLink(self))

        /** Returns the canonical absolute path with every symbolic link in the chain resolved.
          *
          * Fails with `FileNotFoundException` if any element of the path does not exist, or
          * `FileAccessDeniedException` if the filesystem denies access. Useful for safe
          * path-under-root validation: compare `path.realPath` against `root.realPath` instead
          * of relying on syntactic checks (which miss symlinks that point outside the root).
          */
        inline def realPath(using inline frame: Frame): Path < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPath(self))

        /** Returns this path resolved to its canonical real path, but only if that real path is contained
          * within `root` (after resolving `root`'s own symlinks).
          *
          * The check follows every symbolic link in both `self` and `root`, so a symlink inside `root`
          * pointing outside is rejected. The pure path-prefix comparison runs against the canonical parts
          * of both paths; a path equal to `root` is considered contained.
          *
          * Both `self` and `root` must exist; if either does not, fails with `FileNotFoundException`.
          * If `self`'s real path is outside `root`'s real path, fails with `FileAccessDeniedException`
          * carrying the offending real path.
          *
          * Useful for any tool that exposes a configured root and accepts user-supplied relative paths:
          * call `(root / userInput).confinedTo(root)` to obtain a path that is statically known to live
          * under the root, defending against symlink escapes.
          */
        def confinedTo(root: Path)(using Frame): Path < (PathRead & Abort[FileException]) =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPath(root)).map { rootReal =>
                ArrowEffect.suspend(Tag[PathRead], Path.Op.RealPath(self)).map { selfReal =>
                    if selfReal.parts.take(rootReal.parts.size) == rootReal.parts then selfReal
                    else Abort.fail(FileAccessDeniedException(selfReal))
                }
            }

        // --- Read ---

        /** Reads the entire file contents as a UTF-8 string. */
        inline def read(using inline frame: Frame): String < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.Read(self))

        /** Reads the entire file contents using the given charset. */
        inline def read(charset: Charset)(using inline frame: Frame): String < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadCharset(self, charset))

        /** Reads the entire file contents as a `Span[Byte]`. */
        inline def readBytes(using inline frame: Frame): Span[Byte] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadBytes(self))

        /** Returns the size in bytes of the regular file at this path.
          *
          * Fails with `FileReadException` if the path does not exist, is not a regular file, or the underlying read fails.
          */
        inline def size(using inline frame: Frame): Long < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(self))

        /** Returns mtime and size atomically from a single underlying syscall.
          *
          * Fails with `FileReadException` if the path does not exist, is not readable, or the underlying call fails.
          *
          * Prefer this over separate `lastModified` + `size` reads when both are needed:
          * a single syscall guarantees the two values reflect the same instant.
          */
        inline def stat(using inline frame: Frame): PathStat < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.Stat(self))

        /** Reads all lines from the file as a `Chunk[String]` (UTF-8). */
        inline def readLines(using inline frame: Frame): Chunk[String] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLines(self))

        /** Reads all lines from the file as a `Chunk[String]` using the given charset. */
        inline def readLines(charset: Charset)(using inline frame: Frame): Chunk[String] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ReadLinesCharset(self, charset))

        /** Streams the file contents as UTF-8 decoded strings (chunked by the platform buffer size). */
        def readStream(using Frame): Stream[String, PathRead & Scope & Sync] =
            readStream(StandardCharsets.UTF_8)

        /** Streams the file contents as decoded strings using the given charset. */
        def readStream(charset: Charset)(using Frame): Stream[String, PathRead & Scope & Sync] =
            readStream(charset, 8192)

        /** Streams the file contents as decoded strings using the given charset and read buffer size. */
        def readStream(charset: Charset, bufferSize: Int)(using Frame): Stream[String, PathRead & Scope & Sync] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenRead(self))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended read handle at Scope exit
                    val rawBuf      = new Array[Byte](bufferSize)
                    val decoder     = charset.newDecoder()
                    val maxTrailing = math.ceil(charset.newEncoder().maxBytesPerChar()).toInt
                    val inBuf =
                        java.nio.ByteBuffer.allocate(bufferSize + maxTrailing) // extra space for incomplete trailing multi-byte sequence
                    val outBuf = java.nio.CharBuffer.allocate(math.ceil(bufferSize * decoder.maxCharsPerByte()).toInt)
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            val result = handle.readChunk(rawBuf)
                            if result.isEof then
                                // End of file -- flush any bytes still held in inBuf
                                inBuf.flip()
                                outBuf.clear()
                                decoder.decode(inBuf, outBuf, true)
                                decoder.flush(outBuf)
                                outBuf.flip()
                                if outBuf.hasRemaining then
                                    Emit.valueWith(Chunk(outBuf.toString))(Loop.done)
                                else Loop.done
                            else
                                // Append new bytes after any leftover bytes from the previous read
                                inBuf.put(rawBuf, 0, result.bytesRead)
                                inBuf.flip()
                                outBuf.clear()
                                // false = not end-of-input; decoder leaves incomplete trailing sequences in inBuf
                                decoder.decode(inBuf, outBuf, false)
                                inBuf.compact() // leftover incomplete bytes slide to position 0
                                outBuf.flip()
                                if outBuf.hasRemaining then
                                    Emit.valueWith(Chunk(outBuf.toString))(Loop.continue)
                                else Loop.continue
                            end if
                        }
                    }
                }
            }

        /** Streams the raw bytes of the file. */
        def readBytesStream(using Frame): Stream[Byte, PathRead & Scope & Sync] =
            readBytesStream(8192)

        /** Streams the raw bytes of the file using the given read buffer size. */
        def readBytesStream(bufferSize: Int)(using Frame): Stream[Byte, PathRead & Scope & Sync] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenRead(self))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended read handle at Scope exit
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            val buf    = new Array[Byte](bufferSize)
                            val result = handle.readChunk(buf)
                            if result.isEof then Loop.done
                            else if result.bytesRead == bufferSize then
                                Emit.valueWith(Chunk.fromNoCopy(buf))(Loop.continue)
                            else
                                Emit.valueWith(Chunk.fromNoCopy(java.util.Arrays.copyOf(buf, result.bytesRead)))(Loop.continue)
                            end if
                        }
                    }
                }
            }

        /** Streams the file line-by-line as UTF-8 strings. */
        def readLinesStream(using Frame): Stream[String, PathRead & Scope & Sync] =
            readLinesStream(StandardCharsets.UTF_8)

        /** Streams the file line-by-line using the given charset. */
        def readLinesStream(charset: Charset)(using Frame): Stream[String, PathRead & Scope & Sync] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenReadLines(self, charset))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended read handle at Scope exit
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            handle.readLine() match
                                case Absent        => Loop.done
                                case Present(line) => Emit.valueWith(Chunk(line))(Loop.continue)
                        }
                    }
                }
            }

        /** Tails the file, emitting new lines as they are appended. Uses a 100ms default poll delay. */
        def tail(using Frame): Stream[String, PathRead & Async & Scope] =
            tail(100.millis)

        /** Tails the file, emitting new lines as they are appended, sleeping `pollDelay` between polls. */
        def tail(pollDelay: Duration)(using Frame): Stream[String, PathRead & Async & Scope] =
            tail(pollDelay, 8192)

        /** Tails the file, emitting new lines as they are appended, sleeping `pollDelay` between polls, using the given read buffer size.
          */
        def tail(pollDelay: Duration, bufferSize: Int)(using Frame): Stream[String, PathRead & Async & Scope] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenRead(self))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended read handle at Scope exit
                    // Seek to end first, then poll for new content
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(self)).map { fileSize =>
                        Sync.Unsafe.defer {
                            handle.position(fileSize)
                            val buf = new Array[Byte](bufferSize)
                            // State: (file position, leftover bytes from incomplete UTF-8, pending incomplete line text)
                            val emptyBytes = new Array[Byte](0)
                            Loop((fileSize, emptyBytes, "")) { case (pos, leftover, pending) =>
                                Sync.Unsafe.defer {
                                    val result = handle.readChunk(buf)
                                    if result.isEof then
                                        ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(self)).map { currentSize =>
                                            if currentSize < pos then
                                                // File was truncated -- reset to beginning
                                                Sync.Unsafe.defer(handle.position(0L))
                                                    .andThen(Loop.continue((0L, emptyBytes, "")))
                                            else
                                                Async.sleep(pollDelay)
                                                    .andThen(Loop.continue((pos, leftover, pending)))
                                        }
                                    else
                                        val n = result.bytesRead
                                        // Combine leftover bytes from previous read with new bytes
                                        val allBytes =
                                            if leftover.isEmpty then java.util.Arrays.copyOf(buf, n)
                                            else
                                                val combined = new Array[Byte](leftover.length + n)
                                                java.lang.System.arraycopy(leftover, 0, combined, 0, leftover.length)
                                                java.lang.System.arraycopy(buf, 0, combined, leftover.length, n)
                                                combined
                                        // Find how many trailing bytes form an incomplete UTF-8 sequence
                                        val incomplete = incompleteUtf8Tail(allBytes, allBytes.length)
                                        val decodeLen  = allBytes.length - incomplete
                                        val newLeftover =
                                            if incomplete > 0 then java.util.Arrays.copyOfRange(allBytes, decodeLen, allBytes.length)
                                            else emptyBytes
                                        val text  = pending + new String(allBytes, 0, decodeLen, StandardCharsets.UTF_8)
                                        val parts = text.split("\r?\n", -1).toList
                                        val (toEmit, newPending) =
                                            if text.endsWith("\n") then (parts.dropRight(1), "")
                                            else (parts.dropRight(1), parts.last)
                                        if toEmit.isEmpty then Loop.continue((pos + n, newLeftover, newPending))
                                        else Emit.valueWith(Chunk.from(toEmit))(Loop.continue((pos + n, newLeftover, newPending)))
                                    end if
                                }
                            }
                        }
                    }
                }
            }

        // --- Write ---

        /** Writes `value` to the file, creating parent directories when `createFolders = true` (the default). */
        inline def write(value: String, createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Write(self, value, createFolders))

        /** Writes raw bytes to the file. */
        inline def writeBytes(value: Span[Byte], createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteBytes(self, value, createFolders))

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        inline def writeLines(value: Chunk[String], createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteLines(self, value, createFolders))

        /** Appends `value` to the file, creating parent directories when `createFolders = true`. */
        inline def append(value: String, createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Append(self, value, createFolders))

        /** Appends raw bytes to the file. */
        inline def appendBytes(value: Span[Byte], createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendBytes(self, value, createFolders))

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        inline def appendLines(value: Chunk[String], createFolders: Boolean = true)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendLines(self, value, createFolders))

        /** Truncates the file to at most `size` bytes. */
        inline def truncate(size: Long)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Truncate(self, size))

        /** Sets the last-modified time of the file to `epochMs` milliseconds since the Unix epoch.
          *
          * Fails with `FileWriteException` if the path does not exist or the operation is not permitted.
          */
        inline def setLastModified(epochMs: Long)(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.SetLastModified(self, epochMs))

        // --- Directory / structure ---

        /** Creates this path as a directory (including all missing parent directories). */
        inline def mkDir(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkDir(self))

        /** Creates this path as an empty file (parent directories created if missing). */
        inline def mkFile(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.MkFile(self))

        /** Lists all direct children of this directory. */
        inline def list(using inline frame: Frame): Chunk[Path] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ListDir(self))

        /** Lists direct children of this directory whose names match `glob`. */
        inline def list(glob: String)(using inline frame: Frame): Chunk[Path] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ListGlob(self, glob))

        /** Streams all entries under this directory tree (unlimited depth, not following links). */
        def walk(using Frame): Stream[Path, PathRead & Scope & Sync] =
            walk(Int.MaxValue, followLinks = false)

        /** Streams all entries under this directory tree up to `maxDepth`, optionally following symbolic links. */
        def walk(maxDepth: Int = Int.MaxValue, followLinks: Boolean = false)(using
            Frame
        ): Stream[Path, PathRead & Scope & Sync] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenWalk(self, maxDepth, followLinks))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended walk handle at Scope exit
                    Loop.foreach {
                        Sync.Unsafe.defer {
                            handle.next() match
                                case Absent        => Loop.done
                                case Present(path) => Emit.valueWith(Chunk(path))(Loop.continue)
                        }
                    }
                }
            }

        /** Moves this path to `to`. */
        inline def move(
            to: Path,
            replaceExisting: Boolean = false,
            atomicMove: Boolean = false,
            createFolders: Boolean = true
        )(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Move(self, to, replaceExisting, atomicMove, createFolders))

        /** Copies this path to `to`. */
        inline def copy(
            to: Path,
            followLinks: Boolean = true,
            replaceExisting: Boolean = false,
            copyAttributes: Boolean = false,
            createFolders: Boolean = true
        )(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Copy(self, to, followLinks, replaceExisting, copyAttributes, createFolders))

        /** Deletes this path if it exists. Returns `true` if it was deleted, `false` if it did not exist. */
        inline def remove(using inline frame: Frame): Boolean < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Remove(self))

        /** Deletes this path, raising `FileNotFoundException` if it does not exist. */
        inline def removeExisting(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveExisting(self))

        /** Recursively deletes this path and all of its contents. */
        inline def removeAll(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveAll(self))

        /** Returns the underlying `Unsafe` implementation for direct use in unsafe code. */
        def unsafe: Path.Unsafe = self

    end extension

    // --- System directories ---

    /** The current working directory of the JVM (JVM/Native) or Node process (JS).
      *
      * Reads at call time, so subsequent `process.chdir` (or test fixtures that fork with a
      * different working dir) take effect on the next access. Use with `path.ancestors` for
      * "find the project root containing X" style lookups.
      */
    def cwd(using Frame): Path < Sync = Sync.Unsafe.defer(cwdPath)

    /** Well-known base directories for the current OS (cache, config, data, etc.). */
    lazy val basePaths: BasePaths = platformBasePaths

    /** Well-known user directories (home, desktop, downloads, etc.). */
    lazy val userPaths: UserPaths = platformUserPaths

    /** Per-project directories derived from a `(qualifier, organization, application)` triple. */
    def projectPaths(qualifier: String, organization: String, application: String): ProjectPaths =
        platformProjectPaths(qualifier, organization, application)

    /** OS base directories. */
    case class BasePaths(
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        executable: Path,
        preference: Path,
        runtime: Path,
        tmp: Path
    ) derives CanEqual

    /** User home directories. */
    case class UserPaths(
        home: Path,
        audio: Path,
        desktop: Path,
        document: Path,
        download: Path,
        font: Path,
        picture: Path,
        public: Path,
        template: Path,
        video: Path
    ) derives CanEqual

    /** Per-application project directories. */
    case class ProjectPaths(
        path: Path,
        cache: Path,
        config: Path,
        data: Path,
        dataLocal: Path,
        preference: Path,
        runtime: Path
    ) derives CanEqual

    /** Returns the number of trailing bytes that form an incomplete UTF-8 sequence. */
    private def incompleteUtf8Tail(bytes: Array[Byte], len: Int): Int =
        // Scan backwards from the end for a leading byte (11xxxxxx or 0xxxxxxx)
        var i = len - 1
        // Skip continuation bytes (10xxxxxx)
        while i >= 0 && (bytes(i) & 0xc0) == 0x80 do i -= 1
        if i < 0 then 0 // all continuation bytes -- shouldn't happen
        else
            val leading  = bytes(i)
            val startPos = i
            val tailLen  = len - startPos
            // Determine expected sequence length from leading byte
            val expected =
                if (leading & 0x80) == 0 then 1         // 0xxxxxxx -- ASCII
                else if (leading & 0xe0) == 0xc0 then 2 // 110xxxxx
                else if (leading & 0xf0) == 0xe0 then 3 // 1110xxxx
                else if (leading & 0xf8) == 0xf0 then 4 // 11110xxx
                else 1                                  // invalid leading byte, treat as complete
            if tailLen < expected then tailLen else 0
        end if
    end incompleteUtf8Tail

    /** Flattens a sequence of `Part` values into a `Chunk[String]`. */
    private[kyo] def flattenParts(parts: Seq[Part]): Chunk[String] =
        Chunk.from(parts.flatMap {
            case s: String => s.split("[/\\\\]", -1).toSeq // Split on both / and \ for cross-platform support
            case p: Path   => p.parts.toSeq
        })

    // --- Abstract Unsafe class ---

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        // --- Pure accessors (no AllowUnsafe needed) ---

        /** The individual string components of this path. */
        def parts: Chunk[String]

        /** Human-readable string representation of this path. */
        def show: String

        /** Returns `true` if this path is absolute (begins at a filesystem root). */
        def isAbsolute: Boolean

        /** Best-effort sync of the directory itself so that newly-created or renamed
          * children have their directory entries flushed to stable storage. Swallowed
          * silently on platforms that do not support directory fsync (Windows). The
          * default is a no-op; platform implementations override it.
          */
        private[kyo] def syncDir()(using AllowUnsafe): Unit = ()

        /** Returns the human-readable representation; delegates to `show` so Path values display correctly. */
        override def toString: String = show

        // --- Inspection ---

        def exists()(using AllowUnsafe): Boolean
        def exists(followLinks: Boolean)(using AllowUnsafe): Boolean
        def isDirectory()(using AllowUnsafe): Boolean
        def isRegularFile()(using AllowUnsafe): Boolean
        def isSymbolicLink()(using AllowUnsafe): Boolean
        def realPath()(using AllowUnsafe, Frame): Result[FileException, Path]

        // --- Read ---

        def read()(using AllowUnsafe, Frame): Result[FileReadException, String]
        def read(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, String]
        def readBytes()(using AllowUnsafe, Frame): Result[FileReadException, Span[Byte]]
        def readLines()(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]]
        def readLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Chunk[String]]

        // --- Streaming read handles (abstract -- platform provides the concrete handles) ---

        def openRead()(using AllowUnsafe, Frame): Result[FileReadException, Path.ReadHandle]
        def openReadLines(charset: Charset)(using AllowUnsafe, Frame): Result[FileReadException, Path.LineReadHandle]
        def size()(using AllowUnsafe, Frame): Result[FileReadException, Long]
        def stat()(using AllowUnsafe, Frame): Result[FileReadException, PathStat]

        // --- Write ---

        def write(value: String, createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def writeBytes(value: Span[Byte], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def writeLines(value: Chunk[String], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def append(value: String, createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def appendBytes(value: Span[Byte], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def appendLines(value: Chunk[String], createFolders: Boolean = true)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        // --- Directory / structure ---

        def list()(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]]
        def list(glob: String)(using AllowUnsafe, Frame): Result[FileFsException, Chunk[Path]]
        def mkDir()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def mkFile()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def move(to: Path, replaceExisting: Boolean = false, atomicMove: Boolean = false, createFolders: Boolean = true)(using
            AllowUnsafe,
            Frame
        ): Result[FileFsException, Unit]
        def copy(
            to: Path,
            followLinks: Boolean = true,
            replaceExisting: Boolean = false,
            copyAttributes: Boolean = false,
            createFolders: Boolean = true
        )(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def remove()(using AllowUnsafe, Frame): Result[FileFsException, Boolean]
        def removeExisting()(using AllowUnsafe, Frame): Result[FileFsException, Unit]
        def removeAll()(using AllowUnsafe, Frame): Result[FileFsException, Unit]

        // --- Walk handle (abstract -- platform provides the resource management) ---

        def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileFsException, Path.WalkHandle]

        // --- Streaming write handles ---

        /** Opens a write handle for streaming byte or string output. The caller must close the handle via `Scope.acquireRelease`. */
        def openWrite(append: Boolean, createFolders: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle]

        /** Lifts this `Unsafe` value back into the safe `Path` opaque type. */
        def safe: Path = this

    end Unsafe

    // --- WriteHandle -- abstraction for open write channels ---

    /** An open write channel returned by `Path.Unsafe.openWrite`. Platform implementations provide the concrete class. */
    abstract private[kyo] class WriteHandle:
        /** Writes a chunk of bytes to the channel. */
        def writeBytes(chunk: Chunk[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Writes a string to the channel using the given charset. */
        def writeString(s: String, charset: Charset)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Marks the written content complete. On filesystem-backed handles, also flushes all written
          * bytes to stable storage before returning (fsync). If `close()` runs without a prior
          * `finish()`, the partial entry is removed (the delete-on-failure contract the stream sinks
          * rely on).
          */
        def finish()(using AllowUnsafe): Unit

        /** Closes the channel, releasing all OS resources. Contract: if `finish()` was never called, remove the partial entry. */
        def close()(using AllowUnsafe): Unit
    end WriteHandle

    // --- Read handles -- returned by Path.Unsafe.openRead / openReadLines ---

    /** The result of a `ReadHandle.readChunk` call -- either a positive byte count or EOF. */
    opaque type ReadResult = Int

    object ReadResult:
        /** End of file -- no more data will be produced. */
        val Eof: ReadResult = -1

        /** Wraps a raw byte count (from `InputStream.read` or `FileChannel.read`) into a `ReadResult`. */
        def apply(n: Int): ReadResult = n

        extension (self: ReadResult)
            /** `true` when the stream has reached end-of-file. */
            def isEof: Boolean = self <= 0

            /** The number of bytes read, or 0 if EOF. */
            def bytesRead: Int = if self <= 0 then 0 else self
        end extension
    end ReadResult

    /** An open read channel returned by `Path.Unsafe.openRead`. Platform implementations provide the concrete class. */
    abstract private[kyo] class ReadHandle:
        /** Reads up to `buffer.length` bytes into `buffer`. Returns a `ReadResult` -- either `Eof` or a positive byte count. */
        def readChunk(buffer: Array[Byte])(using AllowUnsafe): ReadResult

        /** Sets the channel position to `offset` bytes from the start of the file. */
        def position(offset: Long)(using AllowUnsafe): Unit

        /** Closes the channel, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end ReadHandle

    /** An open buffered line reader returned by `Path.Unsafe.openReadLines`. Platform implementations provide the concrete class. */
    abstract private[kyo] class LineReadHandle:
        /** Reads the next line. Returns `Absent` at EOF. */
        def readLine()(using AllowUnsafe): Maybe[String]

        /** Closes the reader, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end LineReadHandle

    /** An open directory walker returned by `Path.Unsafe.openWalk`. Platform implementations provide the concrete class. */
    abstract private[kyo] class WalkHandle:
        /** Returns the next path in the walk, or `Absent` when exhausted. */
        def next()(using AllowUnsafe): Maybe[Path]

        /** Closes the walker, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end WalkHandle

end Path

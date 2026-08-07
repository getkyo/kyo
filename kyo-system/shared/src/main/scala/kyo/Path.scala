package kyo

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kyo.internal.PathPlatformSpecific

/** A cross-platform, immutable file-system path with effect-tracked I/O.
  *
  * Path provides a unified API for file operations across JVM, Scala.js (Node.js), and Scala Native. Every I/O operation is tracked in the
  * type system via capability effects: reads carry `PathRead` and writes carry `PathWrite`. A runner (`Path.run`, `Path.runReadOnly`,
  * `Path.runWith`, or `Path.runReadOnlyWith`) discharges the capability and leaves `Sync & Abort[FileSystemException]` as the residual.
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
  * val result: String < (Sync & Abort[FileSystemException]) = Path.runReadOnly(content)
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
  *   [[FileSystemException]] for the typed error hierarchy
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
  * `Sync` and the `Abort[FileSystemException]` umbrella are folded into the capability and become visible
  * only after a runner discharges it. Discharge with [[Path.runReadOnly]] (read-only) or
  * [[Path.run]] (read and write). `PathWrite <: PathRead`: a write-capable context also satisfies
  * reads, and a read-only runner rejects write programs at the call site.
  *
  * @see
  *   [[Path.run]], [[Path.runReadOnly]], [[Path.runWith]], [[Path.runReadOnlyWith]] for the runners
  * @see
  *   [[FileSystem]] for the pluggable backend the runners install
  */
sealed trait PathRead extends ArrowEffect[[A] =>> Path.Op[A], Id]

/** Write capability for the file system: writes, appends, `truncate`, `mkDir`, `mkFile`, `move`,
  * `copy`, `remove`, and the scoped `tempDir`. Because `PathWrite <: PathRead`, a write-capable
  * context also satisfies read operations, a mixed read plus write program's row collapses to
  * `PathWrite`, and [[Path.runReadOnly]] rejects a program containing a write at the call site.
  *
  * Discharge with [[Path.run]] or [[Path.runWith]]; only these runners and the staged-write
  * combinators, which install a temporary overlay, can satisfy `PathWrite`
  * in a program's row.
  *
  * @see
  *   [[PathRead]] for the read capability this extends
  * @see
  *   [[Path.stageWrites]], [[Path.commitWritesOnSuccess]], [[Path.discardWrites]] for staged writes
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

    /** Configures file writes and appends.
      *
      * `createFolders` creates missing parent directories when enabled. When disabled, an absent
      * parent fails with [[FileNotFoundException]] and the target remains absent.
      *
      * @param createFolders
      *   whether missing parent directories may be created
      */
    final case class WriteOptions(createFolders: Boolean = true) derives CanEqual

    /** Selects whether a move or copy may replace its target.
      *
      * [[Never]] fails with [[FileAlreadyExistsException]] when the target exists. [[Existing]]
      * replaces an existing target according to the backend's normal move or copy semantics.
      *
      * This policy is shared by [[MoveOptions]] and [[CopyOptions]].
      */
    enum Replace derives CanEqual:
        case Never
        case Existing

    /** Selects the atomicity guarantee required from a move.
      *
      * [[Allowed]] permits the backend's normal move operation. [[Required]] requires the backend
      * or platform to guarantee an atomic move and otherwise fails with
      * [[FileAtomicMoveUnsupportedException]] before changing either path.
      *
      * This policy applies only to [[MoveOptions]].
      */
    enum Atomicity derives CanEqual:
        case Allowed
        case Required

    /** Configures a move operation.
      *
      * Replacement and atomicity are explicit policies. Parent directories are created by default.
      *
      * @param replace
      *   target replacement policy
      * @param atomicity
      *   required move atomicity
      * @param createFolders
      *   whether missing target parents may be created
      */
    final case class MoveOptions(
        replace: Replace = Replace.Never,
        atomicity: Atomicity = Atomicity.Allowed,
        createFolders: Boolean = true
    ) derives CanEqual

    /** Configures a copy operation.
      *
      * Link following, replacement, attribute copying, and parent creation are carried together so
      * call sites do not rely on positional Boolean arguments.
      *
      * @param followLinks
      *   whether symbolic links are followed
      * @param replace
      *   target replacement policy
      * @param copyAttributes
      *   whether supported source attributes are copied
      * @param createFolders
      *   whether missing target parents may be created
      */
    final case class CopyOptions(
        followLinks: Boolean = true,
        replace: Replace = Replace.Never,
        copyAttributes: Boolean = false,
        createFolders: Boolean = true
    ) derives CanEqual

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
      * wrapper, so a failing op short-circuits the runner through the residual `Abort[FileSystemException]`).
      * One shared op family serves both capabilities: a class cannot extend `ArrowEffect` twice with
      * different inputs, and `PathWrite <: PathRead` inherits `PathRead`'s input constructor, so the read
      * operations are the read-group cases and the mutations are the write-group cases of this one enum.
      */
    private[kyo] enum Op[A]:
        // read-group (suspend under Tag[PathRead])
        case Exists(path: Path)                                                             extends Op[Boolean]
        case ExistsFollow(path: Path, followLinks: Boolean)                                 extends Op[Boolean]
        case IsDirectory(path: Path)                                                        extends Op[Boolean]
        case IsRegularFile(path: Path)                                                      extends Op[Boolean]
        case IsSymbolicLink(path: Path)                                                     extends Op[Boolean]
        case RealPath(path: Path)                                                           extends Op[Path]
        case RealPathPrefix(path: Path)                                                     extends Op[Path]
        case Read(path: Path)                                                               extends Op[String]
        case ReadCharset(path: Path, charset: Charset)                                      extends Op[String]
        case ReadBytes(path: Path)                                                          extends Op[Span[Byte]]
        case ReadLines(path: Path)                                                          extends Op[Chunk[String]]
        case ReadLinesCharset(path: Path, charset: Charset)                                 extends Op[Chunk[String]]
        case Size(path: Path)                                                               extends Op[Long]
        case Stat(path: Path)                                                               extends Op[Path.PathStat]
        case ListDir(path: Path)                                                            extends Op[Chunk[Path]]
        case ListGlob(path: Path, glob: Glob, caseSensitivity: Maybe[Glob.CaseSensitivity]) extends Op[Chunk[Path]]
        case DefaultCaseSensitivity()                                                       extends Op[Glob.CaseSensitivity]
        case OpenRead(path: Path)                                                           extends Op[Path.ReadHandle]
        case OpenReadLines(path: Path, charset: Charset)                                    extends Op[Path.LineReadHandle]
        case OpenWalk(path: Path, maxDepth: Int, followLinks: Boolean)                      extends Op[Path.WalkHandle]
        case CurrentReadService()                                                           extends Op[FileSystem.Read[Any]]
        case Raise(error: Result.Error[FileSystemException])                                extends Op[Nothing]
        // write-group (suspend under Tag[PathWrite])
        case Write(path: Path, value: String, options: WriteOptions)                extends Op[Unit]
        case WriteBytes(path: Path, value: Span[Byte], options: WriteOptions)       extends Op[Unit]
        case WriteLines(path: Path, value: Chunk[String], options: WriteOptions)    extends Op[Unit]
        case Append(path: Path, value: String, options: WriteOptions)               extends Op[Unit]
        case AppendBytes(path: Path, value: Span[Byte], options: WriteOptions)      extends Op[Unit]
        case AppendLines(path: Path, value: Chunk[String], options: WriteOptions)   extends Op[Unit]
        case Truncate(path: Path, size: Long)                                       extends Op[Unit]
        case SetLastModified(path: Path, epochMs: Long)                             extends Op[Unit]
        case MkDir(path: Path)                                                      extends Op[Unit]
        case MkFile(path: Path)                                                     extends Op[Unit]
        case Move(from: Path, to: Path, options: MoveOptions)                       extends Op[Unit]
        case Copy(from: Path, to: Path, options: CopyOptions)                       extends Op[Unit]
        case Remove(path: Path)                                                     extends Op[Boolean]
        case RemoveExisting(path: Path)                                             extends Op[Unit]
        case RemoveAll(path: Path)                                                  extends Op[Unit]
        case SyncDirectory(path: Path)                                              extends Op[Unit]
        case SiblingTemporary(path: Path)                                           extends Op[Path.TempFileHandle]
        case DurableReplace(path: Path, bytes: Span[Byte])                          extends Op[Unit]
        case OpenWrite(path: Path, append: Boolean, options: WriteOptions)          extends Op[Path.WriteHandle]
        case TempDir(prefix: String)                                                extends Op[Path.TempDirHandle]
        case WriteChunk(handle: Path.WriteHandle, chunk: Chunk[Byte])               extends Op[Unit]
        case WriteString(handle: Path.WriteHandle, value: String, charset: Charset) extends Op[Unit]
    end Op

    private[kyo] enum WatchOp[A]:
        case Open(path: Path, options: WatchOptions)                                        extends WatchOp[Watcher]
        case OpenWith(fileSystem: FileSystem.Watch[Any], path: Path, options: WatchOptions) extends WatchOp[Watcher]
        case Raise(error: Result.Error[FileWatchException])                                 extends WatchOp[Nothing]
    end WatchOp

    // --- Runners ---

    /** Runs `program`, discharging both write and read capabilities against the Local-selected
      * [[FileSystem]]. Every filesystem op inside `program` suspends under `PathWrite` or
      * `PathRead` and is dispatched to that backend; the residual folds the per-op
      * `Abort[File*Exception]` markers into the umbrella `Abort[FileSystemException]`.
      *
      * Residual: `Sync & Abort[FileSystemException] & S` (the caller's tail `S` rides through).
      *
      * @see
      *   [[runWith]] to install a custom [[FileSystem]] (in-memory, overlay, root-confined host)
      */
    def run[A, S](program: A < (PathWrite & S))(using Frame): A < (Sync & Abort[FileSystemException] & S) =
        FileSystem.useErased(service => runWith(service)(program))

    /** Runs `program`, discharging the read capability only against the Local-selected service. A write
      * op left in the program keeps `PathWrite` undischarged, so the ascribed read-only residual does
      * not compile (the negative capability law).
      *
      * Use this at API boundaries that must not mutate the filesystem (read-only config loaders,
      * projection rebuilds). The effect row is identical to [[run]] except that write ops are rejected
      * at the call site rather than at runtime.
      *
      * @see
      *   [[runReadOnlyWith]] to install a custom read-only [[FileSystem]]
      */
    def runReadOnly[A, S](program: A < (PathRead & S))(using Frame): A < (Sync & Abort[FileSystemException] & S) =
        FileSystem.useReadErased(service => runReadOnlyWith(service)(program))

    /** Runs `program` against an explicit `fileSystem`, discharging write and read; the backend's own
      * effect `FS` rides the residual (the Journal `Backend[S]` mapping).
      *
      * Install [[FileSystem.inMemory]] for hermetic tests, [[FileSystem.overlay]] (wrapped in `Scope`)
      * for copy-on-write staging, or [[FileSystem.host]](root) for root-confined host I/O. The
      * selected service determines when writes become durable relative to the enclosing run.
      */
    def runWith[A, S, FS](fileSystem: FileSystem.Write[FS])(program: A < (PathWrite & S))(using
        Frame
    ): A < (FS & Abort[FileSystemException] & S) =
        FileSystem.letErased(fileSystem) {
            ArrowEffect.handle[[A] =>> Op[A], Id, PathWrite, A, S, FS & Abort[FileSystemException]](Tag[PathWrite], program)(
                [C] => (op, cont) => dispatch(fileSystem, op).map(cont)
            )
        }

    /** Runs `program` against an explicit `service`, discharging the read capability only.
      *
      * Same negative-capability law as [[runReadOnly]]: a write op in `program` keeps `PathWrite`
      * undischarged and fails to compile. The service's effect `FS` and the caller's tail `S` both
      * ride the residual unchanged.
      */
    def runReadOnlyWith[A, S, FS](fileSystem: FileSystem.Read[FS])(program: A < (PathRead & S))(using
        Frame
    ): A < (FS & Abort[FileSystemException] & S) =
        FileSystem.letReadErased(fileSystem) {
            ArrowEffect.handle[[A] =>> Op[A], Id, PathRead, A, S, FS & Abort[FileSystemException]](Tag[PathRead], program)(
                [C] => (op, cont) => dispatchRead(fileSystem, op).map(cont)
            )
        }

    /** Runs `program` against an explicit watch-capable filesystem, discharging [[PathWatch]]. */
    def runWatchWith[A, S, FS](fileSystem: FileSystem.Watch[FS])(program: A < (PathWatch & S))(using
        Frame
    ): A < (FS & Async & Scope & Abort[FileWatchException] & S) =
        ArrowEffect.handle[[A] =>> WatchOp[A], Id, PathWatch, A, S, FS & Async & Scope & Abort[FileWatchException]](
            Tag[PathWatch],
            program
        )(
            [C] =>
                (op, cont) =>
                    op match
                        case WatchOp.Open(path, options)               => fileSystem.openWatcher(path, options).map(cont)
                        case WatchOp.OpenWith(selected, path, options) => selected.openWatcher(path, options).map(cont)
                        case WatchOp.Raise(error)                      => Abort.error(error)
        )

    /** Runs `program`, discharging [[PathWatch]] against the Local-selected watch backend. */
    def runWatch[A, S](program: A < (PathWatch & S))(using
        Frame
    ): A < (Async & Scope & Abort[FileWatchException] & S) =
        ArrowEffect.handle[[A] =>> WatchOp[A], Id, PathWatch, A, S, Async & Scope & Abort[FileWatchException]](
            Tag[PathWatch],
            program
        )(
            [C] =>
                (op, cont) =>
                    op match
                        case WatchOp.Open(path, options) =>
                            FileSystem.useWatchErased(_.openWatcher(path, options)).map(cont)
                        case WatchOp.OpenWith(fileSystem, path, options) => fileSystem.openWatcher(path, options).map(cont)
                        case WatchOp.Raise(error)                        => Abort.error(error)
        )

    /** Stateless isolation for read operations. Each child captures the Local-selected backend and
      * installs an independent Path handler around its computation.
      */
    given isolateRead: Isolate[PathRead, Async, PathRead] with
        type State        = FileSystem.Read[Any]
        type Transform[A] = Result[FileSystemException, A]

        def capture[A, S](f: State => A < S)(using Frame): A < (PathRead & Async & S) =
            FileSystem.useReadErased(f)

        def isolate[A, S](state: State, value: A < (S & PathRead))(using Frame): Result[FileSystemException, A] < (Async & S) =
            Abort.run[FileSystemException](runReadOnlyWith(state)(value))

        def restore[A, S](value: Result[FileSystemException, A] < S)(using Frame): A < (PathRead & S) =
            value.map {
                case Result.Success(result) => result
                case Result.Failure(error) =>
                    ArrowEffect.suspend(Tag[PathRead], Op.Raise(Result.Failure(error)))
                case panic: Result.Panic =>
                    ArrowEffect.suspend(Tag[PathRead], Op.Raise(panic))
            }
    end isolateRead

    /** Stateless isolation for write operations. See [[isolateRead]]. */
    given isolateWrite: Isolate[PathWrite, Sync, PathWrite] with
        type State        = FileSystem.Write[Any]
        type Transform[A] = Result[FileSystemException, A]

        def capture[A, S](f: State => A < S)(using Frame): A < (PathWrite & Sync & S) =
            FileSystem.useErased(f)

        def isolate[A, S](state: State, value: A < (S & PathWrite))(using Frame): Result[FileSystemException, A] < (Sync & S) =
            Abort.run[FileSystemException](runWith(state)(value))

        def restore[A, S](value: Result[FileSystemException, A] < S)(using Frame): A < (PathWrite & S) =
            value.map {
                case Result.Success(result) => result
                case Result.Failure(error) =>
                    ArrowEffect.suspend(Tag[PathWrite], Op.Raise(Result.Failure(error)))
                case panic: Result.Panic =>
                    ArrowEffect.suspend(Tag[PathWrite], Op.Raise(panic))
            }
    end isolateWrite

    /** Stateless isolation for watch operations. See [[isolateRead]]. */
    given isolateWatch: Isolate[PathWatch, Async, PathWatch] with
        type State        = FileSystem.Watch[Any]
        type Transform[A] = Result[FileWatchException, A]

        def capture[A, S](f: State => A < S)(using Frame): A < (PathWatch & Async & S) =
            FileSystem.useWatchErased(f)

        def isolate[A, S](state: State, value: A < (S & PathWatch))(using Frame): Result[FileWatchException, A] < (Async & S) =
            Abort.run[FileWatchException](Scope.run(runWatchWith(state)(value)))

        def restore[A, S](value: Result[FileWatchException, A] < S)(using Frame): A < (PathWatch & S) =
            value.map {
                case Result.Success(result) => result
                case Result.Failure(error) =>
                    ArrowEffect.suspend(Tag[PathWatch], WatchOp.Raise(Result.Failure(error)))
                case panic: Result.Panic =>
                    ArrowEffect.suspend(Tag[PathWatch], WatchOp.Raise(panic))
            }
    end isolateWatch

    /** Shared overlay bootstrap for explicit staged-write scopes.
      *
      * Overlay construction uses `Sync.Unsafe.defer` inside the `handleLoop` handler (lazy first
      * dispatch, or at `done` when the program raised no PathWrite ops). Sync is cast off the
      * residual the same way `exists`/`read` hide Sync behind suspend/dispatch, so combinator
      * return rows stay locked without `Sync`.
      */
    private def bootstrapOverlay(using Frame): WatchableOverlayFileSystem[PathWrite, PathWrite] < PathWrite =
        // Unsafe: create overlay without Scope; lifecycle managed by finish callback.
        // Cast hides Sync (runs at dispatch/done time; outer runner folds Sync).
        Sync.Unsafe.defer(
            new WatchableOverlayFileSystem(
                new ForwardingLowerFileSystem,
                AtomicRef.Unsafe.init(OverlayFileSystem.OverlayState.empty).safe,
                AtomicLong.Unsafe.init(0L).safe,
                AtomicBoolean.Unsafe.init(true).safe,
                summon[Isolate[PathWrite, Sync, PathWrite]]
            )
        ).asInstanceOf[WatchableOverlayFileSystem[PathWrite, PathWrite] < PathWrite]
    end bootstrapOverlay

    private def handleEphemeralOverlay[A, B, S2](
        overlay: WatchableOverlayFileSystem[PathWrite, PathWrite],
        program: A < (PathWrite & S2),
        finish: (A, OverlayFileSystem[PathWrite]) => B < (PathWrite & S2)
    )(using Frame): B < (PathWrite & S2) =
        FileSystem.useErased { lower =>
            val bound = new FileSystem.Watch[Any]:
                def openWatcher(boundPath: Path, boundOptions: WatchOptions)(using
                    Frame
                ): Watcher < (Any & Async & Scope & Abort[FileWatchException]) =
                    Abort.run[FileSystemException](runWith(lower)(overlay.openWatcher(boundPath, boundOptions))).map {
                        case Result.Success(watcher)                   => watcher
                        case Result.Failure(error: FileWatchException) => Abort.fail(error)
                        case Result.Failure(error) =>
                            Abort.fail(FileIOException(boundPath, FileSystemOperation.Watch, error))
                        case Result.Panic(error) => Abort.panic(error)
                    }
            FileSystem.letStagedWatchErased(bound) {
                ArrowEffect.handleLoop(Tag[PathWrite], overlay, program)(
                    [C] =>
                        (op, state, cont) =>
                            // Unsafe: dispatch's Abort[FileSystemException] is never raised here at runtime;
                            // ForwardingLowerFileSystem re-suspends I/O ops as PathWrite so FileSystemExceptions
                            // propagate through the outer PathWrite handler, not inside this handler body
                            dispatch(state, op).asInstanceOf[C < PathWrite].map(result =>
                                Loop.continue(state, cont(result))
                        ),
                    done = (state, result) => finish(result, state)
                )
            }
        }
    end handleEphemeralOverlay

    private def scopedOverlay[A, B, S2](
        program: A < (PathWrite & S2),
        finish: (A, OverlayFileSystem[PathWrite]) => B < (PathWrite & S2)
    )(using Frame): B < (PathWrite & Scope & S2) =
        Scope.acquireRelease(bootstrapOverlay) { overlay =>
            overlay.discardOnScopeExit
        }.map(overlay => handleEphemeralOverlay(overlay, program, finish))
            .asInstanceOf[B < (PathWrite & Scope & S2)]
    end scopedOverlay

    /** Runs `program` against isolated staged writes and commits them when it succeeds. */
    def commitWritesOnSuccess[A, S](program: A < (PathWrite & S))(using
        Frame
    ): A < (PathWrite & Scope & Abort[CommitConflict] & S) =
        scopedOverlay(
            program,
            (result, overlay) =>
                val commit = overlay.commit.asInstanceOf[Unit < (PathWrite & Abort[CommitConflict])]
                commit.andThen(result)
        )
    end commitWritesOnSuccess

    /** Runs `program` against isolated staged writes and always discards them. */
    def discardWrites[A, S](program: A < (PathWrite & S))(using Frame): A < (PathWrite & Scope & S) =
        scopedOverlay(
            program,
            (result, overlay) =>
                overlay.discard.asInstanceOf[Unit < PathWrite].andThen(result)
        )
    end discardWrites

    /** Runs `program` against isolated staged writes and returns their one-shot lifecycle handle. */
    def stageWrites[A, S](program: A < (PathWrite & S))(using
        Frame
    ): (A, FileSystem.StagedChanges[Sync]) < (PathWrite & Scope & S) =
        scopedOverlay(
            program,
            (result, overlay) =>
                (result, overlay.asInstanceOf[FileSystem.StagedChanges[Sync]])
        )
    end stageWrites

    private def dispatch[S, C](service: FileSystem.Write[S], op: Op[C])(using Frame): C < (S & Abort[FileSystemException]) =
        op match
            case Op.Exists(p)                 => service.exists(p)
            case Op.ExistsFollow(p, f)        => service.exists(p, f)
            case Op.IsDirectory(p)            => service.isDirectory(p)
            case Op.IsRegularFile(p)          => service.isRegularFile(p)
            case Op.IsSymbolicLink(p)         => service.isSymbolicLink(p)
            case Op.RealPath(p)               => service.realPath(p)
            case Op.RealPathPrefix(p)         => service.realPathPrefix(p)
            case Op.Read(p)                   => service.read(p)
            case Op.ReadCharset(p, c)         => service.read(p, c)
            case Op.ReadBytes(p)              => service.readBytes(p)
            case Op.ReadLines(p)              => service.readLines(p)
            case Op.ReadLinesCharset(p, c)    => service.readLines(p, c)
            case Op.Size(p)                   => service.size(p)
            case Op.Stat(p)                   => service.stat(p)
            case Op.ListDir(p)                => service.list(p)
            case Op.ListGlob(p, g, c)         => c.fold(service.list(p, g))(service.list(p, g, _))
            case Op.DefaultCaseSensitivity()  => service.defaultCaseSensitivity
            case Op.OpenRead(p)               => service.openRead(p)
            case Op.OpenReadLines(p, c)       => service.openReadLines(p, c)
            case Op.OpenWalk(p, d, f)         => service.openWalk(p, d, f)
            case Op.CurrentReadService()      => FileSystem.useReadErased(selected => selected)
            case Op.Raise(error)              => Abort.error(error)
            case Op.Write(p, v, cf)           => service.write(p, v, cf)
            case Op.WriteBytes(p, v, options) => service.writeBytes(p, v, options)
            case Op.WriteLines(p, v, cf)      => service.writeLines(p, v, cf)
            case Op.Append(p, v, cf)          => service.append(p, v, cf)
            case Op.AppendBytes(p, v, cf)     => service.appendBytes(p, v, cf)
            case Op.AppendLines(p, v, cf)     => service.appendLines(p, v, cf)
            case Op.Truncate(p, s)            => service.truncate(p, s)
            case Op.SetLastModified(p, e)     => service.setLastModified(p, e)
            case Op.MkDir(p)                  => service.mkDir(p)
            case Op.MkFile(p)                 => service.mkFile(p)
            case Op.Move(f, t, options)       => service.move(f, t, options)
            case Op.Copy(f, t, options)       => service.copy(f, t, options)
            case Op.Remove(p)                 => service.remove(p)
            case Op.RemoveExisting(p)         => service.removeExisting(p)
            case Op.RemoveAll(p)              => service.removeAll(p)
            case Op.SyncDirectory(p)          => service.syncDirectory(p)
            case Op.SiblingTemporary(p)       => service.siblingTemporary(p)
            case Op.DurableReplace(p, bytes)  => service.durableReplace(p, bytes)
            case Op.OpenWrite(p, a, cf)       => service.openWrite(p, a, cf)
            case Op.TempDir(prefix)           => service.tempDir(prefix)
            case Op.WriteChunk(h, ch)         => service.writeChunk(h, ch)
            case Op.WriteString(h, s, c)      => service.writeString(h, s, c)
    end dispatch

    private def dispatchRead[S, C](service: FileSystem.Read[S], op: Op[C])(using Frame): C < (S & Abort[FileSystemException]) =
        op match
            case Op.Exists(p)                => service.exists(p)
            case Op.ExistsFollow(p, f)       => service.exists(p, f)
            case Op.IsDirectory(p)           => service.isDirectory(p)
            case Op.IsRegularFile(p)         => service.isRegularFile(p)
            case Op.IsSymbolicLink(p)        => service.isSymbolicLink(p)
            case Op.RealPath(p)              => service.realPath(p)
            case Op.RealPathPrefix(p)        => service.realPathPrefix(p)
            case Op.Read(p)                  => service.read(p)
            case Op.ReadCharset(p, c)        => service.read(p, c)
            case Op.ReadBytes(p)             => service.readBytes(p)
            case Op.ReadLines(p)             => service.readLines(p)
            case Op.ReadLinesCharset(p, c)   => service.readLines(p, c)
            case Op.Size(p)                  => service.size(p)
            case Op.Stat(p)                  => service.stat(p)
            case Op.ListDir(p)               => service.list(p)
            case Op.ListGlob(p, g, c)        => c.fold(service.list(p, g))(service.list(p, g, _))
            case Op.DefaultCaseSensitivity() => service.defaultCaseSensitivity
            case Op.OpenRead(p)              => service.openRead(p)
            case Op.OpenReadLines(p, c)      => service.openReadLines(p, c)
            case Op.OpenWalk(p, d, f)        => service.openWalk(p, d, f)
            case Op.CurrentReadService()     => FileSystem.useReadErased(selected => selected)
            case Op.Raise(error)             => Abort.error(error)
            case _ => Abort.panic[FileSystemException](new IllegalStateException("PathWrite operation reached the PathRead handler"))
    end dispatchRead

    private def restoreRead[A, S](value: Result[FileSystemException, A] < S)(using Frame): A < (PathRead & S) =
        value.map {
            case Result.Success(result) => result
            case Result.Failure(error)  => ArrowEffect.suspend(Tag[PathRead], Op.Raise(Result.Failure(error)))
            case panic: Result.Panic    => ArrowEffect.suspend(Tag[PathRead], Op.Raise(panic))
        }
    end restoreRead

    private[kyo] def suspendTryLock(path: Path, mode: LockMode)(using Frame): Maybe[Lock] < (PathRead & Sync & Scope) =
        ArrowEffect.suspend(Tag[PathRead], Op.CurrentReadService()).map { service =>
            restoreRead(Abort.run[FileSystemException](service.tryLock(path, mode)))
        }
    end suspendTryLock

    private[kyo] def suspendLock(path: Path, mode: LockMode, wait: LockWait)(using Frame): Lock < (PathRead & Async & Scope) =
        ArrowEffect.suspend(Tag[PathRead], Op.CurrentReadService()).map { service =>
            restoreRead(Abort.run[FileSystemException](service.lock(path, mode, wait)))
        }
    end suspendLock

    // --- Scoped tempDir ---

    /** Creates a temporary directory in the active service and registers its recursive removal with
      * the enclosing `Scope`. The removal runs through the service that created the directory (host:
      * real recursive delete; in-memory: map-subtree removal; overlay: upper-entry discard), so a temp
      * dir made by a staged service is never deleted by a host-tier `removeAll`. There is no unscoped
      * public temp-directory primitive. The location of the created directory is service-defined:
      * unconfined host services use the OS temporary directory; root-confined host services create the
      * directory inside their root.
      */
    def tempDir(prefix: String = "kyo")(using Frame): Path < (PathWrite & Sync & Scope) =
        Scope.acquireRelease(
            ArrowEffect.suspend(Tag[PathWrite], Op.TempDir(prefix))
        )(handle => Sync.Unsafe.defer(handle.remove())) // Unsafe: service-vended recursive cleanup at Scope exit
            .map(_.path)

    /** A handle to a service-created temporary directory. Vended by [[FileSystem.tempDir]] so the scoped
      * [[Path.tempDir]] finalizer removes through the creating service. Internal.
      */
    abstract private[kyo] class TempDirHandle:
        def path: Path
        def remove()(using AllowUnsafe): Unit
    end TempDirHandle

    /** Service-owned sibling temporary cleanup handle. Internal. */
    abstract private[kyo] class TempFileHandle:
        def path: Path
        def remove()(using AllowUnsafe): Unit
    end TempFileHandle

    /** Service-owned idempotent close handle used by cancellation finalizers. Internal. */
    abstract private[kyo] class ChannelCloseHandle:
        def close()(using AllowUnsafe): Unit
    end ChannelCloseHandle

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
        size: Maybe[ByteSize],
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

    /** A Scope-managed positioned read capability into an open file.
      *
      * Reads use absolute offsets and never advance an implicit cursor, so independent callers may
      * safely choose their own positions. A read may return fewer bytes than requested at end of
      * file. The capability intentionally has no mutation surface.
      *
      * The [[Scope]] active during acquisition owns the underlying resource. Operations after its
      * exit fail through [[FileReadException]].
      *
      * @tparam S the backend's own effect
      */
    trait ReadChannel[S]:
        /** Reads up to `length` bytes at absolute offset `position`. Tolerates short reads: the
          * returned `Span`'s length is less than `length` when `position + length` exceeds the file's
          * current size.
          */
        def readAt(position: Long, length: Int)(using Frame): Span[Byte] < (S & Abort[FileReadException])

        /** Returns the channel's current view of length. */
        def size(using Frame): Long < (S & Abort[FileReadException])
    end ReadChannel

    /** A Scope-managed positioned write capability into an open file.
      *
      * Writes use absolute offsets and zero-fill a gap beyond the current end. Truncation and
      * explicit synchronization are available, while reads and size inspection are intentionally
      * absent from this capability.
      *
      * The [[Scope]] active during acquisition owns the underlying resource. Operations after its
      * exit fail through [[FileWriteException]].
      *
      * @tparam S the backend's own effect
      */
    trait WriteChannel[S]:
        /** Writes `bytes` at absolute offset `position`. A gap between the channel's current length
          * and `position` is zero-filled.
          */
        def writeAt(position: Long, bytes: Span[Byte])(using Frame): Unit < (S & Abort[FileWriteException])

        /** Persists prior writes, optionally including file metadata. */
        def sync(metadata: Boolean)(using Frame): Unit < (S & Abort[FileWriteException])

        /** Truncates the channel's target to `size`, discarding trailing bytes when `size` is
          * smaller than the current length.
          */
        def truncate(size: Long)(using Frame): Unit < (S & Abort[FileWriteException])
    end WriteChannel

    /** A Scope-managed channel combining positioned read and write capabilities.
      *
      * This is the capability for algorithms that need to inspect and mutate the same open file.
      * It preserves the precise read and write failure rows of its two parent capabilities rather
      * than widening every operation to a common filesystem error.
      *
      * The [[Scope]] active during acquisition owns the underlying resource. There is no explicit
      * close operation on the public channel.
      *
      * @tparam S the backend's own effect
      */
    trait ReadWriteChannel[S] extends ReadChannel[S] with WriteChannel[S]

    /** Selects the compatibility of an advisory path lock. */
    enum LockMode derives CanEqual:
        case Shared
        case Exclusive

    /** Selects whether lock acquisition returns immediately or suspends until it can complete. */
    enum LockWait derives CanEqual:
        case Immediate
        case UntilAvailable
        case Until(deadline: Clock.Deadline)
    end LockWait

    /** Opaque identity of one acquired lock claim. */
    private[kyo] opaque type LockOwnership = AnyRef

    private[kyo] object LockOwnership:
        given CanEqual[LockOwnership, LockOwnership]                 = CanEqual.derived
        def fresh(): LockOwnership                                   = new AnyRef
        def same(left: LockOwnership, right: LockOwnership): Boolean = left eq right
    end LockOwnership

    /** A Scope-managed advisory lock on a path.
      *
      * Shared locks coexist with other shared locks. Exclusive locks conflict with every other
      * holder. The acquiring [[Scope]] owns an opaque token and releases only the matching claim
      * when it closes. There is intentionally no public manual-release operation.
      *
      * Use [[check]] before a protected operation when the backend can lose external ownership.
      * It raises [[FileLockOwnershipLostException]] if the claim no longer belongs to this handle.
      */
    trait Lock:
        /** The compatibility mode granted to this handle. */
        def mode: LockMode

        /** Verifies that this handle still owns its claim. */
        def check(using Frame): Unit < (Sync & Abort[FileLockException])

        private[kyo] def ownership: LockOwnership
        private[kyo] def release(ownership: LockOwnership)(using Frame): Unit < (Sync & Abort[FileLockException])
    end Lock

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

        /** Attempts to acquire a scoped advisory lock, returning `Absent` when an incompatible
          * claim is already held.
          */
        def tryLock(mode: LockMode)(using Frame): Maybe[Lock] < (PathRead & Sync & Scope) =
            suspendTryLock(self, mode)

        /** Acquires a scoped advisory lock according to `wait`. */
        def lock(mode: LockMode, wait: LockWait = LockWait.UntilAvailable)(using Frame): Lock < (PathRead & Async & Scope) =
            suspendLock(self, mode, wait)

        /** Returns this path resolved to its canonical real path, but only if that real path is contained
          * within `root` (after resolving `root`'s own symlinks).
          *
          * The check follows every symbolic link in both `self` and `root`, so a symlink inside `root`
          * pointing outside is rejected. The pure path-prefix comparison runs against the canonical parts
          * of both paths; a path equal to `root` is considered contained.
          *
          * If `self`'s real path is outside `root`'s real path, fails with `FileAccessDeniedException`
          * carrying the offending real path.
          *
          * Useful for any tool that exposes a configured root and accepts user-supplied relative paths:
          * call `(root / userInput).confinedTo(root)` to obtain a path that is statically known to live
          * under the root, defending against symlink escapes. Run the check before using the path,
          * which is also the ordering under which every backend resolves links.
          *
          * The check is only as strong as the active backend's `realPath`:
          *
          *   - Host backends resolve every link, and require both `self` and `root` to exist: either
          *     one missing fails with `FileNotFoundException`.
          *   - Backends whose paths are pure keys with no link topology (in-memory, both zip
          *     backends) resolve a path to itself, so the check reduces to a parts-prefix comparison
          *     and an absent path does not fail. Those backends have no symlinks, so resolution has
          *     nothing to defend against.
          *   - A staged-write overlay defers to its lower for any path it has not staged, and returns
          *     a path it has already staged unchanged. See [[FileSystem.overlay]].
          */
        def confinedTo(root: Path)(using Frame): Path < (PathRead & Abort[FileSystemException]) =
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
                        // Unsafe: bridges vended read-handle chunk reads into the Sync tier.
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
                        // Unsafe: bridges vended read-handle chunk reads into the Sync tier.
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
                        // Unsafe: bridges vended line-read handle into the Sync tier.
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
                        // Unsafe: bridges vended read-handle seek to end into the Sync tier.
                        Sync.Unsafe.defer {
                            handle.position(fileSize)
                            val buf = new Array[Byte](bufferSize)
                            // State: (file position, leftover bytes from incomplete UTF-8, pending incomplete line text)
                            val emptyBytes = new Array[Byte](0)
                            Loop((fileSize, emptyBytes, "")) { case (pos, leftover, pending) =>
                                // Unsafe: bridges vended read-handle tail polling into the Sync tier.
                                Sync.Unsafe.defer {
                                    val result = handle.readChunk(buf)
                                    if result.isEof then
                                        ArrowEffect.suspend(Tag[PathRead], Path.Op.Size(self)).map { currentSize =>
                                            if currentSize < pos then
                                                // File was truncated -- reset to beginning
                                                // Unsafe: bridges vended read-handle seek on truncate into the Sync tier.
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

        /** Writes `value` to the file according to `options`. */
        inline def write(value: String, options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Write(self, value, options))

        /** Writes raw bytes to the file. */
        inline def writeBytes(value: Span[Byte], options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteBytes(self, value, options))

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        inline def writeLines(value: Chunk[String], options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.WriteLines(self, value, options))

        /** Appends `value` to the file according to `options`. */
        inline def append(value: String, options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Append(self, value, options))

        /** Appends raw bytes to the file. */
        inline def appendBytes(value: Span[Byte], options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendBytes(self, value, options))

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        inline def appendLines(value: Chunk[String], options: WriteOptions = WriteOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.AppendLines(self, value, options))

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
        inline def list(glob: Glob)(using inline frame: Frame): Chunk[Path] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ListGlob(self, glob, Absent))

        inline def list(glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using inline frame: Frame): Chunk[Path] < PathRead =
            ArrowEffect.suspend(Tag[PathRead], Path.Op.ListGlob(self, glob, Maybe(caseSensitivity)))

        /** Streams all entries under this directory tree (unlimited depth, not following links). */
        def walk(using Frame): Stream[Path, PathRead & Scope & Sync] =
            walk(Int.MaxValue, followLinks = false)

        /** Streams all entries under this directory tree up to `maxDepth`, optionally following symbolic links. */
        def walk(maxDepth: Int = Int.MaxValue, followLinks: Boolean = false)(using
            Frame
        ): Stream[Path, PathRead & Scope & Sync] =
            walkWhere(maxDepth, followLinks)(_ => true)

        private def walkWhere(maxDepth: Int, followLinks: Boolean)(matches: Path => Boolean)(using
            Frame
        ): Stream[Path, PathRead & Scope & Sync] =
            Stream {
                Scope.acquireRelease(
                    ArrowEffect.suspend(Tag[PathRead], Path.Op.OpenWalk(self, maxDepth, followLinks))
                )(handle => Sync.Unsafe.defer(handle.close())).map { handle => // Unsafe: closes the vended walk handle at Scope exit
                    Loop.foreach {
                        // Unsafe: bridges vended walk-handle iteration into the Sync tier.
                        Sync.Unsafe.defer {
                            handle.next() match
                                case Absent => Loop.done
                                case Present(path) =>
                                    if matches(path) then Emit.valueWith(Chunk(path))(Loop.continue)
                                    else Loop.continue
                        }
                    }
                }
            }

        def walk(glob: Glob)(using Frame): Stream[Path, PathRead & Scope & Sync] =
            Stream.unwrap(
                ArrowEffect.suspend(Tag[PathRead], Path.Op.DefaultCaseSensitivity()).map(cs => walk(glob, cs))
            )

        def walk(glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using Frame): Stream[Path, PathRead & Scope & Sync] =
            walkWhere(Int.MaxValue, followLinks = false)(path => glob.matches(path.parts.drop(self.parts.size), caseSensitivity))

        /** Moves this path to `to`. */
        inline def move(to: Path, options: MoveOptions = MoveOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Move(self, to, options))

        /** Copies this path to `to`. */
        inline def copy(to: Path, options: CopyOptions = CopyOptions())(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Copy(self, to, options))

        /** Deletes this path if it exists. Returns `true` if it was deleted, `false` if it did not exist. */
        inline def remove(using inline frame: Frame): Boolean < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.Remove(self))

        /** Deletes this path, raising `FileNotFoundException` if it does not exist. */
        inline def removeExisting(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveExisting(self))

        /** Recursively deletes this path and all of its contents. */
        inline def removeAll(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.RemoveAll(self))

        /** Synchronizes this directory's entry state. */
        inline def syncDirectory(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.SyncDirectory(self))

        /** Reserves a create-new temporary file beside this target. */
        def siblingTemporary(using Frame): Path < (PathWrite & Scope & Sync) =
            Scope.acquireRelease(ArrowEffect.suspend(Tag[PathWrite], Path.Op.SiblingTemporary(self)))(handle =>
                Sync.Unsafe.defer(handle.remove())
            ).map(_.path)

        /** Durably replaces this path with `bytes`. */
        inline def durableReplace(bytes: Span[Byte])(using inline frame: Frame): Unit < PathWrite =
            ArrowEffect.suspend(Tag[PathWrite], Path.Op.DurableReplace(self, bytes))

        /** Acquires a watcher after its backend registration is active. */
        def openWatcher(options: WatchOptions = WatchOptions())(using Frame): Watcher < PathWatch =
            FileSystem.useStagedWatchErased {
                case Present(fileSystem) => ArrowEffect.suspend(Tag[PathWatch], Path.WatchOp.OpenWith(fileSystem, self, options))
                case Absent              => ArrowEffect.suspend(Tag[PathWatch], Path.WatchOp.Open(self, options))
            }

        /** Returns the underlying `Unsafe` implementation for direct use in unsafe code. */
        def unsafe: Path.Unsafe = self

    end extension

    /** A scope-managed source of normalized filesystem changes.
      *
      * Watchers are acquired with [[Path.openWatcher]] only after their
      * backend registration is active. Their event stream remains valid
      * for the lifetime of the acquisition scope and suspends asynchronously
      * while waiting for changes.
      *
      * Backend failures are reported through [[FileWatchException]]. Event
      * loss and watched-root loss are values in the stream instead, represented
      * by [[PathChange.Overflow]] and [[PathChange.Invalidated]].
      */
    trait Watcher:
        def events: Stream[PathChange, Async & Scope & Abort[FileWatchException]]
    end Watcher

    // --- System directories ---

    /** The current working directory of the JVM (JVM/Native) or Node process (JS).
      *
      * Reads at call time, so subsequent `process.chdir` (or test fixtures that fork with a
      * different working dir) take effect on the next access. Use with `path.ancestors` for
      * "find the project root containing X" style lookups.
      */
    def cwd(using Frame): Path < Sync =
        // Unsafe: bridges platform cwd lookup into the Sync tier.
        Sync.Unsafe.defer(cwdPath)

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

        /** Synchronizes the directory itself so newly-created or renamed children have their
          * directory entries flushed to stable storage. Unsupported platforms return a precise
          * write failure rather than claiming durability.
          */
        private[kyo] def syncDirectory()(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Returns the human-readable representation; delegates to `show` so Path values display correctly. */
        override def toString: String = show

        // --- Inspection ---

        def exists()(using AllowUnsafe, Frame): Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean]
        def exists(followLinks: Boolean)(using
            AllowUnsafe,
            Frame
        )
            : Result[FileInvalidPathException | FileAccessDeniedException | FileIOException, Boolean]
        def isDirectory()(using AllowUnsafe): Boolean
        def isRegularFile()(using AllowUnsafe): Boolean
        def isSymbolicLink()(using AllowUnsafe): Boolean
        def realPath()(using
            AllowUnsafe,
            Frame
        )
            : Result[FileInvalidPathException | FileNotFoundException | FileAccessDeniedException | FileIOException, Path]

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
        private[kyo] def stableIdentity()(using AllowUnsafe, Frame): Result[FileReadException, Maybe[String]]

        // --- Write ---

        def write(value: String, options: WriteOptions = WriteOptions())(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def writeBytes(value: Span[Byte], options: WriteOptions = WriteOptions())(using
            AllowUnsafe,
            Frame
        ): Result[FileWriteException, Unit]

        /** Writes a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `writeLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def writeLines(value: Chunk[String], options: WriteOptions = WriteOptions())(using
            AllowUnsafe,
            Frame
        ): Result[FileWriteException, Unit]
        def append(value: String, options: WriteOptions = WriteOptions())(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def appendBytes(value: Span[Byte], options: WriteOptions = WriteOptions())(using
            AllowUnsafe,
            Frame
        ): Result[FileWriteException, Unit]

        /** Appends a collection of lines to the file.
          *
          * Each line is written followed by the platform line separator (including the last line), so `appendLines(Chunk("a", "b"))`
          * produces `"a\nb\n"` on Unix. Use `write(lines.mkString(lineSep))` if you need to control trailing newline behavior.
          */
        def appendLines(value: Chunk[String], options: WriteOptions = WriteOptions())(using
            AllowUnsafe,
            Frame
        ): Result[FileWriteException, Unit]
        def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]
        def setLastModified(epochMs: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        // --- Directory / structure ---

        def list()(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]]
        def list(glob: Glob, caseSensitivity: Glob.CaseSensitivity)(using AllowUnsafe, Frame): Result[FileStructureException, Chunk[Path]]
        def mkDir()(using AllowUnsafe, Frame): Result[FileStructureException, Unit]
        def mkFile()(using AllowUnsafe, Frame): Result[FileStructureException, Unit]
        def move(to: Path, options: MoveOptions = MoveOptions())(using
            AllowUnsafe,
            Frame
        ): Result[FileStructureException, Unit]
        def copy(to: Path, options: CopyOptions = CopyOptions())(using AllowUnsafe, Frame): Result[FileStructureException, Unit]
        def remove()(using AllowUnsafe, Frame): Result[FileStructureException, Boolean]
        def removeExisting()(using AllowUnsafe, Frame): Result[FileStructureException, Unit]
        def removeAll()(using AllowUnsafe, Frame): Result[FileStructureException, Unit]

        // --- Walk handle (abstract -- platform provides the resource management) ---

        def openWalk(maxDepth: Int, followLinks: Boolean)(using AllowUnsafe, Frame): Result[FileStructureException, Path.WalkHandle]

        // --- Streaming write handles ---

        /** Opens a write handle for streaming byte or string output. The caller must close the handle via `Scope.acquireRelease`. */
        def openWrite(append: Boolean, options: WriteOptions)(using AllowUnsafe, Frame): Result[FileWriteException, Path.WriteHandle]

        // --- Positioned channel (abstract -- platform provides the concrete channel) ---

        private[kyo] def openReadChannelRaw()(using AllowUnsafe, Frame): Result[FileReadException, Path.RawChannel]
        private[kyo] def openWriteChannelRaw(open: FileSystem.WriteOpen)(using
            AllowUnsafe,
            Frame
        ): Result[FileWriteException | FileStructureException, Path.RawChannel]
        private[kyo] def openReadWriteChannelRaw(open: FileSystem.WriteOpen)(using
            AllowUnsafe,
            Frame
        ): Result[FileReadException | FileWriteException | FileStructureException, Path.RawChannel]

        /** Acquires a raw advisory lock on this path in `mode`, non-blocking (fails
          * immediately if the lock is held incompatibly rather than waiting). Platform
          * implementations provide the concrete lock.
          */
        def lock(mode: LockMode)(using AllowUnsafe, Frame): Result[FileLockException, Path.RawLock]

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

        /** Marks the write channel as successfully completed: flushes buffered bytes to stable storage
          * and fsyncs when the platform supports it. After `finish()`, [[close]] releases OS resources
          * without deleting the file.
          *
          * WARNING: if [[close]] is called without a prior `finish()`, the platform implementation
          * deletes the partial entry (the delete-on-close-without-finish contract the stream sinks rely
          * on). Always call `finish()` before scope exit when the written content must be retained.
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

        /** Reads the current content into the handle's own retained buffer and parses the first ASCII-decimal
          * `Long` in place, with no intermediate `String` and no `Maybe` box. TOTAL: returns
          * `ReadHandle.AbsentLong` (`Long.MinValue`) on empty content, on no leading digit after whitespace, or
          * on overflow. The parser recognizes only ASCII decimal digits (no sign), so it can never itself
          * produce a negative `Long`, which makes the sentinel collision-free by construction. Each call parses
          * from the start of the content; it does not advance a cursor across calls, and it does not disturb the
          * `readChunk` cursor: an interleaved `readChunk` resumes exactly where it left off.
          */
        def readLong()(using AllowUnsafe): Long

        /** Sets the channel position to `offset` bytes from the start of the file. */
        def position(offset: Long)(using AllowUnsafe): Unit

        /** Closes the channel, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end ReadHandle

    private[kyo] object ReadHandle:
        /** The sentinel `readLong` returns for empty, unparseable, or overflowing content (`Long.MinValue`).
          * A host metric `readLong` parses (bytes, nanoseconds, counts, pids) is non-negative, and the parser
          * accepts no leading sign, so this value can never collide with a genuine parse result. Peer of the
          * existing `Path.ReadResult.Eof` sentinel-on-companion pattern.
          */
        val AbsentLong: Long = Long.MinValue

        /** Parses the first maximal ASCII-decimal run in `buf[0, len)` as a `Long`, skipping leading ASCII
          * whitespace. Returns `AbsentLong` when no digit leads or the value overflows. Shared by the concrete
          * platform `readLong` implementations after each fills its own retained buffer.
          */
        private[kyo] def parseLeadingLong(buf: Array[Byte], len: Int): Long =
            @scala.annotation.tailrec
            def skip(i: Int): Int =
                if i < len && { val b = buf(i); b == ' ' || b == '\t' || b == '\n' || b == '\r' } then skip(i + 1)
                else i
            @scala.annotation.tailrec
            def digits(i: Int, acc: Long, any: Boolean): Long =
                if i >= len then (if any then acc else AbsentLong)
                else
                    val b = buf(i)
                    if b >= '0' && b <= '9' then
                        val d = (b - '0').toLong
                        if acc > (Long.MaxValue - d) / 10L then AbsentLong
                        else digits(i + 1, acc * 10L + d, true)
                    else if any then acc
                    else AbsentLong
                    end if
            val start = skip(0)
            if start >= len then AbsentLong else digits(start, 0L, false)
        end parseLeadingLong
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

    // --- Raw channel -- platform-provided positioned I/O backing typed channels ---

    private[kyo] enum RawChannelAccess derives CanEqual:
        case Read
        case Write(open: FileSystem.WriteOpen)
        case ReadWrite(open: FileSystem.WriteOpen)
    end RawChannelAccess

    /** A raw positioned-I/O channel returned by the private unsafe acquisition methods. Platform
      * implementations provide the concrete class; `FileSystem` backends wrap it as the
      * public typed channel capabilities.
      */
    abstract private[kyo] class RawChannel:
        /** Reads up to `len` bytes at absolute offset `pos`. Returns fewer bytes than `len`
          * on a short read.
          */
        def readAt(pos: Long, len: Int)(using AllowUnsafe, Frame): Result[FileReadException, Array[Byte]]

        /** Writes `bytes` at absolute offset `pos`. */
        def writeAt(pos: Long, bytes: Array[Byte])(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Durably persists every prior `writeAt` call on this channel. */
        def sync(metadata: Boolean)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Truncates the channel's target to `size`. */
        def truncate(size: Long)(using AllowUnsafe, Frame): Result[FileWriteException, Unit]

        /** Returns the channel's current view of length. */
        def size()(using AllowUnsafe, Frame): Result[FileReadException, Long]

        /** Closes the channel, releasing all OS resources. */
        def close()(using AllowUnsafe): Unit
    end RawChannel

    // --- Raw lock -- platform-provided advisory lock backing Path.Lock ---

    /** A raw advisory lock returned by `Path.Unsafe.lock`. Platform implementations provide the
      * concrete class; `FileSystem` backends wrap it as the public [[Path.Lock]].
      */
    abstract private[kyo] class RawLock:
        /** `true` when this lock excludes every other holder, including other shared holders. */
        def isExclusive: Boolean

        /** Verifies that the platform claim is still owned. */
        def check()(using AllowUnsafe, Frame): Result[FileLockException, Unit]

        /** Releases the lock, freeing it for another acquirer. */
        def release()(using AllowUnsafe, Frame): Result[FileLockException, Unit]
    end RawLock

end Path

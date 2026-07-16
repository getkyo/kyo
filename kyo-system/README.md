# kyo-system

`kyo-system` is the file I/O, process execution, and environment layer for Kyo. File operations are tracked through the `PathRead` and `PathWrite` capabilities: reads and writes suspend under these effects and are discharged by a runner (`Path.run`, `Path.runReadOnly`, or their `runWith` variants), which folds the per-operation `Abort[File*Exception]` markers into the umbrella `Abort[FileException]`. Process launches carry `Abort[CommandException]`, and handles are `Scope`-managed for automatic cleanup. The same API compiles across JVM, Scala Native, and JavaScript (Node.js).

The following example reads a configuration file, fetches a git revision, runs a build step, and writes an artifact record. Every filesystem op runs inside `Path.run`, which installs the default host service and discharges both capabilities:

```scala
import kyo.*

val deploy =
    Path.run {
        for
            config  <- (Path / "etc" / "deploy.toml").read
            version <- Command("git", "rev-parse", "--short", "HEAD").text
            _       <- Command("sbt", "assembly").cwd(Path("backend")).waitForSuccess
            _       <- (Path("dist") / "version.txt").write(version)
        yield ()
    }
```

## Path capabilities

Filesystem I/O is capability-tracked, not method-tracked. A program that only reads carries `< PathRead` in its row; a program that writes carries `< PathWrite` (which also satisfies reads). `Sync`, `Scope`, and the `Abort[FileException]` umbrella appear on the runner residual after discharge, not on individual extension methods.

| Runner | Discharges | Residual (host service) |
|---|---|---|
| `Path.run(program)` | `PathWrite` (and `PathRead` via subtyping) | `Sync & Abort[FileException] & S` |
| `Path.runReadOnly(program)` | `PathRead` only | `Sync & Abort[FileException] & S` |
| `Path.runWith(service)(program)` | `PathWrite` against a custom service | `S & Abort[FileException] & S2` |
| `Path.runReadOnlyWith(service)(program)` | `PathRead` against a custom service | `S & Abort[FileException] & S2` |

`PathWrite <: PathRead`: a write-capable context also satisfies read operations, and `Path.runReadOnly` rejects write programs at the call site (the negative capability law).

Install a custom backend with `Path.runWith`: `Service.host` (default), `Service.host(root)` (root-confined), `Service.inMemory` (hermetic tests), or `Service.overlay(lower)` (copy-on-write staging with explicit commit).

When you need real disk I/O against the host filesystem, use `Service.host` or `Service.host(root)` to confine all paths under a root. When you need hermetic unit tests with no disk side effects, use `Service.inMemory`. When you need to stage writes and commit or discard them as a unit, wrap a lower service with `Service.overlay(lower)`.

`Service.overlay(lower)` is Scope-managed: when the enclosing Scope closes, staged upper state is reset automatically (rollback without an explicit `rollback` call).

Overlay combinators for transactional writes:

```scala
import kyo.*

// Commit staged writes on success; rollback on Abort or CommitConflict
val committed: String < (Sync & Abort[FileException] & Abort[CommitConflict]) =
    Path.run {
        Path.transaction {
            for
                _ <- (Path("data") / "draft.txt").write("hello")
                t <- (Path("data") / "draft.txt").read
            yield t
        }
    }

// Discard staged writes unconditionally
val dryRun: Unit < (Sync & Abort[FileException]) =
    Path.run {
        Path.sandbox {
            (Path("data") / "probe.txt").write("test")
        }
    }
```

Besides `commit` (validate then replay) and `commitWith`, overlays expose `commitOverwrite` for unconditional last-writer-wins replay with no `CommitConflict` row.

`Path.transaction` commits automatically on success and aborts with `CommitConflict` when a read-set stamp diverges from the live lower view. Use `Path.virtual` when the caller needs to inspect staged state or choose a conflict policy after the block completes. The combinator returns `(result, overlay)`; call `commit`, `commitWith`, or `rollback` on the overlay before leaving the ambient `PathWrite` scope:

`commit`, `commitWith`, and `rollback` on a virtual overlay must run while `PathWrite` is still ambient (same or enclosing `runWith`); calling them after `PathWrite` is discharged leaves path operations unresolved at runtime.

```scala
import kyo.*

val committed: String < (Sync & Abort[FileException] & Abort[CommitConflict]) =
    Path.Service.inMemory.flatMap { lower =>
        Path.runWith(lower) {
            Path.virtual {
                for
                    _ <- (Path("data") / "draft.txt").write("hello")
                    t <- (Path("data") / "draft.txt").read
                yield t
            }.flatMap { case (text, overlay) =>
                overlay.commit.map(_ => text)
            }
        }
    }
```

`Path.transaction`, `Path.sandbox`, and `Path.virtual` install a temporary overlay over the ambient `PathWrite` handler; they require an enclosing `Path.run` / `Path.runWith`, not a standalone host runner.

When `overlay.commit` fails with `CommitConflict`, catch the failure row with `Abort.run` and inspect each `Conflict`:

```scala
import kyo.*

val attempt: Result[CommitConflict, Unit] < (Sync & Abort[FileException]) =
    Path.Service.inMemory.flatMap { lower =>
        val p = Path("data") / "shared.txt"
        Path.runWith(lower) {
            Path.virtual(p.write("staged")).flatMap { case (_, overlay) =>
                Abort.run[CommitConflict](overlay.commit)
            }
        }
    }
```

On `Result.Failure(CommitConflict(conflicts))`, retry with a per-path resolution instead of aborting. `Resolution.KeepOurs` replays the overlay's staged entry; `Resolution.KeepTheirs` keeps the live lower value unchanged. `commitWith` resolutions also include `Resolution.Write(entry)` (replay a caller-supplied `Path.Entry`) and `Resolution.Remove` (delete the path on replay):

```scala
import kyo.*

val resolved: Unit < (Sync & Abort[FileException]) =
    Path.Service.inMemory.flatMap { lower =>
        val p = Path("data") / "shared.txt"
        Path.runWith(lower) {
            Path.virtual(p.write("staged")).flatMap { case (_, overlay) =>
                overlay.commitWith(_ => Resolution.KeepTheirs)
            }
        }
    }
```

`Path.tempDir(prefix)` creates a directory through the active service, returns the `Path`, and registers recursive removal via the creating service when the enclosing `Scope` closes.

## File paths

Before reading or writing, you need a path value that identifies the target without touching the disk. `Path` is an immutable value built with the `/` operator or the `apply` factory; pure accessors like `parts` and `parent` require no capability.

The segment type `Part` accepts either a `String` or another `Path`; splicing a `Path` value expands its components inline:

```scala
import kyo.*

val config: Path = Path / "etc" / "myapp" / "config.toml"
val data: Path   = Path("var", "data", "myapp")

// Splice an existing Path into another path
val base: Path   = Path("home") / "user"
val nested: Path = base / Path("projects", "kyo")

// Pure accessors require no Path capability
val parts: Chunk[String] = config.parts
```

Filesystem reads and writes require a runner. Wrap read-only programs in `Path.runReadOnly` and read-write programs in `Path.run`:

```scala
import kyo.*

val text: String < (Sync & Abort[FileException]) =
    Path.runReadOnly((Path / "etc" / "app.toml").read)
```

`Path.fileSeparator` is the segment separator (`"/"` or `"\\"`) and `Path.pathSeparator` is the classpath-style delimiter (`":"` or `";"`) for the current OS.

Pure accessors require no effects:

| Accessor | Type | Description |
|---|---|---|
| `parts` | `Chunk[String]` | Individual segments |
| `name` | `Maybe[String]` | Final segment; `Absent` for a root or empty path |
| `parent` | `Maybe[Path]` | Containing directory |
| `extName` | `Maybe[String]` | Extension including the leading dot, e.g. `".gz"`; a leading dot in the filename is not treated as an extension |
| `isAbsolute` | `Boolean` | Whether the path starts at a filesystem root |

`ancestors` is a pure `Stream[Path, Any]` that yields `self`, then its parent, grandparent, and so on up to the filesystem root without reading the disk. Use `Stream.find` to locate the nearest ancestor that satisfies a predicate, such as the first directory containing a `build.sbt` marker:

```scala
import kyo.*

val src: Path = Path("home") / "user" / "project" / "src"

// Walk toward the root; return the first ancestor that contains build.sbt
val projectRoot: Maybe[Path] < (Sync & Abort[FileException]) =
    Path.runReadOnly {
        src.ancestors.find(ancestor => (ancestor / "build.sbt").exists)
    }
```

### Inspecting files

Before opening a file for read or write, check whether the path exists and what kind of entry it is. `exists`, `isDirectory`, `isRegularFile`, and `isSymbolicLink` suspend under `PathRead`. An inaccessible path produces `false` rather than failing, so a missing config file and a permission-denied path both return `false` instead of aborting. Run them inside `Path.runReadOnly`:

```scala
import kyo.*

val path: Path = Path / "dist" / "release" / "artifact.jar"

val checks: (Boolean, Boolean) < (Sync & Abort[FileException]) =
    Path.runReadOnly {
        for
            e <- path.exists
            f <- path.isRegularFile
        yield (e, f)
    }
```

`exists(followLinks: Boolean)` controls symlink traversal. All four methods return `false` on any permission or access failure.

`confinedTo(root)` resolves all symlinks in both `self` and `root`, then fails with `FileAccessDeniedException` if the real path of `self` falls outside the real path of `root`. A syntactic prefix check is not sufficient because a symlink inside `root` can point outside it. Use this for any tool that accepts user-supplied paths under a configured root:

```scala
import kyo.*

val root: Path = Path / "var" / "uploads"

// Adversarial traversal is rejected after symlink resolution
val safe: Path < (Sync & Abort[FileException]) =
    Path.runReadOnly {
        (root / "../../etc/passwd").confinedTo(root)
    }
```

`realPath` resolves every symbolic link in the chain and returns the canonical absolute path, without any containment check. It fails with `FileNotFoundException` if any element of the path does not exist, or `FileAccessDeniedException` if the filesystem denies access:

```scala
import kyo.*

val canonical: Path < (Sync & Abort[FileException]) =
    Path.runReadOnly {
        (Path / "var" / "run" / "app.sock").realPath
    }
```

## Reading files

Once you know where a file lives, bulk reads and streams pull its contents under `PathRead`. After `Path.runReadOnly`, the residual is `Sync & Abort[FileException]`:

```scala
import kyo.*

val path: Path = Path / "etc" / "app" / "config.toml"

val text: String < (Sync & Abort[FileException]) =
    Path.runReadOnly(path.read)
val bytes: Span[Byte] < (Sync & Abort[FileException]) =
    Path.runReadOnly(path.readBytes)
val lines: Chunk[String] < (Sync & Abort[FileException]) =
    Path.runReadOnly(path.readLines)
```

All three accept an optional `java.nio.charset.Charset`; the default is UTF-8.

`stat` returns `PathStat(lastModifiedMs, sizeBytes)` from a single underlying syscall, which guarantees both fields reflect the same measurement instant. Prefer `stat` over separate `size` and last-modified calls when both are needed:

```scala
import kyo.*

val info: Path.PathStat < (Sync & Abort[FileException]) =
    Path.runReadOnly((Path / "var" / "data" / "records.db").stat)

val sz: Long < (Sync & Abort[FileException]) =
    Path.runReadOnly((Path / "var" / "data" / "records.db").size)
```

Streaming reads keep only a buffer in memory at a time. The OS handle is opened when the stream starts and released when the enclosing `Scope` closes, whether by normal completion, error, or cancellation. All streaming read methods carry `Scope` in the stream's effect row:

```scala
import kyo.*

val processed: Unit < (Sync & Scope & Abort[FileException]) =
    Path.runReadOnly {
        Path("var", "log", "events.ndjson")
            .readLinesStream
            .foreach(line => Sync.defer(println(line)))
    }
```

`readStream(charset, bufferSize)` and `readBytesStream(bufferSize)` expose the buffer-size parameter for tuning. `walk` is a Scope-managed stream of directory entries (covered under Directory operations).

`tail` polls for new content appended to a file. It seeks to EOF, then sleeps for the configured `pollDelay` (default 100ms) and reads any new bytes. When the file size decreases it resets to position 0, handling log rotation and truncation. The stream carries `Async` because of the poll sleep. `tail` is a poll loop, not a kernel watch API (inotify or kqueue):

```scala
import kyo.*

val errors: Unit < (Async & Scope & Sync & Abort[FileException]) =
    Path.runReadOnly {
        Path("var", "app.log")
            .tail(500.millis)
            .filter(_.contains("ERROR"))
            .foreach(line => Sync.defer(println(line)))
    }
```

## Writing files

Persisting output or mutating the tree requires `PathWrite`. Run write methods inside `Path.run`. Write methods create parent directories by default (`createFolders = true`). Pass `createFolders = false` to fail when the parent is absent:

```scala
import kyo.*

val out: Path = Path("dist") / "build" / "version.txt"

val w: Unit < (Sync & Abort[FileException]) =
    Path.run(out.write("1.0.0"))
val a: Unit < (Sync & Abort[FileException]) =
    Path.run(out.append("\nbuilt by CI\n"))
val data: Span[Byte] = Span.from(Array[Byte](0x50.toByte, 0x4b.toByte))
val wb: Unit < (Sync & Abort[FileException]) =
    Path.run(out.writeBytes(data))
```

`writeLines` and `appendLines` follow each line with the platform line separator including the last line. Use `write(lines.mkString(sep))` to control the trailing newline yourself. `truncate(size)` shrinks or pads a file to exactly `size` bytes. `setLastModified(epochMs)` sets the last-modified timestamp.

`Stream[Byte, S].writeTo(path)`, `Stream[String, S].writeTo(path, charset)`, and `Stream[String, S].writeLinesTo(path, charset)` are stream sinks that acquire a write handle in a `Scope`. The sinks carry `PathWrite` in their row. If the stream fails, the partially written file is deleted before the error is re-raised:

```scala
import kyo.*

val sink: Unit < (Async & Scope & Sync & Abort[FileException]) =
    Path.run {
        Path("var", "app.log")
            .tail
            .filter(_.contains("ERROR"))
            .writeLinesTo(Path("var", "errors.log"))
    }
```

### Directory operations

Creating directories, listing children, and moving or deleting entries are write-side mutations that belong with the other `PathWrite` operations. `mkDir` creates a directory and all missing parents. `mkFile` creates an empty file with missing parents. `list` returns direct children; `list(glob)` filters by a glob pattern supporting `*`, `**`, `?`, `[...]`, and `{a,b}` alternation. `walk` returns a Scope-managed stream of all entries in the tree:

```scala
import kyo.*

val dir: Path = Path("var", "uploads")

val mk: Unit < (Sync & Abort[FileException]) =
    Path.run(dir.mkDir)
val all: Chunk[Path] < (Sync & Abort[FileException]) =
    Path.runReadOnly(dir.list)
val tree: Stream[Path, PathRead & Scope & Sync] =
    dir.walk
```

`move` and `copy` each accept optional flags for atomic moves, attribute copying, and symlink handling. `remove` returns `true` when the path was deleted and `false` when it was absent. `removeExisting` raises `FileNotFoundException` when the path does not exist. `removeAll` recursively deletes a directory and all its contents:

```scala
import kyo.*

val src: Path = Path("tmp") / "build-output"
val dst: Path = Path("dist") / "release"

val moved: Unit < (Sync & Abort[FileException]) =
    Path.run(src.move(dst))
val deleted: Boolean < (Sync & Abort[FileException]) =
    Path.run(src.remove)
```

## Error handling

`FileException` is a sealed abstract base. Three sealed marker traits partition the hierarchy by operation category:

| Exception | `FileReadException` | `FileWriteException` | `FileFsException` |
|---|:---:|:---:|:---:|
| `FileNotFoundException` | yes | yes | yes |
| `FileAccessDeniedException` | yes | yes | yes |
| `FileIsADirectoryException` | yes | yes | |
| `FileNotADirectoryException` | | | yes |
| `FileAlreadyExistsException` | | | yes |
| `FileDirectoryNotEmptyException` | | | yes |
| `FileIOException` | yes | yes | yes |

Each concrete exception implements only the marker traits that apply to it. After `Path.runReadOnly`, the runner folds the markers into `Abort[FileException]`:

```scala
import kyo.*

val content: String < (Sync & Abort[FileException]) =
    Path.runReadOnly {
        Abort.recover[FileNotFoundException] { _ =>
            "# default config\n"
        }(Path("etc", "app.toml").read)
    }
```

To materialize the error as a `Result` and decide what to do at the call site:

```scala
import kyo.*

val result: Result[FileException, String] < Sync =
    Abort.run[FileException] {
        Path.runReadOnly(Path("etc", "app.toml").read)
    }
```

`CommandException` is the sealed hierarchy for pre-launch failures:
- `ProgramNotFoundException(command)`: raised when the executable is not found on `$PATH`
- `PermissionDeniedException(command)`: raised when the caller lacks execute permission
- `WorkingDirectoryNotFoundException(path)`: raised when the configured `cwd` does not exist

## Standard directories

`Path.cwd` returns the current working directory. It reads at call time, so a `process.chdir` or fork with a different working directory takes effect on the next call:

```scala
import kyo.*

val cwd: Path < (Sync & Abort[FileException]) =
    Path.runReadOnly(Path.cwd)
```

`Path.basePaths` and `Path.userPaths` are lazy vals that provide OS-appropriate paths without requiring an application identity. On Linux they follow the XDG Base Directory Specification; on macOS they use the `Library` hierarchy; on Windows they use `APPDATA` and `LOCALAPPDATA`:

```scala
import kyo.*

val cacheDir: Path    = Path.basePaths.cache
val configDir: Path   = Path.basePaths.config
val homeDir: Path     = Path.userPaths.home
val downloadDir: Path = Path.userPaths.download
```

`BasePaths` fields: `cache`, `config`, `data`, `dataLocal`, `executable`, `preference`, `runtime`, `tmp`. Three fields whose platform meanings are not immediately obvious:

| Field | Linux | macOS | Windows |
|---|---|---|---|
| `dataLocal` | same as `data` (`$XDG_DATA_HOME`) | same as `data` (`~/Library/Application Support`) | `%LOCALAPPDATA%` (non-roaming; differs from `data` at `%APPDATA%`) |
| `executable` | `~/.local/bin` (`$XDG_BIN_HOME`) | `~/Applications` | `%LOCALAPPDATA%` |
| `runtime` | `$XDG_RUNTIME_DIR` or `~/.local/run` (session-scoped sockets and pipes) | `~/Library/Application Support` | `%LOCALAPPDATA%` |

`UserPaths` fields: `home`, `audio`, `desktop`, `document`, `download`, `font`, `picture`, `public`, `template`, `video`.

`Path.projectPaths(qualifier, organization, application)` derives per-application subdirectories under each base path:

```scala
import kyo.*

val proj            = Path.projectPaths("com", "myorg", "myapp")
val appConfig: Path = proj.config
val appCache: Path  = proj.cache
val appData: Path   = proj.data
```

`ProjectPaths` fields: `path`, `cache`, `config`, `data`, `dataLocal`, `preference`, `runtime`.

Temporary paths are service-scoped through the active runner. `Path.tempDir(prefix)` creates a directory and registers recursive removal when the enclosing `Scope` closes:

```scala
import kyo.*

val tmpDir: Path < (Sync & Scope & Abort[FileException]) =
    Path.run {
        Path.tempDir("kyo-build-")
    }
```

On JVM and Scala Native, `path.toJava: java.nio.file.Path` converts to the standard library type without a cast. It is not available on Scala.js.

## Running commands

When a deploy script, build tool, or health check needs to run an external program, `Command` builds an immutable process description and an execution method launches it. Each builder method returns a new `Command`; construction performs no I/O.

Arguments are passed directly to the OS with no shell interpretation. Pipes, globs, and variable expansion require an explicit shell:

```scala
import kyo.*

// Each string is one OS argument, no expansion
val count: String < (Async & Abort[CommandException]) =
    Command("grep", "-rc", "ERROR", "var/log").text

// Shell required for pipe operators
val piped: String < (Async & Abort[CommandException]) =
    Command("sh", "-c", "grep ERROR var/log/app.log | wc -l").text
```

Builder methods compose in any order, each returning a new `Command`:

```scala
import kyo.*

val build: ExitCode < (Async & Abort[CommandException]) =
    Command("npm", "run", "build")
        .cwd(Path("frontend"))
        .envAppend(Map("NODE_ENV" -> "production"))
        .redirectErrorStream(true)
        .waitFor

val pipeline: String < (Async & Abort[CommandException]) =
    Command("cat", "app.log")
        .andThen(Command("grep", "ERROR"))
        .andThen(Command("head", "-20"))
        .text
```

`andThen(that)` pipes stdout of one command into stdin of the next, equivalent to `|` in a shell.

Environment configuration:
- `envAppend(vars)` adds or overrides variables on top of the inherited environment
- `envRemove(names)` removes named variables from the inherited environment
- `envReplace(vars)` replaces the entire environment with the given map
- `envClear` clears all variables; the process inherits nothing

Stdin variants:
- `stdin(s: String)`: a UTF-8 encoded string (charset overridable)
- `stdin(bytes: Span[Byte])`: raw bytes
- `stdin(stream: Stream[Byte, Sync])`: a pure byte stream, drained into the child at spawn time
- `stdin(input: Process.Input)`: a `Process.Input` value; the three cases are `Process.Input.Inherit` (child reads from the parent's stdin), `Process.Input.FromStream(stream)` (a raw `InputStream` supplied by the caller), and `Process.Input.Pipe` (opens an unmanaged OS pipe, written via `proc.unsafe.stdinJava`)
- `inheritStdin`: pipes the parent process's stdin through to the child
- `pipeStdin`: opens an unmanaged pipe; the caller writes via `proc.unsafe.stdinJava`

> **Caution:** `pipeStdin` exposes the unsafe tier. `proc.unsafe.stdinJava` is a raw `OutputStream` that requires `AllowUnsafe` and bypasses Kyo's effect tracking. Use it only when the caller controls the write loop directly (for example, a JSON-RPC over stdio protocol). Prefer `stdin(stream: Stream[Byte, Sync])` for all other cases: it pumps bytes into the child's stdin through a background fiber at spawn time without leaving the safe API.

Output routing:
- `inheritStdout` / `inheritStderr` / `inheritIO` inherit streams from the parent process
- `stdoutToFile(path, append)` / `stderrToFile(path, append)` redirect output to a file
- `redirectErrorStream(true)` merges stderr into stdout

Execution methods:

| Method | Return type | Description |
|---|---|---|
| `text` | `String < (Async & Abort[CommandException])` | stdout as a UTF-8 string |
| `waitFor` | `ExitCode < (Async & Abort[CommandException])` | exit code as a value |
| `waitForSuccess` | `Unit < (Async & Abort[CommandException \| ExitCode])` | fails on non-zero exit |
| `textWithExitCode` | `(String, ExitCode) < (Async & Abort[CommandException])` | stdout and exit code together |
| `stream` | `Stream[Byte, Async & Scope & Abort[CommandException]]` | stdout as a byte stream |
| `spawn` | `Process < (Sync & Scope & Abort[CommandException])` | process handle, Scope-managed |
| `spawnUnscoped` | `Process < (Sync & Abort[CommandException])` | process handle, caller owns lifetime |

When you need the full stdout as a string and the process exits before returning, use `text`. When you need only the exit code, use `waitFor` or `waitForSuccess`. When stdout is large or arrives incrementally, use `stream` or `spawn` with `collectOutput`. When the process outlives the current scope or you manage lifetime explicitly, use `spawnUnscoped`.

The built `Command` is readable without launching: `args` returns `Chunk[String]`, `workDir` returns `Maybe[Path]` (the configured working directory, or `Absent` when none was set), and `env` returns `Map[String, String]` (the current environment snapshot reflecting any `envAppend`/`envRemove`/`envReplace` calls).

### Process handles

After `spawn`, the returned `Process` handle exposes stdout and stderr streams, lifecycle controls, and concurrent output draining. `Command.spawn` registers the process with the enclosing `Scope`. When the scope closes before the process exits, the process is forcibly killed. `Command.spawnUnscoped` omits scope registration and is appropriate for long-lived workers whose lifetime is managed explicitly:

```scala
import kyo.*

val example: Unit < (Async & Sync & Scope & Abort[CommandException]) =
    for
        proc  <- Command("my-server", "--port", "8080").spawn
        _     <- Async.sleep(5.seconds)
        alive <- proc.isAlive
        _     <- Sync.defer(println(s"alive: $alive"))
    yield ()
    // proc is forcibly killed when the Scope closes
```

`spawnUnscoped` omits scope registration. The caller is responsible for calling `destroy` or `destroyForcibly` at the appropriate moment: after a timed run, on application shutdown, or when the work unit completes:

```scala
import kyo.*

// The caller drives the process lifetime explicitly
val worker: Unit < (Async & Sync & Abort[CommandException]) =
    for
        proc <- Command("background-worker", "--queue", "jobs").spawnUnscoped
        _    <- Async.sleep(30.seconds)
        _    <- proc.destroyForcibly
    yield ()
```

`proc.stdout` and `proc.stderr` return `Stream[Byte, Sync & Scope]`; the underlying `InputStream` is closed when the enclosing `Scope` closes.

> **Caution:** Reading `stdout` then `stderr` sequentially deadlocks when the process writes more than the OS pipe buffer (~64 KB) to both channels. The unread producer blocks the OS, which in turn blocks `waitFor`. Use `collectOutput` to drain both concurrently.

```scala
import kyo.*

val capture: (Chunk[Byte], Chunk[Byte]) < (Async & Sync & Scope & Abort[CommandException]) =
    for
        proc       <- Command("build-tool", "--verbose").spawn
        (out, err) <- proc.collectOutput
    yield (out, err)
```

Other lifecycle operations on a `Process`:

| Method | Return type | Description |
|---|---|---|
| `waitFor` | `ExitCode < Async` | Suspends the fiber until exit |
| `waitFor(timeout)` | `Maybe[ExitCode] < Async` | Returns `Absent` on timeout |
| `exitCode` | `Maybe[ExitCode] < Sync` | Non-blocking poll; `Absent` if still running |
| `isAlive` | `Boolean < Sync` | Non-blocking liveness check |
| `pid` | `Long < Sync` | OS process identifier |
| `destroy` | `Unit < Sync` | Requests termination (SIGTERM or equivalent) |
| `destroyForcibly` | `Unit < Sync` | Forces termination (SIGKILL or equivalent) |

### Exit codes

Once a process exits, interpret its status through `ExitCode`. `ExitCode` is a three-case enum. `ExitCode.Signaled(number)` follows the POSIX shell convention where the raw integer value equals 128 + signal number:

- `ExitCode.Success`: raw value 0
- `ExitCode.Failure(code)`: any non-zero value that does not encode a signal
- `ExitCode.Signaled(number)`: the process was terminated by an OS signal; raw = 128 + number

`ExitCode.apply(raw: Int)` constructs the appropriate case:

```scala
import kyo.*

val ok: ExitCode     = ExitCode(0)   // Success
val fail: ExitCode   = ExitCode(1)   // Failure(1)
val killed: ExitCode = ExitCode(137) // Signaled(9), SIGKILL
```

Named constants are available for common POSIX signals: `ExitCode.SIGHUP`, `SIGINT`, `SIGQUIT`, `SIGKILL`, `SIGSEGV`, `SIGPIPE`, `SIGTERM`. Use them in pattern matches:

```scala
import kyo.*

def describe(code: ExitCode): String = code match
    case ExitCode.Success     => "succeeded"
    case ExitCode.SIGTERM     => "terminated gracefully"
    case ExitCode.SIGKILL     => "killed forcibly"
    case ExitCode.Signaled(n) => s"killed by signal $n"
    case ExitCode.Failure(n)  => s"failed with exit code $n"
```

`waitForSuccess` adds `ExitCode` to the abort channel on non-zero exits alongside `CommandException` for pre-launch failures. The union type distinguishes the two failure origins at the call site:

```scala
import kyo.*

val strict: Unit < (Async & Abort[CommandException | ExitCode]) =
    Command("sbt", "test").waitForSuccess

val withCode: (String, ExitCode) < (Async & Abort[CommandException]) =
    Command("make", "check").textWithExitCode
```

`ExitCode.toInt` converts a code back to its raw integer (0 for `Success`, the original code for `Failure(n)`, and 128 + n for `Signaled(n)`). `ExitCode.isSuccess` returns `true` when the code is `Success`. `ExitCode.signalName` returns `Present("SIGKILL")` and similar for the seven named POSIX signals, or `Absent` for `Success`, `Failure`, and any unrecognized signal number.

## System environment

`System.env[A](name)` and `System.property[A](name)` retrieve an environment variable or system property and parse it to type `A` using a `Parser[E, A]` typeclass instance. A missing variable returns `Absent`; a present but unparseable value fails with `Abort[E]`:

```scala
import kyo.*

val home: Maybe[String] < Sync =
    System.env[String]("HOME")

val port: Maybe[Int] < (Abort[NumberFormatException] & Sync) =
    System.property[Int]("server.port")
```

When the type parameter `A` has `E = Nothing` (such as `String`), the `Abort` disappears from the effect row. Each method also has a default-value variant: `System.env[A](name, default)` and `System.property[A](name, default)` return `A` and fall back to `default` when the variable is absent.

Built-in `Parser` instances cover: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `Byte`, `Short`, `Char`, `Duration`, `java.util.UUID`, `java.net.URI`, `java.net.URL`, `java.time.LocalDate`, `java.time.LocalTime`, `java.time.LocalDateTime`, and `Seq[A]` (comma-split). Provide a `given Parser[E, A]` to support custom types.

`System.lineSeparator` returns the platform line separator (`"\n"` on Linux and macOS, `"\r\n"` on Windows) as `String < Sync`. `System.userName` returns the OS user name as `String < Sync`. Both read from the host environment and carry `Sync`.

`System.live` is the default ambient instance backed by the host environment. All `System.*` calls delegate to it when `System.let` has not been called.

`System.let(system)(computation)` replaces the ambient `System` for the duration of the computation. Use it in tests to inject controlled values without touching the process environment:

```scala
import kyo.*

val mock: System = System(new System.Unsafe:
    def env(name: String)(using AllowUnsafe): Maybe[String] =
        if name == "APP_ENV" then Present("staging") else Absent
    def property(name: String)(using AllowUnsafe): Maybe[String] = Absent
    def lineSeparator()(using AllowUnsafe): String               = "\n"
    def userName()(using AllowUnsafe): String                    = "ci"
    def operatingSystem()(using AllowUnsafe): System.OS          = System.OS.Linux
    def architecture()(using AllowUnsafe): System.Arch           = System.Arch.X86_64
    def availableProcessors()(using AllowUnsafe): Int            = 4)

val result: Maybe[String] < Sync =
    System.let(mock)(System.env[String]("APP_ENV"))
```

`System.operatingSystem` returns a `System.OS` value and `System.architecture` returns `System.Arch`. Both are available across all three platforms. `System.OS` cases: `Linux`, `MacOS`, `Windows`, `BSD`, `Solaris`, `IBMI`, `AIX`, `Unknown`. `System.Arch` cases: `X86`, `X86_64`, `Arm`, `Aarch64`, `Unknown`:

```scala
import kyo.*

val adapted: String < Sync =
    for
        os   <- System.operatingSystem
        arch <- System.architecture
        cpus <- System.availableProcessors
    yield s"$os / $arch, $cpus processors"
```

## Demos

Runnable demos live under `shared/src/test/scala/demo/`. Run with `sbt 'kyo-systemJVM/Test/runMain demo.<Name>'`.

- [`ConfigWorkspaceDemo.scala`](shared/src/test/scala/demo/ConfigWorkspaceDemo.scala): config deploy workspace with overlay transaction and sandbox probe

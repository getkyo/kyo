# kyo-system

The file system, OS processes, and the process environment: `Path`, `Command`, `Process`,
`System`, and the `FileException` hierarchy. These types were part of `kyo-core` through
the previous release and moved here unchanged.

Depends on `kyo-core`. Available on JVM, JS, Native, and WASM.

`Path` is the cross-platform file API. `Command` and `Process` cover OS process execution.

## `Path` construction and inspection

Paths build with the `/` operator (immutable, value-typed):

```scala
val config: Path = Path / "etc" / "myapp" / "config.toml"
val data: Path   = Path("var", "data", "myapp")

val nested: Path = Path("home") / "user" / Path("projects", "kyo")
```

`Path.parts: Chunk[String]` lists components; `Path.name` returns the final segment; `Path.parent` returns the containing directory; `Path.extName` returns the extension (including the leading dot); `Path.isAbsolute` is the boolean predicate.

`Path.projectPaths(qualifier, organization, application)` returns a `ProjectPaths` value with platform-appropriate config, cache, data, and log directories for the named application. The related `Path.basePaths: BasePaths` and `Path.userPaths: UserPaths` provide OS-standard root paths (temp dir, home dir, etc.) without requiring an application identity.

Inspection methods return `... < Sync` (no `Abort`):

```scala
import kyo.*
val config: Path = Path / "etc" / "myapp" / "config.toml"

val checks: (Boolean, Boolean, Boolean) < Sync =
    for
        e <- config.exists
        d <- config.isDirectory
        f <- config.isRegularFile
    yield (e, d, f)
```

> **Note:** `exists`, `isDirectory`, `isRegularFile`, and `isSymbolicLink` return `false` for inaccessible paths rather than failing. They require only `Sync`, not `Abort`.

## Reading and writing

Every reading method adds `Abort[FileReadException]`; every writing method adds `Abort[FileWriteException]`; directory-mutation methods add `Abort[FileFsException]`:

```scala
import kyo.*
val config: Path = Path / "etc" / "myapp" / "config.toml"

val text: String < (Sync & Abort[FileReadException]) =
    config.read

val bytes: Span[Byte] < (Sync & Abort[FileReadException]) =
    config.readBytes

val lines: Chunk[String] < (Sync & Abort[FileReadException]) =
    config.readLines

val wrote: Unit < (Sync & Abort[FileWriteException]) =
    Path("var", "out.txt").write("hello\n")

val appended: Unit < (Sync & Abort[FileWriteException]) =
    Path("var", "log.txt").append("entry\n")
```

`write`, `writeBytes`, `writeLines`, `append`, `appendBytes`, `appendLines` accept an optional `createFolders: Boolean = true` to auto-create parent directories. `truncate(size)` shrinks or extends a file. `mkDir`, `list`, `list(glob)`, `move`, `copy`, `remove`, `removeExisting`, `removeAll` cover directory and lifecycle operations.

A typed handle of decode failure looks like:

```scala
val result: Result[FileReadException, String] < Sync =
    Abort.run[FileReadException] {
        Path("missing.txt").read
    }

// On a missing file the result is:
// Result.Failure(FileNotFoundException(Path("missing.txt")))
```

The `FileException` hierarchy:
- `FileException` (sealed abstract base)
  - `FileReadException`, `FileWriteException`, `FileFsException` (sealed marker traits)
  - Concrete case classes: `FileNotFoundException`, `FileAccessDeniedException`, `FileIsADirectoryException`, `FileNotADirectoryException`, `FileAlreadyExistsException`, `FileDirectoryNotEmptyException`, `FileIOException`.

## Streaming reads

Streaming reads produce `Stream` values that carry `Scope` in their effect type. The OS handle is opened lazily and released when the enclosing `Scope` closes.

```scala
import kyo.*

val processed: Unit < (Async & Sync & Scope & Abort[FileReadException]) =
    Path("var", "log", "events.ndjson")
        .readLinesStream
        .map { line => parseEvent(line) }
        .foreach { event => process(event) }

trait Event
def parseEvent(line: String): Event = ???
def process(e: Event): Unit < Sync  = ???
```

`readStream`, `readBytesStream`, `readLinesStream`, `walk` (directory tree), and `tail` (follow file updates) all return `Scope`-managed streams. `Path.ReadResult` is the typed wrapper around the raw byte count returned by low-level read operations: `ReadResult.Eof` signals end-of-file, and a positive value is the number of bytes read.

> **Note:** Streaming reads carry `Scope` in their effect type. The OS handle is released only when the enclosing `Scope` closes (normal completion, error, or cancellation).

## Running OS processes

When you need to launch an external process (a git invocation, a build step), build a `Command`. Execute with `spawn` (returns a `Process` handle), `text` (collects stdout as `String`), `waitFor` (returns the exit code), or `waitForSuccess` (fails with `ExitCode` on a non-zero exit).

```scala
val output: String < (Async & Abort[CommandException]) =
    Command("git", "rev-parse", "HEAD").text

val exitCode: Process.ExitCode < (Async & Abort[CommandException]) =
    Command("npm", "test").cwd(Path("frontend")).waitFor

val piped: String < (Async & Abort[CommandException]) =
    Command("grep", "ERROR").andThen(Command("wc", "-l")).text
```

`cwd(path)` changes the working directory. `envAppend(map)` adds environment variables. `andThen(that)` pipes stdout of the first command into stdin of the next. `Command.stdin` accepts a `String`, a `Span[Byte]`, a `Stream[Byte, Sync]`, or a `Process.Input` directly; `Process.Input.Inherit` pipes the parent process's stdin through, and `Process.Input.FromStream(is)` feeds a raw `InputStream`.

> **Caution:** `Command(args...)` performs no shell interpretation. Pipes, globs, redirects, and variable expansion require an explicit shell: `Command("sh", "-c", "ls *.log | wc -l")`.

`Process` is the running-process handle:

```scala
val example: Unit < (Async & Sync & Scope & Abort[CommandException]) =
    Command("long-running-thing").spawn.map { proc =>
        proc.isAlive.map { alive =>
            Log.info(s"alive: $alive").andThen {
                proc.collectOutput.map { (out, err) =>
                    Log.info(s"out: ${out.length} bytes, err: ${err.length} bytes")
                }
            }
        }
    }
```

`Process.stdout` and `Process.stderr` expose the output streams directly. `Process.waitFor`, `Process.waitFor(timeout)`, `Process.exitCode`, `Process.destroy`, `Process.destroyForcibly` cover lifecycle. Stdin is provided up front via the `Command` builder (`Command.stdin`), not read back off `Process`; the unsafe tier exposes `Process.unsafe.stdinJava` for callers that opened the pipe with `Command.pipeStdin`.

> **Caution:** Reading `stdout` and `stderr` sequentially can deadlock when output exceeds the ~64KB pipe buffer (the producer blocks on the unread stream). Use `Process.collectOutput` to drain both concurrently.

`CommandException` is the sealed hierarchy for launch failures, before a process exists. Callers usually recover by fixing setup: `ProgramNotFoundException` means choose another executable or repair `PATH`, `PermissionDeniedException` means fix execute permissions or select an allowed binary, and `WorkingDirectoryNotFoundException` means create or choose a valid `cwd`. Once a process starts, failed program execution is reported through the typed `Process.ExitCode` value.

## `System`

```scala
val maxRetries: Maybe[Int] < (Sync & Abort[NumberFormatException]) =
    System.property[Int]("app.maxRetries")

val homeDir: Maybe[String] < Sync =
    System.env[String]("HOME")
```

`System.env(name)` and `System.property(name)` are parameterised by a `Parser[E, A]` typeclass. Built-in `Parser` instances exist for primitive types, `String`, `Duration`, `kyo.UUID`, `java.util.UUID` (the JVM type), `java.net.URI`, `java.net.URL`, and the standard `java.time` types. Parse failures raise `Abort[E]` per the parser.

Canonical `kyo.UUID` values report malformed input as `UUID.InvalidUUID`:

```scala
val serviceId: Maybe[UUID] < (Sync & Abort[UUID.InvalidUUID]) =
    System.property[UUID]("service.id")
```

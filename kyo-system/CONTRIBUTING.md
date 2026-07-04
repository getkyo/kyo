# Contributing to kyo-system

Module-specific guide for kyo-system. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-system.

**The headline invariant:** every public operation in kyo-system is backed by two tiers that are structurally coupled. The safe tier (`Path`, `Command`, `Process`, `System`) tracks all I/O in the type system and lifts platform I/O through `Sync.Unsafe.defer`. The unsafe tier (`Path.Unsafe`, `Command.Unsafe`, `Process.Unsafe`, `System.Unsafe`) is the single place where platform-specific I/O actually executes. The two tiers must stay in sync: every abstract method added to an `Unsafe` class requires a matching safe-tier extension method that lifts it, and every platform split requires a stub in every platform leaf.

---

## Architecture overview

kyo-system owns five capabilities:

| Capability | Safe type | Unsafe abstract class | Effect row |
|---|---|---|---|
| File paths and I/O | `Path` (opaque) | `Path.Unsafe` | `Sync`, `Abort[FileReadException]`, `Abort[FileWriteException]`, `Abort[FileFsException]`, `Scope` |
| OS process launch | `Command` (opaque) | `Command.Unsafe` | `Sync`, `Async`, `Abort[CommandException]`, `Scope` |
| Running process handle | `Process` (opaque) | `Process.Unsafe` | `Sync`, `Async`, `Scope` |
| System environment | `System` (abstract class) | `System.Unsafe` | `Sync`, `Abort[E]` |
| Stream-to-file sinks | `StreamFileExtensions` (object) | (no separate unsafe class; delegates to `Path.WriteHandle`) | `Scope`, `Sync`, `Abort[FileException]` |

The typed error hierarchies for the two operation families are:

- `FileException` and its sub-traits (`FileReadException`, `FileWriteException`, `FileFsException`) cover all Path I/O.
- `CommandException` covers pre-launch failures for Command.

**Dependency rule:** kyo-system depends on `kyo-core` only. No other kyo module is a compile-time dependency. A future `kyo-eventlog` edge arrives when `FileJournal` is introduced; do not add it before then.

### Source layout

```
kyo-system/
  shared/src/main/scala/kyo/
    Path.scala                    # opaque type, safe tier, abstract Unsafe, handle types
    Command.scala                 # opaque type, safe tier, abstract Unsafe, EnvMode
    Process.scala                 # opaque type, safe tier, abstract Unsafe, ExitCode, Input
    System.scala                  # abstract class, live impl, Local, Parser type class
    StreamFileExtensions.scala    # stream-to-file sinks; exported to package
    FileException.scala           # sealed hierarchy with marker traits
    CommandException.scala        # sealed hierarchy for pre-launch failures
    internal/PathDirectories.scala  # shared OS directory resolution logic (private[kyo])

  jvm-native/src/main/scala/kyo/
    PathJvmNative.scala           # toJava extension (JVM + Native only)
    internal/PathPlatformSpecific.scala   # NioPathUnsafe backed by java.nio.file.Path
    internal/ProcessPlatformSpecific.scala
    internal/SystemPlatformSpecific.scala # delegates to java.lang.System

  js-wasm/src/main/scala/kyo/
    internal/PathPlatformSpecific.scala   # Node.js-backed using NodeFs/NodePath facades
    internal/ProcessPlatformSpecific.scala
    internal/SystemPlatformSpecific.scala # Node process.env / process.platform / process.arch

  shared/src/test/scala/kyo/
    PathTest.scala, PathSeparatorsTest.scala, PathSizeTest.scala, PathStatTest.scala
    CommandTest.scala, ProcessTest.scala, SystemTest.scala, FileExceptionTest.scala

  jvm/src/test/scala/kyo/
    PathJvmTest.scala             # JVM-only: toJava, JFiles.createSymbolicLink
```

---

## Path API

### Safe-tier surface

`Path` is an opaque type wrapping `Path.Unsafe`. The safe tier (extension methods on `Path`, defined in `object Path`) provides effect-tracked I/O:

| Safe method | Effect row |
|---|---|
| `path.exists` | `Boolean < Sync` |
| `path.isDirectory` | `Boolean < Sync` |
| `path.isRegularFile` | `Boolean < Sync` |
| `path.isSymbolicLink` | `Boolean < Sync` |
| `path.realPath` | `Path < (Sync & Abort[FileException])` |
| `path.stat` | `PathStat < (Sync & Abort[FileReadException])` |
| `path.size` | `Long < (Sync & Abort[FileReadException])` |
| `path.read` | `String < (Sync & Abort[FileReadException])` |
| `path.readBytes` | `Span[Byte] < (Sync & Abort[FileReadException])` |
| `path.readLines` | `Chunk[String] < (Sync & Abort[FileReadException])` |
| `path.readStream` | `Stream[String, Scope & Sync & Abort[FileReadException]]` |
| `path.readBytesStream` | `Stream[Byte, Scope & Sync & Abort[FileReadException]]` |
| `path.readLinesStream` | `Stream[String, Scope & Sync & Abort[FileReadException]]` |
| `path.tail` | `Stream[String, Async & Scope & Abort[FileReadException]]` |
| `path.write`, `writeBytes`, `writeLines` | `Unit < (Sync & Abort[FileWriteException])` |
| `path.append`, `appendBytes`, `appendLines` | `Unit < (Sync & Abort[FileWriteException])` |
| `path.truncate`, `path.setLastModified` | `Unit < (Sync & Abort[FileWriteException])` |
| `path.mkDir`, `path.mkFile` | `Unit < (Sync & Abort[FileFsException])` |
| `path.list` | `Chunk[Path] < (Sync & Abort[FileFsException])` |
| `path.walk` | `Stream[Path, Sync & Scope & Abort[FileFsException]]` |
| `path.move`, `path.copy`, `path.remove`, `path.removeAll` | `(Unit | Boolean) < (Sync & Abort[FileFsException])` |
| `path.confinedTo(root)` | `Path < (Sync & Abort[FileException])` |

#### Companion-level constants

```scala
Path.pathSeparator  // ":" on Unix, ";" on Windows, Node's path.delimiter on JS
Path.fileSeparator  // "/" on Unix, "\\" on Windows, Node's path.sep on JS
```

Both are `val` on `object Path`, computed once at companion-object initialization via `platformPathSeparator` / `platformFileSeparator` on the `PathPlatformSpecific` trait.

#### Key design points

- `Path` is immutable. Every I/O method lifts through `Sync.Unsafe.defer` at the safe tier and through `AllowUnsafe` at the abstract-class tier.
- Inspection methods (`exists`, `isDirectory`, `isRegularFile`, `isSymbolicLink`) require only `Sync`, not `Abort`: they return `false` for inaccessible or non-existent paths rather than failing.
- Streaming methods (`readStream`, `readBytesStream`, `readLinesStream`, `walk`) carry `Scope` so the underlying OS handle is closed when the enclosing scope exits, whether it completes normally or aborts.
- `path.tail` is the only streaming method that adds `Async` (it sleeps between polls).
- `path.stat` returns a `PathStat(lastModifiedMs, sizeBytes)` from a single underlying syscall, guaranteeing that both values reflect the same instant.
- `path.confinedTo(root)` resolves both paths through `realPath` before comparing prefixes, so a symlink inside the root that escapes to outside is caught. Use it anywhere a configured root is combined with user-supplied relative paths.

### Unsafe tier

`Path.Unsafe` is the abstract class that platform implementations extend. Each abstract method takes `(using AllowUnsafe, Frame)` for effectful operations, or just `(using AllowUnsafe)` for handle operations. The safe-tier extension methods delegate to `self.unsafe.*` inside `Sync.Unsafe.defer`.

The safe-tier lift for a method that returns a `Result` is always:

```scala
def myOp(using Frame): T < (Sync & Abort[SomeException]) =
    Sync.Unsafe.defer(Abort.get(self.unsafe.myOp()))
```

For a method that returns a plain value (no failure), omit `Abort.get`:

```scala
def myFlag(using Frame): Boolean < Sync =
    Sync.Unsafe.defer(self.unsafe.myFlag())
```

### Handle types

Four abstract handle classes live in `object Path` as `private[kyo]`:

| Handle | Returned by | Key method |
|---|---|---|
| `ReadHandle` | `Path.Unsafe.openRead` | `readChunk(buf: Array[Byte]): ReadResult` |
| `LineReadHandle` | `Path.Unsafe.openReadLines(charset)` | `readLine(): Maybe[String]` |
| `WriteHandle` | `Path.Unsafe.openWrite(append, createFolders)` | `writeBytes`, `writeString` |
| `WalkHandle` | `Path.Unsafe.openWalk(maxDepth, followLinks)` | `next(): Maybe[Path]` |

All four have a `close()(using AllowUnsafe): Unit` method. The safe tier always acquires them with `Scope.acquireRelease` so they are closed when the scope exits. Never hold a handle outside a `Scope`.

`ReadResult` is an opaque `Int` wrapper. `ReadResult.Eof` signals end of file; a positive value is the byte count. Use `.isEof` and `.bytesRead` rather than comparing against `-1` directly.

### Adding a new Path operation

1. Add the abstract method to `Path.Unsafe` in `shared/src/main/scala/kyo/Path.scala`. Decide its `Result` error type: `FileReadException`, `FileWriteException`, or `FileFsException`.

2. Add the safe-tier extension method directly below the other safe methods in `object Path` (`extension (self: Path)`). Always lift with `Sync.Unsafe.defer(Abort.get(...))`.

3. Implement in `jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala` on `NioPathUnsafe` using `java.nio.file.*`.

4. Implement in `js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala` on the Node.js-backed path class using `NodeFs` / `NodePath` facades.

5. Add cross-platform test leaves in `shared/src/test/scala/kyo/PathTest.scala`. Place mechanics that genuinely require JVM APIs (for example symlink creation via `JFiles.createSymbolicLink`) in `jvm/src/test/scala/kyo/PathJvmTest.scala`. Contract-level tests belong in the cross-platform shared tree.

### Cross-platform discipline for Path

| Tree | Content |
|---|---|
| `shared/src/main` | `Path.scala` (opaque type, safe tier, abstract `Unsafe`, handle types), `internal/PathDirectories.scala` (shared OS directory logic) |
| `jvm-native/src/main` | `NioPathUnsafe` backed by `java.nio.file.Path`; `PathJvmNative.scala` for `toJava` |
| `js-wasm/src/main` | Node.js-backed impl using `NodeFs` / `NodePath` facades |

The JS implementation uses `@JSImport("node:fs", ...)` and `@JSImport("node:path", ...)` facades. These facades are the only place where `js.native` / `@js.native` appears in path-related code.

When adding a new platform-specific capability, supply a stub in every platform leaf. On a non-supporting platform the stub must raise the appropriate `FileException` or return a documented no-op; it must never throw a raw exception.

`PathJvmNative.scala` exposes a `toJava: java.nio.file.Path` extension method. This extension lives in `jvm-native/` because `java.nio.file.Path` is absent on Scala.js. No JS-specific file lives directly under `kyo/` (only under `internal/`).

---

## FileException hierarchy

```
FileException (sealed abstract class, extends KyoException)
  FileReadException (sealed trait)
  FileWriteException (sealed trait)
  FileFsException (sealed trait)

Concrete leaves and which marker traits they implement:
  FileNotFoundException         - FileReadException, FileWriteException, FileFsException
  FileAccessDeniedException     - FileReadException, FileWriteException, FileFsException
  FileIsADirectoryException     - FileReadException, FileWriteException
  FileNotADirectoryException    - FileFsException
  FileAlreadyExistsException    - FileFsException
  FileDirectoryNotEmptyException - FileFsException
  FileIOException               - FileReadException, FileWriteException, FileFsException
```

Use the most specific subtype. Do not use `FileIOException` when a more specific variant exists (`FileNotFoundException` for a missing file, `FileAccessDeniedException` for a permission failure). Each leaf implements only the marker traits of operations that can actually produce it; this is what makes precise `Abort.recover[FileNotFoundException]` matching work at the call site.

When adding a new leaf, determine which operation categories can produce it and mix in exactly those marker traits. A leaf that mixes in all three when only read operations can raise it is incorrect.

---

## CommandException hierarchy

```
CommandException (sealed abstract class, extends KyoException)
  ProgramNotFoundException(command: String)
  PermissionDeniedException(command: String)
  WorkingDirectoryNotFoundException(path: Path)
```

Unlike `FileException`, `CommandException` has no marker sub-traits. All three leaves extend `CommandException` directly and are exhaustively matchable as a sealed family. Each leaf carries the string or path that triggered the failure; its human-readable message is on the leaf constructor.

`CommandException` covers only pre-launch failures (detectable before any OS process is created). It does not cover runtime process failures (non-zero exits); those surface as `Process.ExitCode` values.

When adding a new `CommandException` leaf, extend `CommandException` directly (no marker traits), carry the relevant identifier as a constructor parameter, and place the human-readable message in the leaf's constructor body rather than at the catch site.

---

## Command API

### Builder and execution model

`Command` is an opaque type wrapping `Command.Unsafe`. It is a pure value: all builder methods (`cwd`, `envAppend`, `envRemove`, `envReplace`, `envClear`, `stdin`, `inheritStdin`, `inheritStdout`, `inheritStderr`, `pipeStdin`, `inheritIO`, `stdoutToFile`, `stderrToFile`, `redirectErrorStream`, `andThen`) return a new `Command` without performing any I/O.

Execution methods launch the process:

| Method | Effect row | What it does |
|---|---|---|
| `spawn` | `Process < (Sync & Scope & Abort[CommandException])` | Spawns; registers with enclosing `Scope` for cleanup |
| `spawnUnscoped` | `Process < (Sync & Abort[CommandException])` | Spawns; caller owns the lifetime |
| `text` | `String < (Async & Abort[CommandException])` | Spawns, waits, returns stdout as UTF-8 |
| `stream` | `Stream[Byte, Async & Scope & Abort[CommandException]]` | Spawns; returns stdout as a byte stream |
| `waitFor` | `ExitCode < (Async & Abort[CommandException])` | Spawns, waits, returns exit code |
| `waitForSuccess` | `Unit < (Async & Abort[CommandException | ExitCode])` | Spawns, waits, aborts on non-zero exit |
| `textWithExitCode` | `(String, ExitCode) < (Async & Abort[CommandException])` | Spawns, drains stdout+stderr concurrently, returns both |

`spawn` mirrors `Fiber.init` (scoped); `spawnUnscoped` mirrors `Fiber.initUnscoped`. Use `spawnUnscoped` only for long-lived worker processes that outlive the spawning computation's scope and are closed explicitly.

`Abort[CommandException]` covers only pre-launch failures (program not found, permission denied, missing working directory). Runtime non-zero exits are `Process.ExitCode` values, not exceptions. Use `waitForSuccess` when a non-zero exit should abort the effect.

### Shell interpretation and injection

Arguments are passed as-is to the OS. There is no shell interpretation. Each `String` in `Command("prog", "arg1", "arg2")` becomes one process argument. Shell features (globbing, `|` pipes, variable expansion) require wrapping with `Command("sh", "-c", "...")` or equivalent.

Use `andThen` for UNIX-style piping: `a.andThen(b)` routes `a`'s stdout into `b`'s stdin.

### EnvMode

`Command.EnvMode` (a `private[kyo]` enum) records how the child process environment is composed relative to the parent:

| `EnvMode` case | Safe builder | Meaning |
|---|---|---|
| `Inherit` | (default) | Full parent environment |
| `Append(vars)` | `envAppend(vars)` | Parent plus additional vars |
| `Remove(names)` | `envRemove(names)` | Parent minus named vars |
| `AppendThenRemove(vars, names)` | `envAppend` then `envRemove` | Parent plus vars, minus names |
| `Replace(vars)` | `envReplace(vars)` | Exactly these vars; parent vars discarded |
| `Clear` | `envClear` | No vars at all |
| `ClearThenAppend(vars)` | `envClear` then `envAppend` | Exactly these vars from scratch |

Never construct `EnvMode` values directly in tests or production code outside `Command.Unsafe` implementations.

### `Command.Unsafe`

`Command.Unsafe` is an abstract class (not a trait) marked `Serializable`. Its effectful methods return `Fiber.Unsafe` for async execution. The `spawn` method returns `Result[CommandException, Process.Unsafe]` so pre-launch failures are typed.

Platform implementations live in `ProcessPlatformSpecific` in the `jvm-native/` and `js-wasm/` trees. The factory `ProcessPlatformSpecific.makeCommand(args: Chunk[String])` is the single entry point for constructing a platform `Command.Unsafe`; `Command.apply` delegates to it.

---

## Process API

### ExitCode

`ExitCode` is exported to the `kyo` package (`export Process.ExitCode`). Its three cases:

- `ExitCode.Success` (exit value 0)
- `ExitCode.Failure(code)` (non-zero, non-signal)
- `ExitCode.Signaled(number)` (exit value = 128 + signal number, POSIX convention)

`ExitCode.apply(code: Int)` maps a raw integer to the right case. Named signal constants (`SIGHUP`, `SIGINT`, `SIGTERM`, etc.) are provided on the companion for pattern matching.

`ExitCode` has a `Render[ExitCode]` given instance so it prints descriptively.

### Process extension methods

`Process` is an opaque type wrapping `Process.Unsafe`. Extension methods:

| Method | Effect row | Note |
|---|---|---|
| `stdout` | `Stream[Byte, Sync & Scope]` | Scope-managed InputStream |
| `stderr` | `Stream[Byte, Sync & Scope]` | Scope-managed InputStream |
| `waitFor` | `ExitCode < Async` | Fiber-suspending, not thread-blocking |
| `waitFor(timeout)` | `Maybe[ExitCode] < Async` | `Absent` on timeout |
| `exitCode` | `Maybe[ExitCode] < Sync` | Non-blocking poll |
| `collectOutput` | `(Chunk[Byte], Chunk[Byte]) < (Async & Scope)` | Concurrent stdout+stderr drain |
| `isAlive` | `Boolean < Sync` | |
| `pid` | `Long < Sync` | |
| `destroy` | `Unit < Sync` | SIGTERM / equivalent |
| `destroyForcibly` | `Unit < Sync` | SIGKILL / equivalent |

**Critical:** reading `stdout` and `stderr` sequentially deadlocks when the process produces more than the OS pipe buffer (~64 KB) on both streams. Use `collectOutput` whenever both streams must be consumed. `Command.textWithExitCode` drains them concurrently for the same reason.

`waitFor` suspends the current fiber using the platform's async notification mechanism (`Process.onExit()` on JVM/Native, the `'exit'` event on Node.js). No OS thread is blocked.

### `Process.Input`

`Process.Input` is a sealed trait with three cases:

- `Input.Inherit` passes the parent's stdin through.
- `Input.FromStream(stream: InputStream)` feeds the given stream into the child's stdin via a background fiber at spawn time.
- `Input.Pipe` opens an unmanaged pipe; the caller drives writes directly via `proc.unsafe.stdinJava`. Use `Pipe` when wiring a child into a custom protocol (for example JSON-RPC over stdio) where the caller controls when bytes flow.

Use `Command.pipeStdin` to set `Pipe` mode; then access `proc.unsafe.stdinJava(using allowUnsafe)` for direct write access.

---

## System API

### Structure and Local

`System` is an abstract class backed by a `Local[System]` initialised to `System.live`. All companion-object methods (`env`, `property`, `lineSeparator`, etc.) read through `local.use(...)`. This means `System.let(customImpl)(body)` is the correct way to substitute a test implementation for the duration of `body`; there is no global mutable state.

`System.live` is the default implementation. It reads environment variables and system properties through `SystemPlatformSpecific`, which is the only place the platform split appears.

### Parser type class

`System.Parser[E, A]` converts a `String` into `Result[E, A]`. Built-in instances cover:

- Primitives: `String`, `Int`, `Long`, `Float`, `Double`, `Boolean`, `Byte`, `Short`, `Char`
- JVM types: `java.util.UUID`, `java.net.URI`, `java.net.URL`
- Temporal: `LocalDate`, `LocalTime`, `LocalDateTime`
- Kyo types: `Duration`
- Collections: `Seq[A]` (comma-split, then parse each element)

Provide a custom parser with `Parser(v => Result.catching[E](...))`; the error type appears in the `Abort[E]` of the calling effect, so callers can recover it precisely.

`System.env[A][E](name)` and `System.property[A][E](name)` use `Reducible[Abort[E]]` to eliminate `Abort[Nothing]` from the row when the parser's error type is `Nothing` (the `String` parser).

### Platform split for System

| Tree | What it provides |
|---|---|
| `jvm-native/internal/SystemPlatformSpecific` | `java.lang.System.getenv`, `java.lang.System.getProperty`, `java.lang.System.getProperty("os.name")` |
| `js-wasm/internal/SystemPlatformSpecific` | `process.env` for environment variables; `process.platform` and `process.arch` for OS and CPU detection (Scala.js returns `null` for `os.name`/`os.arch` Java properties) |

The shared `System.live` implementation does OS detection in one place by normalising the string returned by `SystemPlatformSpecific.osName()`. Do not add OS-detection logic outside `System.live`; add a new case to its `if/else` chain instead.

### `kyo.System` shadows `java.lang.System`

Within any file that imports `kyo.*`, the unqualified name `System` refers to `kyo.System`. Always use the fully-qualified name `java.lang.System` when the Java class is needed (for example `java.lang.System.arraycopy`).

---

## StreamFileExtensions

`StreamFileExtensions` provides file-write sinks on two element types:

- `Stream[Byte, S]` gets `writeTo(path)`.
- `Stream[String, S]` gets `writeTo(path, charset)` and `writeLinesTo(path, charset)`.

`writeLinesTo` exists only on `Stream[String, S]`; there is no byte-stream counterpart. It lives in kyo-system (not kyo-core) because it couples to `Path` and `Path.WriteHandle`, which are kyo-system types.

The private `writeWith(path)(body)` helper implements the shared write contract:

1. Acquires a `WriteHandle` via `Path.Unsafe.openWrite` inside a `Scope.acquireRelease`.
2. Runs `body(handle)` inside `Abort.run[FileWriteException]`.
3. On failure, deletes the partially-written file with `path.remove` before re-raising the error.

Every call to platform I/O inside `StreamFileExtensions` must carry a `// Unsafe:` comment explaining which safe-tier contract it bridges. The four existing sites are:

- In `writeWith` (opening the write handle): `Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(...)))` with comment `// Unsafe: bridges Path.Unsafe.openWrite into the safe tier; the handle is released by the enclosing Scope.`
- In `writeTo[Byte]` (writing a byte chunk): `Sync.Unsafe.defer(Abort.get(handle.writeBytes(chunk)))` with comment `// Unsafe: bridges Path.WriteHandle.writeBytes into the safe tier under the acquired Scope handle.`
- In `writeTo[String]` (writing a string chunk): `Sync.Unsafe.defer(Abort.get(handle.writeString(s, charset)))` with comment `// Unsafe: bridges Path.WriteHandle.writeString into the safe tier under the acquired Scope handle.`
- In `writeLinesTo` (writing a line with separator): `Sync.Unsafe.defer(Abort.get(handle.writeString(s + sep, charset)))` with comment `// Unsafe: bridges Path.WriteHandle.writeString into the safe tier under the acquired Scope handle.`

`writeLinesTo` reads `java.lang.System.lineSeparator()` inside `Sync.defer` so the separator is captured at effect-run time, not at definition time. On Scala.js the shim returns `\n`.

All three extension groups are exported to the `kyo` package via `export StreamFileExtensions.*` at the bottom of the file.

---

## Kyo primitives mandate

See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for the full `Maybe` / `Result` / `Chunk` / `Span` vocabulary that applies across all of Kyo.

The one module-specific exception: raw `java.util.Arrays.copyOf` / `java.lang.System.arraycopy` are permitted inside performance-critical private implementation paths (for example the streaming read and `tail` loops inside `Path.scala`), because `Chunk` does not expose a fast-arraycopy path for partial buffer slices.

---

## Cross-platform discipline

All public APIs live in `shared/src/main`. Every API that appears there must compile and behave correctly on JVM, Scala.js (Node.js), Scala Native, and Wasm. The `jvm-native/` and `js-wasm/` source trees are for platform backend implementations only; nothing user-facing goes there.

| Tree | Permitted content |
|---|---|
| `shared/src/main` | All public APIs, shared logic, abstract `Unsafe` classes |
| `jvm-native/src/main` | `NioPathUnsafe`, JVM/Native process backend, `SystemPlatformSpecific` delegating to `java.lang.System`, `PathJvmNative.toJava` |
| `js-wasm/src/main` | Node.js path and process backends, JS `SystemPlatformSpecific` using `process.*` |
| `jvm/src/test` | Tests that require `java.nio.file.Files` APIs absent on other platforms (symlink creation, `toJava`) |
| `shared/src/test` | All other tests; must pass on all four platforms |

When adding a new platform-specific capability, supply a concrete implementation in every platform leaf. A non-supporting platform must either raise the appropriate typed exception or return a documented no-op; it must never throw a raw exception or call `???`.

Never move a test from `shared/src/test` into a platform-specific tree to avoid a platform cost. Fix the underlying issue instead.

---

## `AllowUnsafe` discipline

Every call to an `Unsafe` method inside kyo-system must be wrapped in `Sync.Unsafe.defer(...)` and annotated with a `// Unsafe:` comment that names the safe-tier contract being bridged. The pattern is:

```scala
// Unsafe: <reason: what safe-tier contract this bridges>
Sync.Unsafe.defer(Abort.get(self.unsafe.someOp()))
```

For operations on handles that are already inside an acquired `Scope`, the comment names the handle and the Scope ownership:

```scala
// Unsafe: bridges Path.WriteHandle.writeBytes into the safe tier under the acquired Scope handle.
Sync.Unsafe.defer(Abort.get(handle.writeBytes(chunk)))
```

Do not introduce `import AllowUnsafe.embrace.danger` in kyo-system sources. All unsafe execution passes through `Sync.Unsafe.defer`.

---

## Test file naming

See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for the global 1:1 naming rule, the orphan-test prohibition, and the scratch-file cleanup requirement. The module-specific mapping is:

| Source | Test file(s) | Note |
|---|---|---|
| `Path.scala` | `PathTest.scala`, `PathSeparatorsTest.scala`, `PathSizeTest.scala`, `PathStatTest.scala` | Aspect split; all in `shared/src/test` |
| `Path.scala` | `PathJvmTest.scala` | JVM-only; in `jvm/src/test` |
| `Command.scala` | `CommandTest.scala` | |
| `Process.scala` | `ProcessTest.scala` | |
| `System.scala` | `SystemTest.scala` | |
| `FileException.scala` | `FileExceptionTest.scala` | |
| `StreamFileExtensions.scala` | `PathTest.scala` | No separate file; `writeTo` and `writeLinesTo` leaves are folded into `PathTest.scala` because `StreamFileExtensions` is the write side of the `Path` surface |
| `CommandException.scala` | `CommandTest.scala` | No separate file; all three exception leaves are exercised in `CommandTest.scala` because `CommandException` types are raised exclusively by `Command` operations |

`PathJvmTest.scala` lives in `jvm/src/test` because its content genuinely requires `JFiles.createSymbolicLink` (symlink creation) and `java.nio.file.Path` interop via `Path.toJava` / `Path.of(jpath)`, neither of which has an equivalent on Scala.js or Scala Native.

---

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-systemJVM/test'

# A single test class
sbt 'kyo-systemJVM/testOnly kyo.PathTest'

# Validate README code blocks
sbt 'kyo-systemJVM/doctest'
```

Building automatically runs scalafmt. Re-read any file you edit after building; formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

---

## Decision checklist: before adding or changing X

Run through this list before touching the internals or adding a new public surface.

1. **New Path operation.** Is the abstract method on `Path.Unsafe` (with `using AllowUnsafe, Frame`)? Is there a safe-tier extension method that lifts with `Sync.Unsafe.defer(Abort.get(...))`? Is there a concrete implementation in both `jvm-native/` and `js-wasm/`? Is the `Result` error type the most specific of `FileReadException`, `FileWriteException`, or `FileFsException`? Is there a cross-platform test leaf in `shared/src/test`? [`Path.scala`]

2. **New streaming Path operation.** Does it return a `Stream` that carries `Scope`? Is the OS handle acquired with `Scope.acquireRelease`? Is `tail` the only method that adds `Async`? [`Path.scala:246-409`]

3. **New FileException leaf.** Does it mix in exactly the marker traits whose operations can actually raise it? Is its message string on the leaf itself (not in the catching code)? Is `FileIOException` used only when no more specific variant exists? [`FileException.scala`]

4. **New CommandException leaf.** Does it extend `CommandException` directly (no marker traits)? Does it carry the relevant identifier (program name or path) as a constructor parameter? Is the human-readable message in the leaf constructor body, not at the catch site? Is it added to the exhaustive match in `CommandTest.scala`? [`CommandException.scala`, `CommandTest.scala:330-341`]

5. **New Command builder method.** Does it return a new `Command` (via `self.unsafe.withX(...).safe`)? Does it compose correctly with `andThen`? Is the corresponding abstract method on `Command.Unsafe`? [`Command.scala`]

6. **New Command execution method.** Does `Abort[CommandException]` cover only pre-launch failures? Are runtime non-zero exits surfaced as `ExitCode` values, not exceptions? Does a method that reads both stdout and stderr drain them concurrently? [`Command.scala:87-137`]

7. **New System capability.** Is it accessed through the ambient `local` (not a field) so `System.let` still works in tests? If a new `SystemPlatformSpecific` method is needed, does it appear in both `jvm-native/` and `js-wasm/` with the correct fallback on JS? [`System.scala`]

8. **New Parser given.** Is the error type in `Parser[E, A]` exactly the exception type thrown by the underlying parse call? Does the given instance use `Result.catching[E]` rather than a try/catch? [`System.scala:258-293`]

9. **New StreamFileExtensions sink.** Does it follow the `writeWith` pattern (acquire handle, run body, delete partial file on failure)? Does every platform I/O call carry a `// Unsafe:` comment? Is it exported at the bottom of the file? Is it a sink on `Stream[String, S]` if it requires string semantics (not on `Stream[Byte, S]`)? [`StreamFileExtensions.scala`]

10. **New dependency from kyo-system.** kyo-system depends on `kyo-core` only. Adding any other kyo module (including `kyo-eventlog`) requires explicit authorisation. [`build.sbt:683-693`]

11. **New test.** Does it extend `kyo.test.Test[Any]`? Does it assert concrete values? Does it live in `shared/src/test` unless it genuinely requires a JVM-only API? Is it folded into the matching `*Test.scala` for the source it covers? Does it include at least one edge case (empty path, non-existent file, non-zero exit, missing env variable)?

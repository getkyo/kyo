# Contributing to kyo-system

Read the repository root [CONTRIBUTING.md](../CONTRIBUTING.md) first. This guide records the
filesystem, process, and environment conventions specific to `kyo-system`.

## Architecture

`kyo-system` is a four-platform cross-project for JVM, JavaScript, Native, and Wasm. Public
filesystem behavior belongs in `shared`; platform leaves contain only host integration.

The module owns these boundaries:

| Surface | Safe API | Effect row |
|---|---|---|
| Filesystem reads | `Path` | `Sync`, `Abort[FileReadException]` |
| Filesystem writes | `Path` | `Sync`, `Abort[FileWriteException]`, `Abort[FileStructureException]` |
| Commands and processes | `Command`, `Process` | `Sync`, `Async`, `Scope` |
| Environment | `System` | `Sync` |

A method carries the failure category it can actually raise, never a wider one. Keep the category
visible in return types and compile-time tests.

### Source layout

```text
kyo-system/
  shared/src/main/scala/kyo/
    Path.scala
    FileSystemException.scala
    Command.scala
    Process.scala
    System.scala
  jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala
  js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala
```

## Path operation flow

1. A safe `Path` extension bridges the matching `Path.Unsafe` operation through `Sync.Unsafe.defer`.
2. The unsafe tier returns a `Result` carrying a precise filesystem failure marker.
3. `Abort.get` lifts that `Result` into the method's declared `Abort` row.

Safe methods carry `Sync` plus a precise `Abort` row. Do not expose platform exceptions or widen an
`Abort` row past what the unsafe tier can return.

Every unsafe bridge must have a nearby `// Unsafe:` comment explaining the boundary. Unsafe methods
return `Result`; safe backend methods lift those results into `Abort`.

## Watching a file

`Path.Origin` says where a byte-level read begins: `Start` replays existing content, `End` skips
existing content, and `Offset(bytes)` resumes at a recorded position. A negative `Offset` is clamped
to 0 on every platform.

`tailBytes` defaults to `Origin.End` because it is the byte-level view of a watched file. `path.tail`
passes `Origin.End` explicitly because the two are siblings over one polling loop. Other drivers over
the loop choose their own default. `Jsonl.watch` in `kyo-schema-json` defaults to `Origin.Start` so it
replays existing records before emitting new ones.

- `path.tail` and `path.tailBytes` carry `Async & Scope & Abort[FileReadException]`: `Abort` because
  opening and measuring the handle can fail, `Async` because they sleep between polls, and `Scope`
  because the handle closes when the enclosing scope exits.
- Both drive one `private[kyo] watch` loop, which owns the open handle, polling, and truncation
  rewind. `tail` threads UTF-8 and line-buffer state through that loop.
- A `watch` step returns `Path.Step`: `Continue(values, state)` reads again, while `Stop(values)`
  emits those values and completes. `Stop` carries no state because no later iteration can consume
  it. `Jsonl.watchResults` uses this to stop when its framer can no longer frame another record.
  `Path.Step` is not `Loop.Outcome2`: an `Outcome` is opaque and only `Loop.apply` can destructure
  one, while this step's result is read by the watch loop itself.
- Watching tracks the open file, not the name. A rename or deletion is invisible to a running stream.
  Truncation rewinds to byte 0 and restores the step's initial state so buffered bytes from before
  the rewind cannot be spliced onto the replayed content.

## Portable matching and named policies

Path listing and walking accept `Glob`, defined in `kyo-data`. Backends must not compile their own
regular expressions or use host glob APIs. Match paths relative to the listed or walked root and use
the backend's default case sensitivity unless the caller supplies one.

Movement and copying use `Path.MoveOptions` and `Path.CopyOptions`. File writes use
`Path.WriteOptions`. Add policy fields to these values instead of restoring Boolean argument
clusters. Required atomicity must either succeed atomically or fail before mutating the target.

## Error contracts

`FileSystemException` is the umbrella. Concrete exceptions mix in only the marker traits for the
operations that can raise them:

- `FileReadException`
- `FileWriteException`
- `FileStructureException`

Use `FileIOException(path, operation, cause)` only when no more precise leaf describes the failure.
Preserve `Result.Panic` as a panic. Do not translate interruption, programmer defects, or unexpected
throwables into expected filesystem failures.

## Adding an operation

1. Decide whether the operation needs read or write authority.
2. Add a focused shared test that proves behavior and its precise failure case.
3. Add the safe `Path` surface bridging the unsafe tier, when the operation belongs on `Path`.
4. Implement both platform leaves for host behavior.
5. Add the safe-to-unsafe bridge with its `// Unsafe:` explanation.
6. Compile and test JVM, JavaScript, Native, and Wasm.

Do not add an operation to the unsafe tier without completing every layer above it.

## Testing

Shared behavior belongs in `shared/src/test`. A test file must share a prefix with its production
source. Platform tests are reserved for genuine host integration differences.

Test effect rows and backend authority with `typeCheck` and `typeCheckErrors`. Never use sleeps or blocking primitives
to make scheduling tests pass.

Before submission, run the affected module tests on all four platforms and the module doctest. Read
formatted files again after sbt completes because compilation formats sources.

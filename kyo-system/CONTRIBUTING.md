# Contributing to kyo-system

Read the repository root [CONTRIBUTING.md](../CONTRIBUTING.md) first. This guide records the
filesystem, process, and environment conventions specific to `kyo-system`.

## Architecture

`kyo-system` is a four-platform cross-project for JVM, JavaScript, Native, and Wasm. Public
filesystem behavior belongs in `shared`; platform leaves contain only host integration.

The module owns these capability boundaries:

| Surface | Safe API | Capability or residual |
|---|---|---|
| Filesystem reads | `Path`, `FileSystem.Read` | `PathRead` |
| Filesystem writes | `Path`, `FileSystem.Write` | `PathWrite` |
| Commands and processes | `Command`, `Process` | `Sync`, `Async`, `Scope` |
| Environment | `System` | `Sync` |

`PathWrite <: PathRead`: write authority includes read authority.
`Path.runReadOnly` intentionally cannot discharge a `PathWrite` operation. Keep this negative
capability law visible in return types and compile-time tests.

### Source layout

```text
kyo-system/
  shared/src/main/scala/kyo/
    Path.scala
    FileSystem.scala
    HostFileSystem.scala
    FileSystemException.scala
    Command.scala
    Process.scala
    System.scala
  jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala
  js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala
```

## Filesystem authority and selection

`FileSystem.Read[S]` contains only inspection and read operations. `FileSystem.Write[S]` extends it
with mutation, typed write channels, and structure changes.

The built-in factories are:

| Factory | Authority | Purpose |
|---|---|---|
| `FileSystem.host` | write | Local host filesystem |

`FileSystem.let(backend)(program)` changes the backend used by the default Path runners for a
dynamic scope. Selection uses `Local`, propagates to child fibers, and restores the previous backend
on exit. `Path.runWith` and `Path.runReadOnlyWith` select a backend explicitly for one runner.

Never widen a read-only factory to `FileSystem.Write`. Authority is part of the public contract.

## Path operation flow

1. A safe extension suspends a `Path.Op` under `PathRead` or `PathWrite`.
2. `Path.run`, `Path.runReadOnly`, or an explicit-service runner dispatches the operation.
3. A backend returns its own effect `S` plus a precise filesystem failure marker.
4. Platform host code bridges a `Path.Unsafe` operation through `Sync.Unsafe.defer`.

Safe Path methods carry capability effects. Backend methods carry backend effects and precise
`Abort` rows. Do not expose platform exceptions or hide backend effects with casts.

Every unsafe bridge must have a nearby `// Unsafe:` comment explaining the boundary. Unsafe methods
return `Result`; safe backend methods lift those results into `Abort`.

## Following a file

`Path.Origin` says where a byte-level read begins: `Start` replays existing content, `End` skips
existing content, and `Offset(bytes)` resumes at a recorded position. A negative `Offset` is clamped
to 0 on every platform.

`tailBytes` defaults to `Origin.End` because it is the byte-level view of a followed file.
`path.tail` passes `Origin.End` explicitly because the two are siblings over one polling loop. Other
drivers over the loop choose their own default. `Jsonl.follow` in `kyo-schema-json` defaults to
`Origin.Start` so it replays existing records before following new ones.

- `path.tail` and `path.tailBytes` carry `PathRead & Async & Scope`: `PathRead` because they open
  their handle through the installed read service, exactly as every other read does, `Async` because
  they sleep between polls, and `Scope` because the handle closes when the enclosing scope exits.
- Both drive one `private[kyo] follow` loop, which owns the open handle, polling, and truncation
  rewind. `tail` threads UTF-8 and line-buffer state through that loop.
- A `follow` step returns `Path.Step`: `Continue(values, state)` reads again, while `Stop(values)`
  emits those values and completes. `Stop` carries no state because no later iteration can consume
  it. `Jsonl.followResults` uses this to stop when its framer can no longer frame another record.
- Following tracks the open file, not the name. A rename or deletion is invisible to a running
  stream. Truncation rewinds to byte 0 and restores the step's initial state so buffered bytes from
  before the rewind cannot be spliced onto the replayed content.

## Portable matching and named policies

Path listing and walking accept `Glob`, defined in `kyo-data`. Backends must not compile their own
regular expressions or use host glob APIs. Match paths relative to the listed or walked root and use
the backend's default case sensitivity unless the caller supplies one.

Movement and copying use `Path.MoveOptions` and `Path.CopyOptions`. File writes use
`Path.WriteOptions`. Add policy fields to these values instead of restoring Boolean argument
clusters. Required atomicity must either succeed atomically or fail before mutating the target.

## Scoped channels

`Path.ReadChannel[S]`, `Path.WriteChannel[S]`, and `Path.ReadWriteChannel[S]` expose positioned
operations according to authority. Acquisition occurs through the matching `FileSystem` tier and is
owned by `Scope`. Public channels do not expose `close`; resource release belongs to the acquiring
scope.

`FileSystem.WriteOpen` separates capability from existence policy:

- `Existing` requires an existing regular file.
- `Create` opens an existing file or creates it.
- `CreateNew` fails if any target already exists.

## Locks

Locks are advisory, scope-managed values. `Path.LockMode` selects shared or exclusive compatibility.
`Path.LockWait` selects immediate, unbounded, or deadline-bounded acquisition. Fiber waiting must use
`Async`; never block an OS thread. Ownership checks and cleanup failures remain typed.

## Error contracts

`FileSystemException` is the umbrella. Concrete exceptions mix in only the marker traits for the
operations that can raise them:

- `FileReadException`
- `FileWriteException`
- `FileStructureException`
- `FileLockException`

Use `FileIOException(path, operation, cause)` only when no more precise leaf describes the failure.
Preserve `Result.Panic` as a panic. Do not translate interruption, programmer defects, or unexpected
throwables into expected filesystem failures.

## Adding an operation

1. Decide whether the operation needs read or write authority.
2. Add a focused shared test that proves behavior and its precise failure case.
3. Add the safe Path surface and reified operation when it is a Path capability operation.
4. Add the narrowest `FileSystem` tier method and precise effect row.
5. Implement both platform leaves for host behavior.
6. Add the safe-to-unsafe bridge with its `// Unsafe:` explanation.
7. Extend the reusable conformance suite when the contract applies to multiple backends.
8. Compile and test JVM, JavaScript, Native, and Wasm.

Do not add an operation to the unsafe tier without completing every layer above it.

## Testing

Shared behavior belongs in `shared/src/test`. A test file must share a prefix with its production
source. Platform tests are reserved for genuine host integration differences.

Use the reusable suites for backend laws:

- `FileSystemReadTestSuite`
- `FileSystemWriteTestSuite`
- `FileSystemChannelTestSuite`
- `FileSystemLockTestSuite`

Test capability rows with `typeCheck` and `typeCheckErrors`. Use deterministic `Async` coordination
for lock tests. Never use sleeps or blocking primitives to make scheduling tests pass.

Before submission, run the affected module tests on all four platforms and the module doctest. Read
formatted files again after sbt completes because compilation formats sources.

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
| Filesystem observation | `Path.Watcher`, `FileSystem.Watch` | `PathWatch` |
| Commands and processes | `Command`, `Process` | `Sync`, `Async`, `Scope` |
| Environment | `System` | `Sync` |

`PathWrite <: PathRead`: write authority includes read authority. `PathWatch` is independent.
`Path.runReadOnly` intentionally cannot discharge a `PathWrite` operation. Keep this negative
capability law visible in return types and compile-time tests.

### Source layout

```text
kyo-system/
  shared/src/main/scala/kyo/
    Path.scala
    FileSystem.scala
    HostFileSystem.scala
    InMemoryFileSystem.scala
    OverlayFileSystem.scala
    ZipReadOnlyFileSystem.scala
    ZipRewriteFileSystem.scala
    FileSystemException.scala
    Command.scala
    Process.scala
    System.scala
  jvm-native/src/main/scala/kyo/internal/PathPlatformSpecific.scala
  js-wasm/src/main/scala/kyo/internal/PathPlatformSpecific.scala
```

## Filesystem authority and selection

`FileSystem.Read[S]` contains only inspection and read operations. `FileSystem.Write[S]` extends it
with mutation, typed write channels, durable replacement, and structure changes. A backend mixes in
`FileSystem.Watch[S]` only when it can satisfy the complete watcher contract.

The built-in factories are:

| Factory | Authority | Purpose |
|---|---|---|
| `FileSystem.host` | write and watch | Local host filesystem |
| `FileSystem.host(root)` | write and watch | Canonically root-confined host access |
| `FileSystem.inMemory` | write and watch | Hermetic shared implementation |
| `FileSystem.overlay(lower)` | write, watch, staged changes | Copy-on-write staging |
| `FileSystem.zipReadOnly(path)` | read | Immutable archive view |
| `FileSystem.zip(path)` | write and staged changes | Whole-archive rewrite |

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

## Portable matching and named policies

Path listing and walking accept `Glob`, defined in `kyo-data`. Backends must not compile their own
regular expressions or use host glob APIs. Match paths relative to the listed or walked root and use
the backend's default case sensitivity unless the caller supplies one.

Movement and copying use `Path.MoveOptions` and `Path.CopyOptions`. File writes use
`Path.WriteOptions`. Add policy fields to these values instead of restoring Boolean argument
clusters. Required atomicity must either succeed atomically or fail before mutating the target.

## Scoped channels and durable replacement

`Path.ReadChannel[S]`, `Path.WriteChannel[S]`, and `Path.ReadWriteChannel[S]` expose positioned
operations according to authority. Acquisition occurs through the matching `FileSystem` tier and is
owned by `Scope`. Public channels do not expose `close`; resource release belongs to the acquiring
scope.

`FileSystem.WriteOpen` separates capability from existence policy:

- `Existing` requires an existing regular file.
- `Create` opens an existing file or creates it.
- `CreateNew` fails if any target already exists.

`durableReplace` has a fixed workflow: reserve a sibling temporary file, open it create-new, write,
sync file content and metadata, close it, require an atomic replacement move, then sync the parent
directory. Cleanup before movement leaves the original target unchanged. Failure of the final
directory sync is still reported, although replacement has already occurred. Never add a non-atomic
fallback.

## Locks and watchers

Locks are advisory, scope-managed values. `Path.LockMode` selects shared or exclusive compatibility.
`Path.LockWait` selects immediate, unbounded, or deadline-bounded acquisition. Fiber waiting must use
`Async`; never block an OS thread. Ownership checks and cleanup failures remain typed.

Watchers use the independent `PathWatch` capability. Acquisition returns only after backend
registration is active. Events are normalized as `PathChange`; overflow and root invalidation are
stream values. Invalidation is terminal: emit it exactly once, close the stream, and release the
registration. Staged backends expose only changes visible in their staged view.

## Confinement and archives

`FileSystem.host(root)` resolves the root canonically. Existing targets are checked by real path;
missing write targets are checked through their nearest existing parent. Prefix-only string checks
are security defects because symlinks can escape them. `Path.confinedTo(root)` provides the same
canonical containment rule when the checked path is itself needed as a value.

Archive behavior is shared across all platforms. `zipReadOnly` exposes no mutation surface.
`zip` stages entry changes and materializes the complete archive on commit through durable
replacement. Do not add platform archive libraries or in-place writes to compressed entries.

## Explicit staged writes

Use these public combinators:

| API | Behavior |
|---|---|
| `Path.commitWritesOnSuccess(program)` | Isolate changes and commit after success |
| `Path.discardWrites(program)` | Isolate changes and always discard |
| `Path.stageWrites(program)` | Return result plus one-shot `FileSystem.StagedChanges` |

`StagedChanges.commit` validates observed lower entries before replay. `commitWith` resolves each
`CommitConflict` using `FileSystem.Resolution`. `discard` terminates without touching the lower
backend. Every terminal method is one-shot and a second terminal action must fail explicitly.

Do not claim multi-file external atomicity. The staging API provides isolation before commit,
conflict detection, deterministic replay, and durable replacement for materialized archive files.

## Error contracts

`FileSystemException` is the umbrella. Concrete exceptions mix in only the marker traits for the
operations that can raise them:

- `FileReadException`
- `FileWriteException`
- `FileStructureException`
- `FileLockException`
- `FileWatchException`

Use `FileIOException(path, operation, cause)` only when no more precise leaf describes the failure.
Preserve `Result.Panic` as a panic. Do not translate interruption, programmer defects, or unexpected
throwables into expected filesystem failures.

## Adding an operation

1. Decide whether the operation needs read, write, or watch authority.
2. Add a focused shared test that proves behavior and its precise failure case.
3. Add the safe Path surface and reified operation when it is a Path capability operation.
4. Add the narrowest `FileSystem` tier method and precise effect row.
5. Implement shared backends: in-memory, overlay, and archive backends where supported.
6. Implement both platform leaves for host behavior.
7. Add the safe-to-unsafe bridge with its `// Unsafe:` explanation.
8. Extend the reusable conformance suite when the contract applies to multiple backends.
9. Compile and test JVM, JavaScript, Native, and Wasm.

Do not add an operation to the unsafe tier without completing every layer above it.

## Testing

Shared behavior belongs in `shared/src/test`. A test file must share a prefix with its production
source. Platform tests are reserved for genuine host integration differences.

Use the reusable suites for backend laws:

- `FileSystemReadTestSuite`
- `FileSystemWriteTestSuite`
- `FileSystemChannelTestSuite`
- `FileSystemDurabilityTestSuite`
- `FileSystemLockTestSuite`
- `FileSystemWatchTestSuite`

Test capability rows with `typeCheck` and `typeCheckErrors`. Use deterministic `Async` coordination
for watcher and lock tests. Never use sleeps or blocking primitives to make scheduling tests pass.

Before submission, run the affected module tests on all four platforms and the module doctest. Read
formatted files again after sbt completes because compilation formats sources.

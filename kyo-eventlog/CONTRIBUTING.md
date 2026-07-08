# Contributing to kyo-eventlog

Module-specific guide for kyo-eventlog. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-eventlog.

**The headline invariant:** every `Journal` operation is encoded as an ArrowEffect: it suspends a reified op, carries its own `Abort[<per-op trait>]` in the row beside `Journal`, and the handler (`Journal.run`) discharges `Journal` while converting each backend `Abort` call into a `Result` that rides in the continuation, leaving the per-op traits on the residual. The safe-tier ops, the Op enum's GADT encoding, and the handler's per-branch `Abort.run` are all consequences of this single structural choice.

---

## Architecture overview

kyo-eventlog owns the Journal capability and its backing infrastructure:

| File | Purpose |
|---|---|
| `Journal.scala` | `Journal` sealed trait; `Op` enum (GADT); `Backend[S]` SPI; `append`, `read`, `streamInfo` public ops; `run` handler; `Unsafe` forwarders |
| `JournalError.scala` | Sealed `KyoException` hierarchy: umbrella base, three per-op traits, five leaves |
| `JournalEvent.scala` | Wire-vocabulary types: `StreamId`, `EventId`, `EventType`, `StreamOffset`, `StreamVersion`, `ExpectedOffset`, `StreamInfo`, `EventEnvelope`, `RecordedEvent`, `AppendResult` |
| `JournalMetadata.scala` | `EventMetadata` case class; `MetadataKey` opaque type |
| `internal/InMemoryJournal.scala` | Ephemeral in-memory backend: CAS over `AtomicRef`, `Loop`-based retry |
| `jvm-native/src/main/scala/kyo/FileJournal.scala` | Durable file backend (`Journal.Backend.file`); JVM and Native only |

**Dependency rule:** kyo-eventlog depends on `kyo-core`, `kyo-schema`, and `kyo-system`. `kyo-schema` is pulled in for `Structure.Value`, `Codec.Writer`/`Reader`, and `Schema.init`, which `MetadataValue`'s constructor-exact codec uses. `kyo-system` is pulled in by `FileJournal`, whose `FileChannel`-based append, fsync, and advisory locking reach the platform I/O layer through `Path.Unsafe` and the `toJava` extension from `kyo-system`; its `jvm-native` source tree resolves `Path`, `Path.Unsafe`, and `toJava` through that edge. No other kyo module is a compile-time dependency. [`build.sbt:675`]

### Source layout

All source is cross-platform and lives in the shared tree:

```
kyo-eventlog/
  shared/src/main/scala/kyo/
    Journal.scala
    JournalError.scala
    JournalEvent.scala
    JournalMetadata.scala
    internal/
      InMemoryJournal.scala

  shared/src/test/scala/kyo/
    JournalTest.scala
    JournalBackendTest.scala
    InMemoryJournalBackendTest.scala
    JournalEventTest.scala
    JournalMetadataTest.scala

  jvm-native/src/main/scala/kyo/
    FileJournal.scala              # durable file backend; JVM and Native only

  jvm-native/src/test/scala/kyo/
    FileJournalBackendTest.scala   # contract suite subclass
    FileJournalCodecTest.scala     # segment codec unit tests
    FileJournalCrashTest.scala     # crash, recovery, and corruption suite
    FileJournalTest.scala          # rotation, metadata, streamId, lock, failed-open

  jvm/src/  js/src/  native/src/  wasm/src/
    (empty)
```

The `jvm-native/` tree is compiled on JVM and Native only. `Journal.Backend.file` and `FileJournal.Config` are available only on those two platforms. All other public types and operations (`Journal`, `Journal.Backend.inMemory`, the wire types, the error hierarchy) compile and behave identically on JVM, Scala.js, Scala Native, and Wasm.

---

## ArrowEffect encoding

### The TypeLambda extends clause

[`Journal.scala:29`]

```scala
sealed trait Journal extends ArrowEffect[[A] =>> Journal.Op[A], Id]
```

The `[A] =>> Journal.Op[A]` form is a type lambda. Writing `ArrowEffect[Journal.Op, Id]` would pass an unapplied type constructor, and `TagMacro` rejects that because it requires a fully applied type expression to derive a `Tag`. The type lambda wraps `Journal.Op[A]` so the compiler sees a kind-`* -> *` shape that satisfies `ArrowEffect`'s `Input[_]` parameter without exposing an unapplied constructor to the macro.

Use this pattern whenever the input type constructor of an `ArrowEffect` is a named type (an enum, a sealed trait) rather than an anonymous type alias.

### The kyo.StreamInfo qualified GADT bound

[`Journal.scala:40`]

```scala
case StreamInfo(streamId: StreamId) extends Op[Result[JournalStreamInfoFailure, kyo.StreamInfo]]
```

Inside the `enum Op` body the unqualified name `StreamInfo` resolves to `Op.StreamInfo`, the case being defined. The result type must reference the top-level `kyo.StreamInfo` enum, so the fully qualified name is required. Any future op case whose result type names a type that shares a name with an existing Op case must use the same qualification.

### suspend and the Abort.get lift

[`Journal.scala:94-96`, `105-107`, `111-112`]

Each public op suspends with `ArrowEffect.suspend`, which returns `Result[<per-op-trait>, A] < Journal`. The `.map(r => Abort.get(r))` call immediately lifts that `Result` into `Abort[<per-op-trait>]` on the caller's row:

```scala
inline def append(...)(using inline frame: Frame): AppendResult < (Journal & Abort[JournalAppendFailure]) =
    ArrowEffect.suspend(Tag[Journal], Op.Append(streamId, expected, events)).map(r => Abort.get(r))
```

The result is that callers see `Journal & Abort[JournalAppendFailure]` in the row, not `Journal` alone, which makes the failure surface statically visible before any handler is installed.

### The handler's per-branch Abort.run conversion

[`Journal.scala:127-137`]

`Journal.run` uses `ArrowEffect.handleLoop`. Each branch of the op match wraps the corresponding backend method in `Abort.run[<per-op-trait>]` to convert the backend's `Abort` effect into a `Result`, then passes that `Result` to the continuation (`cont(r)`) and wraps the result in `Loop.continue`:

```scala
case Op.Append(sid, exp, evs) =>
    Abort.run[JournalAppendFailure](backend.append(sid, exp, evs)).map(r => Loop.continue(cont(r)))
```

The continuation `cont` is typed to accept `Result[JournalAppendFailure, AppendResult]`, matching what `ArrowEffect.suspend` originally handed to the caller. Because each branch runs its own `Abort.run`, the umbrella `JournalError` never appears on the handler's residual.

### The run residual carries no umbrella

[`Journal.scala:115-137`]

The residual of `Journal.run[S, A, S2](backend)(v)` is `A < (S & S2)`. The per-op `Abort[<trait>]` terms introduced by the program ride in `S2` and are left untouched; only `Journal` is discharged. To catch any journal failure in one shot, wrap with `Abort.run[JournalError](...)`: every per-op trait widens to the sealed base by subtyping, so one recovery handles all five leaves.

---

## Error hierarchy

### Shape

[`JournalError.scala:19-53`]

```
JournalError (sealed abstract class, extends KyoException)
  JournalAppendFailure (sealed trait)
  JournalReadFailure (sealed trait)
  JournalStreamInfoFailure (sealed trait)

Leaves and the op traits they implement:
  JournalEmptyAppendError()                                   JournalAppendFailure
  JournalConflictError(streamId, expected, actual)            JournalAppendFailure
  JournalInvalidIdentifierError(kind, value)                  (none: construction-time Result error)
  JournalCorruptedError(streamId: Maybe[StreamId], detail)    JournalAppendFailure, JournalReadFailure, JournalStreamInfoFailure
  JournalStorageError(detail, cause: Maybe[Throwable])        JournalAppendFailure, JournalReadFailure, JournalStreamInfoFailure
```

`JournalInvalidIdentifierError` carries no op trait because it is the `Result` error type returned by opaque-type constructors (such as `StreamId.apply`), not an op failure reachable via `Abort` in a journal program.

`JournalCorruptedError` and `JournalStorageError` cross-cut all three ops because any durable backend operation can encounter storage corruption or I/O failure.

### Adding a new error leaf

1. Identify which operations can produce it. If it is a construction-time validation error, extend `JournalError` only, with no op traits. If it is an append-only failure, mix in `JournalAppendFailure`. If it can occur on any storage-backed op, mix in all three op traits.

2. Define a `case class` that extends `JournalError` and the appropriate op traits. Carry the typed context that identifies the failure (stream id, expected value, detail string) as constructor parameters. Build the human-readable message from those parameters inside the extends clause, not at the catch site.

3. Accept a `(using Frame)` parameter so the frame is captured at construction (inherited via `KyoException`). [`JournalError.scala:19`]

4. Derive `CanEqual`. [`JournalError.scala:31-53`]

A skeleton for a new append-only leaf:

```scala
case class JournalMyError(streamId: StreamId, detail: String)(using Frame)
    extends JournalError(s"My failure on stream '${streamId.value}': $detail.")
    with JournalAppendFailure derives CanEqual
```

---

## Wire types

### Opaque-type vocabulary

[`JournalEvent.scala`, `JournalMetadata.scala`]

All public identifier and position types are opaque aliases for primitives. The pattern for each:

- `apply(value)(using Frame): Result[JournalInvalidIdentifierError, T]` for public validated construction.
- `private[kyo] inline def fromUnchecked(value: Underlying): T` for internal use where the invariant is already guaranteed (for example, offsets assigned by `InMemoryJournal`).
- An `extension` block exposing `value: Underlying` and any derived operations.
- `inline given CanEqual[T, T] = CanEqual.derived`.

| Type | Underlying | Validation rule | Constants / derived ops |
|---|---|---|---|
| `StreamId` | `String` | Non-empty | `value` |
| `EventId` | `String` | Non-empty | `value` |
| `EventType` | `String` | Non-empty | `value` |
| `StreamOffset` | `Long` | `[0, Long.MaxValue)` | `first = 0L`, `fromUnchecked` |
| `StreamVersion` | `Long` | `>= 0` | `initial = 0L`, `after(offset)` |
| `MetadataKey` | `String` | Non-empty; no leading, trailing, or consecutive dots | `value`, `segments: Chunk[String]` |

### The version = lastOffset + 1 invariant

[`JournalEvent.scala:123`, `internal/InMemoryJournal.scala:109-110`]

`StreamVersion.after(offset)` computes `offset.value + 1L`. For a contiguous zero-based stream whose last event sits at `offset`, the version (one-based count of events) equals `offset.value + 1`. `InMemoryJournal.info` enforces this whenever it constructs a `StreamInfo.Existing`:

```scala
val lastOffset = StreamOffset.fromUnchecked(events.length.toLong - 1L)
StreamInfo.Existing(StreamVersion.after(lastOffset), lastOffset)
```

Any new backend must maintain this invariant or `JournalBackendTest`'s `streamInfo` leaves will fail.

### ExpectedOffset and StreamInfo

[`JournalEvent.scala:141-162`]

`ExpectedOffset` and `StreamInfo` are plain enums, not opaque types: they model structured choices, not validated primitives.

`ExpectedOffset` has three cases:
- `Any`: the check is skipped.
- `NoStream`: the stream must be absent.
- `Exact(offset: StreamOffset)`: the live last offset must equal `offset`.

`StreamInfo` has two cases:
- `Absent`: the stream has no events.
- `Existing(version: StreamVersion, lastOffset: StreamOffset)`: the stream has `version.value` events, the last at `lastOffset`.

`StreamInfo` also provides `exists: Boolean` as a convenience method that returns `true` for `Existing` and `false` for `Absent`. [`JournalEvent.scala:158-162`]

`JournalConflictError` carries the `actual: StreamInfo` observed at append time so a caller can inspect the conflict without a second round-trip.

### EventEnvelope, RecordedEvent, AppendResult

[`JournalEvent.scala:172-205`]

These are plain `final case class` values.

`EventEnvelope` is the submitted form: `id`, `eventType`, `payload: Span[Byte]`, `metadata`. The payload is opaque bytes; schemas and codecs live above this layer.

`RecordedEvent` is the stored form returned by reads: adds `streamId` and `offset` to the envelope fields. The field `id` in `EventEnvelope` is named `eventId` in `RecordedEvent`; all other envelope fields carry through with the same names. The `offset` is the zero-based position assigned by the backend.

`AppendResult` reports the outcome of a successful batch append: `streamId`, `firstOffset`, `lastOffset`, and the post-append `streamInfo`.

`Span` equality via `==` is reference-based. To compare payload contents, use `Span#is`, not `==` on envelopes or records.

---

## EventMetadata and MetadataKey

[`JournalMetadata.scala:14-181`]

`EventMetadata` is a `final case class` wrapping `Map[MetadataKey, MetadataValue]` (`JournalMetadata.scala:14`). `MetadataValue` is an opaque type backed by `Structure.Value` with a constructor-exact codec: each of the ten `Structure.Value` constructors encodes as a one-field record keyed by its tag name (`str`, `int`, `bool`, `decimal`, `bignum`, `null`, `seq`, `record`, `entries`, `variant`), so every constructor round-trips without loss through any Codec. Construct with `MetadataValue(sv: Structure.Value)` and project with `.value: Structure.Value`. Metadata is for infrastructure concerns (correlation identifiers, tracing tags) that consumers may need without decoding the payload.

`MetadataKey` is an opaque `String` with dotted-path validation: non-empty, no leading dot, no trailing dot, no consecutive dots (`foo..bar` is rejected). The `segments` extension splits on `.` and returns a `Chunk[String]`.

`EventMetadata.empty` is the canonical metadata-free value.

---

## Backend SPI

### Contract

[`Journal.scala:59-76`]

`Journal.Backend[S]` is the storage contract that `Journal.run` dispatches to:

```scala
trait Backend[S]:
    def append(streamId, expected, events): AppendResult < (S & Abort[JournalAppendFailure])
    def read(streamId, from, maxCount): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure])
    def streamInfo(streamId): StreamInfo < (S & Abort[JournalStreamInfoFailure])
```

Each method names its precise failure trait in the `Abort` row. The type parameter `S` is the backend's own effect: `Sync` for the in-memory backend, `Async` for a future asynchronous backend.

### No Frame on methods; Frame at construction

[`Journal.scala:59-76`, `internal/InMemoryJournal.scala:21`]

Backend methods take no `(using Frame)`. A backend captures its construction-time `Frame` as a class parameter (with `using Frame` on the class or on its `init` factory method) so that error values carry a frame for attribution. Custom backends must follow the same pattern.

The in-memory backend shows the convention:

```scala
final private class InMemoryJournal(state: AtomicRef[InMemoryJournal.State])(using Frame) extends Journal.Backend[Sync]
```

### JournalBackendTest: the contract test suite

[`JournalBackendTest.scala:7`]

Every backend must pass `JournalBackendTest` unchanged. A new backend provides its factory in the constructor of a one-line test class:

```scala
class MyBackendTest extends JournalBackendTest(MyBackend.init)
```

`JournalBackendTest` is parameterized with `newBackend: => Journal.Backend[Sync] < (Sync & Scope)` so each test gets a fresh isolated backend. A durable backend under test must supply a factory that creates a fresh, empty backend for each test case.

The contract exercised covers:
- Consecutive zero-based offset assignment from the first appended event.
- `NoStream`, `Exact`, and `Any` expected-offset semantics, including all mismatch cases.
- All-or-nothing batch atomicity: a conflicting append leaves the stream unchanged.
- Empty batch fails with `JournalEmptyAppendError`; stream is unchanged.
- Missing stream, non-positive `maxCount`, and out-of-range `from` all return an empty chunk (never a failure) for `read`.
- Events returned in offset order, bounded by `maxCount`.
- Payload and metadata preserved through append and read.
- Streams are independent: appending to one stream does not affect another.
- `streamInfo` reports `Absent` for a missing stream and `Existing` with the correct version and last offset after appends.
- Optimistic concurrency: exactly one of two racing `Exact` appends to the same stream wins; the other gets a `JournalConflictError`.

### Adding a new backend

1. Implement `Journal.Backend[S]` for the appropriate effect `S`. Satisfy every invariant listed above.

2. Capture a `Frame` at construction via a `(using Frame)` class parameter. Do not add `(using Frame)` to the trait methods.

3. Add a `class MyBackendTest extends JournalBackendTest(MyBackend.init)` test class alongside the backend, following the pattern of `InMemoryJournalBackendTest`. A backend without a `JournalBackendTest` subclass is incomplete.

4. Follow kyo-eventlog's dependency rule: the three authorized compile-time dependencies are `kyo-core`, `kyo-schema`, and `kyo-system`. New backends in `shared/src/main/scala/kyo/` need no extra dependency. Backends that reach raw platform I/O (file channels, advisory locks, fsync) belong in `jvm-native/src/main/scala/kyo/` and must use `Sync.Unsafe.defer` with per-site `// Unsafe:` comments (see the section below).

### File backend

[`jvm-native/src/main/scala/kyo/FileJournal.scala`]

`FileJournal` is the durable file backend: an append-only segment log on disk, available on JVM and Native. The public entry point is `Journal.Backend.file`, an extension on `Journal.Backend.type`:

```scala
Journal.Backend.file(dir: Path, config: FileJournal.Config = FileJournal.Config.default)
    : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError])
```

The `dir` parameter is a `Path` from `kyo-system`. `Scope` finalization releases the advisory LOCK and closes all open segment channels. A second `Backend.file` open of a held root directory fails immediately with `JournalStorageError`.

`FileJournal.Config` has two fields:

| Field | Default | Notes |
|---|---|---|
| `fsync: Boolean` | `true` | Flush each acknowledged append to stable storage before returning. Set to `false` only in tests; the crash-survival guarantee does not hold when `false`. |
| `segmentSize: Long` | 67108864 (64 MiB) | Soft rotation threshold. A single record larger than the threshold still writes, into its own segment. |

**Segment format:** each segment file begins with `KJN1` + `0x01` (4-byte magic + 1-byte version). Record frames follow with the layout `length(4) | crc32(4) | body`; the CRC covers the body only. Each append batch is closed by a terminator (`KJNC` + record count + CRC), which is the commit boundary. A torn tail in the active segment (no valid terminator after the trailing record group, from a prior crash) is silently truncated at recovery with a WARN log entry naming the segment and byte range. Non-tail corruption and unknown segment versions are fatal (`JournalCorruptedError`).

**LOCK:** one advisory file lock per root directory, acquired at open and released on `Scope` finalization. A second open of a held root fails immediately.

### Unsafe tier in backend implementations

A backend that bridges raw platform I/O must wrap each site in `Sync.Unsafe.defer` and annotate it with a `// Unsafe:` comment naming the safe-tier contract being bridged:

```scala
// Unsafe: raw FileChannel for fsync; force(false) is not reachable through Path.Unsafe
Sync.Unsafe.defer(channel.force(false))
```

`FileJournal` uses this pattern at every `Path.Unsafe` call (directory creation, listing, truncate) and at every raw `FileChannel` operation (positional write, `force(false)` for fdatasync, `tryLock` for advisory locking). `FileChannel` is the platform-I/O layer that `Path.Unsafe` does not expose; a new backend that needs operations `Path.Unsafe` cannot reach must bridge them the same way. Do not introduce `import AllowUnsafe.embrace.danger` in backend source; `AllowUnsafe` evidence flows from the enclosing `Sync.Unsafe.defer` scope.

---

## In-memory backend

### Design

[`internal/InMemoryJournal.scala:10-111`]

`InMemoryJournal` implements `Journal.Backend[Sync]` using an `AtomicRef[State]` where `State` is an immutable `Map[StreamId, Chunk[RecordedEvent]]`. There are no locks; atomicity is achieved through a compare-and-set loop.

The public factory is `Journal.Backend.inMemory` (`Journal.scala:80-81`), which delegates to the `private[kyo]` `InMemoryJournal.init`. `InMemoryJournal.init` allocates the `AtomicRef` inside `Sync` and wraps it in a new `InMemoryJournal`. Separate `Journal.Backend.inMemory` calls produce independent backends that do not share streams. [`internal/InMemoryJournal.scala:12-13`]

### Append: CAS loop

[`internal/InMemoryJournal.scala:48-58`]

The `modify` helper drives the optimistic-concurrency loop:

```scala
private def modify[A](operation: State => Result[JournalAppendFailure, (State, A)]): A < (Sync & Abort[JournalAppendFailure]) =
    Loop(()) { _ =>
        state.get.map { current =>
            Abort.get(operation(current)).map { (next, value) =>
                state.compareAndSet(current, next).map {
                    case true  => Loop.done(value)
                    case false => Loop.continue
                }
            }
        }
    }
```

`appendToState` is a pure function from the current `State` to either a `JournalAppendFailure` or the next `State` plus an `AppendResult`. If the CAS fails (another fiber modified the state between the read and the write), `Loop.continue` retries from the latest snapshot. If `appendToState` returns a failure, `Abort.get` surfaces it immediately without retrying.

`StreamOffset.fromUnchecked` is used for internally assigned offsets because the backend guarantees they are valid: the first offset of a batch is the current event count, subsequent offsets increment by one, and the count is always in `[0, Long.MaxValue)` for any realistic workload. [`internal/InMemoryJournal.scala:72`, `80`]

### Read and streamInfo

[`internal/InMemoryJournal.scala:36-46`]

`read` and `streamInfo` call `state.use` for a single snapshot read without a CAS loop. Both are pure: they return an empty chunk or `StreamInfo.Absent` when the stream is missing, and never fail under `Sync`.

---

## Journal.Unsafe

[`Journal.scala:139-171`]

`Journal.Unsafe` provides three forwarders that bypass the ArrowEffect suspend and handler dispatch, invoking the backend directly:

```scala
def append[S](backend: Backend[S])(streamId, expected, events)(using AllowUnsafe): AppendResult < (S & Abort[JournalAppendFailure])
def read[S](backend: Backend[S])(streamId, from, maxCount)(using AllowUnsafe): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure])
def streamInfo[S](backend: Backend[S])(streamId)(using AllowUnsafe): StreamInfo < (S & Abort[JournalStreamInfoFailure])
```

`AllowUnsafe` marks acceptance of the bypass, not a gate on an otherwise-unreachable operation. `Journal.Backend` is a public SPI; calling `backend.append(...)` directly is already possible and safe at identical cost. These forwarders are the blessed, discoverable equivalent under the `Unsafe` namespace for call sites that want to skip the handler seam for performance.

Both tiers expose the same per-op `Abort` surface. The difference: safe ops go through `ArrowEffect.suspend` and `Journal.run` (one `Result` round-trip through the handler); Unsafe ops call the backend directly under its concrete effect `S`.

---

## Test conventions

See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for the global test naming rules, orphan-test prohibition, scratch-file cleanup, and the module test base (`kyo.test.Test[Any]`).

### Coverage mapping

| Source | Test file | Notes |
|---|---|---|
| `Journal.scala` | `JournalTest.scala` | Capability row types, `run` dispatch, `Unsafe` forwarders, inMemory integration |
| `JournalError.scala` | `JournalTest.scala` | Error leaves exercised via `FailingBackend` and `JournalBackendTest` |
| `JournalEvent.scala` | `JournalEventTest.scala` | Wire-type validation, opaque-type extensions, envelope/record fields |
| `JournalMetadata.scala` | `JournalMetadataTest.scala` | `MetadataKey` validation, `EventMetadata` |
| `internal/InMemoryJournal.scala` | `InMemoryJournalBackendTest.scala` | Covered via `JournalBackendTest` contract suite |

### Deterministic concurrency: the Latch pattern

[`JournalBackendTest.scala:203-231`]

Concurrency tests use `Latch.init(n)` to coordinate fibers to a common start point, then `Fiber.initUnscoped` to race them. A latch of 1 makes both fibers block at `latch.await` until `latch.release` fires, at which point they proceed simultaneously. The test then retrieves both results and asserts exactly one success and one conflict. Use this pattern for any optimistic-concurrency test in kyo-eventlog; do not use `sleep` to simulate concurrency.

---

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-eventlogJVM/test'

# A single test class
sbt 'kyo-eventlogJVM/testOnly kyo.JournalTest'

# Validate README code blocks
sbt 'kyo-eventlogJVM/doctest'
```

Building automatically runs scalafmt. Re-read any file you edit after building; formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

---

## Decision checklist: before adding or changing X

Run through this list before touching the internals or adding a new public surface.

1. **New Journal operation.** Is it a case of `Op[Result[<per-op-trait>, A]]` with the correct GADT bound (using `kyo.` qualification if the result type name shadows an Op case)? Is there a corresponding `Backend[S]` method that names the same per-op trait in `Abort`? Is there a public `inline` op method that calls `ArrowEffect.suspend(...).map(r => Abort.get(r))`? Does `Journal.run`'s handler have a new branch calling `Abort.run[<per-op-trait>](backend.newOp(...)).map(r => Loop.continue(cont(r)))`? Are there matching `Journal.Unsafe` forwarders? Does `JournalBackendTest` have new contract leaves for the operation? [`Journal.scala`, `JournalBackendTest.scala`]

2. **New JournalError leaf.** Does it extend `JournalError`? Does it mix in exactly the op traits whose operations can actually raise it (and none others)? Does it carry the relevant context as typed constructor fields? Is the message built from those fields, not at the catch site? Does it accept `(using Frame)` and derive `CanEqual`? [`JournalError.scala`]

3. **New opaque wire type.** Does `apply(value)(using Frame)` return `Result[JournalInvalidIdentifierError, T]`? Is `fromUnchecked` scoped to `private[kyo]`? Is there a `value` extension and a `CanEqual` given? [`JournalEvent.scala`, `JournalMetadata.scala`]

4. **New backend implementation.** Does it implement all three `Backend[S]` methods with the correct per-op `Abort` rows? Does it capture a `Frame` at construction rather than on each method? Is there a `JournalBackendTest` subclass that passes unchanged? [`internal/InMemoryJournal.scala`, `JournalBackendTest.scala`]

5. **New dependency from kyo-eventlog.** kyo-eventlog depends on `kyo-core`, `kyo-schema`, and `kyo-system`. `kyo-system` is present because `FileJournal` uses `Path`, `Path.Unsafe`, and `toJava` for its segment files and LOCK file. Adding any module beyond these three requires explicit authorisation. [`build.sbt:675`]

6. **New test.** Does it extend `kyo.test.Test[Any]`? Does it assert concrete values? Is it folded into the matching `*Test.scala` for the source it covers? Does concurrency use the `Latch` pattern rather than `sleep`? Is payload comparison done with `Span#is`, not `==`?

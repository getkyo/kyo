# Contributing to kyo-eventlog

Module-specific guide for kyo-eventlog. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-eventlog.

**The headline invariant:** every `Journal` operation is encoded as an ArrowEffect: it suspends a reified op, carries its own `Abort[<per-op trait>]` in the row beside `Journal`, and the handler (`Journal.run`) discharges `Journal` while converting each backend `Abort` call into a `Result` that rides in the continuation, leaving the per-op traits on the residual. The safe-tier ops, the Op enum's GADT encoding, and the handler's per-branch `Abort.run` are all consequences of this single structural choice.

---

## Architecture overview

kyo-eventlog owns the Journal capability and its backing infrastructure:

| File | Purpose |
|---|---|
| `Journal.scala` | `Journal` sealed trait; `Op` enum (GADT); `Reader[S]`; `Backend[S] extends Reader[S]`; public ops; `run` handler; `Unsafe` forwarders |
| `EventLog.scala` | Typed facade `EventLog[A]` and `EventLog.Typed[A]` over raw journal ops |
| `EventPayloadCodec.scala` | Payload encoding strategies (`bytes`, `schema[A]`) for file backends |
| `JournalError.scala` | Sealed `KyoException` hierarchy: umbrella base, three per-op traits, five leaves |
| `JournalEvent.scala` | Wire-vocabulary types: `StreamId`, `EventId`, `EventType`, `StreamOffset`, `StreamVersion`, `ExpectedOffset`, `StreamInfo`, `EventEnvelope`, `RecordedEvent`, `AppendResult` |
| `JournalMetadata.scala` | `EventMetadata` case class; `MetadataKey` opaque type |
| `internal/InMemoryJournal.scala` | Ephemeral in-memory backend: CAS over `AtomicRef`, `Loop`-based retry |
| `FileJournal.scala` | `FileJournal.Config`, `Fsync`, `SegmentFormat`; platform `Backend.file` / `Reader.file` extensions |
| `internal/FileJournalCore.scala` | Shared orchestration: recovery driver, segment index, in-process root registry, CAS claim, rotation, read path |
| `internal/SegmentStore.scala` | Platform I/O seam (`open`, `acquireLock`, `syncDir`); effect-polymorphic store seam for Sync vs Async |
| `internal/SegmentCodec.scala`, `internal/JsonlSegmentCodec.scala` | Binary and JSONL segment codecs selected by `Config.format` |
| `internal/CRC32.scala` | Table-driven pure Scala CRC32 (reflected IEEE 802.3 polynomial `0xEDB88320`); byte-identical on every platform |
| `jvm-native/src/main/scala/kyo/FileJournalBackend.scala` | `FileChannel`-backed store; `Backend.file`, `Backend.fileAsync`, `Reader.file`, `Reader.fileAsync` |
| `js-wasm/src/main/scala/kyo/FileJournalBackend.scala` | `isNodeRuntime` predicate; sync and async file backends; reader extensions |
| `js-wasm/src/main/scala/kyo/internal/NodeJournalStore.scala` | `NodeFsSync` / `NodeAsyncJournalStore` facades; `NodeSegmentStore`, `NodeHandle`, `NodeFileLock` |

**Dependency rule:** kyo-eventlog depends on `kyo-core`, `kyo-schema`, and `kyo-system`. `kyo-schema` is pulled in for `Structure.Value`, `Codec.Writer`/`Reader`, and `Schema.init`, which `MetadataValue`'s constructor-exact codec uses. `kyo-system` is pulled in by the file backend: the shared orchestration uses `Path.Unsafe` for cross-platform directory operations (exists, mkdir, list), and the jvm-native backend additionally uses the `toJava` extension for `FileChannel.open`. The journal-specific Node facades (`NodeFsSync`, `NodeOsHost`, and the durability primitives `fdatasyncSync`/`fsyncSync`/`ftruncateSync`) live in the kyo-eventlog `js-wasm` tree rather than kyo-system, to keep those journal-specific bindings scoped to the module that needs them. No other kyo module is a compile-time dependency. [`build.sbt:675`]

### Source layout

```
kyo-eventlog/
  shared/src/main/scala/kyo/
    Journal.scala
    JournalError.scala
    JournalEvent.scala
    JournalMetadata.scala
    FileJournal.scala              # Config and Fsync knobs
    internal/
      InMemoryJournal.scala
      FileJournalCore.scala        # shared orchestration and recovery driver
      SegmentStore.scala           # platform I/O seam (open, acquireLock, syncDir)
      CRC32.scala                  # pure shared CRC32 (IEEE 802.3 polynomial)

  shared/src/test/scala/kyo/
    JournalTest.scala
    JournalBackendTest.scala
    InMemoryJournalBackendTest.scala
    JournalEventTest.scala
    JournalMetadataTest.scala
    FileJournalBackendTest.scala   # contract suite subclass; runs on all four platforms
    FileJournalCodecTest.scala     # segment codec unit tests; all four platforms
    FileJournalCrashTest.scala     # crash, recovery, and corruption suite; all four platforms
    FileJournalTest.scala          # rotation, metadata, streamId, lock, failed-open

  jvm-native/src/main/scala/kyo/
    FileJournalBackend.scala       # FileChannel SegmentStore; FileChannel.tryLock lock; Backend.file (JVM/Native)
    internal/
      PlatformSupport.scala        # yieldCurrentThread via Thread.yield

  js-wasm/src/main/scala/kyo/
    FileJournalBackend.scala       # isNodeRuntime predicate; Backend.file (JS/Wasm)
    internal/
      NodeJournalStore.scala       # NodeFsSync/NodeOsHost facades; NodeSegmentStore; NodeFileLock
      PlatformSupport.scala        # yieldCurrentThread (no-op on single-threaded runtimes)

  js-wasm/src/test/scala/kyo/
    FileJournalNodeLockTest.scala  # O_EXCL lock failure matrix (cases 1-7)
    FileJournalNodeRuntimeTest.scala # isNodeRuntime predicate; browser-fail typed error

  jvm/src/test/scala/kyo/
    CRC32EqualityTest.scala        # equality with java.util.zip.CRC32 over a fixed corpus (JVM-only)
```

`Journal.Backend.file` and `FileJournal.Config` are available on all four platforms. On JVM and Native the backend uses a `FileChannel`-backed store. On JS and Wasm it uses Node's synchronous `fs` API and requires a Node.js runtime; on a browser runtime (no `node:fs`) the call fails immediately with a typed `JournalStorageError` rather than at first I/O. No browser persistence backend exists. The `jvm-native/` tree compiles on JVM and Native; the `js-wasm/` tree compiles on both JS and Wasm. All other public types and operations (`Journal`, `Journal.Backend.inMemory`, the wire types, the error hierarchy) compile and behave identically on all four platforms.

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

`EventMetadata` is a `final case class` wrapping `Map[MetadataKey, MetadataValue]` (`JournalMetadata.scala:14`). `MetadataValue` is an opaque type backed by `Structure.Value` with a constructor-exact codec: each of the ten `Structure.Value` constructors encodes as a one-field record keyed by its tag name (`str`, `int`, `bool`, `decimal`, `bignum`, `null`, `seq`, `record`, `entries`, `variant`), so every constructor round-trips without loss through the self-describing MsgPack codec the file backend uses. The tag-keyed open shape (notably the array-of-arrays `entries` form) is not read back by every text codec, so the round-trip guarantee is scoped to the binary MsgPack path; `JournalMetadataTest` verifies all ten constructors through the `given Schema[MetadataValue]` under MsgPack. Construct with `MetadataValue(sv: Structure.Value)` and project with `.value: Structure.Value`. Metadata is for infrastructure concerns (correlation identifiers, tracing tags) that consumers may need without decoding the payload.

`MetadataKey` is an opaque `String` with dotted-path validation: non-empty, no leading dot, no trailing dot, no consecutive dots (`foo..bar` is rejected). The `segments` extension splits on `.` and returns a `Chunk[String]`.

`EventMetadata.empty` is the canonical metadata-free value.

---

## Typed event layer (EventLog)

[`EventLog.scala`, `EventPayloadCodec.scala`]

`EventLog[A]` wraps `Journal` with schema-driven encode/decode for domain type `A`. Methods mirror `Journal` ops and carry the same per-op `Abort` traits. No backend is captured at construction; call inside `Journal.run(backend)(...)`.

`EventPayloadCodec` selects on-disk payload shape for file backends:

| Value | Encoding |
|---|---|
| `EventPayloadCodec.bytes` | Raw `Span[Byte]` identity |
| `EventPayloadCodec.schema[A]` | Schema-derived JSON (requires `Schema[A]`) |

Write-side deciders are ordinary `Journal.run` programs: read typed history, fold to state, append the next event with `ExpectedOffset` conflict recovery. See the kyo-eventlog README decider section and `EventLog` companion scaladoc.

---

## Backend SPI

### Contract

[`Journal.scala:43-82`]

`Journal.Reader[S]` is the read-only contract (`read`, `streamInfo`). `Journal.Backend[S] extends Reader[S]` adds `append`:

```scala
trait Reader[S]:
    def read(streamId, from, maxCount): Chunk[RecordedEvent] < (S & Abort[JournalReadFailure])
    def streamInfo(streamId): StreamInfo < (S & Abort[JournalStreamInfoFailure])

trait Backend[S] extends Reader[S]:
    def append(streamId, expected, events): AppendResult < (S & Abort[JournalAppendFailure])
```

Each method names its precise failure trait in the `Abort` row. The type parameter `S` is the backend's own effect: `Sync` for the in-memory and sync file backends, `Async` for `Backend.fileAsync` and `Reader.fileAsync`.

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

4. Follow kyo-eventlog's dependency rule: the three authorized compile-time dependencies are `kyo-core`, `kyo-schema`, and `kyo-system`. New backends in `shared/src/main/scala/kyo/` need no extra dependency. Backends that reach raw platform I/O (file channels, advisory locks, fsync, Node raw-fd calls) belong in the appropriate platform source tree (`jvm-native/` or `js-wasm/`) and must use `Sync.Unsafe.defer` with per-site `// Unsafe:` comments (see the section below).

### File backend

[`shared/src/main/scala/kyo/FileJournal.scala`, `jvm-native/src/main/scala/kyo/FileJournalBackend.scala`, `js-wasm/src/main/scala/kyo/FileJournalBackend.scala`]

`FileJournal` is the durable file backend: an append-only segment log on disk, available on JVM, Native, JS-under-Node, and Wasm-under-Node. Public entry points are extensions on `Journal.Backend.type` and `Journal.Reader.type` in each platform tree's `FileJournalBackend.scala`:

```scala
Journal.Backend.file(dir: Path, config: FileJournal.Config = default, payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes)
    : Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError])

Journal.Backend.fileAsync(dir: Path, config: FileJournal.Config = default, payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes)
    : Journal.Backend[Async] < (Sync & Scope & Abort[JournalStorageError])

Journal.Reader.file(dir: Path, config: FileJournal.Config = default, payloadCodec: EventPayloadCodec = EventPayloadCodec.bytes)
    : Journal.Reader[Sync] < (Sync & Scope & Abort[JournalStorageError])

Journal.Reader.fileAsync(dir: Path, ...)
    : Journal.Reader[Async] < (Sync & Scope & Abort[JournalStorageError])
```

The `dir` parameter is a `Path` from `kyo-system`. `Scope` finalization releases the advisory LOCK and closes all open segment channels. A second `Backend.file` open of a held root directory fails immediately with `JournalStorageError`. Reader opens skip the writer lock (SWMR).

`FileJournal.Config` has three fields:

| Field | Default | Notes |
|---|---|---|
| `fsync: Fsync` | `Fsync.Always` | Flush each acknowledged append to stable storage before returning. Set to `Fsync.Disabled` only in tests; the crash-survival guarantee does not hold when `Fsync.Disabled` is set. |
| `segmentSize: FileSize` | `64L.mib` (64 MiB) | Soft rotation threshold. Checked before an append, not after. |
| `format: SegmentFormat` | `SegmentFormat.Binary` | Fixed at root creation via the `FORMAT` marker file. `Binary` (`.seg`, CRC32 frames) or `Jsonl` (`.jsonl`, one JSON object per line). |

**Segment format:** each segment file begins with `KJN1` + `0x01` (4-byte magic + 1-byte version). Record frames follow with the layout `length(4) | crc32(4) | body`; the CRC covers the body only. Each append batch is closed by a terminator (`KJNC` + record count + CRC), which is the commit boundary. A torn tail in the active segment (no valid terminator after the trailing record group, from a prior crash) is silently truncated at recovery with a WARN log entry naming the segment and byte range. Non-tail corruption and unknown segment versions are fatal (`JournalCorruptedError`).

**CRC32:** the CRC is computed by a table-driven pure Scala implementation (`internal/CRC32.scala`) using the reflected IEEE 802.3 polynomial `0xEDB88320`, producing the same 32-bit values as `java.util.zip.CRC32`. Using one shared implementation across all platforms makes cross-platform byte-identity hold by construction rather than by relying on two independent standard libraries agreeing. `CRC32EqualityTest` (jvm-only) asserts equality with `java.util.zip.CRC32` over a fixed corpus; `FileJournalCodecTest` verifies known-answer vectors on every platform.

**LOCK:** the file backend enforces single-owner exclusion at two levels. An in-process registry (`heldRoots` in `FileJournalCore`) checks same-process opens on every platform before any OS-level call. The cross-process layer differs by platform:

- **JVM and Native:** `FileChannel.tryLock()` on the `LOCK` file. The OS drops the advisory lock on process death, so no manual cleanup is needed after a crash.
- **JS and Wasm (Node.js):** an `O_EXCL` lockfile (`openSync("wx")` = `O_CREAT | O_EXCL | O_WRONLY`). The file carries the holder's pid, hostname, and start timestamp in JSON. On `EEXIST`, the holder's pid is probed with `process.kill(pid, 0)` (signal 0 tests reachability without sending a signal): `ESRCH` means the holder is dead and the lock is reclaimed; any other result is treated as alive and the open fails. Reclaim is atomic: the stale lock is moved aside under a per-attempt unique name before the `O_EXCL` create is retried, so two concurrent reclaimers cannot collide; ownership is decided solely by which process wins the subsequent `openSync("wx")`. An unparseable lock or a lock from another hostname is never reclaimed and fails with `JournalStorageError` (fail-closed). Release calls `unlinkSync(LOCK)` best-effort. This protocol gives the same crash-recovery semantics as the JVM `FileChannel.tryLock`: a crashed holder's lock is reclaimed on the next open.

A second open of a held root always fails immediately with `JournalStorageError`, regardless of platform.

**Same-stream append serialization (Sync backend).** `Journal.Backend.file` returns `Backend[Sync]`: the whole critical section (offset check, frame, positional write, fsync, index publish) runs inside one `Sync.Unsafe.defer`. Concurrent appends to the same stream serialize on a per-stream CAS flag (`StreamState.writer`) taken in `claim`. The spin-wait behavior is platform-split via `yieldCurrentThread()` in `internal/PlatformSupport.scala`.

**Async backend (`fileAsync`).** `Journal.Backend.fileAsync` returns `Backend[Async]`. Store I/O runs through the effect-polymorphic store seam: JVM/Native use `Async.defer(Sync.Unsafe.defer(...))` blocking offload; Node uses `NodeAsyncJournalStore` with `fs.promises`. Concurrent appenders coalesce durability flushes via group commit (`GroupCommitCoordinator` + parked claim permits). `JournalBackendTest` remains Sync-typed; async backends have dedicated liveness tests.

**Directory durability.** `FileChannel.force` on a segment file flushes its data but not the parent directory's link to it; on POSIX a newly created file or directory is not durable until its containing directory is fsync'd. On the `Fsync.Always` path `createSegment` therefore fsyncs the stream directory after creating a segment (and the `streams/` directory when the stream directory is new) via `fsyncDir`, which opens the directory read-only and forces it. Windows cannot open a directory as a `FileChannel`, so that open throws `IOException` and is tolerated (the platform makes the entry durable without an explicit directory sync); a force failure on an opened directory propagates and maps to `JournalStorageError`. This path is not black-box testable: it needs a power loss between the directory write and its sync.

### Unsafe tier in backend implementations

A raw platform I/O backend uses two distinct patterns. Both appear in the jvm-native backend; the Node backend follows the same conventions with its own raw-fd facade.

**Open and release boundaries.** The `FileJournalCore.acquire` function takes `(using AllowUnsafe)` explicitly and performs all platform I/O (directory creation via `Path.Unsafe`, `FileChannel.open` or `NodeFsSync.openSync`, `acquireLock`) without any `Sync.Unsafe.defer` inside it. `FileJournalCore.open` calls it inside a single `Sync.Unsafe.defer` at the open boundary:

```scala
Sync.Unsafe.defer(Abort.get(FileJournalCore.acquire(dir, config, store)))
```

The `Scope` release path wraps `backend.release()` the same way:

```scala
Sync.Unsafe.defer(backend.release())
```

Each boundary is one `Sync.Unsafe.defer` wrapping the entire call, not a separate call per operation inside `acquire` or `release`.

**Class-level threading for backend methods.** `FileJournalCore` captures `(using allow: AllowUnsafe)` as a constructor parameter. This is the `AllowUnsafe` evidence that `Sync.Unsafe.defer` in `FileJournalCore.open` provides at construction time; the class retains it and threads it through all private methods as a regular parameter. Each public `Backend` method wraps a single call to the corresponding private function in one `Sync.Unsafe.defer`:

```scala
Sync.Unsafe.defer(appendUnsafe(streamId, expected, events, log.unsafe))
```

All `SegmentStore.Handle` and `Path.Unsafe` calls inside `appendUnsafe`, `readUnsafe`, and `streamInfoUnsafe` run under the constructor `allow` already in scope. No additional `Sync.Unsafe.defer` appears per call site inside those private functions.

**Node raw-fs bridge pattern.** `NodeSegmentStore` and `NodeFileLock` (in `js-wasm/src/main/scala/kyo/internal/NodeJournalStore.scala`) bridge the kyo effect system to raw Node.js `fd` operations via the `NodeFsSync` facade. The same conventions apply: `AllowUnsafe` flows from the `FileJournalCore` constructor, every raw Node call carries a `// Unsafe:` comment naming what it bridges, and the entire `SegmentStore.Handle` and `SegmentStore.Lock` call surface is inside the class body (not in per-call `Sync.Unsafe.defer`). The lock protocol (`NodeFileLock.acquire`) takes `(using AllowUnsafe, Frame)` directly and is called from inside the single `Sync.Unsafe.defer` in `FileJournalCore.acquire`.

Annotate each `Sync.Unsafe.defer` call with a `// Unsafe:` comment naming the safe-tier contract being bypassed. Do not introduce `import AllowUnsafe.embrace.danger` in backend source; `AllowUnsafe` flows from the class constructor parameter, which is itself supplied by the `Sync.Unsafe.defer` in `FileJournalCore.open`.

---

## Prior art

The file backend's design is grounded in established storage engineering patterns. Knowing the lineage makes the design decisions easier to reason about when extending or debugging.

**Kafka log segments.** Kafka names each segment by the base offset of its first record (zero-padded), rotates at a size threshold, and frames each record with a CRC. FileJournal's segment naming (20-digit base offset), soft-threshold rotation, and per-record CRC follow this model directly.

**Write-ahead-log tail recovery (PostgreSQL, RocksDB).** A WAL is read forward on restart and stops at the first torn or unchecksummed record at the tail, treating everything after the last good record as an incomplete write to discard. FileJournal's recovery is the same forward scan with tail truncation; the refinement is that the commit boundary is a batch terminator, not a single record.

**ARIES commit records.** ARIES marks a transaction durable only when its commit log record is on stable storage; recovery replays only committed transactions. The batch-commit terminator (binary `KJNC`) is exactly this: a batch is committed iff its terminator is durable, which is what makes a multi-event append all-or-nothing.

**Magic + version container convention.** A leading magic constant plus an explicit format-version byte is the standard self-describing-file convention (PNG, class files, many WALs). FileJournal's `KJN1` + version byte follows it, enabling safe detection of unknown or corrupt segment headers before any record parse.

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
| `FileJournal.scala`, `internal/FileJournalCore.scala`, `internal/SegmentStore.scala` | `FileJournalBackendTest.scala`, `FileJournalCrashTest.scala`, `FileJournalTest.scala` | All in `shared/src/test`; run on JVM, Native, JS-node, Wasm-node |
| `internal/CRC32.scala` | `FileJournalCodecTest.scala` (shared); `jvm/src/test: CRC32EqualityTest.scala` | Known-answer vectors on all platforms; equality against `java.util.zip.CRC32` is JVM-only |
| `jvm-native/FileJournalBackend.scala` | `FileJournalTest.scala` (shared, second-open and failed-open cases), `FileJournalCrashTest.scala` | The FileChannel lock path is covered by the shared second-open case running on JVM and Native |
| `js-wasm/FileJournalBackend.scala`, `js-wasm/internal/NodeJournalStore.scala` | `FileJournalNodeLockTest.scala`, `FileJournalNodeRuntimeTest.scala` | js-wasm-only; Node lock matrix (cases 1-7) and browser-fail typed error |

### Deterministic concurrency: the Latch pattern

[`JournalBackendTest.scala:243-273`]

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

5. **New dependency from kyo-eventlog.** kyo-eventlog depends on `kyo-core`, `kyo-schema`, and `kyo-system`. `kyo-system` is present because the file backend uses `Path.Unsafe` for cross-platform directory operations on every platform, and the jvm-native backend additionally uses the `toJava` extension for `FileChannel.open`. Journal-specific Node facades (`NodeFsSync`, `NodeOsHost`) live in the kyo-eventlog `js-wasm` tree, not kyo-system. Adding any module beyond these three requires explicit authorisation. [`build.sbt:675`]

6. **New test.** Does it extend `kyo.test.Test[Any]`? Does it assert concrete values? Is it folded into the matching `*Test.scala` for the source it covers? Does concurrency use the `Latch` pattern rather than `sleep`? Is payload comparison done with `Span#is`, not `==`?

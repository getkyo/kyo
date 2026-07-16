# kyo-eventlog

`kyo-eventlog` is an append-only event streaming module: a way to record immutable domain events into named streams, read them back in offset order, and use an expected-offset check to guard concurrent writers without locks.

A `Journal` program is a composed value. Operations (`append`, `read`, `streamInfo`) suspend as reified effects, and `Journal.run(backend)(program)` installs a storage backend, discharges the `Journal` capability, and leaves each operation's typed failure on the `Abort` row. Schemas, codecs, and domain event types live at the layer above; the payload is raw bytes throughout.

The module targets JVM, JavaScript, Scala Native, and WebAssembly. It depends on `kyo-core`, `kyo-schema`, and `kyo-system`; callers using `Journal.Backend.file` must include `kyo-system` in their own build to construct a `Path` value. Metadata values are `MetadataValue` instances, an opaque wrapper around `Structure.Value` with a constructor-exact codec.

<!-- doctest:setup
```scala
import kyo.*

sealed trait FleetEvent derives Schema
case class VehicleAdded(id: String, make: String) extends FleetEvent derives Schema
case class VehicleRetired(id: String)             extends FleetEvent derives Schema

val sid: StreamId      = StreamId("fleet-main").getOrThrow
val evType1: EventType = EventType("VehicleAdded").getOrThrow
val evType2: EventType = EventType("VehicleRetired").getOrThrow
val evId1: EventId     = EventId("evt-001").getOrThrow
val evId2: EventId     = EventId("evt-002").getOrThrow
```
-->

```scala
for
    backend <- Journal.Backend.inMemory
    envelope = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
    events <- Journal.run(backend):
        for
            _ <- Journal.append(sid, ExpectedOffset.NoStream, Chunk(envelope))
            r <- Journal.read(sid, StreamOffset.first, 10)
        yield r
yield events
end for
```

See `demo.FleetLedgerDemo` for a file-backed EventLog session that reopens the journal after scope close (`sbt 'kyo-eventlogJVM/Test/runMain demo.FleetLedgerDemo'`).

## Installation

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-eventlog" % "<latest version>"
```

All public types live in the `kyo` package:

```scala
import kyo.*
```

## Compose journal programs

Before appending or reading events, pick a backend and wrap the program in `Journal.run`. The runner installs storage, discharges the `Journal` capability, and leaves each operation's typed `Abort` on the residual row.

`Journal.run[S, A, S2](backend: Backend[S])(program: A < (Journal & S2))(using Frame): A < (S & S2)` installs a backend and discharges the `Journal` capability. The backend's own effect `S` joins the residual; the per-op `Abort` traits in `S2` pass through untouched.

| Operation | Row inside `run` | Residual after `run` (inMemory, `S = Sync`) |
|---|---|---|
| `append` | `AppendResult < (Journal & Abort[JournalAppendFailure])` | `AppendResult < (Sync & Abort[JournalAppendFailure])` |
| `read` | `Chunk[RecordedEvent] < (Journal & Abort[JournalReadFailure])` | `Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure])` |
| `streamInfo` | `StreamInfo < (Journal & Abort[JournalStreamInfoFailure])` | `StreamInfo < (Sync & Abort[JournalStreamInfoFailure])` |

For an `Async` backend, `Sync` in the residual column becomes `Async`; the per-op `Abort` traits stay on the row regardless of the backend.

`Journal.run` does not add an umbrella `Abort[JournalError]` to the row. To handle all journal failures at once, wrap the program in `Abort.run[JournalError]`. Every per-op trait extends the sealed `JournalError` base, so the widening is sound:

```scala
val safe: Result[JournalError, Chunk[RecordedEvent]] < Sync =
    for
        backend <- Journal.Backend.inMemory
        envelope = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
        result <- Abort.run[JournalError]:
            Journal.run(backend):
                for
                    _ <- Journal.append(sid, ExpectedOffset.NoStream, Chunk(envelope))
                    r <- Journal.read(sid, StreamOffset.first, 10)
                yield r
    yield result
```

### Error model

`JournalError` is the sealed umbrella base. Three per-operation traits name each operation's failure set:

| Error | `JournalAppendFailure` | `JournalReadFailure` | `JournalStreamInfoFailure` | Trigger |
|---|---|---|---|---|
| `JournalEmptyAppendError` | yes | | | empty batch |
| `JournalConflictError` | yes | | | expected-offset mismatch |
| `JournalCorruptedError` | yes | yes | yes | unrecoverable corruption (durable backends) |
| `JournalStorageError` | yes | yes | yes | I/O failure (durable backends) |
| `JournalInvalidIdentifierError` | | | | bad identifier; `Result.Failure` from constructors only |

> **Note:** `JournalInvalidIdentifierError` is not a subtype of any op trait. It is the `Result.Failure` of `StreamId(...)`, `EventId(...)`, `EventType(...)`, `StreamOffset(...)`, `StreamVersion(...)`, and `MetadataKey(...)`, never on an `Abort` op row.

### Low-level escape hatch

`Journal.Unsafe` bypasses the ArrowEffect suspend and handler dispatch, invoking the backend directly under its effect `S`. Use it in library integrations or performance-sensitive paths where the ArrowEffect round-trip is measurable overhead.

```scala
def appendDirect(backend: Journal.Backend[Sync], env: EventEnvelope)(using
    AllowUnsafe
)
    : AppendResult < (Sync & Abort[JournalAppendFailure]) =
    Journal.Unsafe.append(backend)(sid, ExpectedOffset.Any, Chunk(env))
```

The same per-op `Abort[<trait>]` failure surface applies as on the safe ops. The three unsafe duals are `Journal.Unsafe.append`, `Journal.Unsafe.read`, and `Journal.Unsafe.streamInfo`.

## Streams and events

A stream is an ordered, append-only sequence of events sharing one identity, typically one aggregate or one logical log. Each event is submitted as an `EventEnvelope` and stored as a `RecordedEvent`. The payload is raw bytes; typed encoding and decoding belong to the layer above kyo-eventlog.

**Submitted (`EventEnvelope`):** `id: EventId`, `eventType: EventType`, `payload: Span[Byte]`, `metadata: EventMetadata`.

**Stored (`RecordedEvent`):** all four envelope fields plus `streamId: StreamId` and `offset: StreamOffset`.

`StreamId`, `EventId`, `EventType`, `StreamOffset`, `StreamVersion`, and `MetadataKey` are all opaque `String` or `Long` values. Each constructor validates the input and returns `Result[JournalInvalidIdentifierError, T]`:

```scala
val ok: Result[JournalInvalidIdentifierError, StreamId]  = StreamId("fleet-main")
val bad: Result[JournalInvalidIdentifierError, StreamId] = StreamId("")
```

To unwrap into a plain value, call `.getOrThrow` on the result; because `JournalInvalidIdentifierError` extends `KyoException` which extends `Exception`, the required evidence holds. `StreamOffset` is zero-based; `StreamOffset.first` (`0L`) is the first event position in any stream. `StreamVersion` is the one-based count view: `StreamVersion.after(offset)` equals `offset.value + 1`.

> **Note:** `Span` equality via `==` is reference-based. To compare payload contents, use `Span#is`, not `==` on `EventEnvelope` or `RecordedEvent`.

## Append with optimistic concurrency

Concurrent writers append to the same stream without locks by checking an expected offset atomically with each write. The three subsections below cover the guard model, the append API, and recovery when a guard fails.

### Optimistic concurrency

`ExpectedOffset` guards each append with a check that is atomic with the write, so two writers cannot both believe they own the same tail offset:

| Value | Condition required | Fails with |
|---|---|---|
| `ExpectedOffset.Any` | none | never |
| `ExpectedOffset.NoStream` | stream must be absent | `JournalConflictError` if any events exist |
| `ExpectedOffset.Exact(offset)` | live last offset must equal `offset` | `JournalConflictError` if absent or offset differs |

A failed check leaves the stream unchanged. `JournalConflictError` carries `actual: StreamInfo`, the stream state observed at conflict time. Use `actual` to compute the corrected `ExpectedOffset` before retrying; see Conflict recovery below.

### Appending events

Events go into the named stream as an atomic batch: all events land with consecutive offsets, or none do. An empty batch fails immediately with `JournalEmptyAppendError` without a storage round-trip, so callers cannot accidentally submit no-op writes that still touch the backend.

`Journal.append(streamId, expected, events)` returns `AppendResult < (Journal & Abort[JournalAppendFailure])`.

```scala
val placed: AppendResult < (Sync & Abort[JournalAppendFailure]) =
    for
        backend <- Journal.Backend.inMemory
        envelope = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
        result <- Journal.run(backend)(Journal.append(sid, ExpectedOffset.NoStream, Chunk(envelope)))
    yield result
```

`AppendResult` carries `firstOffset`, `lastOffset`, and the post-append `streamInfo`. Use `lastOffset` as the `Exact` guard for the next append on the same stream:

```scala
val fleetBatch: AppendResult < (Sync & Abort[JournalAppendFailure]) =
    for
        backend <- Journal.Backend.inMemory
        envelope1 = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
        envelope2 = EventEnvelope(evId2, evType2, Span.empty, EventMetadata.empty)
        result <- Journal.run(backend):
            for
                r1 <- Journal.append(sid, ExpectedOffset.NoStream, Chunk(envelope1))
                r2 <- Journal.append(sid, ExpectedOffset.Exact(r1.lastOffset), Chunk(envelope2))
            yield r2
    yield result
```

### Conflict recovery

When `Journal.append` fails with `JournalConflictError`, `actual` holds the `StreamInfo` observed at conflict time. Catch the failure row with `Abort.run` and inspect the result:

```scala
val attempt: Result[JournalAppendFailure, AppendResult] < Sync =
    for
        backend <- Journal.Backend.inMemory
        envelope = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
        result <- Abort.run[JournalAppendFailure]:
            Journal.run(backend)(Journal.append(sid, ExpectedOffset.NoStream, Chunk(envelope)))
    yield result
```

(The example uses a fresh in-memory backend, so `ExpectedOffset.NoStream` succeeds on first attempt; in practice a concurrent writer appending to the same stream between your read and your write is what produces the conflict this recovery handles.)

On a `Result.Failure(conflict: JournalConflictError)`, read `conflict.actual` to choose the next guard:

```scala
def nextOffset(conflict: JournalConflictError): ExpectedOffset =
    conflict.actual match
        case StreamInfo.Existing(_, last) => ExpectedOffset.Exact(last)
        case StreamInfo.Absent            => ExpectedOffset.NoStream
```

When `actual` is `Existing`, `last` is the current last offset; pass it as `Exact` on the retry append. When `actual` is `Absent`, the stream was concurrently deleted; use `NoStream` or abandon.

## Read and inspect streams

After events are appended, bounded reads and stream metadata queries let you replay history or check tail state before the next write.

### Reading events

`Journal.read` returns a bounded slice of a stream's events in ascending offset order. `Journal.read(streamId, from, maxCount)` returns `Chunk[RecordedEvent] < (Journal & Abort[JournalReadFailure])`.

```scala
val fleetHistory: Chunk[RecordedEvent] < (Sync & Abort[JournalReadFailure]) =
    for
        backend <- Journal.Backend.inMemory
        result  <- Journal.run(backend)(Journal.read(sid, StreamOffset.first, 100))
    yield result
```

A missing stream, a `from` at or past the event count, and `maxCount <= 0` all return an empty `Chunk` on the in-memory backend; they are not failures. Paginate by advancing `from` by the length of the previous batch.

### Stream inspection

`Journal.streamInfo` reports whether a stream exists and, if so, its current last offset and event count. `Journal.streamInfo(streamId)` returns `StreamInfo < (Journal & Abort[JournalStreamInfoFailure])`.

`StreamInfo` is either `Absent` or `Existing(version, lastOffset)`. The `exists` predicate distinguishes the two. For a contiguous stream, `version.value == lastOffset.value + 1` always holds.

```scala
val fleetState: StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
    for
        backend <- Journal.Backend.inMemory
        result  <- Journal.run(backend)(Journal.streamInfo(sid))
    yield result
```

## Typed events with EventLog

`EventLog[A]` wraps raw `Journal` operations with schema-driven encoding and decoding for domain type `A`. Construct with `for log <- EventLog[FleetEvent]` via `EventLog.apply` (requires `Schema[A]` in implicit scope, returns `EventLog[A] < Sync`). `EventLog.init[A]` is the `AllowUnsafe` bootstrap when you already hold `AllowUnsafe`. Call its methods inside `Journal.run(backend)(...)` exactly as you would call `Journal.append`, `Journal.read`, and `Journal.streamInfo`:

`EventLog.apply[A]` is the normal constructor (`EventLog[A] < Sync`); `EventLog.init[A]` is the unsafe bootstrap when you already hold `AllowUnsafe`.

```scala
val appended: AppendResult < (Sync & Abort[JournalAppendFailure]) =
    for
        backend <- Journal.Backend.inMemory
        log     <- EventLog[FleetEvent]
        result <- Journal.run(backend) {
            log.append(sid, ExpectedOffset.NoStream, Chunk(VehicleAdded("V001", "Toyota")))
        }
    yield result
```

`read` returns `Chunk[EventLog.Typed[A]]` with the decoded payload in `.payload`. Decode failures surface as `JournalCorruptedError` on the read row.

`EventLog.streamInfo` mirrors `Journal.streamInfo` on the same stream id and carries `Abort[JournalStreamInfoFailure]`.

`EventPayloadCodec` selects how event payloads are stored on disk. Pass it to `Journal.Backend.file` and `Journal.Backend.fileAsync`:

| Codec | Behavior |
|---|---|
| `EventPayloadCodec.bytes` (default) | Raw `Span[Byte]` identity encoding |
| `EventPayloadCodec.schema[A]` | Binary segments store Ion Binary-encoded payloads (default); JSONL segments embed a JSON value transcoded through the schema (requires `Schema[A]`) |
| `EventPayloadCodec.schema[A](binary)` | Same as above with an explicit binary `Codec` (e.g. `MsgPack()`) |

`FileJournal.SegmentFormat` chooses the on-disk segment encoding, fixed at journal root creation:

| Format | Segment files | Notes |
|---|---|---|
| `SegmentFormat.Binary` (default) | `.seg` with `KJN1` header | CRC32-verified binary frames |
| `SegmentFormat.Jsonl` | `.jsonl` lines | Human-readable; payload shape follows the selected codec |

Set `format` on `FileJournal.Config` when opening a new root. Set `metadataCodec` to choose the binary metadata wire format (`EventMetadataCodec.default` is Ion Binary; pass `EventMetadataCodec.msgPack` for the legacy MsgPack wire).

## Write-side deciders

A decider reads typed history, folds it into local state, and appends the next domain event when the state says to act. It is an ordinary `Journal.run` program using `EventLog[A]`: no separate decider API type is required.

The write loop follows three steps: read the stream, decide the next event (a pure function over `Chunk[EventLog.Typed[A]]`), append with an `ExpectedOffset` guard. When a concurrent writer causes `JournalConflictError`, inspect `conflict.actual`, re-read if needed, and retry with the corrected guard (see Conflict recovery above).

```scala
def activeVehicleCount(events: Chunk[EventLog.Typed[FleetEvent]]): Int =
    events.foldLeft(0) { (count, e) =>
        e.payload match
            case VehicleAdded(_, _) => count + 1
            case VehicleRetired(_)  => count - 1
    }

def decideRetire(events: Chunk[EventLog.Typed[FleetEvent]]): Maybe[FleetEvent] =
    events.foldLeft(Absent: Maybe[FleetEvent]) { (found, e) =>
        found match
            case Present(_) => found
            case Absent =>
                e.payload match
                    case VehicleAdded(id, _) => Present(VehicleRetired(id))
                    case _                   => Absent
    }

val deciderStep: Unit < (Sync & Abort[JournalAppendFailure | JournalReadFailure]) =
    for
        backend <- Journal.Backend.inMemory
        log     <- EventLog[FleetEvent]
        _ <- Journal.run(backend) {
            for
                events <- log.read(sid, StreamOffset.first, maxCount = 1000)
                _ <- decideRetire(events) match
                    case Present(event) =>
                        val expected = events.lastOption match
                            case Some(last) => ExpectedOffset.Exact(last.offset)
                            case None       => ExpectedOffset.NoStream
                        log.append(sid, expected, Chunk(event)).map(_ => ())
                    case Absent =>
                        Sync.defer(())
            yield ()
        }
    yield ()
```

Projection (replaying events into a read model without writing) is the companion pattern: fold `Chunk[EventLog.Typed[A]]` with a pure function, as in the kyo-examples car rental demo.

## Metadata

`EventMetadata` holds infrastructure data alongside the payload: correlation identifiers, tracing keys, tags. Values are `MetadataValue` instances. `MetadataValue` is an opaque wrapper around `Structure.Value` with a constructor-exact codec that round-trips all ten `Structure.Value` constructors without loss through the binary metadata codec (Ion Binary by default). Construct with `MetadataValue(sv: Structure.Value)` and access the underlying value with `.value`. Domain fields belong in the encoded payload bytes; metadata is for infrastructure concerns that consumers need without decoding.

```scala
val key: Result[JournalInvalidIdentifierError, MetadataKey] = MetadataKey("trace.correlation_id")
val meta: EventMetadata                                     = EventMetadata.empty
```

`MetadataKey` is a validated dotted-path string. Empty keys, leading or trailing dots, and consecutive dots (`foo..bar`) are all rejected. `key.segments` splits on `.` and returns a `Chunk[String]`.

## Backends

`Journal.Backend[S]` is the storage contract. Implement its three methods, each returning their op's failure trait under the backend's own effect `S`, and pass the instance to `Journal.run`. Backend methods capture a `Frame` at construction, not per call; custom backends must follow the same pattern so failures carry source-location information.

`Journal.Backend.inMemory` creates an ephemeral in-memory backend backed by an atomic CAS loop:

```scala
val inMem: Journal.Backend[Sync] < Sync =
    Journal.Backend.inMemory
```

> **Note:** The in-memory backend is for tests and local programs; each call creates an isolated store and separate calls do not share state.

`Journal.Backend.file` opens a durable, segment-based file journal rooted at a directory:

```scala doctest:expect=skipped
val dir: Path = Path("my-journal")
val backend: Journal.Backend[Sync] < (Sync & Scope & Abort[JournalStorageError]) =
    Journal.Backend.file(dir, payloadCodec = EventPayloadCodec.bytes)
```

`Journal.Backend.fileAsync` opens the same on-disk format under `Backend[Async]`. `Journal.Reader.file` and `Journal.Reader.fileAsync` open a read-only view that skips the writer lock (single-writer, multiple-reader).

`Journal.Reader.file` and `Reader.fileAsync` mirror backend overloads: pass `FileJournal.Config` and `payloadCodec` when opening a read-only root (defaults match `Journal.Backend.file`).

This constructor is available on JVM, Native, and Node.js (including Wasm-under-Node). On a browser runtime (no `node:fs`) it fails immediately with a typed `JournalStorageError` rather than at first I/O; no browser persistence backend exists. The `dir` parameter is a `Path` from `kyo-system`. `Scope` finalization releases the root lock and closes all open segment channels. A second open of a held root directory fails immediately with `JournalStorageError`.

On JVM and Native the root lock is an advisory file lock (`FileChannel.tryLock`); the OS drops it on process death, so no cleanup is needed after a crash. On Node.js the lock is an `O_EXCL` lockfile carrying the holder's pid and hostname. On the next open after a crash, the dead holder's pid is probed with `process.kill(pid, 0)`; an `ESRCH` result (no such process) triggers an atomic reclaim, and the open succeeds. An unparseable lock or a lock from another host is never reclaimed and fails closed. The crash-recovery guarantee (a dead holder's lock is always reclaimed on the next open) is the same on all platforms.

Pass `FileJournal.Config` to override three knobs:

| Field | Default | Notes |
|---|---|---|
| `fsync` | `Fsync.Always` | Flush each acknowledged append to stable storage before returning. Set to `Fsync.Disabled` only in tests; the crash-survival guarantee does not hold when `Fsync.Disabled` is set. |
| `segmentSize` | `64L.mib` (64 MiB) | Soft rotation threshold. The threshold is checked before an append, not after, so the active segment can grow past it: a record larger than the threshold is written whole into the current active segment rather than a dedicated one. |
| `format` | `SegmentFormat.Binary` | On-disk segment encoding; fixed at root creation via the `FORMAT` marker file. |

Every acknowledged append (with `fsync = Fsync.Always`) survives a crash. A torn tail at the end of the active segment (from a prior crash) is silently truncated at recovery; prior committed events are never lost. Recovery runs lazily on the first touch of a stream, and a `read` or `streamInfo` can be that first touch, so a nominally read-only call can perform the one-time torn-tail truncation on disk. Non-tail corruption and unknown segment versions are fatal (`JournalCorruptedError`). Metadata values round-trip exactly through the file backend: all ten `Structure.Value` constructors encode without loss.

Every custom backend must pass `JournalBackendTest`, an abstract test class covering offset assignment, expected-offset semantics, batch atomicity, bounded reads, stream isolation, and concurrent conflict detection:

```scala doctest:expect=skipped
class MyBackendTest extends JournalBackendTest(myBackend)
```

## Retry patterns

Because `Abort[JournalAppendFailure]` remains on the residual after `Journal.run`, `Retry[JournalAppendFailure]` composes directly around `Journal.append` calls. `Retry` adds `Async` to the row and retries on any `JournalAppendFailure` using the default exponential backoff schedule:

```scala
val withRetry: AppendResult < (Async & Sync & Abort[JournalAppendFailure]) =
    for
        backend <- Journal.Backend.inMemory
        envelope = EventEnvelope(evId1, evType1, Span.empty, EventMetadata.empty)
        result <- Journal.run(backend):
            Retry[JournalAppendFailure](Journal.append(sid, ExpectedOffset.Any, Chunk(envelope)))
    yield result
```

After `Journal.run`, the residual carries both `Abort[JournalAppendFailure]` (for failures that exhausted retries) and `Async` (from the delay between attempts); both are resolvable at the call site. For a custom schedule, pass it as the first argument: `Retry[JournalAppendFailure](schedule)(Journal.append(...))`. `Retry` is suited to transient backend failures such as `JournalStorageError`; a stale `Exact(offset)` guard will conflict again on every attempt because the stream state does not change between retries, so that case requires re-computing the guard from updated stream state as shown in Conflict recovery.

## Demos

Runnable demos live under `shared/src/test/scala/demo/`. Run with `sbt 'kyo-eventlogJVM/Test/runMain demo.<Name>'`.

- [`FleetLedgerDemo.scala`](shared/src/test/scala/demo/FleetLedgerDemo.scala): fleet ledger with file journal, EventLog, and reopen replay

# kyo-eventlog

`kyo-eventlog` is an append-only event streaming module: a way to record immutable domain events into named streams, read them back in offset order, and use an expected-offset check to guard concurrent writers without locks.

A `Journal` program is a composed value. Operations (`append`, `read`, `streamInfo`) suspend as reified effects, and `Journal.run(backend)(program)` installs a storage backend, discharges the `Journal` capability, and leaves each operation's typed failure on the `Abort` row. Schemas, codecs, and domain event types live at the layer above; the payload is raw bytes throughout.

The module targets JVM, JavaScript, Scala Native, and WebAssembly. It depends on `kyo-core`, `kyo-schema`, and `kyo-system`; callers using `Journal.Backend.file` must include `kyo-system` in their own build to construct a `Path` value. Metadata values are `Event.Metadata.Value` instances, an opaque wrapper around `Structure.Value` with a constructor-exact codec.

For guided, worked walkthroughs, see the three tutorials: [Basic EventLog](docs/tutorials/basic-eventlog.md), [Raw Journal](docs/tutorials/raw-journal.md), and [Custom storage](docs/tutorials/custom-storage.md).

<!-- doctest:setup
```scala
import kyo.*

enum QuestEvent derives Schema, CanEqual:
    case QuestStarted(quest: String, title: String)
    case MembersJoined(quest: String, at: Long, location: String, members: Chunk[String])
end QuestEvent

val questId: Event.StreamId = Event.StreamId("destroy-one-ring").getOrThrow
val startedId: Event.Id     = Event.Id("quest-started-1").getOrThrow
val joinedId: Event.Id      = Event.Id("members-joined-1").getOrThrow
val startedType: Event.Type = Event.Type("QuestStarted").getOrThrow
val joinedType: Event.Type  = Event.Type("MembersJoined").getOrThrow
```
-->

```scala
for
    backend <- Journal.Backend.inMemory
    pending = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
    events <- Journal.run(backend):
        for
            _ <- Journal.append(questId, ExpectedOffset.NoStream, Chunk(pending))
            r <- Journal.read(questId, Event.StreamOffset.first, 10)
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
| `read` | `Chunk[Event.Recorded] < (Journal & Abort[JournalReadFailure])` | `Chunk[Event.Recorded] < (Sync & Abort[JournalReadFailure])` |
| `streamInfo` | `StreamInfo < (Journal & Abort[JournalStreamInfoFailure])` | `StreamInfo < (Sync & Abort[JournalStreamInfoFailure])` |

For an `Async` backend, `Sync` in the residual column becomes `Async`; the per-op `Abort` traits stay on the row regardless of the backend.

`Journal.run` does not add an umbrella `Abort[JournalError]` to the row. To handle all journal failures at once, wrap the program in `Abort.run[JournalError]`. Every per-op trait extends the sealed `JournalError` base, so the widening is sound:

```scala
val safe: Result[JournalError, Chunk[Event.Recorded]] < Sync =
    for
        backend <- Journal.Backend.inMemory
        pending = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
        result <- Abort.run[JournalError]:
            Journal.run(backend):
                for
                    _ <- Journal.append(questId, ExpectedOffset.NoStream, Chunk(pending))
                    r <- Journal.read(questId, Event.StreamOffset.first, 10)
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

> **Note:** `JournalInvalidIdentifierError` is not a subtype of any op trait. It is the `Result.Failure` of `Event.StreamId(...)`, `Event.Id(...)`, `Event.Type(...)`, `Event.StreamOffset(...)`, `Event.StreamVersion(...)`, and `Event.Metadata.Key(...)`, never on an `Abort` op row.

### Low-level escape hatch

`Journal.Unsafe` bypasses the ArrowEffect suspend and handler dispatch, invoking the backend directly under its effect `S`. Use it in library integrations or performance-sensitive paths where the ArrowEffect round-trip is measurable overhead.

```scala
def appendDirect(backend: Journal.Backend[Sync], event: Event.New)(using
    AllowUnsafe
)
    : AppendResult < (Sync & Abort[JournalAppendFailure]) =
    Journal.Unsafe.append(backend)(questId, ExpectedOffset.Any, Chunk(event))
```

The same per-op `Abort[<trait>]` failure surface applies as on the safe ops. The three unsafe duals are `Journal.Unsafe.append`, `Journal.Unsafe.read`, and `Journal.Unsafe.streamInfo`.

## Streams and events

A stream is an ordered, append-only sequence of events sharing one identity, typically one aggregate or one logical log. Each event is submitted as an `Event.New` and stored as an `Event.Recorded`. The payload is raw bytes; typed encoding and decoding belong to the layer above kyo-eventlog.

**Submitted (`Event.New`):** `id: Event.Id`, `eventType: Event.Type`, `payload: Span[Byte]`, `metadata: Event.Metadata`.

**Stored (`Event.Recorded`):** all four pending fields plus `streamId: Event.StreamId` and `offset: Event.StreamOffset`.

`Event.StreamId`, `Event.Id`, `Event.Type`, `Event.StreamOffset`, `Event.StreamVersion`, and `Event.Metadata.Key` are all opaque `String` or `Long` values. Each constructor validates the input and returns `Result[JournalInvalidIdentifierError, T]`:

```scala
val ok: Result[JournalInvalidIdentifierError, Event.StreamId]  = Event.StreamId("destroy-one-ring")
val bad: Result[JournalInvalidIdentifierError, Event.StreamId] = Event.StreamId("")
```

To unwrap into a plain value, call `.getOrThrow` on the result; because `JournalInvalidIdentifierError` extends `KyoException` which extends `Exception`, the required evidence holds. `Event.StreamOffset` is zero-based; `Event.StreamOffset.first` (`0L`) is the first event position in any stream. `Event.StreamVersion` is the one-based count view: `Event.StreamVersion.after(offset)` equals `offset.value + 1`.

> **Note:** `Span` equality via `==` is reference-based. To compare payload contents, use `Span#is`, not `==` on `Event.New` or `Event.Recorded`.

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
        pending = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
        result <- Journal.run(backend)(Journal.append(questId, ExpectedOffset.NoStream, Chunk(pending)))
    yield result
```

`AppendResult` carries `firstOffset`, `lastOffset`, and the post-append `streamInfo`. Use `lastOffset` as the `Exact` guard for the next append on the same stream:

```scala
val chained: AppendResult < (Sync & Abort[JournalAppendFailure]) =
    for
        backend <- Journal.Backend.inMemory
        first  = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
        second = Event.New(joinedId, joinedType, Span.empty, Event.Metadata.empty)
        result <- Journal.run(backend):
            for
                r1 <- Journal.append(questId, ExpectedOffset.NoStream, Chunk(first))
                r2 <- Journal.append(questId, ExpectedOffset.Exact(r1.lastOffset), Chunk(second))
            yield r2
    yield result
```

### Conflict recovery

When `Journal.append` fails with `JournalConflictError`, `actual` holds the `StreamInfo` observed at conflict time. Catch the failure row with `Abort.run` and inspect the result:

```scala
val attempt: Result[JournalAppendFailure, AppendResult] < Sync =
    for
        backend <- Journal.Backend.inMemory
        pending = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
        result <- Abort.run[JournalAppendFailure]:
            Journal.run(backend)(Journal.append(questId, ExpectedOffset.NoStream, Chunk(pending)))
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

`Journal.read` returns a bounded slice of a stream's events in ascending offset order. `Journal.read(streamId, from, maxCount)` returns `Chunk[Event.Recorded] < (Journal & Abort[JournalReadFailure])`.

```scala
val history: Chunk[Event.Recorded] < (Sync & Abort[JournalReadFailure]) =
    for
        backend <- Journal.Backend.inMemory
        result  <- Journal.run(backend)(Journal.read(questId, Event.StreamOffset.first, 100))
    yield result
```

A missing stream, a `from` at or past the event count, and `maxCount <= 0` all return an empty `Chunk` on the in-memory backend; they are not failures. Paginate by advancing `from` by the length of the previous batch.

### Stream inspection

`Journal.streamInfo` reports whether a stream exists and, if so, its current last offset and event count. `Journal.streamInfo(streamId)` returns `StreamInfo < (Journal & Abort[JournalStreamInfoFailure])`.

`StreamInfo` is either `Absent` or `Existing(version, lastOffset)`. The `exists` predicate distinguishes the two. For a contiguous stream, `version.value == lastOffset.value + 1` always holds.

```scala
val state: StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
    for
        backend <- Journal.Backend.inMemory
        result  <- Journal.run(backend)(Journal.streamInfo(questId))
    yield result
```

## Typed events with EventLog

`EventLog[A]` wraps raw `Journal` operations with schema-driven encoding and decoding for domain type `A`. The ergonomic constructor is `EventLog.setup`: a fluent builder that stages the codec choices, registers one `define[E]` per member of `A`, and bakes the per-member routing into the log. `build` proves at compile time that every member of `A` is routed and returns the log; it captures no backend. `import log.given` brings the baked `Event.Definition`s into scope, so a caller writes `log.append(event)` with no visible codec or membership witness. Call its methods inside `Journal.run(backend)(...)` exactly as you would `Journal.append`, `Journal.read`, and `Journal.streamInfo`:

```scala
val appended =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        log <- EventLog.setup[QuestEvent](journalId)
            .codecs()
            .define[QuestEvent.QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            .define[QuestEvent.MembersJoined](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            .build
        backend <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            import log.given
            log.append(QuestEvent.QuestStarted("destroy-one-ring", "Destroy the One Ring"): QuestEvent.QuestStarted)
    yield result
```

For finer control the lower-level path stays available: build the codecs with `EventLog.Codecs.schema[A]`, construct the log with `EventLog.init(codecs, journalId)`, and put each `given Event.Definition[A, E]` in scope by hand instead of `import log.given`.

`read` returns `Chunk[Event.Record[A]]` with the decoded payload in `.payload` and a logical `ref`. Decode failures surface as `JournalCorruptedError` on the read row.

```scala
val typedHistory =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        codecs    <- EventLog.Codecs.schema[QuestEvent]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        records <- Journal.run(backend):
            given Event.Definition[QuestEvent, QuestEvent.QuestStarted] =
                Event.Definition.schema[QuestEvent, QuestEvent.QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            for
                r <- log.append(QuestEvent.QuestStarted("destroy-one-ring", "Destroy the One Ring"): QuestEvent.QuestStarted)
                h <- log.read(r.streamId, Event.StreamOffset.first, 10)
            yield h
            end for
    yield records
```

`EventLog.Codecs` is the single value-and-metadata codec authority. `EventLog.Codecs.schema[A]` derives the value codec (Ion Binary for `.seg` segments, JSON for JSONL segments) and the metadata codec (`EventLog.MetadataCodec`, Ion Binary by default) from `Schema[A]`. There is no separate payload-codec or metadata-codec selector to pass to a backend: the codecs travel inside the `FileJournal.Configuration` (see Backends below), so a codec clash between a log and a backend is a compile error, not a runtime discovery.

## Write-side deciders

A decider reads typed history, folds it into local state, and appends the next domain event when the state says to act. It is an ordinary `Journal.run` program using `EventLog[A]`: no separate decider API type is required.

The write loop follows three steps: read the stream, decide the next event (a pure function over `Chunk[Event.Record[A]]`), append with an `ExpectedOffset` guard. When a concurrent writer causes `JournalConflictError`, inspect `conflict.actual`, re-read if needed, and retry with the corrected guard (see Conflict recovery above).

```scala
def memberCount(events: Chunk[Event.Record[QuestEvent]]): Int =
    events.foldLeft(0) { (count, e) =>
        e.payload match
            case QuestEvent.MembersJoined(_, _, _, members) => count + members.size
            case _                                          => count
    }

def decideArrival(events: Chunk[Event.Record[QuestEvent]]): Maybe[QuestEvent] =
    events.lastOption match
        case Some(last) =>
            last.payload match
                case QuestEvent.MembersJoined(quest, at, _, _) =>
                    Present(QuestEvent.MembersJoined(quest, at + 1, "Rivendell", Chunk.empty))
                case _ => Absent
        case None => Absent
```

Projection (replaying events into a read model without writing) is the companion pattern: fold `Chunk[Event.Record[A]]` with a pure function, as in the kyo-examples car rental demo.

## Metadata

`Event.Metadata` holds infrastructure data alongside the payload: correlation identifiers, tracing keys, tags. Values are `Event.Metadata.Value` instances. `Event.Metadata.Value` is an opaque wrapper around `Structure.Value` with a constructor-exact codec that round-trips all ten `Structure.Value` constructors without loss through the binary metadata codec (Ion Binary by default). Construct with `Event.Metadata.Value(sv: Structure.Value)` and access the underlying value with `.value`. Domain fields belong in the encoded payload bytes; metadata is for infrastructure concerns that consumers need without decoding.

```scala
val key: Result[JournalInvalidIdentifierError, Event.Metadata.Key] = Event.Metadata.Key("trace.correlation_id")
val value: Event.Metadata.Value                                    = Event.Metadata.Value(Structure.Value.Str("req-42"))
val meta: Event.Metadata                                           = Event.Metadata.empty
```

`Event.Metadata.Key` is a validated dotted-path string. Empty keys, leading or trailing dots, and consecutive dots (`foo..bar`) are all rejected. `key.segments` splits on `.` and returns a `Chunk[String]`.

## Backends

`Journal.Backend[S]` is the storage contract. It extends `Journal.Reader[S]`, so it has exactly three methods: `read` and `streamInfo` from the reader, and `append` added by the backend. Each returns its op's failure trait under the backend's own effect `S`; pass the instance to `Journal.run`. Backend methods capture a `Frame` at construction, not per call; custom backends must follow the same pattern so failures carry source-location information.

`Journal.Backend.inMemory` creates an ephemeral in-memory backend backed by an atomic CAS loop:

```scala
val inMem: Journal.Backend[Sync] < Sync =
    Journal.Backend.inMemory
```

> **Note:** The in-memory backend is for tests and local programs; each call creates an isolated store and separate calls do not share state.

`Journal.Backend.file` opens a durable, segment-based file journal rooted at a directory. It takes a `FileJournal.Configuration`, built by `FileJournal.Binary.configuration` (CRC-verified `.seg` segments, the default) or `FileJournal.Jsonl.configuration` (human-readable `.jsonl` segments). Both carry the log's `EventLog.Codecs` and optional `FileJournal.Options`:

```scala
val opened =
    Scope.run:
        Path.run:
            for
                journalId <- JournalId("quest-party")
                codecs    <- EventLog.Codecs.schema[QuestEvent]()
                config    <- FileJournal.Binary.configuration(journalId, codecs)
                dir       <- Path.tempDir("quest-party-")
                backend   <- Journal.Backend.file(dir, config)
                info      <- Journal.run(backend)(Journal.streamInfo(questId))
            yield info
```

> **Note:** On disk, the Binary profile writes `.seg` segments and the JSONL profile writes `.jsonl` segments under the journal directory, both opened through `Journal.Backend.file`. A logical `JournalEntryRef` carries only ids (`journal:<journalId>/<streamId>/<offset>`), never a physical path, segment file, or byte offset.

`Journal.Backend.fileAsync` opens the same on-disk format under `Backend[Async]`. `Journal.Reader.file` opens a read-only view over the committed frontier that skips the writer lock (single-writer, multiple-reader): it never mutates or truncates the tail, so write-mode owns all repair.

This constructor is available on JVM, Native, and Node.js (including Wasm-under-Node). On a browser runtime (no `node:fs`) it fails immediately with a typed `JournalStorageError` rather than at first I/O; no browser persistence backend exists. The `dir` parameter is a `Path` from `kyo-system`. `Scope` finalization releases the root lock and closes all open segment channels. A second open of a held root directory fails immediately with `JournalStorageError`.

On JVM and Native the root lock is an advisory file lock (`FileChannel.tryLock`); the OS drops it on process death, so no cleanup is needed after a crash. On Node.js the lock is an `O_EXCL` lockfile carrying the holder's pid and hostname. On the next open after a crash, the dead holder's pid is probed with `process.kill(pid, 0)`; an `ESRCH` result (no such process) triggers an atomic reclaim, and the open succeeds. An unparseable lock or a lock from another host is never reclaimed and fails closed. The crash-recovery guarantee (a dead holder's lock is always reclaimed on the next open) is the same on all platforms.

Pass `FileJournal.Options` to override two knobs:

| Field | Default | Notes |
|---|---|---|
| `fsync` | `Fsync.Always` | Flush each acknowledged append to stable storage before returning. Set to `Fsync.Disabled` only in tests; the crash-survival guarantee does not hold when `Fsync.Disabled` is set. |
| `segmentSize` | `64L.mib` (64 MiB) | Soft rotation threshold. The threshold is checked before an append, not after, so the active segment can grow past it: a record larger than the threshold is written whole into the current active segment rather than a dedicated one. |

The on-disk profile (Binary or JSONL) is fixed by the configuration factory at journal root creation. Every acknowledged append (with `fsync = Fsync.Always`) survives a crash. A torn tail at the end of the active segment (from a prior crash) is truncated at recovery under a write-mode open (`Journal.Backend.file` / `fileAsync`); prior committed events are never lost. A `Journal.Reader.file` open never mutates or truncates the tail: write-mode owns all repair. Non-tail corruption and unknown segment versions are fatal (`JournalCorruptedError`). Metadata values round-trip exactly through the file backend: all ten `Structure.Value` constructors encode without loss.

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
        pending = Event.New(startedId, startedType, Span.empty, Event.Metadata.empty)
        result <- Journal.run(backend):
            Retry[JournalAppendFailure](Journal.append(questId, ExpectedOffset.Any, Chunk(pending)))
    yield result
```

After `Journal.run`, the residual carries both `Abort[JournalAppendFailure]` (for failures that exhausted retries) and `Async` (from the delay between attempts); both are resolvable at the call site. For a custom schedule, pass it as the first argument: `Retry[JournalAppendFailure](schedule)(Journal.append(...))`. `Retry` is suited to transient backend failures such as `JournalStorageError`; a stale `Exact(offset)` guard will conflict again on every attempt because the stream state does not change between retries, so that case requires re-computing the guard from updated stream state as shown in Conflict recovery.

## Tutorials

Three worked tutorials build on this reference:

- [Basic EventLog](docs/tutorials/basic-eventlog.md): a typed `EventLog` for a quest party, covering union/enum/sealed-trait events, append, prepare, batch, guard, read, and the Binary and JSONL file profiles.
- [Raw Journal](docs/tutorials/raw-journal.md): the envelope layer under `EventLog`, expected-offset guards, structural metadata, durability, recovery, committed-frontier readers, and logical references.
- [Custom storage](docs/tutorials/custom-storage.md): the two built-in file profiles and implementing the `Journal.Backend` SPI directly.

## Demos

Runnable demos live under `shared/src/test/scala/demo/`. Run with `sbt 'kyo-eventlogJVM/Test/runMain demo.<Name>'`.

- [`FleetLedgerDemo.scala`](shared/src/test/scala/demo/FleetLedgerDemo.scala): fleet ledger with file journal, EventLog, and reopen replay

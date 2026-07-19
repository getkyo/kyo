# Raw Journal: envelopes, guards, metadata, and logical references

`EventLog[A]` is the typed layer. Under it sits the raw `Journal`: an append-only store of opaque
byte payloads addressed by stream and offset. Working at this layer means assembling
`Event.New` envelopes yourself, choosing the expected-offset guard on each append, and reading
back `Event.Recorded` records. Use it when you need control the typed layer abstracts away, or when
you are building infrastructure on top of the journal.

This tutorial stays with the quest-party domain from the [Basic EventLog](basic-eventlog.md)
tutorial, dropping to the envelope level.

<!-- doctest:setup
```scala
import kyo.*
```
-->

## Submit an envelope

A submitted event is an `Event.New`: a producer-assigned `Event.Id`, an `Event.Type` routing
label, the raw `Span[Byte]` payload, and structural `Event.Metadata`. The journal treats the
payload as opaque bytes; encoding is your concern at this layer. `Journal.append` takes the target
stream, an `ExpectedOffset` guard, and a nonempty `Chunk` of pending events.

```scala
val submit =
    for
        streamId <- Abort.get(Event.StreamId("destroy-one-ring"))
        eventId  <- Abort.get(Event.Id("quest-started-1"))
        etype    <- Abort.get(Event.Type("QuestStarted"))
        backend  <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            val pending = Event.New(eventId, etype, Span.empty, Event.Metadata.empty)
            Journal.append(streamId, ExpectedOffset.NoStream, Chunk(pending))
    yield result
```

A successful append returns an `AppendResult` with the assigned `firstOffset`, `lastOffset`, and the
post-append `streamInfo`.

## Guard each append with an expected offset

`ExpectedOffset` makes the offset check atomic with the write, so two writers cannot both claim the
same tail.

| Value | Condition required | Fails with |
|---|---|---|
| `ExpectedOffset.Any` | none | never |
| `ExpectedOffset.NoStream` | stream must be absent | `JournalConflictError` if any events exist |
| `ExpectedOffset.Exact(offset)` | live last offset must equal `offset` | `JournalConflictError` on a mismatch |

Chain appends by feeding each result's `lastOffset` into the next guard as `Exact`.

```scala
val chained =
    for
        streamId <- Abort.get(Event.StreamId("destroy-one-ring"))
        first    <- Abort.get(Event.Id("e-1"))
        second   <- Abort.get(Event.Id("e-2"))
        etype    <- Abort.get(Event.Type("MembersJoined"))
        backend  <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            for
                r1 <- Journal.append(
                    streamId,
                    ExpectedOffset.NoStream,
                    Chunk(Event.New(first, etype, Span.empty, Event.Metadata.empty))
                )
                r2 <- Journal.append(
                    streamId,
                    ExpectedOffset.Exact(r1.lastOffset),
                    Chunk(Event.New(second, etype, Span.empty, Event.Metadata.empty))
                )
            yield r2
    yield result
```

A failed guard leaves the stream unchanged and carries the observed `StreamInfo` on
`JournalConflictError.actual`, which you can inspect to compute the corrected guard and retry.

## Attach structural metadata

`Event.Metadata` is a map from validated dotted-path `Event.Metadata.Key` values to
`Event.Metadata.Value` wrappers around `Structure.Value` trees. It holds infrastructure data
(correlation ids, tracing keys, tags) that consumers may need without decoding the payload. All ten
`Structure.Value` constructors round-trip without loss through the binary metadata codec.

```scala
val tagged =
    for
        key      <- Abort.get(Event.Metadata.Key("trace.correlation_id"))
        streamId <- Abort.get(Event.StreamId("destroy-one-ring"))
        eventId  <- Abort.get(Event.Id("quest-started-1"))
        etype    <- Abort.get(Event.Type("QuestStarted"))
        backend  <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            val metadata = Event.Metadata(Map(key -> Event.Metadata.Value(Structure.Value.Str("req-42"))))
            Journal.append(streamId, ExpectedOffset.Any, Chunk(Event.New(eventId, etype, Span.empty, metadata)))
    yield result
```

## Choose a durability policy

File-backed journals acknowledge appends under a durability policy set on `FileJournal.Options`.
`Fsync.Always` (the default) flushes each acknowledged append to stable storage before returning, so
every acknowledged append survives a crash; `Fsync.Disabled` skips the flush and is for tests only.
`segmentSize` is the soft rotation threshold.

```scala
val durableConfig =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.bytes()
        config = FileJournal.Binary.configuration(
            journalId,
            codecs,
            FileJournal.Options(fsync = FileJournal.Fsync.Always)
        )
    yield config
```

## Recovery is automatic and lazy

There is no recovery call to make. When you reopen a journal directory, recovery runs lazily on the
first touch of a stream, and a `read`, `append`, or `streamInfo` can be that first touch. A torn tail
at the end of the active segment (from a prior crash) is silently truncated then; prior committed
events are never lost. Non-tail corruption and unknown segment versions are fatal
(`JournalCorruptedError`). Reopening and reading is all recovery needs.

```scala
val reopened =
    Scope.run:
        Path.run:
            for
                journalId <- JournalId("quest-party")
                streamId  <- Abort.get(Event.StreamId("destroy-one-ring"))
                codecs    <- EventLog.Codecs.bytes()
                config = FileJournal.Binary.configuration(journalId, codecs)
                dir     <- Path.tempDir("quest-party-")
                backend <- Journal.Backend.file(dir, config)
                // Reopening and reading is a first touch: torn-tail recovery, if any, runs
                // transparently here before the read returns.
                events <- Journal.run(backend)(Journal.read(streamId, Event.StreamOffset.first, 100))
            yield events
```

## Read the committed frontier without the writer lock

`Journal.Reader.file` opens a read-only view over a directory's committed frontier. It skips the
writer lock (single-writer, multiple-reader) and never mutates or truncates the tail: write-mode owns
all repair. A reader sees only committed data.

```scala
val committed =
    Scope.run:
        Path.run:
            for
                journalId <- JournalId("quest-party")
                streamId  <- Abort.get(Event.StreamId("destroy-one-ring"))
                codecs    <- EventLog.Codecs.bytes()
                config = FileJournal.Binary.configuration(journalId, codecs)
                dir    <- Path.tempDir("quest-party-")
                reader <- Journal.Reader.file(dir, config)
                events <- reader.read(streamId, Event.StreamOffset.first, 100)
            yield events
```

## Reference an entry logically

A `JournalEntryRef` names an entry by logical identity: `(journalId, streamId, offset)`. Its `uri`
renders `journal:<journalId>/<streamId>/<offset>`, a logical reference carrying ids only, never a
physical path, segment file, or byte offset. `JournalEntryRef.parse` reads that logical form back and
rejects any physical URI.

```scala
val roundTrip =
    for
        ref <- JournalEntryRef.parse("journal:quest-party/destroy-one-ring/0")
    yield ref.uri
```

The rendered reference stays valid across compaction, segment rotation, and moving the journal
directory, because it names no physical storage location.

## Next steps

- [Basic EventLog](basic-eventlog.md): the typed layer that assembles these envelopes for you.
- [Custom storage](custom-storage.md): the two file profiles and implementing a `Journal.Backend`
  directly.

# Custom storage: file profiles and custom backends

Storage in kyo-eventlog has two extension tiers. The first is the pair of built-in file profiles,
Binary and JSONL, which share one segmented-append engine and differ only in on-disk encoding. The
second is a direct implementation of the `Journal.Backend` SPI, for a genuinely custom store (a
database, a cloud log, a test double) that has nothing to do with the file engine. This tutorial
covers both, staying with the quest-party domain.

<!-- doctest:setup
```scala
import kyo.*
```
-->

## Tier one: the built-in file profiles

A file backend is configured through a `FileJournal.Configuration[A]`, built by one of the two
profile factories. Both take a `JournalId`, an `EventLog.Codecs[A]`, and optional
`FileJournal.Options`.

`FileJournal.Binary.configuration` writes CRC-verified `.seg` binary segments; it is the default and
the right choice for production durability. `FileJournal.Jsonl.configuration` writes human-readable
`.jsonl` segments, useful when you want to inspect the log with ordinary text tools.

```scala
val binary =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.schema[String]()
        config    <- FileJournal.Binary.configuration(journalId, codecs)
    yield config
```

```scala
val jsonl =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.schema[String]()
        config    <- FileJournal.Jsonl.configuration(journalId, codecs, FileJournal.Options(segmentSize = 16L.mib))
    yield config
```

Both configurations open the same way through `Journal.Backend.file`, and both preserve the shared
engine's group-commit, atomic-move, and kill-at-every-step recovery guarantees. The value type `A` is
the only load-bearing type parameter; the profile choice is fixed at the configuration.

There is no caller-assembled component tier: you do not stitch a backend together from segment
formats, families, and component witnesses. The two profiles are closed built-ins over the shared
engine, and anything outside them is a direct backend implementation, covered next.

## Tier two: implement the `Journal.Backend` SPI

`Journal.Backend[S]` is the storage contract behind the `Journal` capability. It extends
`Journal.Reader[S]`, so it has exactly three methods: `read` and `streamInfo` from the reader, and
`append` added by the backend. Each method runs under the backend's own effect `S` and names its
precise failure set on the row. The methods take no `(using Frame)`: a backend captures its `Frame`
at construction.

Here is a backend that adds append observability by wrapping an in-memory store. It shows the exact
three-method shape a fully custom backend implements; a real store would replace the delegation with
its own reads and writes.

```scala
val observable =
    for
        appends <- AtomicInt.init(0)
        inner   <- Journal.Backend.inMemory
    yield new Journal.Backend[Sync]:
        def append(streamId: Event.StreamId, expected: ExpectedOffset, events: Chunk[Event.New])
            : AppendResult < (Sync & Abort[JournalAppendFailure]) =
            appends.incrementAndGet.andThen(inner.append(streamId, expected, events))

        def read(streamId: Event.StreamId, from: Event.StreamOffset, maxCount: Int)
            : Chunk[Event.Recorded] < (Sync & Abort[JournalReadFailure]) =
            inner.read(streamId, from, maxCount)

        def streamInfo(streamId: Event.StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
            inner.streamInfo(streamId)
```

Pass the backend to `Journal.run`, exactly as you would the in-memory or file backend. The rest of
your program does not know or care which backend is installed.

```scala
val runCustom =
    for
        streamId <- Abort.get(Event.StreamId("destroy-one-ring"))
        eventId  <- Abort.get(Event.Id("quest-started-1"))
        etype    <- Abort.get(Event.Type("QuestStarted"))
        appends  <- AtomicInt.init(0)
        inner    <- Journal.Backend.inMemory
        backend =
            new Journal.Backend[Sync]:
                def append(streamId: Event.StreamId, expected: ExpectedOffset, events: Chunk[Event.New])
                    : AppendResult < (Sync & Abort[JournalAppendFailure]) =
                    appends.incrementAndGet.andThen(inner.append(streamId, expected, events))

                def read(streamId: Event.StreamId, from: Event.StreamOffset, maxCount: Int)
                    : Chunk[Event.Recorded] < (Sync & Abort[JournalReadFailure]) =
                    inner.read(streamId, from, maxCount)

                def streamInfo(streamId: Event.StreamId): StreamInfo < (Sync & Abort[JournalStreamInfoFailure]) =
                    inner.streamInfo(streamId)
        result <- Journal.run(backend):
            Journal.append(streamId, ExpectedOffset.NoStream, Chunk(Event.New(eventId, etype, Span.empty, Event.Metadata.empty)))
    yield result
```

## Prove the contract with `JournalBackendTest`

A custom backend is only correct if it satisfies the same behavioral contract every built-in backend
does: consecutive zero-based offset assignment, an expected-offset check atomic with the write,
all-or-nothing batches, bounded reads that never fail for missing streams, and stream isolation.
`JournalBackendTest` is the abstract suite that verifies all of it. Extend it with a factory for your
backend, and every backend passes the identical suite unchanged.

```scala doctest:expect=skipped
class QuestPartyBackendTest extends JournalBackendTest(Journal.Backend.inMemory)
```

## Next steps

- [Basic EventLog](basic-eventlog.md): the typed layer that runs over any of these backends.
- [Raw Journal](raw-journal.md): the envelope layer, durability policy, recovery, and readers.

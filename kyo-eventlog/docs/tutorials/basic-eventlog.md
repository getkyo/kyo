# Basic EventLog: a typed event log for a quest party

`EventLog[A]` is the typed, backend-free program facade over the raw `Journal`. It encodes a
domain event to bytes on the way in, decodes on the way out, and resolves each event's stream and
identity from per-member evidence, so a caller writes `log.append(event)` with no visible codec or
membership plumbing.

This tutorial builds a log for a quest party: a fellowship forms, members join at each stop, and
the party arrives at a location. The write model is `QuestParty`; the events describe what happened
to it. We model the events three ways (a union, a Scala 3 enum, and a sealed trait) so you can pick
the shape that fits your domain, then append, batch, guard, and read them.

<!-- doctest:setup
```scala
import kyo.*

// Union model: two distinctly-shaped events under one union type.
final case class QuestStarted(quest: String, title: String) derives Schema, CanEqual
final case class MembersJoined(quest: String, at: Long, location: String, members: Chunk[String]) derives Schema, CanEqual
type QuestEvent = QuestStarted | MembersJoined

// Scala 3 enum model.
enum PartyMove derives Schema, CanEqual:
    case MembersDeparted(quest: String, members: Chunk[String])
    case MembersEscaped(quest: String, members: Chunk[String])
end PartyMove

// Sealed-trait model.
sealed trait QuestPath derives Schema, CanEqual
object QuestPath:
    final case class ArrivedAtLocation(quest: String, at: Long, location: String) extends QuestPath derives Schema, CanEqual
    final case class QuestEnded(quest: String, at: Long)                          extends QuestPath derives Schema, CanEqual
end QuestPath
```
-->

## Construct a log

The ergonomic way to build an `EventLog[A]` is `EventLog.setup`: a fluent builder that stages the
codec choices, registers one `define[E]` per member of `A`, and bakes the per-member routing into
the produced log. `build` proves at compile time that every member of `A` is routed (a missing
`define` is a compile error), assembles the `EventLog.Codecs[A]` from `Schema[A]` (Ion Binary for
`.seg` segments, JSON for JSONL segments, Ion Binary for metadata), and returns the log. The
`JournalId` is a logical route-segment identity; it carries no physical path meaning.

```scala
val questLog =
    for
        journalId <- JournalId("quest-party")
        started   <- Event.StreamName("quest")
        log <- EventLog.setup[QuestEvent](journalId)
            .codecs()
            .define[QuestStarted](Event.StreamSelector.by(started)(e => Chunk(e.quest)))
            .define[MembersJoined](Event.StreamSelector.by(started)(e => Chunk(e.quest)))
            .build
    yield log
```

`EventLog.setup` captures no backend. Every operation is an ordinary `Journal` program run inside
`Journal.run(backend)(program)`. Bringing the baked routing into scope with `import log.given`
lets `log.append(event)` resolve each member's `Event.Definition` with no hand-written `given`.

For finer control, the lower-level `EventLog.init` constructor takes an `EventLog.Codecs[A]` and a
`JournalId` directly, and the caller supplies each `given Event.Definition[A, E]` by hand:

```scala
val questLogLowLevel =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.schema[QuestEvent]()
        log       <- EventLog.init(codecs, journalId)
    yield log
```

## Append a union event

Each concrete event resolves an `Event.Definition[A, E]` given: the member evidence that supplies
the event type, the stream selector, the id policy, and the metadata for `E`. A log built through
`EventLog.setup` already bakes that evidence in, so `import log.given` brings it into scope and
`log.append(event)` needs nothing else. Here the stream is derived from the quest id through
`Event.StreamSelector.by`, so every event about one quest routes to one stream.

```scala
val started =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        log <- EventLog.setup[QuestEvent](journalId)
            .codecs()
            .define[QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            .define[MembersJoined](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            .build
        backend <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            import log.given
            log.append(QuestStarted("destroy-one-ring", "Destroy the One Ring"))
    yield result
```

`log.append` returns an `AppendResult` carrying the resolved `streamId`, the `firstOffset` and
`lastOffset` assigned to the batch, and the post-append `streamInfo`. The lower-level
`EventLog.init` path stays available: build the log from `EventLog.Codecs.schema` and put each
`given Event.Definition[A, E]` in scope by hand instead of `import log.given`.

## Append an enum event, then a sealed-trait event

The same `log.append(event)` form works whichever way the event ADT is modeled. An enum case is
appended with its case type; a sealed-trait leaf is appended directly.

```scala
val moved =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.schema[PartyMove]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            given Event.Definition[PartyMove, PartyMove.MembersDeparted] =
                Event.Definition.schema[
                    PartyMove,
                    PartyMove.MembersDeparted
                ](Event.StreamSelector.constant(Event.StreamId("destroy-one-ring").getOrThrow))
            log.append(PartyMove.MembersDeparted("destroy-one-ring", Chunk("Boromir")): PartyMove.MembersDeparted)
    yield result
```

```scala
val arrived =
    for
        journalId <- JournalId("quest-party")
        codecs    <- EventLog.Codecs.schema[QuestPath]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            given Event.Definition[QuestPath, QuestPath.ArrivedAtLocation] =
                Event.Definition.schema[
                    QuestPath,
                    QuestPath.ArrivedAtLocation
                ](Event.StreamSelector.constant(Event.StreamId("destroy-one-ring").getOrThrow))
            log.append(QuestPath.ArrivedAtLocation("destroy-one-ring", 15, "Rivendell"))
    yield result
```

## Prepare and batch

`log.prepare` stages one event into a log-bound `Command` without appending, so several commands
can be batched into one atomic append with `log.appendAll(first, rest*)`. A prepared command is
path-dependent on the log that produced it: it cannot be appended through another log.

```scala
val fellowshipForms =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        codecs    <- EventLog.Codecs.schema[QuestEvent]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            given Event.Definition[QuestEvent, QuestStarted] =
                Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            given Event.Definition[QuestEvent, MembersJoined] =
                Event.Definition.schema[QuestEvent, MembersJoined](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            for
                start   <- log.prepare(QuestStarted("destroy-one-ring", "Destroy the One Ring"))
                hobbits <- log.prepare(MembersJoined("destroy-one-ring", 1, "Hobbiton", Chunk("Frodo", "Sam")))
                bree    <- log.prepare(MembersJoined("destroy-one-ring", 10, "Bree", Chunk("Aragorn")))
                results <- log.appendAll(start, hobbits, bree)
            yield results
            end for
    yield result
```

## Guard with an expected offset

`EventLog.AppendDirective.expected(offset)` guards an append with an atomic expected-offset check,
the optimistic-concurrency lock two writers use to avoid clobbering each other's tail.
`ExpectedOffset.NoStream` requires the stream to be absent; `ExpectedOffset.Exact(offset)` requires
the live last offset to equal `offset`. A mismatch fails with `JournalConflictError`, leaving the
stream unchanged.

```scala
val guarded =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        codecs    <- EventLog.Codecs.schema[QuestEvent]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        result <- Journal.run(backend):
            given Event.Definition[QuestEvent, QuestStarted] =
                Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            given Event.Definition[QuestEvent, MembersJoined] =
                Event.Definition.schema[QuestEvent, MembersJoined](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            for
                first <- log.append(
                    QuestStarted("destroy-one-ring", "Destroy the One Ring"),
                    EventLog.AppendDirective.expected(ExpectedOffset.NoStream)
                )
                next <- log.append(
                    MembersJoined("destroy-one-ring", 1, "Hobbiton", Chunk("Frodo", "Sam")),
                    EventLog.AppendDirective.expected(ExpectedOffset.Exact(first.lastOffset))
                )
            yield next
            end for
    yield result
```

## Read the stream back

`log.read(streamId, from, maxCount)` returns a bounded, ascending slice of `Event.Record[A]`
values, each with its decoded `payload`, its `eventId`, `eventType`, `metadata`, and a logical
`ref`. Read from the `streamId` an append resolved to.

```scala
val replay =
    for
        journalId <- JournalId("quest-party")
        name      <- Event.StreamName("quest")
        codecs    <- EventLog.Codecs.schema[QuestEvent]()
        log       <- EventLog.init(codecs, journalId)
        backend   <- Journal.Backend.inMemory
        records <- Journal.run(backend):
            given Event.Definition[QuestEvent, QuestStarted] =
                Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
            for
                appended <- log.append(QuestStarted("destroy-one-ring", "Destroy the One Ring"))
                history  <- log.read(appended.streamId, Event.StreamOffset.first, maxCount = 10)
            yield history
            end for
    yield records
```

## Persist to disk: Binary and JSONL

The in-memory backend is for tests and local programs. To persist across restarts, open a file
backend from a `FileJournal.Configuration`. The two built-in profiles share one segmented-append
engine and differ only in on-disk encoding: `FileJournal.Binary.configuration` writes CRC-verified
`.seg` binary segments (the default), `FileJournal.Jsonl.configuration` writes human-readable
`.jsonl` segments. Both open through `Journal.Backend.file`.

Pick a profile by choosing its configuration factory; the value type `A` is the only load-bearing
type parameter.

```scala
val profiles =
    for
        journalId    <- JournalId("quest-party")
        codecs       <- EventLog.Codecs.schema[QuestEvent]()
        binaryConfig <- FileJournal.Binary.configuration(journalId, codecs)
        jsonlConfig  <- FileJournal.Jsonl.configuration(journalId, codecs)
    yield (binaryConfig, jsonlConfig)
```

Open the backend inside a `Scope`, run the program, and let scope finalization release the root
lock and close the segments. Reopening the same directory replays the committed history from disk.

```scala
val persisted =
    Scope.run:
        Path.run:
            for
                journalId <- JournalId("quest-party")
                name      <- Event.StreamName("quest")
                codecs    <- EventLog.Codecs.schema[QuestEvent]()
                config    <- FileJournal.Binary.configuration(journalId, codecs)
                dir       <- Path.tempDir("quest-party-")
                log       <- EventLog.init(codecs, journalId)
                backend   <- Journal.Backend.file(dir, config)
                records <- Journal.run(backend):
                    given Event.Definition[QuestEvent, QuestStarted] =
                        Event.Definition.schema[QuestEvent, QuestStarted](Event.StreamSelector.by(name)(e => Chunk(e.quest)))
                    for
                        appended <- log.append(QuestStarted("destroy-one-ring", "Destroy the One Ring"))
                        history  <- log.read(appended.streamId, Event.StreamOffset.first, maxCount = 10)
                    yield history
                    end for
            yield records
```

For a fleet-service take on the same file-backed flow (append, close, reopen, replay), see
`demo.FleetLedgerDemo`. QuestParty is the first domain you meet here; the fleet ledger is the
secondary worked example.

## Next steps

- [Raw Journal](raw-journal.md): the untyped envelope layer under `EventLog`, expected-offset
  guards, structural metadata, durability, and logical references.
- [Custom storage](custom-storage.md): the two file profiles and implementing a `Journal.Backend`
  directly.

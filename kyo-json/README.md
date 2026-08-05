<!-- doctest:default scope=inherited -->

# kyo-json

JSONL (also called NDJSON) is one complete JSON value per line: an append-only record log rather than a document. Structured application logs and AI agent session transcripts are written this way because appending a line is cheap, and because a reader can start consuming records before the writer has finished producing them. A whole-file JSON array can do neither. It has no valid prefix, so a reader must wait for the closing bracket and hold the entire array in memory.

`kyo-json` is the streaming half of that story. Every entry point produces or consumes a `Stream`, so a multi-gigabyte log is processed record by record, and a file that is still being written can be read as records arrive.

The module is one object, `Jsonl`, and its methods split along two axes. The first axis is the source: `read` for a file that is complete, `pipe` for any `Stream[Byte, S]` you already hold, and `follow` for a file still being appended to. The second axis is what a bad record does: the strict methods emit every record decoded before the failure and then abort, while the `Results` variants emit one `Result` element per record and carry on, which is what a heterogeneous or half-written log needs.

Framing and parsing are pure and live in `Json.Lines` (kyo-schema-json). `Jsonl` is the effectful driver over that framer, so it contains no newline scanning, no line splitting, and no JSON parsing of its own. JVM, JavaScript, Scala Native, and Wasm compile the same shared source.

<!-- doctest:setup
```scala
import kyo.*

case class Source(service: String, host: String) derives Schema
case class LogEvent(timestampMs: Long, level: String, message: String, source: Source) derives Schema

val logFile: Path         = Path("/var/log/app.jsonl")
val errorFile: Path       = Path("/var/log/errors.jsonl")
val sampleEvent: LogEvent = LogEvent(1717000000000L, "ERROR", "upstream timeout", Source("api", "host-1"))
val lastOffset: Long      = 4096L
```
-->

```scala doctest:scope=nested
case class Source(service: String, host: String) derives Schema
case class LogEvent(timestampMs: Long, level: String, message: String, source: Source) derives Schema

val logFile = Path("/var/log/app.jsonl")

// Attach to a log that another process is still appending to, and take the next ten records.
Scope.run(Jsonl.follow[LogEvent](logFile, Path.Origin.End).take(10).run)
```

## Installation

```scala doctest:scope=nested expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-json" % "<latest version>"
```

This pulls in `kyo-schema-json` (the JSON codec and the pure framer) and `kyo-core` (the effect runtime, `Stream`, and `Path`) transitively.

## Reading a file

A JSONL file that nothing is still writing to is the ordinary case: a shipped log, a completed transcript, an export. `Jsonl.read` turns it into a stream of decoded values, so the memory a consumer needs is one chunk of records rather than the whole file.

```scala
val everyEvent: Stream[LogEvent, Scope & Sync & Abort[FileReadException | DecodeException]] =
    Jsonl.read[LogEvent](logFile)
```

The `Scope` in the effect row is the file handle. It is registered with the enclosing scope and closed when that scope ends, so a consumer never pairs the read with a close of its own:

```scala
val errorCount = Scope.run(everyEvent.filterPure(_.level == "ERROR").run.map(_.size))
```

Framing accepts what real files contain. Blank and whitespace-only lines are skipped, `\r\n` terminators are accepted, a leading byte order mark is stripped, and a final record with no trailing newline is still emitted, because the framer is finished at end of input.

`Jsonl.readResults` is the same read with the failure decision reversed, covered under [What a bad record does](#what-a-bad-record-does).

## Decoding any byte source

There is no `Jsonl.decodeStream`, because a `Stream[Byte, S]` already is the input. The module ships a `Pipe` and lets `.into` do the joining, so a socket, an HTTP response body, a `java.io.InputStream`, and an in-memory chunk are all the same call:

```scala
def eventsFrom(is: java.io.InputStream): Stream[LogEvent, Sync & Scope & Abort[DecodeException]] =
    Stream.fromInputStream(is).into(Jsonl.pipe[LogEvent]())
```

The same pipe over bytes already in memory:

```scala
val body =
    """{"timestampMs":1717000000000,"level":"INFO","message":"started","source":{"service":"api","host":"h1"}}
      |{"timestampMs":1717000000500,"level":"ERROR","message":"upstream timeout","source":{"service":"api","host":"h1"}}
      |""".stripMargin

val decoded: Stream[LogEvent, Abort[DecodeException]] =
    Stream.init(Chunk.from(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .into(Jsonl.pipe[LogEvent]())
```

`Jsonl.read` is exactly `path.readBytesStream.into(Jsonl.pipe[A]())`, so everything this section says about framing holds there too. `Jsonl.pipeResults` is the per-record `Result` pipe, covered under [What a bad record does](#what-a-bad-record-does).

What a consumer receives never depends on how the source chunked its bytes. The framer retains whatever it cannot yet complete and resumes on the next chunk, so a source feeding one byte at a time and a source feeding the whole file in one chunk produce the identical sequence of records, and a UTF-8 multibyte character straddling a chunk boundary is not a hazard because framing splits bytes before any text is decoded.

> **Caution:** `path.tailBytes(...).into(Jsonl.pipe[LogEvent]())` is not `Jsonl.follow[LogEvent](path)`, and the difference is a corrupt record rather than a slower one. When the followed file is truncated, `tailBytes` rewinds to byte 0 and replays, and that rewind carries no marker in the byte stream. A framer sitting downstream still holds the partial record it had before the truncation and splices it onto the first replayed record. `Jsonl.follow` carries the framer inside the follow loop, so the rewind restores a fresh framer and no record can span the truncation. That is the reason `follow` exists as its own entry point.

## Following a live file

A log or a transcript is interesting while it is being written, not only afterwards. `Jsonl.follow` reads the file in one continuous pass that switches from draining to polling once it reaches the end, rather than a read concatenated with a tail, so a record appended while the existing content is still being replayed is never missed:

```scala
val live: Stream[LogEvent, Scope & Async & Abort[FileReadException | DecodeException]] =
    Jsonl.follow[LogEvent](logFile)
```

`Jsonl.followResults` is the same follow with the failure decision reversed, covered under [What a bad record does](#what-a-bad-record-does).

### Where reading begins

`Path.Origin` names the three starting points, and the choice between them is the choice between replaying history and watching only what comes next:

```scala
val replayThenFollow = Jsonl.follow[LogEvent](logFile, Path.Origin.Start)
val newRecordsOnly   = Jsonl.follow[LogEvent](logFile, Path.Origin.End)
val resumeWhereLeft  = Jsonl.follow[LogEvent](logFile, Path.Origin.Offset(lastOffset))
```

`Path.Origin.Start` replays every record already in the file and then follows. It is the default for `Jsonl.follow`, because reading a live transcript wants the whole conversation rather than only the turns written after the reader attached.

> **Note:** `Path.tailBytes` takes the same `from` parameter with the opposite default, `Path.Origin.End`, because it is the byte-level view of a followed file and follow-only is what a byte-level tail means. It is `Path.tail`'s sibling over one polling loop rather than a layer beneath `Jsonl.follow`, so the two defaults are independent choices. A reader who knows `tail -f` will guess `Jsonl.follow`'s default wrong.

`Path.Origin.End` skips whatever the file already holds, which is what a monitor watching for new errors wants. `Path.Origin.Offset(bytes)` resumes at a recorded byte position, and the position to record is one the consumer kept itself.

> **Caution:** an `Offset` past the current end of the file replays the whole file from byte 0. The follower cannot distinguish "start beyond the end" from "the file shrank below my position", and its answer to the second is to rewind. Record an offset only from a stream that actually read the file.

### Bounding the stream

A well-formed log never completes the stream on its own. Until the enclosing scope ends there is always the possibility of another record, so the consumer decides when it has read enough:

```scala
val firstFiveErrors =
    Scope.run(
        Jsonl.follow[LogEvent](logFile, Path.Origin.End)
            .filterPure(_.level == "ERROR")
            .take(5)
            .run
    )

val untilShutdown =
    Scope.run(Jsonl.follow[LogEvent](logFile).takeWhilePure(_.message != "shutdown").run)
```

`take`, `takeWhilePure`, and an interrupt of the fiber running the stream are the three ways out. A bare `.run` on a follow stream over a well-formed log waits forever. Two things do end it: `follow` aborts on the first undecodable record or record-size breach, and `followResults`, which survives both of those, ends when the pending bytes outgrow `maxLineSize` with no newline among them (see [Limits](#limits)).

`pollDelay` is how long the follower sleeps between polls once it reaches end of file, `100.millis` by default. Raising it trades latency for fewer wakeups:

```scala
val relaxed = Jsonl.follow[LogEvent](logFile, pollDelay = 500.millis)
```

### Rotation, truncation, and deletion

Following tracks the open file and not the name, which is what `tail -f` does and `tail -F` does not. The file is opened once and every later decision is made from that open file, so three things follow, and they are the contract rather than an accident:

- **Rename or replace.** The stream stays on the original file. It emits whatever is still appended through that file's new name, and nothing written to the new file created under the old name. A consumer that needs to pick up a rotated-in replacement closes this stream and opens a new one against the path.
- **Deletion.** No further records, and no abort. An unlinked file that is still open keeps its content and can still be measured, so the stream waits, indefinitely, for bytes that can no longer arrive.
- **Truncation in place.** The file is the same file, so the follower rewinds to byte 0 and replays it, rebuilding the framer at the rewind. A record left half written when the truncation happened is dropped with the rest of the old content and is never spliced onto the first replayed record. That holds wherever the cut fell, so a log rotated in place replays as a clean read of its new content. This is a replay, not an error: a consumer will see records it has already seen.

The first two describe POSIX descriptor behavior. Windows keeps the directory entry alive until the last handle closes and refuses to rename or unlink an open file for some open modes, so the rename and delete outcomes are undefined there. Truncation in place is unaffected, and so is everything else in this README: the platform caveat is about what the operating system permits a third party to do to the name, not about which platforms this module runs on.

### Reading and following differ on the last record

Both surfaces frame the same bytes with the same framer, and they disagree about exactly one record: the last one, when it has no trailing newline.

`read` runs on a finite input, so the framer is finished at end of input and an unterminated trailing record is emitted. `follow` never reaches an end of input, so it holds those bytes as a partial record and emits them only once the newline arrives. Read a half-written file and you get its last, incomplete line as a record; follow the same file and you do not, until the writer terminates it.

## What a bad record does

One decision names half this module's API: what happens to the stream when a record does not decode. A log written by an older version of a service, a line truncated by a crash, a record whose shape this consumer does not know: each is a normal event in the life of a record log, and the two answers suit different consumers.

### Strict: emit the prefix, then abort

`read`, `pipe`, and `follow` abort with the first `DecodeException`:

```scala
val strictRun = Abort.run[DecodeException](Scope.run(Jsonl.read[LogEvent](logFile).run))
```

> **Note:** aborting does not discard what already decoded. Every record before the failing one is emitted first, and only then does the abort happen. That is what keeps the output before an abort a function of the data alone: a 1000-record input failing at record 500 delivers 499 records to a `foreach` whether the bytes arrived one at a time or in a single chunk. Chunking is an upstream detail and is not observable here.

### Results: one element per record, keep going

`readResults`, `pipeResults`, and `followResults` move the failure out of the effect row and into the element:

```scala
val perRecord: Stream[Result[DecodeException, LogEvent], Scope & Sync & Abort[FileReadException]] =
    Jsonl.readResults[LogEvent](logFile)

val report = Scope.run(
    perRecord.mapPure(
        _.foldError(
            event => event.message,
            {
                case Result.Failure(e: RecordDecodeException) =>
                    s"undecodable record ${e.recordIndex} at byte ${e.byteOffset}: ${e.record}"
                case error => s"framing stopped: $error"
            }
        )
    ).run
)
```

A failed element carries `RecordDecodeException`, which names the record rather than the file: `recordIndex` (counting only the records that were emitted, so blank lines consume no index), `byteOffset` into the input, the raw `record` text, and the underlying `DecodeException` as `cause`. That is what lets a consumer report "record 41 at byte 9302 is malformed" instead of "the file is malformed".

Both counters are relative to where this stream started framing, not to the file. `read` and `readResults` always start at byte 0, so their offsets are file offsets. A follower started at `Path.Origin.End` or `Path.Origin.Offset` begins counting at that start position, and a truncation rewind restarts both counters, since framing restarts from the file's first byte. Add the start position back before treating a `byteOffset` from a follower as a file offset.

Both directions are available on every source, so the choice is per call site rather than per module:

| Source | Strict | Per-record `Result` |
|---|---|---|
| A complete file | `Jsonl.read` | `Jsonl.readResults` |
| Any `Stream[Byte, S]` | `Jsonl.pipe` | `Jsonl.pipeResults` |
| A file still being written | `Jsonl.follow` | `Jsonl.followResults` |

> **Caution:** the `Results` variants survive an undecodable record, and they survive a record larger than `maxLineSize` (the framer's record-size ceiling, covered under [Limits](#limits)) only when that record's newline arrived. A terminated over-long record has a boundary to resume at, so it costs one failure element and framing carries on with the record after it. Pending bytes that outgrow the ceiling with no newline among them have no boundary to skip to: the stream emits every record framed before them, then that single failure element, and then it ends. Consumers that treat a `Results` stream as unbounded need to handle it ending.

## Writing and appending

Going the other way, `Jsonl.encode` turns a stream of values into a stream of JSONL bytes. It is the exact inverse of `pipe`:

```scala
val outbound: Stream[LogEvent, Any] = Stream.init(Chunk(sampleEvent))

val bytes: Stream[Byte, Any] = Jsonl.encode(outbound)

val roundTripped: Stream[LogEvent, Abort[DecodeException]] =
    Jsonl.encode(outbound).into(Jsonl.pipe[LogEvent]())
```

Encoding happens per chunk, so a chunk of values becomes a chunk of bytes and nothing accumulates across the stream. The newline terminates each record rather than each chunk, so the bytes produced are a function of the values alone and never of how the upstream source chunked them.

`Jsonl.write` and `Jsonl.append` send that same encoding to a file, holding one handle across the whole stream:

```scala
val replaceFile: Unit < (Sync & Abort[FileWriteException]) =
    Jsonl.write(errorFile, outbound)

val extendFile: Unit < (Sync & Abort[FileWriteException]) =
    Jsonl.append(logFile, outbound)
```

They differ in one thing: `write` empties the file first, `append` does not. Everything else is shared. Both create the file and, with `createFolders = true` (the default), its missing parent directories. Both do that before any chunk arrives, so writing an empty stream leaves an empty file rather than the previous content, and appending an empty stream still creates the file.

A failure part way through leaves the records already written on disk rather than removing the file. A JSONL file is a record log and a prefix of one is still a valid log, so a consumer reads what completed instead of losing all of it to the record that did not.

> **Caution:** `append` starts at the file's current end and writes no separator of its own. A file whose last record has no trailing newline therefore gets the first appended record spliced onto that last record, producing one corrupt line where there were two. Everything this module writes ends in a newline, so this only reaches a file produced elsewhere or truncated mid-record. Terminate such a file's last line before appending to it.

## Limits

Three parameters bound what a decode can cost, and two of them look interchangeable and are not. All three appear on `read`, `readResults`, `pipe`, `pipeResults`, `follow`, and `followResults`, with the same defaults everywhere.

`maxLineSize` is the framer's `ByteSize` record ceiling, `Json.Lines.DefaultMaxLineSize` (`16.mib`) by default. It is what keeps a byte stream containing no newline from growing the framer's pending buffer without bound:

```scala
val capped = Jsonl.read[LogEvent](logFile, maxLineSize = 64.kib)
```

The limit counts the record's own bytes, the ones a decoder sees, and never the line terminator: a `'\n'` and any `'\r'` immediately before it are excluded. A record at the ceiling therefore occupies one additional byte on the wire when it ends in `\n`, and two additional bytes when it ends in `\r\n`. A pending partial record is measured the same way, so a record is accepted or rejected identically whether its terminator arrived in the same chunk or a later one.

What a breach costs depends on whether the offending line was terminated. A terminated one has a known boundary, so framing skips it and resumes at the next record: the `Results` variants report one failure for it and carry on. Pending bytes that outgrow the limit with no newline among them have nothing to skip to, and that is the case the `Results` variants end on.

> **Caution:** a strict `maxLineSize` breach aborts with a bare `LimitExceededException`, not a `RecordDecodeException`. No record was framed, so there is no record to attribute it to. Both are `DecodeException`, so the effect row does not distinguish them, and a handler that matches only on `RecordDecodeException` will miss the framing breach entirely. Match on `DecodeException`, or on both.

`maxDepth` and `maxCollectionSize` bound one record's decoding: how deeply objects and arrays may nest, and how many entries a map, set, or array may hold. They default to `Json.DefaultMaxDepth` and `Json.DefaultMaxCollectionSize`:

```scala
val guarded = Jsonl.read[LogEvent](logFile, maxDepth = 8, maxCollectionSize = 1000)
```

These operate inside one document and say nothing about how long a record may be. A single line of ten million characters passes any `maxDepth`; `maxLineSize` is the parameter that rejects it. Breaching either one arrives as a `LimitExceededException` wrapped in a `RecordDecodeException.cause`, because the record was framed and only its decoding failed. Its `limit` field names which of the two was applied.

## Putting it together

A shipper that resumes where it stopped, watches a live log, and copies the errors into a second file uses one call from each direction:

```scala
val shipErrors =
    Jsonl.append(
        errorFile,
        Jsonl.follow[LogEvent](logFile, Path.Origin.Offset(lastOffset), maxLineSize = 64.kib)
            .filterPure(_.level == "ERROR")
            .take(100)
    )

val shipped = Scope.run(shipErrors)
```

The follow stream is the value passed to `append`, so nothing buffers between them: each chunk of records the follower frames is encoded and written before the next poll. The `take(100)` is what ends it, since the follower would otherwise run until interrupted. The write handle belongs to `append`, which holds it across the whole fold and closes it on every exit; the follow handle is the `Scope` in the effect row, and `Scope.run` closes that one.

## Whole inputs without effects

When the input is already a `String` or a `Span[Byte]` in memory and no streaming is wanted, drop to `Json.Lines`, the pure surface `Jsonl` drives:

```scala
val text: String = Json.Lines.encodeAll(List(sampleEvent))

val back: Result[DecodeException, Chunk[LogEvent]] = Json.Lines.decodeAll[LogEvent](text)
```

`Json.Lines` holds the whole-input pair `decodeAll` / `encodeAll` with their `Span[Byte]` counterparts, the per-record `decodeAllBytesResults`, `encodeLine` for a single record, and `Json.Lines.Framer` for a caller driving its own read loop. It is documented in the [JSONL section of the kyo-schema README](../kyo-schema/README.md#jsonl), and living in kyo-schema-json is what lets it be used with no Kyo effect runtime on the classpath.

The module's internal contracts, including why the framer is the follow loop's carried state and why every entry point routes through one private framing function, are in [CONTRIBUTING.md](CONTRIBUTING.md).

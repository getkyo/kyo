# kyo-schema-json

Define a Scala value with a `Schema`, then use the same structural description to encode or decode ordinary JSON, derive its JSON Schema, or exchange a sequence of values as JSONL. Whole values use `Json`, complete in-memory JSONL uses `Json.Lines`, and effectful byte streams and files use `Jsonl`, so callers can move from one document to bounded record-by-record processing without changing the domain model or serialization rules.

<!-- doctest:setup
```scala
import java.nio.charset.StandardCharsets
import kyo.*

case class Event(name: String, count: Int) derives Schema, CanEqual

def utf8(value: String): Span[Byte] =
    Span.from(value.getBytes(StandardCharsets.UTF_8))
```
-->

```scala
val events  = Seq(Event("opened", 1), Event("closed", 2))
val jsonl   = Json.Lines.encodeAll(events)
val decoded = Json.Lines.decodeAll[Event](jsonl)
assert(decoded.getOrThrow == Chunk.from(events))
```

## Installation

Add the single module dependency to `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-schema-json" % "<latest version>"
```

This artifact includes ordinary JSON, JSON Schema, pure JSONL, and effectful JSONL streaming. It pulls in `kyo-schema` and `kyo-system` transitively, so no additional dependency is needed for `Schema`, `Stream`, or `Path`.

## Encoding and decoding values

Start here when one Scala value corresponds to one complete JSON document. A single `Schema[A]` describes the domain shape used by both directions.

### Defining the domain contract

Derive `Schema` on a case class to make its field names and types available to every operation in this module:

```scala
val event                 = Event("opened", 1)
val schema: Schema[Event] = summon[Schema[Event]]
assert(schema != null)
```

The schema is the contract. Encoding and decoding do not require a second JSON-specific derivation.

### Encoding text and bytes

When the destination is text, use `Json.encode`. When the destination already accepts UTF-8 bytes, use `Json.encodeBytes` and avoid an intermediate `String`:

```scala
val event = Event("opened", 1)
val text  = Json.encode(event)
val bytes = Json.encodeBytes(event)

assert(text == "{\"name\":\"opened\",\"count\":1}")
assert(new String(bytes.toArray, StandardCharsets.UTF_8) == text)
```

> **Note:** `Json.encode` and `Json.encodeBytes` use the caller-scoped `Json` given passed as their trailing contextual argument, so codec behavior selected in the caller's scope is honored while explicit `Schema` call sites remain source-compatible.

### Decoding complete documents

When all input bytes represent one document, use `Json.decode` for text or `Json.decodeBytes` for bytes. Both return `Result[DecodeException, A]` and reject malformed input, a mismatched shape, and trailing content:

```scala
val textResult = Json.decode[Event]("{\"name\":\"opened\",\"count\":1}")
val byteResult = Json.decodeBytes[Event](utf8("{\"name\":\"closed\",\"count\":2}"))

assert(textResult.getOrThrow == Event("opened", 1))
assert(byteResult.getOrThrow == Event("closed", 2))
```

Depth and collection limits bound work performed by untrusted documents. Override them per call when a domain needs tighter limits:

```scala
val rejected = Json.decode[Event](
    "{\"name\":\"opened\",\"count\":1}",
    maxDepth = 0,
    maxCollectionSize = 16
)
assert(rejected.isFailure)
```

> **Note:** `Json.DefaultMaxDepth` and `Json.DefaultMaxCollectionSize` are the defaults for nesting and collection or object entry limits. They do not bound document or JSONL record byte length.

A failure remains a value until the caller chooses how to handle it:

```scala
val invalid = Json.decode[Event]("{\"name\":7}")
invalid match
    case Result.Failure(error) => assert(error.isInstanceOf[DecodeException])
    case _                     => assert(false)
```

> **Caution:** Nested `Option[Option[A]]` is lossy in JSON because `null` cannot distinguish the two empty layers.

> **Unlike** codecs that map `Unit` to `null`, this codec encodes and describes `Unit` as an empty JSON object.

## Describing JSON shapes

When another system needs a machine-readable contract, derive JSON Schema from the same `Schema[A]` that drives wire encoding.

### Deriving an enriched schema

Use `Json.jsonSchema[A]` to obtain a Draft 2020-12-compatible value. Documentation, field descriptions, deprecation markers, examples, constraints, dropped fields, and renamed fields registered on the runtime schema enrich the result:

```scala
val description: Json.JsonSchema = Json.jsonSchema[Event]
assert(description.isInstanceOf[Json.JsonSchema.Obj])
```

> **Unlike** `Json.JsonSchema.from[A]`, which derives structure only, `Json.jsonSchema[A]` applies runtime `Schema` metadata and transformations.

The returned `Json.JsonSchema` enum represents object, array, string, numeric, integer, boolean, null, nullable, and `oneOf` shapes. Pattern matching lets tooling inspect the result without parsing generated JSON:

```scala
val propertyNames = Json.jsonSchema[Event] match
    case Json.JsonSchema.Obj(properties, _, _, _, _, _) =>
        properties.map(_._1)
    case _ => Nil

assert(propertyNames == List("name", "count"))
```

> **Note:** `Json.JsonSchema.jsonSchemaSchema` is a handwritten given that emits standard JSON Schema objects rather than the normal tagged sealed-trait shape.

Use `Json.encode(Json.jsonSchema[A])` when the contract must cross a wire or be written to a file:

```scala
val document = Json.encode(Json.jsonSchema[Event])
assert(document.contains("\"properties\""))
```

## Processing complete JSONL inputs

When a complete JSONL value is already in memory, use the pure `Json.Lines` surface. `Json.Lines` is an alias for the public `JsonLines` object, so both names identify the same namespace while `Json.Lines` keeps JSON formats grouped at call sites.

### Encoding complete inputs

Use `encodeLine` for one newline-terminated record, `encodeAll` for a text batch, and `encodeAllBytes` for its UTF-8 byte representation:

```scala
val first  = Event("opened", 1)
val events = Seq(first, Event("closed", 2))

val line  = Json.Lines.encodeLine(first)
val text  = Json.Lines.encodeAll(events)
val bytes = Json.Lines.encodeAllBytes(events)

assert(line == "{\"name\":\"opened\",\"count\":1}\n")
assert(new String(bytes.toArray, StandardCharsets.UTF_8) == text)
```

Every encoded record ends with a newline, including the last one. An empty sequence produces empty text or bytes.

### Decoding strict and recoverable batches

Use `decodeAll` or `decodeAllBytes` when one bad record should fail the whole input:

```scala
val input =
    "{\"name\":\"opened\",\"count\":1}\n" +
        "{\"name\":\"closed\",\"count\":2}\n"

val fromText  = Json.Lines.decodeAll[Event](input)
val fromBytes = Json.Lines.decodeAllBytes[Event](utf8(input))

assert(fromText.getOrThrow == Chunk(Event("opened", 1), Event("closed", 2)))
assert(fromBytes.getOrThrow == fromText.getOrThrow)
```

Use `decodeAllBytesResults` when each record needs an independent outcome. A bad record occupies its original position and later records can still succeed:

```scala
val input =
    "{\"name\":\"opened\",\"count\":1}\n" +
        "{\"name\":7}\n" +
        "{\"name\":\"closed\",\"count\":2}\n"
val results = Json.Lines.decodeAllBytesResults[Event](utf8(input))

assert(results.size == 3)
assert(results(0).getOrThrow == Event("opened", 1))
assert(results(1).isFailure)
assert(results(2).getOrThrow == Event("closed", 2))
```

Both strict and recoverable forms use the same depth, collection, and line-size limits. The difference is whether a record failure stops the batch or remains one `Result` element.

### Decoding a positioned record

When framing is managed separately, `decodeRecord` preserves the record's index and byte offset in a `RecordDecodeException`:

```scala
val record = Json.Lines.Record(
    utf8("{\"name\":\"opened\",\"count\":1}"),
    index = 3L,
    byteOffset = 72L
)
val decoded = Json.Lines.decodeRecord[Event](record)

assert(decoded.getOrThrow == Event("opened", 1))
assert(record.text == "{\"name\":\"opened\",\"count\":1}")
```

> **Note:** Blank lines do not consume record indexes, while oversized lines skipped after a known terminator do consume them. Byte offsets count every original byte, including blanks and a leading byte order mark.

> **Caution:** Do not compare `Json.Lines.Record` values with `==`. Their `Span` bytes use reference equality, so compare `record.text` or `record.bytes.is(other.bytes)` instead.

## Framing arbitrary byte chunks

When network or file reads split JSONL at arbitrary byte positions, carry a `Json.Lines.Framer` between chunks. It frames at the byte level, so a UTF-8 character split across chunks remains intact.

### Feeding and finishing a framer

Create a framer with `Framer.init`, feed each arriving `Span[Byte]`, then call `finish` only when the input is known to be finite:

```scala
val initial = Json.Lines.Framer.init()
val framed  = initial.feed(utf8("{\"name\":\"ope"))

val advanced = framed match
    case Json.Lines.Framed.Continued(next, lines) =>
        assert(lines.isEmpty)
        next
    case Json.Lines.Framed.Halted(_, breach) => throw breach

val completed = advanced.feed(utf8("ned\",\"count\":1}\n"))
assert(completed.isInstanceOf[Json.Lines.Framed.Continued])
```

`feed` returns a new immutable framer. The receiver remains reusable, and chunk boundaries do not affect records, indexes, or offsets.

### Handling framing outcomes

`Line.Kept` carries a `Record`; `Line.Skipped` carries the size-limit failure occupying that record position. `Framed.Continued` carries resolved lines plus the next framer, while `Framed.Halted` carries resolved lines plus the terminal breach:

```scala
val outcome = Json.Lines.Framer.init(maxLineSize = 8.bytes).feed(utf8("123456789\n12345678\n"))
val texts = outcome match
    case Json.Lines.Framed.Continued(_, lines) =>
        lines.collect { case Json.Lines.Line.Kept(record) => record.text }
    case Json.Lines.Framed.Halted(_, breach) => throw breach

assert(texts == Chunk("12345678"))
```

> **Unlike** a terminated oversized line, which becomes `Line.Skipped` inside `Framed.Continued` and lets framing resume after its newline, an oversized unterminated residual produces `Framed.Halted` because no record boundary exists to resume from.

`Json.Lines.DefaultMaxLineSize` is the default `ByteSize` bound for both a pending residual and a complete record. It is `16.mib`. Supply a lower value to `Framer.init` and the decoding APIs when the application accepts smaller records.

## Reading and following streams

When input arrives incrementally or lives in a file, use `Jsonl`. It composes pure framing and decoding with `Stream`, `Scope`, and `Path` without accumulating the whole source.

### Transforming arbitrary byte streams

Use `Jsonl.pipe[A]` when the first failure should abort the stream. Use `pipeResults[A]` when malformed or oversized terminated records should be emitted as values and processing should continue:

```scala
def strict(input: Stream[Byte, Any]): Stream[Event, Abort[DecodeException]] =
    input.into(Jsonl.pipe[Event]())

def recoverable(input: Stream[Byte, Any]): Stream[Result[DecodeException, Event], Any] =
    input.into(Jsonl.pipeResults[Event]())
```

Both pipes accept `maxDepth`, `maxCollectionSize`, and the `ByteSize` parameter `maxLineSize`. Their output is independent of how the upstream stream groups bytes into chunks.

> **Note:** Strict streaming emits every successfully decoded record before the first failure, including when those records and the failure arrive in one upstream chunk. The emitted prefix therefore does not depend on upstream chunk size.

### Reading finite files

Use `Jsonl.read` for a strict file stream and `Jsonl.readResults` for per-record outcomes. The file handle belongs to the enclosing `Scope`:

```scala
def load(path: Path): Chunk[Event] < (Async & Abort[FileReadException | DecodeException]) =
    Scope.run(Jsonl.read[Event](path).run)

def inspect(path: Path): Chunk[Result[DecodeException, Event]] < (Async & Abort[FileReadException]) =
    Scope.run(Jsonl.readResults[Event](path).run)
```

The exact inferred row for `load` is `Async & Abort[FileReadException | DecodeException]`: `Scope.run` closes the file, `Async` drives the scoped resource lifecycle, and the strict decoder adds its typed failure.

### Following live files

Use `Jsonl.follow` to replay a file and continue waiting for appended records. The default `Path.Origin.Start` replays existing data; `Path.Origin.End` waits for new records; `Path.Origin.Offset` resumes from a recorded byte position. `followResults` keeps decode failures in the element type:

```scala
def live(path: Path): Stream[Event, Scope & Async & Abort[FileReadException | DecodeException]] =
    Jsonl.follow[Event](path, from = Path.Origin.End)

def liveResults(path: Path): Stream[Result[DecodeException, Event], Scope & Async & Abort[FileReadException]] =
    Jsonl.followResults[Event](path)
```

Followers run until interrupted, the scope ends, or framing reaches an oversized residual with no boundary. Bound a consumer with operations such as `take` when it expects a finite number of records.

> **Unlike** a finite `Jsonl.pipe` or `Jsonl.read`, which emits an unterminated final record when end-of-input proves it complete, `Jsonl.follow` and `Jsonl.followResults` hold an unterminated record until a newline arrives because a live source has no end-of-input signal.

> **Note:** Following tracks the open file rather than its path name. Rename-based rotation keeps reading the original open file and does not switch to a replacement created at the old path.

## Writing record streams

When values are produced over time, encode and persist them one upstream chunk at a time so memory use stays bounded by stream chunk size.

### Encoding a value stream

Use `Jsonl.encode` to turn `Stream[A, S]` into newline-terminated UTF-8 bytes while preserving the upstream effect row:

```scala
val values                                           = Stream.init(Chunk(Event("opened", 1), Event("closed", 2)))
val bytes: Stream[Byte, Any]                         = Jsonl.encode(values)
val roundTrip: Stream[Event, Abort[DecodeException]] = bytes.into(Jsonl.pipe[Event]())
```

Each value determines one record regardless of upstream chunking. An empty stream emits no bytes.

### Replacing or extending a file

Use `Jsonl.write` to empty or create a file before writing. Use `Jsonl.append` to preserve existing bytes and add records at its current end:

```scala
def replace(path: Path, events: Stream[Event, Any]): Unit < (Sync & Abort[FileWriteException]) =
    Jsonl.write(path, events)

def extend(path: Path, events: Stream[Event, Any]): Unit < (Sync & Abort[FileWriteException]) =
    Jsonl.append(path, events)
```

Both methods write incrementally and preserve the successfully written prefix if encoding or an upstream effect fails. `write` with an empty stream leaves an empty file; `append` with an empty stream still creates a missing file.

> **Caution:** `Jsonl.append` inserts no separator before its first record. If the existing file's last record lacks a newline, the first appended record is spliced onto it and the resulting line is corrupt.

The same strict read can be named for a higher-level workflow without widening its inferred effects:

```scala
def archive(path: Path): Chunk[Event] < (Async & Abort[FileReadException | DecodeException]) =
    Scope.run(Jsonl.read[Event](path).run)
```

The exact inferred row for `archive` remains `Async & Abort[FileReadException | DecodeException]`.

## Cross-platform behavior

The shared API and tests target JVM, JavaScript, Native, and Wasm.

## Integrating with codec machinery

When implementing a schema or codec extension, use the concrete JSON factories rather than duplicating the module's reader or writer construction.

### Constructing a writer

Use `Json.newWriter` to obtain the `Codec.Writer` that schema serialization uses. Custom codec integrations can call its public writer methods directly, then take the result in the required representation:

```scala
val writer: Codec.Writer = Json().newWriter()
writer.string("opened")

assert(writer.resultString == "\"opened\"")
```

### Constructing a reader

Use `Json.newReader` when custom integration code already owns the UTF-8 bytes and needs the same concrete `Codec.Reader` as `Json.decodeBytes`. Its public reader methods decode individual JSON values:

```scala
val reader: Codec.Reader = Json().newReader(utf8("\"opened\""))
val value                = reader.string()

assert(value == "opened")
```

These hooks expose codec machinery, not a second public decoding policy. Application code that owns one complete document should prefer `Json.encode`, `Json.decode`, and their byte variants because those entry points apply the complete-input checks and return typed failures.

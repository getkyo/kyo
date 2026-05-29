# Phase 03 Decisions Log

## D-001: Stream.statefulChunk does not exist

**Observation**: The plan calls `Stream.statefulChunk[...]` in `FramerImpl`. This method is absent from `kyo.Stream`.

**Resolution**: Implemented stateful parsing using `AtomicRef[Chunk[Byte]]` + `Stream.mapChunk[Chunk[Byte], Chunk[Byte], Sync]`. Each incoming chunk of chunks is accumulated with the leftover buffer stored in the `AtomicRef`, frames are extracted, and the leftover is stored back. `Stream.unwrap` fuses the `Sync` effect from `AtomicRef.init` into the stream's effect row. Since `Async <: Sync`, the resulting `Async & Abort[Closed] & Sync` row simplifies to `Async & Abort[Closed]`.

## D-002: Console.readLine returns String < (Sync & Abort[IOException]), not Maybe[String]

**Observation**: The plan's `StdioWireTransport.incoming` uses `Console.readLine.map { case Maybe.Absent => ... case Maybe.Present(line) => ... }`. The actual `Console.readLine` signature returns `String < (Sync & Abort[IOException])` and throws `EOFException` (subtype of `IOException`) on EOF.

**Resolution**: Used `Abort.run[java.io.IOException](Console.readLine).map { case Result.Failure(_) => Maybe.Absent; case Result.Panic(_) => Maybe.Absent; case Result.Success(line) => Maybe.Present(...) }`. Both `EOFException` on EOF and any other `IOException` terminate the stream gracefully via `Absent`.

## D-003: Json.decode[Structure.Value] does not parse standard JSON-RPC envelopes

**Observation**: The plan uses `Json.decode[Structure.Value](jsonStr)` in `WireTransportAdapter.incoming` and `Json.encode(structure)` in `send`. `Structure.Value` derives `Schema` as a sealed enum, so its JSON encoding uses discriminated union wrappers (`{"Record":{"fields":[...]}}`) rather than plain JSON. `Json.decode[Structure.Value]('{"jsonrpc":"2.0","method":"ping"}')` returns `Result.Failure` because the standard JSON-RPC format does not match the kyo-schema variant encoding.

**Resolution**: Added `RawJsonParser` (new internal file) that bidirectionally converts between raw JSON strings and `Structure.Value` trees. `RawJsonParser.parse` maps JSON objects to `Structure.Value.Record`, arrays to `Sequence`, strings to `Str`, numbers to `Integer`/`Decimal`, booleans to `Bool`, and `null` to `Null`. `RawJsonParser.encode` is the inverse. Both `WireTransportAdapter.send` and `incoming` use `RawJsonParser` instead of `Json.encode`/`Json.decode`. Similarly, `JsonRpcTransportTest` uses `RawJsonParser.parse` in test 28 to verify the captured stdout.

## D-004: DecodeException is a sealed trait, not a case class

**Observation**: The first attempt to signal parse errors used `throw DecodeException(...)`. `DecodeException` is a sealed trait; its concrete subtype `ParseException` is what can be instantiated.

**Resolution**: `RawJsonParser.parse` uses `Result.catching[ParseException]` and throws `ParseException(Json(), input, detail)` on errors. `WireTransportAdapter.incoming` matches on `Result[ParseException, _]` (widened to `Result.Failure` via structural typing).

## D-005: Stream.emit does not exist in kyo.Stream

**Observation**: `FramerTest` initially used `Stream.emit[Chunk[Byte]](Chunk(input))` which does not exist. The correct API is `Stream.init`.

**Resolution**: Changed all occurrences to `Stream.init[Chunk[Byte], Any](Seq(input))`.

## D-006: Channel.streamUntilClosed returns Stream[A, Async] (no Abort[Closed])

**Observation**: `WireTransportTest` needed a `Stream[Chunk[Byte], Async & Abort[Closed]]` from a `Channel`. `streamUntilClosed()` returns `Stream[A, Async]` (absorbs Closed). `stream()` returns `Stream[A, Abort[Closed] & Async]`.

**Resolution**: Used `channel.stream()` in the test's WireTransport implementations; the receiver wraps the `incoming.take(1).run` call in `Abort.run[Closed]`.

## D-007: mapChunk effect row includes Sync

**Observation**: `FramerImpl.parseLineDelimited` and `parseContentLength` use `mapChunk[Chunk[Byte], Chunk[Byte], Sync]` adding `Sync` to the stream's effect row. The `Framer.parse` return type is `Stream[Chunk[Byte], Async & Abort[Closed]]`.

**Resolution**: Since `Async <: Sync`, any effect row containing `Async` already contains `Sync`. The Scala compiler resolves `Async & Abort[Closed] & Sync` to `Async & Abort[Closed]` automatically. No explicit type ascription needed.

## D-008: RawJsonParser added as unplanned file

**Observation**: The plan does not list `RawJsonParser.scala` as a file to produce.

**Resolution**: The plan's `Json.decode[Structure.Value]` call is incorrect for standard JSON-RPC wire bytes (D-003). `RawJsonParser` is the minimal adaptation to make the design work correctly. It is `private[kyo]` and tested indirectly through `WireTransportAdapter` (tests 20, 28, 29).

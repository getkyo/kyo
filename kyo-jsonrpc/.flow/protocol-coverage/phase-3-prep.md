# Phase 03 Preparation: Wire Transport Seam plus Stdio

## Document Purpose
Establish design baseline, prior-art patterns, and cross-platform constraints for Phase 03 (Items 7, 5): `WireTransport` + `Framer` (lineDelimited, contentLength) + `JsonRpcTransport.fromWire` + `JsonRpcTransport.stdio`.

## 1. Current JsonRpcTransport Shape

**File**: `/Users/fwbrasil/workspace/kyo/.claude/worktrees/crispy-swinging-lemur/kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcTransport.scala` (lines 1-32)

```scala
trait JsonRpcTransport:
    def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed])
    def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]]
    def close(using Frame): Unit < Async
end JsonRpcTransport
```

**Existing implementations**:
- `InMemoryTransport` (lines 1-19 in `internal/InMemoryTransport.scala`): wraps paired Channels; used by `JsonRpcTransport.inMemory` factory.
- Phase 03 will add `WireTransportAdapter` (wraps `WireTransport` + `Framer` + `JsonRpcCodec`).
- Phase 03 will add `StdioWireTransport` (reads `Console.readLine`, writes `Console.printLine`).

**Key contract**: `send` and `incoming` must handle `Closed` abort; `close` is fire-and-forget cleanup.

## 2. Byte-Stream Transport Precedents

### kyo-http Platform Transport Patterns

**HttpClient (`shared/src/main/scala/kyo/HttpClient.scala` lines 1-96)**:
- Opaque type wrapping `HttpClientBackend[?]`; lazy default client in companion.
- Fiber-local state via `Local` for request lifecycle (timeouts, retries, redirects, pooling).
- Extension methods: `sendWith[In, Out, A](route, request)(f)` for typed request/response.
- Lifecycle: `close(gracePeriod)`, `close()` (30s default), `closeNow()`.
- No `incoming` stream; responses are request-response paired.

**HttpServer (`shared/src/main/scala/kyo/HttpServer.scala` lines 1-91)**:
- Opaque type with address/port accessors.
- Registered handlers dispatch via `HttpRouter`.
- Lifecycle mirrors HttpClient: `close(gracePeriod)`, `close()`, `closeNow()`, `await()`.
- No outbound stream; handlers receive inbound requests and must reply synchronously.

**kyo-http Transport Layer** (`jvm/src/main/scala/kyo/internal/{NioTransport,HttpPlatformTransport}.scala`):
- NIO selector-based for JVM; kqueue/epoll for Native.
- Bidirectional connection abstraction: send/receive byte buffers.
- TLS handled at transport layer, not JSON-RPC layer.

**Relevance to Phase 03**: JsonRPC is simpler (sequence-oriented, no request-response multiplexing). No httpClient-style fiber-local wiring needed; stdio and wire adapters are stateless factories.

### JsonRpcEnvelope and Codec Patterns

**JsonRpcEnvelope** (`shared/src/main/scala/kyo/JsonRpcEnvelope.scala` line 25):
```scala
case Malformed(id: Maybe[JsonRpcId], reason: String, raw: Structure.Value)
```
After Phase 01, Malformed carries extracted id for callers to recover from peer errors. Phase 03 adapter must route JSON parse failures as `Malformed(Absent, ...)` per Phase 01 contract.

**JsonRpcCodec** (`shared/src/main/scala/kyo/JsonRpcCodec.scala` lines 1-?):
- `def encode(env: JsonRpcEnvelope): Structure.Value < Abort[JsonRpcError]`
- `def decode(sv: Structure.Value): JsonRpcEnvelope < Sync`
- WireTransportAdapter calls both; encode failures logged (not aborted to endpoint caller).

## 3. Console.readLine / Console.printLine Shape

**File**: `/Users/fwbrasil/workspace/kyo/kyo-core/shared/src/main/scala/kyo/Console.scala` (lines 1-100)

```scala
def readLine(using Frame): String < (Sync & Abort[IOException]) = 
    Sync.Unsafe.defer(Abort.get(unsafe.readLine()))

def println(s: Text)(using Frame): Unit < Sync = 
    Sync.Unsafe.defer(unsafe.printLine(s.show))
```

**Live implementation** (lines 75-86):
- `readLine` wraps `scala.Console.in.readLine()` returning `Maybe[String]`; `Absent` becomes `Result.fail(EOFException)`.
- `println` wraps `scala.Console.out.println(s)` synchronously (no exceptions thrown per PrintStream contract).
- No effect for write failures; use `checkErrors()` to detect buffering issues.

**Cross-platform considerations**:
- **JVM**: `scala.Console.in`, `scala.Console.out` → stdin/stdout file descriptors.
- **JS** (Node.js): `process.stdin.on('data', ...)` and `process.stdout.write(...)` async; buffering via event loop.
- **JS** (browser): stdin unavailable; stdout is console.log. Stdio is unsuitable in-browser.
- **Native**: direct libc stdin/stdout via FFI.

## 4. Framing Contracts and Byte Parsing

### Content-Length Framing (RFC 3156 LSP dialect)

**Header format**: `Content-Length: <N>\r\n\r\n<N bytes>`

**Tolerance on parse**:
- Accepts `\n\n` as frame separator (for stdin from systems not forcing CRLF).
- Strict `\r\n\r\n` on emit (canonical form).
- Header errors (non-numeric length, negative, missing header) fail the parse.

**State machine** (FramerImpl.parseOneContentLengthFrame):
1. Scan for `\r\n\r\n` (4 bytes) or `\n\n` (2 bytes); use the first occurrence.
2. Parse Content-Length value from headers before separator.
3. Validate `len >= 0`.
4. Check buffer has `bodyStart + len` bytes available; if not, await more input.
5. Extract frame from `bodyStart` to `bodyEnd`; leftover back to buffer.

**Byte offset arithmetic** (FramerImpl lines 803-828):
```scala
val sepIdx   = ...  // index of separator found
val sepLen   = if sepIdx == sep1 then 4 else 2
val headers  = new String(arr.slice(0, sepIdx), "UTF-8")
val len      = parseContentLengthHeader(headers).getOrElse(-1)
val bodyStart = sepIdx + sepLen
val bodyEnd   = bodyStart + len
```

### Line-Delimited Framing (stdio convention)

**Frame boundary**: LF byte (`\n`); CR before LF is stripped.

**Empty line handling**: skipped on parse (not emitted as frames).

**EOF handling**: partial line is dropped (no flush).

**Byte offset arithmetic** (FramerImpl lines 778-791):
```scala
while i < arr.length do
    if arr(i) == '\n'.toByte then
        val end = if i > 0 && arr(i - 1) == '\r'.toByte then i - 1 else i
        val frame = Chunk.from(arr.slice(start, end))
        if frame.nonEmpty then builder += frame
        start = i + 1
    i += 1
```

## 5. Stream Stateful Parsing Pattern

**Stream.statefulChunk** (used by both framers):
```scala
Stream.statefulChunk[Chunk[Byte], Async & Abort[Closed], Chunk[Byte]](Chunk.empty)(stream) { (buffer, chunk) =>
    val combined = buffer ++ chunk
    val (frames, leftover) = splitOnLf(combined)
    (leftover, frames)
}
```

**Contract**:
- State: `Chunk[Byte]` buffer (initially empty).
- Input: `Stream[Chunk[Byte], Async & Abort[Closed]]` (raw byte chunks from wire).
- Output: `Stream[Chunk[Byte], Async & Abort[Closed]]` (framed chunks).
- Per chunk: accumulate into buffer, split on frame boundary, emit frames, keep leftover.

## 6. Stdio Platform Gaps

| Platform | Availability | Notes |
|----------|--------------|-------|
| JVM | Yes | scala.Console.in/out fully functional |
| JS (Node) | Yes | `process.stdin`/`process.stdout` via FFI; backpressure via event loop |
| JS (Browser) | No | No stdin; stdout is console.log (unsuitable for binary JSON-RPC) |
| Native | Yes | libc stdin/stdout via FFI; blocking reads on some platforms |

**Design implication**: `JsonRpcTransport.stdio` is for CLI tools (LSP servers, MCP servers). No browser-facing JSON-RPC stdio.

## 7. Key Design Decisions Locked by Phase 03 Plan

1. **WireTransport trait** (public): byte-level seam independent of framing or codec.
2. **Framer trait** (public): frame/parse pair for any wire transport.
3. **WireTransportAdapter** (internal): lifts WireTransport + Framer + JsonRpcCodec to JsonRpcTransport.
4. **StdioWireTransport** (internal): reads Console.readLine, writes Console.printLine; strips/adds newlines.
5. **JsonRpcTransport.fromWire** (public): factory returns WireTransportAdapter.
6. **JsonRpcTransport.stdio** (public): factory chains StdioWireTransport -> fromWire.

## 8. Cross-Platform Test Constraints

**Shared tests** (all platforms):
- WireTransportTest: empty preset, round-trip via in-memory wire.
- FramerTest: line-delimited parsing, content-length parsing, tolerance cases.
- JsonRpcTransportTest: stdio is JVM-only (Node mocking expensive, browser unsuitable).

**Platform-specific**:
- JS tests: mock Console via Local; skip stdio integration.
- Native tests: skip stdio (requires runtime availability; unit framers covered in shared).

## 9. Key Files and Line References

| File | Purpose | Lines |
|------|---------|-------|
| JsonRpcTransport.scala | Public trait + inMemory factory | 1-32 |
| JsonRpcEnvelope.scala | Malformed contract | 25 |
| InMemoryTransport.scala | Reference JsonRpcTransport impl | 1-19 |
| Console.scala (kyo-core) | readLine/println signatures | 20-25, 41-46 |
| HttpClient.scala | close(gracePeriod) pattern | 86-93 |
| HttpServer.scala | lifecycle pattern | 51-63 |

---

**Status**: Prep complete. Phase 03 implementation proceeds with baseline established, prior-art patterns catalogued, and cross-platform gotchas surfaced.

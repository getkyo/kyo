# Stream-First Transport Design

Three layers, each using its natural abstraction:

1. **Transport**: pull-based `Stream[Span[Byte], Async]` with shared event loop groups, `@blocking` kernel waits, and safe Promise dispatch. Eliminates per-connection event fds and 1ms polling.
2. **Protocol**: pure functions that thread `(result, remainingStream)` — no mutable state, no classes. Eliminates `ByteArrayOutputStream`, `BufferedStream`, `pushBack`, `StreamReader`.
3. **Exchange**: uniform `exchange(request)` interface for both HTTP/1.1 (Channel-serialized) and HTTP/2 (stream-ID-multiplexed). The connection pool stores Exchanges — protocol version is invisible to callers.

```
┌──────────────────────────────────────────────────────────────┐
│  HttpClient / HttpServer                                     │
│    ▼                                                         │
│  ConnectionPool[Exchange[HttpReq, HttpResp, Event, E]]       │
│    ├── HTTP/1.1: http1Exchange (Channel-coordinated)         │
│    │     └── ByteStream pure functions → Http1Protocol        │
│    └── HTTP/2:  http2Exchange (stream-ID-multiplexed)        │
│          └── Http2FrameCodec → Http2Protocol                 │
│                                                              │
│  Transport: Connection <: TransportStream                    │
│    read: Stream[Span[Byte], Async]                           │
│    write: Span[Byte] → Unit < Async                          │
│    └── EventLoopGroup (kqueue / epoll / NIO / Node.js)       │
└──────────────────────────────────────────────────────────────┘
```

---

# Implementation Phases

Build alongside existing backends. Old code stays working until the final flip. Each phase compiles and passes tests independently.

---

## Phase 1: Core Traits + ByteStream

New shared abstractions and pure byte-stream functions. No platform code, no changes to existing code.

### Files

```
kyo-http/shared/src/main/scala/kyo/internal/
    TransportStream2.scala       — new TransportStream, Transport, TransportListener
    ByteStream.scala             — readUntil, readExact, readLine, indexOf
kyo-http/shared/src/test/scala/kyo/internal/
    ByteStreamTest.scala
```

### Code

```scala
// ── TransportStream2.scala ──────────────────────────────────

trait TransportStream2:
    def read: Stream[Span[Byte], Async]
    def write(data: Span[Byte])(using Frame): Unit < Async

trait Transport2:
    type Connection <: TransportStream2
    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using Frame)
        : Connection < (Async & Abort[HttpException])
    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using Frame)
        : TransportListener2[Connection] < (Async & Scope)
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Sync
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async

class TransportListener2[+C <: TransportStream2](
    val port: Int,
    val host: String,
    val connections: Stream[C, Async]
)

// ── ByteStream.scala ────────────────────────────────────────

object ByteStream:
    val CRLF      = "\r\n".getBytes(StandardCharsets.UTF_8)
    val CRLF_CRLF = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)

    def readUntil(
        src: Stream[Span[Byte], Async], delimiter: Array[Byte], maxSize: Int
    )(using Frame)
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        Loop(src, Span.empty[Byte]) { (stream, buffer) =>
            stream.splitAt(1).map { (chunk, rest) =>
                if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
                else
                    val span     = chunk(0)
                    val combined = if buffer.isEmpty then span else Span.concat(buffer, span)
                    if combined.size > maxSize then
                        Abort.fail(HttpProtocolException("Data exceeds max size before delimiter"))
                    else
                        indexOf(combined.toArrayUnsafe, delimiter) match
                            case -1 => Loop.continue(rest, combined)
                            case idx =>
                                val before    = combined.slice(0, idx)
                                val after     = combined.slice(idx + delimiter.length, combined.size)
                                val remaining =
                                    if after.nonEmpty then Stream.init(Seq(after)).concat(rest)
                                    else rest
                                Loop.done((before, remaining))
            }
        }

    def readExact(src: Stream[Span[Byte], Async], n: Int)(using Frame)
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        if n <= 0 then (Span.empty[Byte], src)
        else
            Loop(src, Chunk.empty[Span[Byte]], 0) { (stream, chunks, count) =>
                stream.splitAt(1).map { (chunk, rest) =>
                    if chunk.isEmpty then Abort.fail(HttpConnectionClosedException())
                    else
                        val span     = chunk(0)
                        val take     = math.min(span.size, n - count)
                        val piece    = span.slice(0, take)
                        val newCount = count + take
                        if newCount >= n then
                            val after =
                                if take < span.size then span.slice(take, span.size)
                                else Span.empty[Byte]
                            val remaining =
                                if after.nonEmpty then Stream.init(Seq(after)).concat(rest)
                                else rest
                            Loop.done((Span.concat(chunks.append(piece).toSeq*), remaining))
                        else
                            Loop.continue(rest, chunks.append(piece), newCount)
                }
            }

    def readLine(src: Stream[Span[Byte], Async], maxSize: Int = 8192)(using Frame)
        : (Span[Byte], Stream[Span[Byte], Async]) < (Async & Abort[HttpException]) =
        readUntil(src, CRLF, maxSize)

    private[internal] def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int = ...
```

### Tests: ByteStreamTest

Helper: `def streamOf(spans: Span[Byte]*): Stream[Span[Byte], Async] = Stream.init(spans.toSeq)`

**readUntil:**
- Delimiter in single span → correct split, remaining has leftover bytes
- Delimiter spans two spans → correct accumulation and split
- Delimiter at very start of data → empty before, all data in remaining
- Delimiter at very end → all data before, empty remaining
- Multiple delimiters → stops at first, remaining contains the rest
- No delimiter, stream ends → Abort(HttpConnectionClosedException)
- Exceeds maxSize before delimiter → Abort(HttpProtocolException)
- Empty stream → Abort(HttpConnectionClosedException)
- Single-byte spans (worst case fragmentation) → correct accumulation
- Delimiter is 1 byte → works correctly
- Data exactly equals delimiter → empty before, empty remaining
- Large data (100KB) split into 8KB spans → correct accumulation up to maxSize
- Remaining stream is consumable: readUntil → use remaining → readUntil again on same stream

**readExact:**
- Exact fit: one span of exactly n bytes → correct data, empty remaining
- Span larger than n → correct data, remaining has leftover
- Multiple spans needed → accumulates correctly, single Span result
- n = 0 → empty data, original stream unchanged
- Stream ends before n bytes → Abort(HttpConnectionClosedException)
- n = 1 → single byte extracted
- Large n (1MB) across many small spans → correct accumulation
- Remaining stream consumable after readExact

**readLine:**
- `"hello\r\n"` → `"hello"`, remaining empty
- `"hello\r\nworld\r\n"` → `"hello"`, remaining has `"world\r\n"`
- Sequential readLine calls on same stream → each gets next line
- Empty line `"\r\n"` → empty data
- Line exceeds maxSize → Abort
- No CRLF, stream ends → Abort

**Composition (chained operations on same stream):**
- readUntil(CRLF_CRLF) → readExact(n) on remaining → correct data
- readLine → readLine → readExact → all from same stream, all correct
- readUntil → remaining stream still delivers subsequent data

---

## Phase 2: Http1Protocol (new)

HTTP/1.1 read/write as pure functions over the new stream API. Uses `ByteStream` from Phase 1. Tested in-memory.

### Files

```
kyo-http/shared/src/main/scala/kyo/internal/
    Http1Protocol2.scala          — new protocol using ByteStream
kyo-http/shared/src/test/scala/kyo/internal/
    StreamTestTransport.scala     — in-memory Transport2 for testing
    Http1Protocol2Test.scala
```

### Code: StreamTestTransport

In-memory `Transport2` using Channels. Same architecture as existing `TestTransport` but with `Stream[Span[Byte], Async]` read API.

```scala
class StreamTestTransport extends Transport2:
    type Connection = StreamTestConnection
    // Same Channel-pair architecture as TestTransport, but Connection extends TransportStream2
    // read: drains readCh into a Stream via Loop.foreach + Emit
    // write: puts Spans into writeCh
```

### Code: Http1Protocol2

```scala
object Http1Protocol2:
    // readRequest:  Stream → ((method, path, headers, body), remaining Stream)
    // readResponse: Stream → ((status, headers, body), remaining Stream)
    // writeRequest: TransportStream2 → method, path, headers, body → Unit
    // writeResponse: TransportStream2 → status, headers, body → Unit
    // writeStreamingBody: filter empty → mapPure encodeChunk → concat lastChunk → foreach write
    // readBody, readChunkedBody: internal, thread remaining stream
    // Pure parsing functions: same as current Http1Protocol
```

### Tests: Http1Protocol2Test

Helper: `withPair { (client, server) => ... }` — creates connected StreamTestTransport pair.

**Request parsing (roundtrip: write → read):**
- GET with no body → correct method, path, headers, `HttpBody.Empty`
- POST with Content-Length body → `HttpBody.Buffered` with correct bytes
- POST with chunked body → `HttpBody.Buffered` with reassembled data
- PUT with binary body (all byte values 0x00-0xFF) → no corruption
- All standard methods: GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
- Request with query string `/path?a=1&b=2` → path preserved
- Multiple headers with same name → all preserved
- Header with empty value `X-Empty:` → empty string
- Large headers (60KB) → succeeds under MaxHeaderSize
- Headers exceeding MaxHeaderSize → Abort
- Unicode in header values → preserved
- Custom request headers → round-trip correctly

**Response parsing (roundtrip: write → read):**
- 200 OK with body → correct status, headers, body
- 204 No Content → no body read
- 304 Not Modified → no body read
- HEAD response with Content-Length → no body read regardless
- 1xx informational → no body
- Chunked response → reassembled correctly
- Multi-chunk response (3 chunks) → correct concatenation
- Chunk with extension `;ext=val` → extension ignored, data correct
- Empty chunked body (just `0\r\n\r\n`) → empty body

**Keep-alive (sequential requests on same stream):**
- 3 GET requests on same stream → all parsed correctly, stream threads through
- POST then GET on same stream → body bytes don't leak between requests
- Connection: close → `isKeepAlive` returns false
- Default (no Connection header) → `isKeepAlive` returns true

**Streaming write:**
- `writeStreamingBody` with 3 non-empty spans → correctly chunked-encoded
- Empty spans filtered out
- Last chunk `0\r\n\r\n` appended
- Write response head → correct `HTTP/1.1 STATUS REASON\r\n` format

**Error cases:**
- Malformed request line → Abort(HttpProtocolException)
- Malformed status line → Abort(HttpProtocolException)
- Invalid Content-Length → Abort(HttpProtocolException)
- Content-Length exceeds maxSize → Abort(HttpPayloadTooLargeException)
- Connection closed mid-headers → Abort(HttpConnectionClosedException)
- Connection closed mid-body → Abort(HttpConnectionClosedException)

---

## Phase 3: Native Transport (kqueue)

Event loop, connection, and transport for macOS. Real TCP sockets, `@blocking` kqueue wait, Promise-based dispatch.

### Files

```
kyo-http/native/src/main/scala/kyo/internal/
    EventLoop.scala               — EventLoop, EventLoopGroup
    KqueueNativeTransport2.scala  — implements Transport2
kyo-http/native/src/test/scala/kyo/internal/
    KqueueTransport2Test.scala
```

### Code

EventLoop, EventLoopGroup, KqueueConnection, Transport2 implementation — as specified in the design doc. Uses existing `PosixBindings` and `kyo_tcp.c`.

### Tests: KqueueTransport2Test

**Connection lifecycle:**
- Connect to listening server → `isAlive` true
- Connect to non-existent port → Abort(HttpConnectException)
- `closeNow` → `isAlive` false
- Double `closeNow` → idempotent, no error

**Read/write:**
- Server writes `"hello"`, client reads → stream yields `Span("hello")`
- Client writes `"world"`, server reads → stream yields `Span("world")`
- Write empty span → no error, no data sent
- 1MB single write → all bytes arrive in stream (may span multiple Spans)
- 10MB in 1KB writes → all bytes arrive ordered
- Read after peer closes → stream yields empty Span (EOF), then stream ends
- Write after peer closes → Abort or error

**Stream properties:**
- `read` returns a reusable `Stream[Span[Byte], Async]` — pulling from it yields data
- Multiple pulls from same stream → sequential OS reads
- Stream ends on EOF (empty Span, then no more elements)

**Concurrent connections:**
- 50 concurrent connections → all succeed
- Each connection independent (data doesn't cross)
- Connections on different event loops → no interference

**Server listen:**
- `listen` port 0 → `TransportListener2` with assigned port > 0
- `connections` stream yields accepted connections
- Scope exit → server socket closed, connections stream ends
- Each accepted connection is a `TransportStream2`
- Handler exception → that connection closed, server continues accepting

**Backpressure:**
- Fast writer, slow reader → TCP window fills, writer suspends, no data loss
- Reader resumes → writer unblocks

**Resource cleanup:**
- Scope exit during active connections → all fds closed
- Event loop poller fibers terminated on group scope exit

---

## Phase 4: HTTP/1.1 over Native TCP

Wire `Http1Protocol2` with `KqueueNativeTransport2`. Full HTTP over real TCP.

### Files

```
kyo-http/native/src/test/scala/kyo/internal/
    Http1NativeTest.scala
```

No new production code — this phase wires Phase 2 + Phase 3 together.

### Tests: Http1NativeTest

Helper: start server with `transport.listen`, client with `transport.connect`, use `Http1Protocol2.readRequest`/`writeResponse`/etc.

**Basic HTTP roundtrip:**
- Client sends GET, server responds 200 with body → client receives correct response
- Client sends POST with 1KB JSON body → server receives correct body
- Client sends PUT with binary body → no corruption
- HEAD request → response has no body

**Keep-alive over TCP:**
- 5 sequential requests on same connection → all succeed
- Stream threads correctly across requests (no byte leaking)

**Chunked transfer over TCP:**
- Server sends chunked response → client reassembles correctly
- Client sends chunked request → server reassembles correctly

**Concurrent requests (different connections):**
- 10 clients, each sending 5 requests → all correct
- Responses match requests (no cross-contamination)

**Error handling:**
- Client disconnects mid-request → server's readRequest aborts
- Server disconnects mid-response → client's readResponse aborts
- Malformed response → client aborts with protocol error

**Large payloads:**
- 1MB body → arrives complete
- Body exceeding maxSize → Abort(HttpPayloadTooLargeException)

---

## Phase 5: HTTP/1.1 Exchange

Exchange factory for HTTP/1.1 over the new transport. Tests Exchange lifecycle, error propagation, and pool interaction.

### Files

```
kyo-http/shared/src/main/scala/kyo/internal/
    Http1Exchange.scala           — http1Exchange factory
kyo-http/shared/src/test/scala/kyo/internal/
    Http1ExchangeTest.scala       — tests with StreamTestTransport
kyo-http/native/src/test/scala/kyo/internal/
    Http1ExchangeNativeTest.scala — tests with real TCP
```

### Code: Http1Exchange

Uses `Exchange.init` with auto-assigned sequential Int IDs (no manual counter needed).
`Event = Nothing` since HTTP/1.1 has no push events.

The `inflight` Channel (capacity 1) serializes HTTP/1.1 request/response pairs:
the writer puts `(id, method)` after writing the request, and the reader takes it
before reading the response. This ensures write-then-signal ordering. Both the
Channel and Exchange are managed by the same enclosing Scope.

Requires `ConcreteTag[HttpException]` and `Tag[Emit[Chunk[Http1Wire]]]` in scope.
Both are derived at the `Exchange.init` call site where `Http1Wire` is visible.

```scala
object Http1Exchange:
    private sealed trait Http1Wire
    private case class OutReq(id: Int, req: HttpRequest) extends Http1Wire
    private case class InResp(id: Int, resp: HttpResponse) extends Http1Wire

    def init(conn: TransportStream2, maxSize: Int)(using Frame)
        : Exchange[HttpRequest, HttpResponse, Nothing, HttpException] < (Sync & Scope) =
        Channel.init[(Int, HttpMethod)](1).map { inflight =>
            Exchange.init[HttpRequest, HttpResponse, Http1Wire, Nothing, HttpException](
                encode = (id, req) => OutReq(id, req),
                send   = {
                    case OutReq(id, req) =>
                        Http1Protocol2.writeRequest(conn, req.method, req.url.path,
                            req.headers, req.body).andThen {
                            inflight.put((id, req.method))
                        }
                    case _ => Kyo.unit
                },
                receive = Stream {
                    Loop(conn.read) { stream =>
                        inflight.take().map { case (id, method) =>
                            Http1Protocol2.readResponse(stream, maxSize, method).map {
                                case ((status, headers, body), rest) =>
                                    val wire: Http1Wire =
                                        InResp(id, HttpResponse(status, headers, body))
                                    Emit.valueWith(Chunk(wire))(Loop.continue(rest))
                            }
                        }
                    }
                },
                decode = {
                    case InResp(id, resp) => Exchange.Message.Response(id, resp)
                    case _                => Exchange.Message.Skip
                }
            )
        }
```

### Tests: Http1ExchangeTest (in-memory)

**Basic request/response:**
- `exchange(GET /hello)` → 200 OK with body
- `exchange(POST /submit)` with body → correct response
- Sequential: `exchange(req1)` then `exchange(req2)` → both correct

**Lifecycle:**
- `exchange.close` → pending request fails with Closed
- `exchange.awaitDone` suspends until close, then completes (Abort[Closed])
- Server closes connection → `exchange.awaitDone` completes, pending requests fail

**Error propagation:**
- Server sends malformed response → Exchange fails with HttpException
- Connection drops → all pending requests fail with HttpException
- After failure, `exchange.awaitDone` raises Abort[HttpException]

**Channel coordination:**
- Write-then-signal: request bytes fully written before reader reads response
- Reader blocks on `inflight.take()` until a request is sent

### Tests: Http1ExchangeNativeTest (real TCP)

Same scenarios as above but over real TCP. Verifies the full stack: Exchange → Http1Protocol2 → KqueueNativeTransport2 → TCP.

---

## Phase 6: Server + Client Backends

Wire Exchange into `HttpTransportServer` and `HttpTransportClient`. Flip native `HttpPlatformBackend` to new stack. All existing native tests must pass.

### Files

```
kyo-http/shared/src/main/scala/kyo/internal/
    HttpTransportServer2.scala    — server backend using Transport2
    HttpTransportClient2.scala    — client backend using Exchange
kyo-http/native/src/main/scala/kyo/internal/
    HttpPlatformBackend.scala     — flip to new backends
```

### Code: HttpTransportServer2

```scala
class HttpTransportServer2(transport: Transport2) extends HttpBackend.Server:
    def bind(handlers: Seq[HttpHandler[?, ?, ?]], config: HttpServerConfig)(using Frame)
        : HttpBackend.Binding < Async =
        val router = HttpRouter(handlers, config.cors)
        transport.listen(config.host, config.port, config.backlog, config.tls).map { listener =>
            Fiber.init {
                listener.connections.foreach { conn =>
                    Fiber.init {
                        Sync.ensure(transport.closeNow(conn)) {
                            serveConnection(conn, router, config)
                        }
                    }.map(_ => ())
                }
            }.map { serverFiber => ... binding ... }
        }

    // serveConnection: keep-alive loop using Http1Protocol2.readRequest/writeResponse
    // threading the stream through each request-response cycle
```

### Code: HttpTransportClient2

Pool stores Exchange instances so protocol version (HTTP/1.1 vs future HTTP/2) is invisible to callers. `Connection` type is a wrapper holding both the transport connection and its Exchange.

```scala
class HttpTransportClient2(transport: Transport2) extends HttpBackend.Client:
    // Wrapper holding transport connection + its Exchange
    case class Conn(
        tc: transport.Connection,
        exchange: Exchange[HttpRequest, HttpResponse, Nothing, HttpException]
    )
    type Connection = Conn

    def connectWith[A](url: HttpUrl, connectTimeout: Maybe[Duration])(
        f: Connection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        val tls = if url.ssl then Present(TlsConfig.default) else Absent
        val base = transport.connect(url.host, url.port, tls).map { tc =>
            Http1Exchange.init(tc, 65536).map { exchange =>
                f(Conn(tc, exchange))
            }
        }
        connectTimeout match
            case Present(t) =>
                Abort.recover[Timeout](_ =>
                    Abort.fail(HttpTimeoutException(t, "CONNECT", s"${url.host}:${url.port}"))
                )(Async.timeout(t)(base))
            case Absent => base

    def sendWith[In, Out, A](conn: Connection, route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onRelease: Maybe[Result.Error[Any]] => Unit < Sync = _ => Kyo.unit)(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.ensure(onRelease) {
            // Exchange.apply sends request and awaits response
            // For HTTP/1.1, requests are serialized by the inflight Channel
            conn.exchange(request).map(f)
        }

    def isAlive(conn: Connection)(using Frame): Boolean < Sync =
        transport.isAlive(conn.tc)
    def closeNow(conn: Connection)(using Frame): Unit < Sync =
        conn.exchange.close.andThen(transport.closeNow(conn.tc))
    def close(conn: Connection, gracePeriod: Duration)(using Frame): Unit < Async =
        conn.exchange.close.andThen(transport.close(conn.tc, gracePeriod))
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Kyo.unit
```

Note: `sendWith` here bypasses the two-phase streaming lifecycle from the current `HttpTransportClient` because Exchange already manages the request/response lifecycle. The Exchange's reader fiber handles response reading asynchronously. Streaming response bodies will need the Exchange to produce `HttpBody.Streamed` with a body stream that the caller consumes — the connection can only be returned to the pool after the stream is fully consumed (same constraint as current impl, enforced by the `onRelease` callback).

### Tests

**Run ALL existing shared tests** (`HttpServerTest`, `HttpClientTest`, `HttpCodecTest`, `HttpHandlerTest`, `WebSocketLocalTest`) on Native with the new backend. Zero regressions.

Additionally:

**Server backend:**
- Bind port 0 → assigned port accessible
- `close()` → stops accepting, drains connections
- Keep-alive: 3 requests on same connection
- Connection: close → server closes after response
- Handler panic → 500 response, connection stays open for keep-alive
- 50 concurrent clients → all served correctly

**Client backend:**
- GET/POST/PUT/DELETE → correct responses
- Connection pooling: 2 sequential same-host requests → connection reused
- Pool eviction on error → connection discarded
- Connect timeout → Abort
- Response body > maxSize → Abort

---

## Phase 7: JVM NioTransport

Java NIO Selector-based transport for JVM. Same `Transport2` interface, `SSLEngine` for TLS.

### Files

```
kyo-http/jvm/src/main/scala/kyo/internal/
    NioTransport2.scala           — Selector + SSLEngine
    HttpPlatformBackend.scala     — flip to new backends
kyo-http/jvm/src/test/scala/kyo/internal/
    NioTransport2Test.scala
```

### Code

Same architecture as kqueue transport but using `java.nio.channels.Selector`:
- Selector thread (daemon) replaces kqueue poll loop
- `SocketChannel` replaces fd
- `SelectionKey` interest ops replace kqueue register
- `SSLEngine` wrap/unwrap for TLS

### Tests: NioTransport2Test

**All transport tests from Phase 3** adapted for NIO:
- Connection lifecycle, read/write, concurrent connections, server listen, backpressure, resource cleanup

**NIO-specific:**
- `Selector.wakeup()` on new registrations → no missed events
- SSLEngine handshake (NEED_UNWRAP/NEED_WRAP/NEED_TASK) → completes
- TLS connect to HTTPS host → correct handshake, data encrypted

**Run ALL existing shared + JVM tests** with new backend. Zero regressions.

---

## Phase 8: Delete Old Backends

Remove old transport code and dependencies.

### Native: delete

- `KqueueNativeTransport.scala`, `EpollNativeTransport.scala`
- `NativeTlsStream.scala` (replaced by TlsSession)
- Old `PosixBindings` functions no longer referenced
- `h2o_wrappers.c`, `curl_wrappers.c` (if still present)
- Old native link flags in `build.sbt`

### JVM: delete

- `NettyServerBackend.scala`, `NettyClientBackend.scala`, `NettyServerHandler.scala`, `NettyConnection.scala`
- `FlatNettyHttpHeaders.scala`, `NettyWebSocketFrameHandler.scala`, `NettyWebSocketClientBackend.scala`
- Netty dependencies in `build.sbt`

### Shared: delete

- Old `Transport.scala`, `TransportStream`, `TransportListener`
- Old `Http1Protocol.scala` (including `BufferedStream`, `PrefixedStream`)
- Old `TestTransport.scala`
- Rename `*2` classes: `TransportStream2` → `TransportStream`, etc.

### Tests

Run ALL tests on ALL platforms. Zero regressions.

---

## Phase Summary

| Phase | What | Where | Tests |
|-------|------|-------|-------|
| 1 | Core traits + ByteStream | shared | ~30 unit tests with mock streams |
| 2 | Http1Protocol2 + StreamTestTransport | shared | ~40 protocol roundtrip tests |
| 3 | Kqueue transport | native | ~25 TCP-level tests |
| 4 | HTTP/1.1 over TCP | native | ~15 integration tests |
| 5 | Http1Exchange | shared + native | ~15 Exchange lifecycle tests |
| 6 | Server/Client backends, flip native | shared + native | ALL existing native tests |
| 7 | NIO transport, flip JVM | jvm | ALL existing JVM tests |
| 8 | Delete old code, rename | all | ALL tests, all platforms |

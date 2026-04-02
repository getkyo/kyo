# Transport Design Proposal (Revised)

## Current Problems

1. **`readLine` reads one byte at a time.** `Http1Protocol.readLine` allocates `new Array[Byte](1)` and calls `stream.read(buf)` in a loop. For a 30-byte request line this is 30 async operations instead of one OS read. The entire rest of `readHeaderBlock` reads in 4096-byte chunks, but chunked body parsing (which uses `readLine` for chunk-size headers) is penalised unnecessarily.

2. **Polling wastes CPU and adds latency.** Every read/write does `Async.sleep(1.millis)` + zero-timeout poll in a loop. Minimum 1ms per I/O operation; fibers pile up sleeping.

3. **Per-connection event fds.** 3 fds per connection (socket + readKq + writeKq / readEpfd + writeEpfd). macOS default soft limit 256 → exhausted at ~85 connections.

4. **`stream(connection)` is vestigial.** Returns a thin wrapper that just delegates back to the connection's fd. Adds indirection with no value for HTTP/1.1.

5. **`BufferedStream.pushBack` is a workaround.** `readHeaderBlock` reads past `\r\n\r\n` into body territory, then pushes back the overshoot. The design forces this because `TransportStream.read` is mutable and position-unaware.

6. **Type discipline.** `Chunk[Byte]` boxes each byte. All byte-level transport data must use `Span[Byte]` — an unboxed slice of a byte array.

---

## Key primitives

```
@blocking kqueueWait / epollWaitTimeout
    — Scala Native pins the call to a dedicated OS thread.
      A blocking kernel wait doesn't starve the fiber scheduler.

Promise.init[A, S]
    — fiber suspends on promise.get; OS callback calls promise.unsafe.complete.
      Zero spinning, zero sleeping.

Channel[Span[Byte]] (bounded)
    — MPMC, backpressure via blocking put / non-blocking offer.
      channel.streamUntilClosed returns Stream[Span[Byte], Async].

Stream[Span[Byte], Async]
    — pull-based, each element is one unboxed Span (one OS read worth of data).
      Combinators (splitAt, takeWhile, fold, into) enable protocol parsing
      without mutable byte-by-byte loops.
```

---

## Design A — Event-Loop Group + Array[Byte] API

*N shared event-loop fds (default 2×CPUs). Each connection is assigned to one loop round-robin. A `@blocking` poller fiber per loop completes Promises when fds are ready.*

### Transport API

```scala
trait TransportStream:
    def read(buf: Array[Byte])(using Frame): Int < Async   // unchanged
    def write(data: Span[Byte])(using Frame): Unit < Async

trait Transport:
    type Connection <: TransportStream
    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using Frame)
        : Connection < (Async & Abort[HttpException])
    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(
        handler: Connection => Unit < Async
    )(using Frame): TransportListener < (Async & Scope)
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Sync
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async
```

`Connection <: TransportStream` collapses the vestigial `stream(connection)` call.
`tls: Maybe[TlsConfig]` on both `connect` and `listen` replaces the separate `listenTls`/`connectTls` pair.

### EventLoop

```scala
final class EventLoop(val efd: Int):
    // One map per loop — no contention across loops.
    private val pendingReads  = new ConcurrentHashMap[Int, (Promise.Unsafe[Int, Any], Array[Byte])]()
    private val pendingWrites = new ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]]()
    private val pendingAccept = new ConcurrentHashMap[Int, Promise.Unsafe[Unit, Any]]()

    def awaitRead(fd: Int, buf: Array[Byte])(using Frame): Int < Async =
        Promise.init[Int, Any].map { p =>
            pendingReads.put(fd, (p.unsafe, buf))
            kqueueRegister(efd, fd, EVFILT_READ)   // ONESHOT
            p.get
        }

    def awaitWrite(fd: Int)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { p =>
            pendingWrites.put(fd, p.unsafe)
            kqueueRegister(efd, fd, EVFILT_WRITE)  // ONESHOT
            p.get
        }

    def awaitAccept(fd: Int)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { p =>
            pendingAccept.put(fd, p.unsafe)
            kqueueRegister(efd, fd, EVFILT_READ)   // ONESHOT — accept-ready
            p.get
        }

    /** Runs on a dedicated OS thread (@blocking kqueueWait pins it). */
    def pollLoop()(using Frame): Unit < Async =
        Loop.foreach {
            Zone {
                val outFds    = alloc[CInt](64)
                val outFilter = alloc[CInt](64)
                val n = kqueueWait(efd, outFds, outFilter, 64)  // @blocking, 100ms timeout
                import AllowUnsafe.embrace.danger
                var i = 0
                while i < n do
                    val fd  = outFds(i)
                    val flt = outFilter(i)
                    if flt == EVFILT_READ then
                        val read = pendingReads.remove(fd)
                        if read != null then
                            val (p, buf) = read
                            val n2 = Zone { val ptr = alloc[Byte](buf.length); val r = tcpRead(fd, ptr, buf.length); copyToArray(ptr, buf, r); r }
                            p.completeDiscard(Result.succeed(if n2 == 0 then -1 else n2))
                        else
                            val acc = pendingAccept.remove(fd)
                            if acc != null then acc.completeDiscard(Result.succeed(()))
                    else if flt == EVFILT_WRITE then
                        val write = pendingWrites.remove(fd)
                        if write != null then write.completeDiscard(Result.succeed(()))
                    i += 1
            }
            Loop.continue
        }

object EventLoopGroup:
    def init(n: Int)(using Frame): EventLoopGroup < (Async & Scope) =
        // create n EventLoops, start n pollLoop fibers via Fiber.init (Scope-tracked)
```

### Connection (kqueue)

```scala
final class KqueueConnection(val fd: Int, val loop: EventLoop, ...) extends TransportStream:

    def read(buf: Array[Byte])(using Frame): Int < Async =
        tls match
            case Present(t) => t.read(buf)
            case Absent     => loop.awaitRead(fd, buf)

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else tls match
            case Present(t) => t.write(data)
            case Absent     => writeLoop(data)

    private def writeLoop(data: Span[Byte])(using Frame): Unit < Async =
        loop.awaitWrite(fd).andThen {
            val written = Zone { /* tcpWrite(fd, ptr, len) */ }
            if written < 0 then Abort.panic(HttpProtocolException(s"write failed"))
            else if written < data.size then writeLoop(data.slice(written, data.size))
            else Kyo.unit
        }
```

### Protocol layer stays unchanged

`TransportStream.read(buf)` is the same signature. `Http1Protocol` works as-is — but `readLine` should still be fixed to use a larger buffer internally rather than 1 byte at a time (this is a bug in `readLine`, orthogonal to the transport design).

### TLS

`TlsSession` wraps the raw `loop.awaitRead`/`loop.awaitWrite` for a given fd, performing encrypt/decrypt via OpenSSL memory BIOs or SSLEngine. It implements `read(buf)` and `write(data)` and is stored in `connection.tls`.

### Assessment

| | |
|---|---|
| Fds per connection | 1 (socket only) |
| Event fds total | O(N_loops) = O(2×CPUs) |
| Min I/O latency | ~0ms (event-driven) |
| Protocol layer change | None |
| `readLine` still 1-byte | Yes (bug; fixable independently) |
| Memory per idle connection | Minimal |

**Pros**: Minimal change to protocol layer. Familiar pattern. True event-driven dispatch.
**Cons**: `read(buf: Array[Byte])` API makes it easy to re-introduce the 1-byte bug. `BufferedStream.pushBack` stays. No structural improvement to protocol parsing.

---

## Design B — Channel-Push with Explicit Backpressure

*The poller reads from sockets and pushes `Span[Byte]` into a bounded `Channel` per connection. Consumer signals the poller to resume reading after taking, propagating backpressure back to TCP.*

### Why TCP flow control isn't enough on its own

TCP flow control operates at the OS receive buffer level (typically 128KB–4MB). Without application-level backpressure in a push model, the poller reads as fast as TCP delivers: it calls `tcpRead` after every EPOLLIN/EVFILT_READ event and pushes into the channel. If the consumer (protocol parser or handler) is slow, the channel fills up.

The problem: the poller has already consumed bytes from the OS receive buffer. Those bytes are now in the channel, as heap-allocated `Span[Byte]` objects. The OS buffer is drained, so TCP thinks the receiver is ready and the sender keeps sending. Result: unbounded memory accumulation at the application level before TCP's window update mechanism can slow the sender. Under high load with slow handlers this causes OOM, not graceful backpressure.

The fix: **pause socket reads when the channel is full**. Concretely:
- When `channel.offer(span)` returns false (channel full), do NOT re-register read interest on the fd.
- When the consumer takes from the channel, explicitly re-register read interest.
- This bounds memory to `capacity × avg_span_size` per connection.
- The OS receive buffer naturally fills (the kernel keeps buffering network packets), then TCP's window shrinks, and the sender slows.

This is exactly what Node.js does with `socket.pause()` / `socket.resume()`.

### Architecture

```
IoDispatcher
  ├── efd: Int                          // single shared epoll/kqueue fd
  ├── pollerFiber: Fiber                // @blocking wait loop
  └── conns: ConcurrentHashMap[Int, ConnState]

ConnState
  ├── fd: Int
  ├── channel: Channel[Span[Byte]]      // bounded, e.g. capacity = 8
  ├── paused: AtomicBoolean             // true when channel was full
  └── tls: Maybe[TlsSession]
```

### Poller logic (push + pause)

```scala
// In pollLoop, on EPOLLIN/EVFILT_READ for fd:
val state = conns.get(fd)
if state != null then
    val span = readSpan(state.fd)       // one tcpRead call → one Span[Byte]
    import AllowUnsafe.embrace.danger
    val offered = state.channel.unsafe.offer(span)
    offered match
        case Result.Success(true)  =>
            reregister(efd, state.fd)   // channel has space, re-arm ONESHOT immediately
        case Result.Success(false) =>
            state.paused.set(true)      // channel full — do NOT re-register
            // fd is now "paused": no further EPOLLIN events until consumer resumes
        case Result.Failure(_)     =>   // channel closed (connection closing)
```

### Consumer resumes after take

```scala
class ChannelStream(state: ConnState, dispatcher: IoDispatcher) extends TransportStream:
    private var leftover: Span[Byte] = Span.empty

    // Standard read(buf) backed by channel
    def read(buf: Array[Byte])(using Frame): Int < Async =
        nextSpan.map { span =>
            val n = math.min(buf.length, span.size)
            discard(span.slice(0, n).copyToArray(buf))
            leftover = span.slice(n, span.size)
            n
        }

    private def nextSpan(using Frame): Span[Byte] < Async =
        if leftover.nonEmpty then
            Sync.defer { val s = leftover; leftover = Span.empty; s }
        else
            state.channel.take.map { span =>
                // Resume the socket read if it was paused
                if state.paused.compareAndSet(true, false) then
                    dispatcher.reregister(state.fd)
                span
            }

    def write(data: Span[Byte])(using Frame): Unit < Async = ... // same as Design A

    // Functional access — each element is one Span[Byte], no boxing
    def spans: Stream[Span[Byte], Async] =
        state.channel.streamUntilClosed
```

### TLS in Design B

TLS runs as a transform pipeline at channel construction. Two fibers bridge the encrypted/plaintext layers:

```scala
def connectTls(state: ConnState, cfg: TlsConfig)(using Frame): Unit < Async =
    val rawChannel = state.channel
    Channel.initUnscoped[Span[Byte]](8).map { appChannel =>
        state.channel = appChannel          // swap in plaintext channel
        val ssl = TlsSession.init(cfg)
        ssl.handshake(rawChannel).andThen {
            Fiber.initUnscoped(ssl.decryptLoop(rawChannel, appChannel)).unit
        }
    }
```

`ssl.decryptLoop` takes from `rawChannel` (ciphertext Spans), decrypts, puts into `appChannel` (plaintext Spans). No change to `ChannelStream` — it always sees plaintext.

### Assessment

| | |
|---|---|
| Fds per connection | 1 (socket only) |
| Event fds total | 1 (single dispatcher) — or N for sharding |
| Backpressure | Explicit: pause socket reads when channel full |
| Memory per connection | Bounded: `capacity × avg_span_size` |
| Extra allocations | 1 `Span[Byte]` per OS read (heap slice) |
| Protocol layer change | None (same `read(buf: Array[Byte])`) |
| TLS | Fiber pipeline per TLS connection |

**Pros**: Bounded memory even under slow handlers. Pause/resume semantics explicit and testable. `spans: Stream[Span[Byte], Async]` gives callers functional access for free (it's just `channel.streamUntilClosed`).

**Cons**: Two extra fibers per TLS connection. Single dispatcher is a potential hotspot (fixable by sharding: N dispatchers, same as Design A). `paused` coordination between poller and consumer requires careful `AtomicBoolean` usage. The intermediate Channel buffer exists even when the consumer keeps up — adding latency and allocation where not needed.

---

## Design C — Stream-First API

*`TransportStream` exposes `bytes: Stream[Span[Byte], Async]` instead of `read(buf: Array[Byte])`. Protocol parsing uses a `StreamReader` that works on in-memory Spans — no byte-by-byte async loops.*

### Transport API

```scala
trait TransportStream:
    def bytes: Stream[Span[Byte], Async] // TODO read would be more clear
    def write(data: Span[Byte])(using Frame): Unit < Async

trait Transport:
    type Connection <: TransportStream
    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using Frame)
        : Connection < (Async & Abort[HttpException])
    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(
        handler: Connection => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) // TODO how about returning a stream?
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Sync
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async
```

The underlying event loop is the same as Design A. The difference is only in what `TransportStream` exposes. A `KqueueStream.bytes` is:

```scala
def bytes: Stream[Span[Byte], Async] =
    Stream {
        Loop.foreach {
            loop.awaitSpan(fd).map {           // one @blocking kqueueWait → one Span[Byte]
                case span if span.nonEmpty => Emit.valueWith(span)(Loop.continue)
                case _                    => Loop.done(())   // EOF
            }
        }
    }
```

Each pull on the stream registers EVFILT_READ, suspends via Promise, and delivers one `Span[Byte]` from one OS read. No polling, no sleeping. Backpressure is structural: `bytes` only calls `tcpRead` when the consumer pulls — if the consumer doesn't pull, the OS receive buffer fills, TCP window shrinks.

### StreamReader — the only protocol-layer change

`Http1Protocol` no longer holds a `BufferedStream` subclass. It uses a `StreamReader` that wraps `bytes`:

```scala
final class StreamReader(src: Stream[Span[Byte], Async]):
    private var leftover: Span[Byte] = Span.empty

    /** Read until delimiter. Returns everything before it; leftover holds bytes after. */
    def readUntil(delimiter: Array[Byte])(using Frame): Span[Byte] < (Async & Abort[HttpException]) = // TODO how about we redesign the apis to return a stream?
        val acc = new java.io.ByteArrayOutputStream(1024) // TODO we don't need mutable state I think, use Stream combinators like fold
        Loop.foreach {
            nextSpan.map { span =>
                // search delimiter in span (in-memory, fast)
                indexOf(acc.toByteArray, span.toArrayUnsafe, delimiter) match
                    case -1 =>
                        acc.write(span.toArrayUnsafe)
                        Loop.continue
                    case idx =>
                        val total   = acc.toByteArray ++ span.toArrayUnsafe
                        val end     = idx + delimiter.length
                        leftover    = Span.fromUnsafe(total.drop(end))
                        Loop.done(Span.fromUnsafe(total.take(idx)))
            }
        }

    /** Read one CRLF-terminated line. Works on whole Spans — no byte-by-byte async. */
    def readLine()(using Frame): String < (Async & Abort[HttpException]) = // TODO this could return a stream?
        readUntil(CRLF).map(span => new String(span.toArrayUnsafe, Utf8))

    /** Read exactly n bytes. */
    def readExact(n: Int)(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        val result = new java.io.ByteArrayOutputStream(n)
        Loop.foreach {
            if result.size() >= n then Loop.done(Span.fromUnsafe(result.toByteArray.take(n)))
            else
                nextSpan.map { span =>
                    val need = n - result.size()
                    result.write(span.toArrayUnsafe, 0, math.min(span.size, need))
                    if span.size > need then leftover = span.slice(need, span.size)
                    Loop.continue
                }
        }

    /** The remaining unconsumed bytes as a stream (for streaming body). */
    def remainingStream: Stream[Span[Byte], Async] =
        val lo = leftover
        leftover = Span.empty
        if lo.nonEmpty then
            Stream(Emit.valueWith(lo)(src.emit))
        else
            src

    private def nextSpan(using Frame): Span[Byte] < (Async & Abort[HttpException]) =
        if leftover.nonEmpty then
            Sync.defer { val s = leftover; leftover = Span.empty; s }
        else
            src.runFirst.map {
                case Present(span) => span
                case Absent        => Abort.fail(HttpConnectionClosedException())
            }

private val CRLF      = "\r\n".getBytes(Utf8)
private val CRLF_CRLF = "\r\n\r\n".getBytes(Utf8)
```

### Http1Protocol with StreamReader

```scala
object Http1Protocol extends Protocol:

    def readRequest(stream: TransportStream, maxSize: Int)(using Frame)
        : (HttpMethod, String, HttpHeaders, HttpBody) < (Async & Abort[HttpException]) =
        val reader = new StreamReader(stream.bytes)
        reader.readUntil(CRLF_CRLF).map { headerBytes =>
            val lines = new String(headerBytes.toArrayUnsafe, Utf8).split("\r\n")
            Abort.get(parseRequestLine(lines(0))).map { (method, path) =>
                val headers = parseHeaders(lines, startIndex = 1)
                readBody(reader, headers, maxSize).map { body =>
                    (method, path, headers, body)
                }
            }
        }

    private def readBody(reader: StreamReader, headers: HttpHeaders, maxSize: Int)(using Frame)
        : HttpBody < (Async & Abort[HttpException]) =
        if isChunked(headers) then
            Sync.defer(HttpBody.Streamed(readChunkedStream(reader)))
        else headers.get("Content-Length") match
            case Present(lenStr) =>
                val len = lenStr.trim.toInt
                if len > maxSize then Abort.fail(HttpPayloadTooLargeException(len, maxSize))
                else if len == 0 then Sync.defer(HttpBody.Empty)
                else reader.readExact(len).map(HttpBody.Buffered(_))
            case Absent => Sync.defer(HttpBody.Empty)

    private def readChunkedStream(reader: StreamReader)(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold(reader) { r =>
            r.readLine().map { line =>
                parseChunkHeader(line) match
                    case Result.Success(0)    => r.readLine().andThen(Maybe.empty)
                    case Result.Success(size) =>
                        r.readExact(size).map { data => r.readLine().andThen(Maybe((data, r))) }
                    case _                    => Maybe.empty
            }
        }
```

**Gone**: `BufferedStream`, `PrefixedStream`, `pushBack`, the 1-byte `readLine` loop, `ByteArrayOutputStream` in `readHeaderBlock`. All replaced by `StreamReader` with a single `leftover: Span[Byte]`.

**The readLine improvement concretely**: old `readLine` makes one async round-trip per byte (N round-trips for an N-byte line). New `readLine` calls `readUntil(CRLF)` which calls `nextSpan` at most once per OS read chunk. A typical chunked-encoding size header like `"1a\r\n"` costs 1 async op instead of 4.

### Keep-alive with StreamReader

`StreamReader` is created once per connection, not per request. `Http1Protocol.buffered(stream)` returns a `StreamReader`-backed `TransportStream`. It survives across keep-alive iterations because `leftover` naturally carries bytes between requests.

```scala
// In HttpTransportServer.serveConnection:
val reader = Http1Protocol.buffered(stream)  // creates StreamReader once
Loop.foreach {
    Http1Protocol.readRequest(reader, config.maxContentLength).map { ... }
}
```

`Http1Protocol.buffered` currently returns a `BufferedStream`; under Design C it returns a `StreamReader` wrapper that also implements `TransportStream` for write delegation.

### TLS in Design C

TLS is a stream transform composed at connect time:

```scala
def bytes: Stream[Span[Byte], Async] =
    tls match
        case Present(session) => rawBytes.flatMap(span => session.decrypt(span))
        case Absent           => rawBytes
```

`session.decrypt(span)` returns `Stream[Span[Byte], Async]` — possibly multiple plaintext spans from one ciphertext span (or zero if the TLS record isn't complete yet). `flatMap` handles this naturally. No separate TLS fibers, no extra channels.

### Assessment

| | |
|---|---|
| Fds per connection | 1 (socket only) |
| Event fds total | O(N_loops) = O(2×CPUs) |
| Min I/O latency | ~0ms (event-driven, pull-only-when-consumed) |
| Backpressure | Structural: tcpRead only on stream pull |
| Protocol layer change | `StreamReader` replaces `BufferedStream` + `readLine` fix |
| `readLine` | In-memory search on Spans — N lines ≠ N×len async ops |
| Memory per idle connection | Minimal (just `leftover: Span[Byte]`) |
| TLS | `flatMap` stream transform, no extra fibers |

**Pros**:
- `readLine` is correct: works on whole OS-delivered Spans in memory. No byte-by-byte async loop.
- `BufferedStream.pushBack` is eliminated. The `StreamReader.leftover` field is a single `Span[Byte]` — simpler and typed.
- Structural backpressure: `tcpRead` is only called when the stream is pulled. No intermediate Channel buffer; memory use stays minimal.
- TLS as `flatMap` is clean and testable in isolation.
- `StreamReader.remainingStream` naturally gives the body as a `Stream[Span[Byte], Async]` for streaming responses — no wrapping.
- Chunked body parsing: `readChunkedStream` no longer needs `readLine` to be 1-byte-at-a-time.

**Cons**:
- `Http1Protocol` needs to be rewritten (not a cost concern per the design goals, but it's the only real change needed).
- `StreamReader.readUntil` still accumulates into a `ByteArrayOutputStream` for the header block. This is unavoidable — headers must be fully in memory to parse. The accumulation is bounded by `MaxHeaderSize` (64KB).
- `src.runFirst` inside `StreamReader.nextSpan` needs to be available as a `Stream` method (returns `Maybe[Span[Byte]] < S`). This is essentially `stream.splitAt(1)` — extracting the first element while returning the rest. Looking at Kyo's Stream API, `splitAt(n)` exists and returns `(Chunk[V], Stream[V, S]) < S`. We can use `splitAt(1)` and reassign `src`, or maintain `src` as a `Ref[Stream[Span[Byte], Async]]` inside `StreamReader`.

---

## Revised Comparison

| | Design A | Design B | Design C |
|---|---------|---------|---------|
| **Event-driven (no polling)** | ✅ | ✅ | ✅ |
| **Fds per connection** | 1 | 1 | 1 |
| **Total event fds** | O(N_loops) | 1–N | O(N_loops) |
| **readLine efficiency** | ❌ Still 1-byte unless fixed | ❌ Same unless fixed | ✅ In-memory Span search |
| **BufferedStream / pushBack** | ❌ Stays | ❌ Stays | ✅ Eliminated |
| **Structural backpressure** | 🟡 TCP only | ✅ Pause/resume + TCP | ✅ Pull-only, structural |
| **Memory per idle connection** | Minimal | Channel capacity × span_size | Minimal |
| **TLS integration** | Wrapper read/write | Fiber pipeline | Stream flatMap |
| **Protocol layer simplification** | ❌ None | ❌ None | ✅ Significant |
| **Extra fibers per TLS conn** | 0 | 2 | 0 |
| **Span[Byte] throughout** | ✅ (write only) | ✅ | ✅ |

---

## Recommendation: Design C

Design C is the right design. The goal is "safer, modular, performant, simple" for the **final** code — not for the migration path.

**`readLine` is the clearest win.** The current implementation is objectively wrong: it reads one byte per async round-trip. With `StreamReader`, `readLine` calls `readUntil(CRLF)` which searches for `\r\n` in whole OS-delivered Spans. The protocol parser makes one async operation per OS read, not one per byte.

**`BufferedStream` disappears entirely.** `pushBack` is a workaround for the fact that `TransportStream.read(buf)` has no position semantics. `StreamReader.leftover` is a single `Span[Byte]` that the reader carries naturally. It's smaller, typed, and doesn't require a special subclass.

**Backpressure is structural, not bolted on.** With `bytes: Stream[Span[Byte], Async]`, `tcpRead` is only called when the consumer explicitly pulls the next element. If the handler is slow, the stream simply isn't pulled, the OS receive buffer fills, and TCP flow control kicks in. No `AtomicBoolean`, no pause/resume coordination, no extra Channel buffer.

**TLS as `flatMap` is the cleanest model.** One ciphertext Span → zero or more plaintext Spans is exactly `flatMap`. No extra fibers, no extra Channels, no coordination between encrypt and decrypt fibers.

**Design B's explicit backpressure is unnecessary given Design C's structural backpressure.** Design B is the right model if you want to decouple the I/O poller from the consumer (e.g., to pre-buffer some data or to share a poller across many connections with different consumers). But the extra Channel, the `paused` coordination, and the TLS pipeline fibers add complexity without benefit when the pull model provides backpressure for free.

**Design A vs Design C for the event loop**: they use the same mechanism (N event loops, `@blocking` `kqueueWait`/`epollWaitTimeout`, Promise dispatch). Design C adds only the `bytes: Stream[Span[Byte], Async]` surface, which is a thin wrapper over the same Promise-based `awaitSpan` call. The event loop implementation is identical.

### Implementation plan

1. **`EventLoopGroup`** (shared): N event loops, `@blocking` poller fibers, Promise dispatch. Used by all platform transports.
2. **`Transport` / `TransportStream`** (shared): new API — `bytes: Stream[Span[Byte], Async]`, `write(Span[Byte])`, `connect` with `tls: Maybe[TlsConfig]`, `listen` with `tls: Maybe[TlsConfig]`.
3. **`StreamReader`** (shared): `readUntil`, `readLine`, `readExact`, `remainingStream`. Replaces `BufferedStream` entirely.
4. **`Http1Protocol`** (shared): rewrite using `StreamReader`. Pure parsing functions unchanged. Total rewrite of I/O methods (currently ~200 lines → ~150 lines).
5. **`KqueueNativeTransport`** (Native macOS): `Connection.bytes` backed by `loop.awaitSpan`.
6. **`EpollNativeTransport`** (Native Linux): same.
7. **`NioTransport`** (JVM): `Connection.bytes` backed by NIO Selector event loop.
8. **`JsTransport`** (JS): `Connection.bytes` backed by existing `socket.resume()` / `data` callback Promise pattern — already the right model.

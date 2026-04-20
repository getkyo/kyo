# Execution Plan: `connectRaw` for kyo-http + kyo-pod

## Goal

Add `HttpClient.connectRaw` to kyo-http — a method that sends an HTTP request and upgrades the connection to raw bidirectional byte streaming. Then use it in kyo-pod's `HttpContainerBackend` to implement `execInteractive` and `attach` natively (replacing CLI subprocess fallbacks).

## Key Design Decisions

1. **Follow the `connectWebSocket` pattern** — bypasses connection pool, owns connection lifecycle via `Scope`, uses `ConnectionBackedStream` for I/O.
2. **Docker exec/attach protocol**: POST to `/exec/{id}/start` with `Content-Type: application/vnd.docker.raw-stream` returns a raw multiplexed stream. The connection upgrades to bidirectional byte streaming after the HTTP response headers.
3. **`HttpRawConnection`** is a simple public data class (like `HttpWebSocket`) — `read` stream + `write` function, scoped lifetime.

---

## Phase 1: Add `HttpRawConnection` + `connectRaw` to kyo-http

### Step 1.1: Create `HttpRawConnection.scala` (new public API file)

**File**: `kyo-http/shared/src/main/scala/kyo/HttpRawConnection.scala`

```scala
package kyo

/** Bidirectional raw byte connection obtained by upgrading an HTTP request.
  *
  * The HTTP connection is detached from the pool and becomes a raw byte stream.
  * Closed automatically when the enclosing Scope exits.
  *
  * Used for protocols that upgrade HTTP connections to raw streaming
  * (e.g. Docker exec/attach, CONNECT proxies).
  */
final class HttpRawConnection private[kyo] (
    val read: Stream[Span[Byte], Async],
    val write: Span[Byte] => Unit < Async
)
```

**Rationale**: `final class` not `case class` — no need for pattern matching, and `private[kyo]` constructor prevents external instantiation. Fields are `val` for direct access. Follows `HttpWebSocket` pattern (public class, `private[kyo]` constructor).

### Step 1.2: Add `connectRaw` to `HttpClientBackend`

**File**: `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala`

Add a new method after `connectWebSocket`:

```scala
def connectRaw(
    url: HttpUrl,
    method: HttpMethod,
    body: Span[Byte],
    headers: HttpHeaders,
    connectTimeout: Duration
)(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope)
```

**Implementation** (follows `connectWebSocket` closely):

1. Connect via `transport.connect` / `transport.connectUnix` (same as `connectWebSocket` lines 492-497)
2. Wrap connect timeout via `Async.timeout` (same pattern)
3. Handle `Closed | Timeout` errors (same error mapping)
4. On success:
   a. Create `Http1ClientConnection` from the transport connection's channels
   b. Build the HTTP request: compute host header, call `conn.http1.sendDirect(method, path, headers, body, hostHeader, contentLength, chunked=false)`
   c. Wait for `ParsedResponse` from the IOPromise
   d. Validate status: accept 101 (Switching Protocols), 200 (OK), or any 2xx. Fail with `HttpStatusException` on anything else.
   e. After receiving response headers, the remaining bytes on the inbound channel are raw stream data
   f. Create a `ConnectionBackedStream` wrapping the transport connection
   g. Register `Scope.ensure` to close the connection on scope exit
   h. Return `HttpRawConnection(stream.read, data => stream.write(data))`

**Key difference from WebSocket**: No protocol framing after upgrade — raw bytes flow directly through the connection's inbound/outbound channels. The HTTP/1.1 response parser has already consumed the response headers; any trailing bytes in `conn.http1.lastBodySpan` need to be prepended to the read stream.

**Handling `lastBodySpan`**: After the HTTP response headers are parsed, `conn.http1.lastBodySpan` may contain bytes that arrived in the same read as the headers. These must be emitted first in the read stream before switching to the transport channel stream. Build the stream as:
```scala
val lastBody = conn.http1.lastBodySpan
val rawInbound = connection.inbound.safe.streamUntilClosed()
val readStream =
    if lastBody.isEmpty then rawInbound
    else Stream.init(Seq(lastBody)).concat(rawInbound)
```

### Step 1.3: Add `connectRaw` extension on `HttpClient`

**File**: `kyo-http/shared/src/main/scala/kyo/HttpClient.scala`

Add a new public method in the companion object, after the WebSocket methods section:

```scala
// ==================== Raw connection methods ====================

/** Sends an HTTP request and upgrades the connection to raw bidirectional byte streaming.
  * Used for protocols that upgrade HTTP connections (Docker exec/attach, CONNECT proxies).
  * The connection is removed from the pool and closed when the Scope exits.
  * Fails with HttpStatusException if the server returns a non-2xx/non-101 status.
  */
def connectRaw(
    url: String | HttpUrl,
    method: HttpMethod = HttpMethod.POST,
    body: Span[Byte] = Span.empty,
    headers: HttpHeaders | Seq[(String, String)] = HttpHeaders.empty
)(using Frame): HttpRawConnection < (Async & Abort[HttpException] & Scope) =
    local.use { (client, clientConfig) =>
        resolveUrl(url).map(parsed =>
            client.connectRaw(parsed, method, body, resolveHeaders(headers), clientConfig.connectTimeout)
        )
    }
```

### Step 1.4: Tests for `connectRaw`

**File**: `kyo-http/shared/src/test/scala/kyo/HttpRawConnectionTest.scala` (new file)

Tests require a server that accepts a POST, responds with 200 (or 101), then switches to raw byte streaming. The kyo-http `HttpHandler` doesn't natively support connection hijacking, so tests will use the internal `connectRaw` against a purpose-built handler or test the internal method directly.

**Alternative test approach**: Use `HttpWebSocket.connect`-style local testing — create a raw channel pair, wire them up, and test the `HttpRawConnection` without a real server. This avoids the need for a server that supports connection hijacking.

**Concrete tests**:

1. **`connectRaw constructs read stream from connection inbound`** — Unit test with mock connection channels
2. **`connectRaw write sends bytes to connection outbound`** — Unit test verifying outbound channel receives data
3. **`connectRaw fails on non-2xx/non-101 status`** — Integration test with real server returning 400
4. **`connectRaw connection closed on scope exit`** — Verify the connection is closed when Scope exits

---

## Phase 2: Update kyo-pod — replace CLI fallback with `connectRaw`

### Step 2.1: Implement `execInteractive` via `connectRaw`

**File**: `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala`

Replace the current `Abort.fail(ContainerException.NotSupported(...))` with:

1. Create exec instance via POST `/containers/{id}/exec` (same as `exec` method)
   - Set `AttachStdin = true`, `AttachStdout = true`, `AttachStderr = true`, `Tty = false` in request body
2. Use `HttpClient.connectRaw` to POST to `/exec/{execId}/start` with:
   - `Content-Type: application/vnd.docker.raw-stream`
   - Body: `{"Detach":false,"Tty":false}`
3. The Docker API responds with 200 and the connection becomes a raw multiplexed stream
4. Wrap the `HttpRawConnection` as an `AttachSession`:
   - `read` stream: demux the raw inbound bytes (8-byte docker stream header per frame)
   - `write`: write raw bytes to the outbound (Docker expects raw stdin bytes, no framing when `Tty = false` and stdin is attached)
   - `resize`: POST to `/exec/{execId}/resize?h={height}&w={width}`

**DTO changes**: Update `ExecCreateRequest` to include:
- `AttachStdin: Boolean = false`
- `Tty: Boolean = false`

### Step 2.2: Implement `attach` via `connectRaw`

**File**: `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala`

Replace the current CLI-based `attach` implementation with:

1. Use `HttpClient.connectRaw` to POST to `/containers/{id}/attach`:
   - Query params: `stream=true`, `stdin={stdin}`, `stdout={stdout}`, `stderr={stderr}`
   - `Content-Type: application/vnd.docker.raw-stream`
   - Empty body
2. Docker responds with 200 (or 101 for HTTP/1.1 upgrade) and the connection becomes a raw multiplexed stream
3. Wrap the `HttpRawConnection` as an `AttachSession` (same pattern as `execInteractive`)

### Step 2.3: Stream demuxing helper

Both `execInteractive` and `attach` need streaming demux of Docker's multiplexed stream format (8-byte header per frame). Add a private helper method that transforms `Stream[Span[Byte], Async]` into `Stream[LogEntry, Async]` using the existing `demuxStream` logic but operating on a streaming basis (handling partial frames across chunk boundaries).

**Design**: Use `Loop` with a `GrowableByteBuffer` accumulator that handles the case where a Docker frame spans multiple TCP chunks:

```scala
private def demuxStreamingRaw(
    raw: Stream[Span[Byte], Async]
): Stream[LogEntry, Async] =
    // Buffer partial frames across chunk boundaries
    // Each Docker frame: [streamType:1][0:3][size:4][payload:size]
```

### Step 2.4: Tests for kyo-pod changes

These tests require a running Docker daemon and will go in the existing `ContainerTest.scala`:

5. **`execInteractive with HTTP backend`** — Create container, exec interactive command, verify bidirectional communication
6. **`attach with HTTP backend`** — Create container with stdin open, attach, verify bidirectional communication

---

## Phase 3: Final validation

- Run full kyo-http test suite
- Run full kyo-pod test suite
- Verify no regressions in existing tests

---

## File Inventory

### New files
| File | Description |
|------|-------------|
| `kyo-http/shared/src/main/scala/kyo/HttpRawConnection.scala` | Public API — bidirectional raw byte connection |
| `kyo-http/shared/src/test/scala/kyo/HttpRawConnectionTest.scala` | Tests for `connectRaw` |

### Modified files
| File | Change |
|------|--------|
| `kyo-http/shared/src/main/scala/kyo/HttpClient.scala` | Add `connectRaw` extension method |
| `kyo-http/shared/src/main/scala/kyo/internal/client/HttpClientBackend.scala` | Add `connectRaw` internal implementation |
| `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala` | Replace `execInteractive` NotSupported + `attach` CLI fallback with `connectRaw` |

---

## Risk Assessment

1. **`lastBodySpan` handling**: After HTTP response parsing, the HTTP/1.1 parser may have consumed some post-header bytes into its internal buffer. The `lastBodySpan` must be correctly prepended to the raw stream. This is the same issue handled in `buildBodyStream` for streaming responses.

2. **Docker stream multiplexing**: Docker uses an 8-byte header format for multiplexed streams. The existing `demuxStream` method handles complete byte arrays. For streaming, we need a stateful parser that handles frame headers split across chunk boundaries.

3. **Connection lifecycle**: The raw connection must be properly closed when the `Scope` exits. `Scope.ensure` with `connection.close()` handles this, matching the WebSocket pattern.

4. **HTTP/1.1 parser state**: After reading the response status/headers, the HTTP/1.1 parser's internal state machine must not interfere with raw bytes flowing through the transport channels. Since `Http1ClientConnection` reads from a separate `bodyChannel` (not the raw transport channel), and we'll read from `connection.inbound` directly, this should be fine — but needs careful validation.

5. **Test infrastructure**: Testing `connectRaw` end-to-end requires a server that supports connection hijacking. The kyo-http server doesn't support this natively, so tests may need to use the internal channel-level APIs or mock the connection.

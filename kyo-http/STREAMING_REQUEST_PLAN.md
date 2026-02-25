# Curl Streaming Request Body — Implementation Plan

## Problem

The native curl client doesn't send streaming request bodies. In `CurlClientBackend.configureHandle`, the `onStreaming` branch (line 189) sets URL and headers but ignores the body stream entirely:

```scala
onStreaming = (url, headers, contentType, stream) =>
    // Streaming request bodies not supported via curl on native
    configureCommon(handle, conn, route, url, headers, request.headers, transferId, state)
```

This causes the "streaming request" tests to fail in both `HttpServerTest` and `HttpClientTest` — the server receives an empty body.

## Background: How Streaming Responses Already Work

The existing streaming *response* path is the mirror pattern we need to invert. Here's how it works:

### Data flow (response body FROM server)

1. **Event loop thread** — curl's `writeCallback` (CurlEventLoop2.scala:419) fires with response bytes
2. `onWrite()` (line 311) copies bytes and calls `ss.bodyChannel.offer(Present(span))`
3. If channel is full, returns `CURL_WRITEFUNC_PAUSE` and sets `ss.isPaused = true` (line 331-332)
4. Each event loop iteration calls `checkPausedTransfers()` (line 180) which iterates all transfers, checks if `isPaused && !channel.full()`, and calls `curl_easy_pause(handle, CURLPAUSE_CONT)` to resume
5. **Kyo fiber thread** — the body `Stream` (CurlClientBackend.scala:134-145) reads from the channel via `byteChannel.safe.takeWith`, emitting chunks until `Absent` sentinel

### Key APIs used (Channel.Unsafe)

- `offer(value)` — non-blocking, returns `Result[Closed, Boolean]` (true=accepted, false=full)
- `poll()` — non-blocking, returns `Result[Closed, Maybe[A]]`
- `full()` / `empty()` — non-blocking status checks
- `putFiber(value)` — returns a fiber that completes when the value is accepted (used for `Absent` sentinel at stream end)
- `close()` — closes channel, signals all waiters

### Transfer state classes (CurlTransferState.scala)

```scala
sealed trait CurlTransferState:
    def easyHandle: CurlBindings.CURL
    def headerList: Ptr[Byte]
    def setHeaderList(p: Ptr[Byte]): Unit
    def responseHeaders: StringBuilder
    def statusCode: Int
    def setStatusCode(code: Int): Unit

class CurlBufferedTransferState(promise, easyHandle, host, port)
    // responseBody: ByteArrayOutputStream, responseHeaders: StringBuilder

class CurlStreamingTransferState(headerPromise, bodyChannel, easyHandle, host, port)
    // responseHeaders: StringBuilder, headersCompleted: Boolean, isPaused: Boolean
```

### Curl bindings (CurlBindings.scala)

Already defined:
- `CURLOPT_WRITEFUNCTION = 20011`, `CURLOPT_WRITEDATA = 10001`
- `CURLOPT_HEADERFUNCTION = 20079`, `CURLOPT_HEADERDATA = 10029`
- `CURLOPT_COPYPOSTFIELDS = 10165`, `CURLOPT_POSTFIELDSIZE_LARGE = 30120`
- `CURL_WRITEFUNC_PAUSE: CSize = 0x10000001L`
- `CURLPAUSE_CONT = 0`
- `curl_easy_setopt` overloads for Long, Ptr[Byte], and CFuncPtr4

NOT defined (needed for read callback):
- `CURLOPT_READFUNCTION`, `CURLOPT_READDATA`, `CURLOPT_UPLOAD`
- `CURL_READFUNC_PAUSE`

### Static callback pattern (CurlEventLoop2.scala:398-449)

All curl callbacks are static `CFuncPtr` vals in the companion object. They decode a `transferId` from the userdata pointer, find the owning event loop by iterating `loops`, and delegate to instance methods:

```scala
val writeCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
    CFuncPtr4.fromScalaFunction { (data, size, nmemb, userdata) =>
        val transferId = ptrToLong(userdata)
        // find loop, call loop.onWrite(transferId, data, totalSize)
    }
```

### Event loop lifecycle (CurlEventLoop2.scala:112-163)

```
while running:
    drainRequestQueue()      // add new curl handles from Scala threads
    checkPausedTransfers()   // unpause if channel conditions changed
    poll(socketMap + selfPipe, timeoutMs)
    for each ready socket: curl_multi_socket_action()
    processCompletedTransfers()  // complete promises, cleanup
```

### Curl wrappers (curl_wrappers.c)

Fixed-signature C wrappers for variadic curl functions (ARM64 calling convention):
- `kyo_curl_easy_setopt_long(handle, option, long_param)`
- `kyo_curl_easy_setopt_ptr(handle, option, void_ptr_param)`
- `kyo_curl_easy_getinfo_ptr(handle, info, void_ptr_out)`
- `kyo_curl_multi_setopt_ptr(multi, option, void_ptr_param)`

The existing `kyo_curl_easy_setopt_ptr` wrapper handles function pointers (CFuncPtr) since they're pointer-sized.

## Design: Streaming Request Bodies

### Architecture

Mirror the response streaming pattern:

```
Kyo fiber (stream producer)         Event loop thread (curl consumer)
           |                                    |
  Stream[Span[Byte]]                   readCallback fires
           |                                    |
     drain chunks                     onRead(transferId, buf, max)
           |                                    |
  channel.putFiber(Present(span))     channel.poll()
           |                              /          \
     eventLoop.wakeUp()         Present(data)     empty
           |                        |                |
           |                  copy to buf    CURL_READFUNC_PAUSE
           |                  return count   set isPaused=true
           |                                        |
  channel.putFiber(Absent)            checkPausedTransfers()
     [stream ended]                 if !empty: CURLPAUSE_CONT
```

### Partial chunk handling

curl's read callback requests N bytes at a time. A stream chunk might be larger than N. We need a buffer with an offset to handle partial reads:

```
readCallback called with maxSize=4096
currentChunk = [10000 bytes], offset = 0
  → copy 4096 bytes, offset = 4096, return 4096

readCallback called again with maxSize=4096
currentChunk = [10000 bytes], offset = 4096
  → copy 4096 bytes, offset = 8192, return 4096

readCallback called again with maxSize=4096
currentChunk = [10000 bytes], offset = 8192
  → copy 1808 bytes, currentChunk = null, offset = 0, return 1808
  → next call will poll channel for new chunk
```

### CURLOPT_UPLOAD and method override

`CURLOPT_UPLOAD = 1` tells curl to use the read callback for the request body. Internally curl switches to PUT semantics, but `CURLOPT_CUSTOMREQUEST` (already set in `configureCommon`) overrides the HTTP method on the wire. This is the standard curl pattern.

`CURLOPT_UPLOAD` causes curl to add `Expect: 100-continue` by default. We suppress this by adding an empty `Expect:` header to avoid a round-trip delay.

Without `CURLOPT_INFILESIZE_LARGE`, curl uses `Transfer-Encoding: chunked` automatically.

### CURLPAUSE_CONT unpauses both directions

`CURLPAUSE_CONT = 0` unpauses both read and write. This is fine because:
- A transfer either has a streaming response body OR a streaming request body (HTTP doesn't do bidirectional streaming within one request/response)
- Even if both were paused, unpausing both is correct — each will re-pause independently if their channel condition hasn't changed

## Implementation

### 1. `CurlBindings.scala` — Add constants

```scala
inline val CURLOPT_READFUNCTION = 20012
inline val CURLOPT_READDATA     = 10009
inline val CURLOPT_UPLOAD       = 46
val CURL_READFUNC_PAUSE: CSize  = 0x10000001L.toCSize
```

### 2. `CurlTransferState.scala` — Add read state

New class to hold streaming request body state:

```scala
final class CurlReadState(val channel: Channel.Unsafe[Maybe[Span[Byte]]]):
    var currentChunk: Array[Byte] = null
    var offset: Int = 0
    @volatile var isPaused: Boolean = false
```

Add to the `CurlTransferState` trait:

```scala
sealed trait CurlTransferState:
    // ... existing ...
    var readState: CurlReadState  // null when no streaming request body
```

Initialize to `null` in both `CurlBufferedTransferState` and `CurlStreamingTransferState`.

### 3. `CurlEventLoop2.scala` — Add read callback + unpause logic

**Static callback** in companion object (same pattern as writeCallback):

```scala
val readCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
    CFuncPtr4.fromScalaFunction { (buffer, size, nmemb, userdata) =>
        val maxSize = size * nmemb
        val transferId = ptrToLong(userdata)
        // find loop, call loop.onRead(transferId, buffer, maxSize)
    }
```

**Instance method** `onRead(transferId, buffer, maxSize): CSize`:

```
1. Get transfer state, get readState
2. If readState.currentChunk has remaining bytes:
   - Copy min(remaining, maxSize) bytes to buffer
   - Advance offset, clear chunk if exhausted
   - Return bytes copied
3. Else poll channel:
   - Present(span): set as currentChunk, recurse/copy from it
   - Absent: return 0 (EOF, stream ended)
   - Empty (no data yet): set isPaused=true, return CURL_READFUNC_PAUSE
   - Closed: return 0 (EOF)
```

**Update `checkPausedTransfers()`** to also handle read pauses:

```scala
// Existing: response write pause
case st: CurlStreamingTransferState if st.isPaused =>
    if !st.bodyChannel.full() then unpause

// New: request read pause
val rs = entry.getValue.readState
if rs != null && rs.isPaused then
    rs.channel.empty() match
        case Result.Success(false) =>
            rs.isPaused = false
            curl_easy_pause(entry.getValue.easyHandle, CURLPAUSE_CONT)
        case _ => ()
```

**Update `cleanupTransfer()`** to close read channel:

```scala
if state.readState != null then
    discard(state.readState.channel.close())
```

### 4. `CurlClientBackend.scala` — Wire up streaming request body

In `configureHandle`, replace the `onStreaming` branch:

```scala
onStreaming = (url, headers, contentType, stream) =>
    configureCommon(handle, conn, route, url, headers, request.headers, transferId, state)

    // Headers
    var headerList = state.headerList
    headerList = curl_slist_append(headerList, toCString(s"Content-Type: $contentType"))
    headerList = curl_slist_append(headerList, toCString("Expect:"))  // suppress 100-continue
    state.setHeaderList(headerList)
    if headerList != null then
        discard(curl_easy_setopt(handle, CURLOPT_HTTPHEADER, headerList))

    // Set up read callback for streaming body
    val readChannel = Channel.Unsafe.init[Maybe[Span[Byte]]](8)
    state.readState = new CurlReadState(readChannel)

    discard(curl_easy_setopt(handle, CURLOPT_UPLOAD, 1L))
    discard(curl_easy_setopt(handle, CURLOPT_READFUNCTION, CurlEventLoop2.readCallback))
    val idPtr = fromRawPtr[Byte](Intrinsics.castLongToRawPtr(transferId))
    discard(curl_easy_setopt(handle, CURLOPT_READDATA, idPtr))

    // Launch fiber to drain stream into channel
    discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
        Scope.run {
            Abort.run[Closed] {
                stream.foreach { span =>
                    readChannel.safe.put(Present(span))
                }.andThen {
                    readChannel.safe.put(Absent)  // EOF sentinel
                }
            }.unit
        }.map(_ => eventLoop.wakeUp())
    }))
```

Note: After each chunk is put, the `safe.put` suspends the fiber if the channel is full (backpressure). After the fiber completes (stream exhausted or error), we call `wakeUp()` to trigger `checkPausedTransfers`.

Actually, we need `wakeUp()` after each put, not just at the end, because the read callback might be paused waiting for data. We should wrap the stream drain to wakeup after each chunk:

```scala
stream.foreach { span =>
    readChannel.safe.put(Present(span)).andThen(Sync.defer(eventLoop.wakeUp()))
}.andThen {
    readChannel.safe.put(Absent).andThen(Sync.defer(eventLoop.wakeUp()))
}
```

## Safety Analysis

| Concern | Mitigation |
|---------|-----------|
| Read callback blocks event loop | `channel.poll()` is non-blocking; returns PAUSE if empty |
| Stream errors | Fiber catches exceptions; channel close → read callback returns 0 (EOF) |
| Transfer completes before stream drained | `cleanupTransfer` closes channel → fiber's `put` fails with Closed |
| Memory: currentChunk outlives callback | Array[Byte] is heap-allocated, lives until next chunk replaces it |
| Thread safety of currentChunk/offset | Only accessed from read callback, which runs on event loop thread |
| Concurrent read + write pause | CURLPAUSE_CONT=0 unpauses both; each re-pauses independently |
| Expect: 100-continue delay | Suppressed with empty `Expect:` header |

## Verification

```bash
# The specific failing test
sbt 'kyo-httpNative/testOnly kyo.http2.HttpServerTest -- -z "streaming request"'

# All streaming tests
sbt 'kyo-httpNative/testOnly kyo.http2.HttpClientTest -- -z "streaming"'

# Full regression
sbt 'kyo-httpNative/testOnly kyo.http2.HttpServerTest kyo.http2.HttpClientTest kyo.http2.internal.*'
```

Expected: streaming request test passes (was failing with empty body), no regressions.

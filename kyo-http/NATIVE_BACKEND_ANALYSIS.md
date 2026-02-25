# Native HTTP Backend Analysis

## Current State

### Client: CurlClientBackend + CurlEventLoop2

The curl client uses `curl_multi_socket_action` with a single event loop thread driving all transfers via `posix_poll`. Kyo threads enqueue transfers through a `ConcurrentLinkedQueue` + self-pipe wakeup. Promises bridge the async gap — fibers await response completion without blocking OS threads.

This design is fundamentally sound. Issues are localized:

- **Callback routing**: `writeCallback`, `readCallback`, `headerCallback` iterate all `CurlEventLoop2` instances to find which loop owns a transfer. Works because there's typically 1 instance, but inconsistent with `socketCallback`/`timerCallback` which route via `userp` directly.
- **Pause polling**: `checkPausedTransfers()` iterates every transfer on every event loop cycle to check if paused transfers can resume. Should be a targeted queue of transfers needing resume.
- **Connection pool is dead weight**: `Conn` is a stateless token (`isAlive` returns `true`, `closeNowUnsafe` is a no-op). The lock-free MPMC ring buffer in `ConnectionPool` runs but accomplishes nothing — curl_multi manages TCP/TLS connections internally.
- **Minor allocations**: `Zone` allocation in `wakeUp()` for writing 1 byte to a pipe. Double body copy via `stackalloc` + `CURLOPT_COPYPOSTFIELDS`.

### Server: MhdServerBackend

The server has fundamental design problems that limit it to processing one request at a time.

**Architecture**: MHD runs with `MHD_USE_THREAD_PER_CONNECTION`. A C bridge (`mhd_wrappers.c`, ~300 lines) buffers complete requests and enqueues them via a pthread mutex-protected linked list. A single Scala poll thread dequeues requests every 50ms, routes them, invokes handlers, and sends responses.

**Critical issues**:

1. **Single poll thread serializes all handlers**. The flow is: dequeue → route → launch fiber → `CountDownLatch.await()` → encode response → send. Every request blocks the only dispatch thread waiting for the handler to complete. If a handler takes 100ms, throughput caps at ~10 req/s regardless of available cores.

2. **Streaming responses are fully buffered**. `materializeStream()` launches a fiber to drain the entire `Stream[Span[Byte]]` into a `ByteArrayOutputStream`, then blocks on a second `CountDownLatch` waiting for it to finish. A 1GB streaming response loads entirely into memory.

3. **Streaming requests are fake**. The C bridge buffers the complete request body before enqueuing. The body is then wrapped in a single-chunk `Stream`. There is no incremental delivery.

4. **50ms poll latency**. `pollRequest(server, 50)` means up to 50ms delay for the first request after idle.

5. **Complex C bridge**. `mhd_wrappers.c` implements a full request queue with `pthread_mutex`, `pthread_cond`, dynamic body buffer growth, 128-header cap, and response synchronization — all to paper over the mismatch between MHD's synchronous callback model and Kyo's async fiber model.

## Why the Mismatch Exists

MHD's `access_handler` callback must return the response synchronously. The callback runs on an MHD-owned native thread (not GC-registered, can't run Scala). So the current design:

1. Buffers the request entirely in C (because MHD delivers body in chunks across multiple callback invocations)
2. Enqueues to a C-level queue (because MHD threads can't call Scala)
3. Blocks the MHD thread on `pthread_cond` (because the response must be returned from the callback)
4. Polls from a Scala thread (because we need a GC-registered thread)
5. Blocks the Scala thread on `CountDownLatch` (because MHD needs the response before it can proceed)

Every design choice follows from the previous constraint. The root cause is that MHD's threading model is incompatible with async request handling.

## Proposed Design: Replace MHD with libh2o

[h2o](https://github.com/h2o/h2o) is an HTTP server written in C with an embeddable library (libh2o). Its model is natively async:

- Handler returns `0` ("I'll respond later") — no blocking required
- Responses sent via `h2o_send()` at any later point on the event loop thread
- Streaming responses use pull-based generators (`proceed`/`stop` callbacks)
- Single-threaded event loop, no thread-per-connection
- Built-in HTTP/2 and HTTP/3

The key insight: **h2o's async model matches Kyo's fiber model with zero friction**. The current MHD bridge exists entirely to bridge a model mismatch that h2o doesn't have.

### Architecture

```
h2o event loop thread                    Kyo executor threads
──────────────────────                    ────────────────────
accept connection
parse HTTP (h2o handles this)
handler CFuncPtr fires
  extract method/path/headers/body
  router.find(method, path)
  if not found → h2o_send 404 immediately
  else → launch Kyo fiber ───────────→  fiber runs user handler
         return 0 (async)               RouteUtil.encodeResponse(...)
                                        enqueue response to ConcurrentLinkedQueue
                                        write 1 byte to response pipe
                                          │
pipe-readable callback fires  ←───────────┘
  drain response queue
  for each: h2o_send(req, body, FINAL)
```

### C Bridge

The C bridge is minimal because h2o handles HTTP parsing, connection management, keep-alive, and the event loop internally. The bridge handles server lifecycle, the listener socket, and the response pipe:

```c
typedef struct {
    h2o_globalconf_t config;
    h2o_context_t ctx;
    h2o_accept_ctx_t accept_ctx;
    h2o_socket_t *listener;
    h2o_socket_t *response_sock;   // response pipe registered with evloop
    int response_pipe[2];          // [0]=h2o reads, [1]=Scala writes
    int actual_port;               // from getsockname() after bind
    volatile int running;
} kyo_h2o_server;

// Lifecycle
kyo_h2o_server* kyo_h2o_start(int port, const char *host,
                               int max_body_size, int backlog);
void kyo_h2o_run(kyo_h2o_server* s);       // blocks, runs event loop
void kyo_h2o_stop(kyo_h2o_server* s);
int  kyo_h2o_port(kyo_h2o_server* s);
int  kyo_h2o_response_fd(kyo_h2o_server* s); // write end of pipe for Scala
```

Handler registration and request data extraction happen in Scala via `CFuncPtr` callbacks (same proven pattern as the curl client's `writeCallback`/`readCallback`). The C bridge creates the server, binds the socket, and registers the response pipe with h2o's event loop.

No request queue. No pthread_mutex. No pthread_cond. No body buffer management. No response synchronization. h2o handles all of it.

### Scala Backend

```scala
class H2oServerBackend extends HttpBackend.Server:
    def bind(handlers, config) =
        val router = HttpRouter(handlers)
        val server = H2oBindings.start(config.port, config.host,
                                        config.maxContentLength, config.backlog)
        val responsePipeFd = H2oBindings.responseFd(server)
        val responseQueue = new ConcurrentLinkedQueue[PendingResponse]()

        // The handler CFuncPtr runs on h2o's event loop thread (a Java Thread,
        // GC-registered). It extracts request data, routes, and launches a fiber.
        H2oBindings.setHandler(server,
            onRequest(router, responseQueue, responsePipeFd))

        // Register response pipe with h2o's evloop. When a fiber writes to
        // the pipe, h2o wakes up and our callback drains the response queue.
        H2oBindings.registerResponseCallback(server,
            () => drainResponses(responseQueue))

        // Start event loop
        val thread = new Thread(() => H2oBindings.run(server), "kyo-h2o-evloop")
        thread.setDaemon(true)
        thread.start()

        new Binding { port = H2oBindings.port(server); ... }
```

### Request Handling

The `CFuncPtr` handler runs on h2o's event loop thread. Since we create this thread as a `java.lang.Thread`, it's GC-registered and can call Scala/Kyo APIs directly:

```scala
private def onRequest(router, responseQueue, pipeFd)(h2oReq: Ptr[H2oReq]): CInt =
    val method = extractMethod(h2oReq)
    val path = extractPath(h2oReq)

    router.find(method, path) match
        case Result.Success(routeMatch) =>
            val headers = extractHeaders(h2oReq)
            val body = extractBody(h2oReq)

            // Streaming request routes: wrap buffered body in single-chunk stream
            val decoded =
                if routeMatch.isStreamingRequest then
                    val bodyStream = if body.isEmpty then Stream.empty
                                     else Stream(Emit.value(Chunk(body)))
                    RouteUtil.decodeStreamingRequest(route, captures, query,
                                                     headers, bodyStream, path)
                else
                    RouteUtil.decodeBufferedRequest(route, captures, query,
                                                    headers, body, path)

            decoded match
                case Result.Success(request) =>
                    launchFiber {
                        val response = handler(request)
                        encodeAndEnqueue(response, responseQueue, pipeFd, h2oReq)
                    }
                    0
                case Result.Failure(_) =>
                    sendImmediate(h2oReq, 400)
                    0
                case Result.Panic(_) =>
                    sendImmediate(h2oReq, 500)
                    0

        case Result.Failure(FindError.NotFound) =>
            sendImmediate(h2oReq, 404)
            0

        case Result.Failure(FindError.MethodNotAllowed(allowed)) =>
            sendImmediate(h2oReq, 405, allowHeader(allowed))
            0
```

### Response Delivery

When the response pipe becomes readable, h2o's event loop calls our drain callback:

```scala
private def drainResponses(queue: ConcurrentLinkedQueue[PendingResponse]): Unit =
    var resp = queue.poll()
    while resp != null do
        resp match
            case Buffered(h2oReq, status, headers, body) =>
                setStatus(h2oReq, status)
                setHeaders(h2oReq, headers)
                h2oSend(h2oReq, body, H2O_SEND_STATE_FINAL)
            case StreamStart(h2oReq, status, headers, streamCtx) =>
                setStatus(h2oReq, status)
                setHeaders(h2oReq, headers)
                h2oStartResponse(h2oReq, streamCtx.generator)
                // Send first chunk if already available
                streamCtx.tryDeliver()
            case StreamChunk(streamCtx) =>
                streamCtx.tryDeliver()
        resp = queue.poll()
```

### Streaming Response Backpressure

h2o's generator model is pull-based — h2o calls `proceed` when it's ready for more data. Combined with the fiber pushing chunks through a queue:

```scala
sealed trait StreamState
case object ReadyForData extends StreamState       // proceed was called, waiting for chunk
case object WaitingForProceed extends StreamState   // chunk sent, waiting for h2o

class StreamContext(h2oReq: Ptr[H2oReq], responseQueue, pipeFd):
    val generator: Ptr[H2oGenerator] = ... // allocated with h2o_mem_alloc_shared
    @volatile var state: StreamState = ReadyForData
    val chunkQueue = new ConcurrentLinkedQueue[(Span[Byte], Boolean)]() // (data, isFinal)

    // Called by h2o event loop via generator.proceed
    def onProceed(): Unit =
        val chunk = chunkQueue.poll()
        if chunk != null then
            val (data, isFinal) = chunk
            h2oSend(h2oReq, data, if isFinal then FINAL else IN_PROGRESS)
            state = WaitingForProceed
        else
            state = ReadyForData

    // Called on event loop thread when drain callback processes a StreamChunk
    def tryDeliver(): Unit =
        if state == ReadyForData then
            val chunk = chunkQueue.poll()
            if chunk != null then
                val (data, isFinal) = chunk
                h2oSend(h2oReq, data, if isFinal then FINAL else IN_PROGRESS)
                state = WaitingForProceed

    // Called by Kyo fiber (any thread) to enqueue a chunk
    def enqueueChunk(data: Span[Byte], isFinal: Boolean): Unit =
        chunkQueue.add((data, isFinal))
        wakeEventLoop(pipeFd) // will trigger drainResponses → tryDeliver
```

No channels. No pause/resume. No `Channel.Unsafe`. Just a queue and a two-state flag. The `stop` callback on the generator handles client disconnection by closing the chunk queue.

### Request Body Streaming

h2o buffers the entire request body in `req->entity` (configurable via `max_request_entity_size`; default 1GB). This is a [known limitation](https://github.com/h2o/h2o/issues/1253) — true streaming request bodies are not supported. For routes that declare streaming request bodies, we wrap the buffered body in a single-chunk stream, identical to current MHD behavior. This is acceptable for now.

### SSE and NDJSON Streaming

Server-Sent Events and NDJSON streaming are response streaming features. They work through the same generator mechanism:

1. `RouteUtil.encodeResponse` produces `onStreaming` with a `Stream[Span[Byte], Async & Scope]`
2. The fiber drains the stream, enqueuing each chunk via `StreamContext.enqueueChunk()`
3. The event loop delivers chunks to the client via `h2o_send()`

The content type (`text/event-stream` for SSE, `application/x-ndjson` for NDJSON) is set in the response headers by `RouteUtil.encodeResponse`. h2o doesn't need to know about the encoding — it just sends bytes.

### Keep-Alive

h2o handles HTTP/1.1 keep-alive natively. No configuration needed on our side — it follows the HTTP spec by default. The `config.keepAlive` flag can be mapped to h2o's connection timeout settings if we want to disable it.

### Port 0 (Random Port Assignment)

h2o examples hardcode ports. We need dynamic assignment for tests (`config.port = 0`). The C bridge handles this:

```c
// In kyo_h2o_start():
bind(fd, &addr, sizeof(addr));
// Get actual port after bind
struct sockaddr_in bound_addr;
socklen_t len = sizeof(bound_addr);
getsockname(fd, (struct sockaddr*)&bound_addr, &len);
server->actual_port = ntohs(bound_addr.sin_port);
```

Standard POSIX pattern, same as any server supporting port 0.

### Graceful Shutdown

h2o's `h2o_evloop_destroy()` [was never properly implemented for library use](https://github.com/h2o/h2o/issues/899). We handle shutdown ourselves:

1. Close the listener socket (stop accepting new connections)
2. Track in-flight requests with an `AtomicInteger` counter (increment in handler, decrement on response send)
3. Wait up to `gracePeriod` for in-flight count to reach 0
4. Break the event loop (`running = false`, wake via pipe)
5. Call `h2o_context_dispose()` for cleanup
6. Join the event loop thread

This is straightforward and gives us more control than relying on h2o's shutdown anyway.

### Error Handling

| Condition | Handling |
|-----------|----------|
| Route not found | 404 — respond immediately on event loop thread |
| Method not allowed | 405 with `Allow` header — respond immediately |
| Request decode failure | 400 — respond immediately |
| Handler exception/panic | 500 — fiber catches, enqueues error response |
| Handler returns Abort.fail | Map to appropriate status (HttpResponse.Halt → custom status, other → 500) |
| Client disconnects mid-stream | h2o calls generator `stop` callback → cleanup StreamContext |
| Request body too large | h2o enforces `max_request_entity_size` → returns 413 automatically |

## Thread Blocking Analysis

### Current (MHD)

| Thread | Blocks on | Duration |
|--------|-----------|----------|
| MHD thread (per connection) | `pthread_cond_wait` for response | Entire request lifetime |
| Scala poll thread | `CountDownLatch.await()` per handler | Handler execution time |
| Scala poll thread | `CountDownLatch.await()` per stream materialization | Stream drain time |

Total: 2 threads blocked per request (1 MHD + 1 Scala).

### Proposed (h2o)

| Thread | Blocks on | Duration |
|--------|-----------|----------|
| h2o event loop | `h2o_evloop_run()` (poll/epoll) | Only when idle — wakes on any I/O or pipe signal |
| Kyo fiber | Nothing — runs on executor | N/A |

Total: 0 threads blocked per request. The event loop thread is never blocked waiting for application logic. Kyo fibers run on the shared executor without blocking OS threads.

## Feature Coverage Matrix

Every feature the server must support, and how it maps to h2o:

| Feature | Current (MHD) | Proposed (h2o) |
|---------|--------------|----------------|
| GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS | Via router | Via router (unchanged) |
| Path captures & rest captures | Via router | Via router (unchanged) |
| Query parameter extraction | Via RouteUtil | Via RouteUtil (unchanged) |
| Request header extraction | C accessor functions | Direct from `h2o_req_t.headers` |
| JSON request body | Buffered decode | Buffered decode (unchanged) |
| Text/binary request body | Buffered decode | Buffered decode (unchanged) |
| Form data (urlencoded) | Buffered decode | Buffered decode (unchanged) |
| Multipart (buffered) | Buffered decode | Buffered decode (unchanged) |
| Multipart (streaming) | Fake (pre-buffered in C) | Fake (pre-buffered by h2o) |
| Byte stream request body | Fake (single chunk) | Fake (single chunk) |
| NDJSON request stream | Fake (single chunk) | Fake (single chunk) |
| JSON response body | RouteUtil encode → send | RouteUtil encode → h2o_send (unchanged) |
| Text/binary response body | RouteUtil encode → send | RouteUtil encode → h2o_send (unchanged) |
| Byte stream response | **Fully materialized** | **True streaming via generator** |
| NDJSON response stream | **Fully materialized** | **True streaming via generator** |
| SSE (Server-Sent Events) | **Fully materialized** | **True streaming via generator** |
| Response headers | Set via C bridge | Set via `h2o_add_header()` |
| Response cookies (Set-Cookie) | Via response headers | Via response headers (unchanged) |
| Status codes (all) | Via C bridge | Via `h2o_req_t.res.status` |
| 404 / 405 / 400 / 500 errors | Via router + handler | Via router + handler (unchanged) |
| 413 payload too large | Manual check in C | **h2o enforces automatically** |
| Keep-alive | Manual in C bridge | **h2o handles natively** |
| Port 0 (random) | Via `MhdBindings.serverPort()` | Via `getsockname()` after bind |
| Backlog | Passed to MHD | Passed to `listen()` in C bridge |
| Graceful shutdown | Stop flag + thread join | Listener close + in-flight drain + thread join |
| Concurrent requests | **1 at a time** | **Unlimited (fiber per request)** |
| HTTP/2 | **No** | **Yes (built-in)** |

## Migration Scope

### Files to Delete

| File | Lines | Purpose |
|------|-------|---------|
| `native/.../resources/scala-native/mhd_wrappers.c` | ~300 | Request queue, body buffering, pthread sync, response delivery |
| `native/.../kyo/internal/MhdBindings.scala` | ~60 | Scala Native extern bindings to C bridge |
| `native/.../kyo/http2/internal/MhdServerBackend.scala` | ~320 | Poll thread, CountDownLatch blocking, stream materialization |

### Files to Create

| File | Purpose |
|------|---------|
| `native/.../resources/scala-native/h2o_wrappers.c` | Server lifecycle, listener socket, accept callback, response pipe integration |
| `native/.../kyo/internal/H2oBindings.scala` | Scala Native `@extern` bindings for h2o wrapper functions |
| `native/.../kyo/http2/internal/H2oServerBackend.scala` | Handler callback, response queue drain, streaming state machine |

### Files to Modify

| File | Change |
|------|--------|
| `native/.../kyo/http2/internal/HttpPlatformBackend.scala` | `new MhdServerBackend` → `new H2oServerBackend` |
| `native/.../test/.../MhdServerBackendTest.scala` | Rename, update type assertions |
| Build config (sbt/mill) | Link `libh2o-evloop` instead of `libmicrohttpd` |

### Files Unchanged

- `HttpBackend.Server` trait — same `bind()` signature
- `HttpServer.scala` — delegates to backend
- `HttpRouter` — same `find()` API
- `RouteUtil` — same decode/encode signatures
- `ConnectionPool` — used by client (unrelated)
- All shared tests — exercise server through `HttpBackend.Server` interface
- All client code — `CurlClientBackend`, `CurlEventLoop2`, `CurlTransferState`

## Client Improvements

Independent of the server migration, these improve the curl client:

### 1. Skip Connection Pool for Curl Backend

Add a flag to `HttpBackend.Client`:
```scala
trait Client:
    def pooled: Boolean = true // curl overrides to false
```
`HttpClient.poolWith()` skips pool operations when `false`. One `if` in one method, zero overhead for curl.

### 2. Event-Driven Pause Resume

Replace `checkPausedTransfers()` (O(all transfers) per loop cycle) with a `ConcurrentLinkedQueue[Long]` of transfer IDs needing resume. When a streaming consumer frees channel space, it enqueues the transfer ID and calls `wakeUp()`. The event loop drains this queue — O(resumed) instead of O(all).

### 3. Direct Callback Routing

Pack `(loopId, transferId)` into callback userdata pointers. Use high 32 bits for loopId, low 32 bits for transferId. Each callback does one `ConcurrentHashMap.get` instead of iterating all loop instances.

## Risks and Mitigations

### No prior Scala Native + libh2o integration

Nobody has used h2o from Scala Native before. The Scala Native FFI (`@extern`, `CFuncPtr`, `Zone`, `Ptr`) is well-proven with curl and MHD in this same codebase. h2o's C API follows the same patterns (function pointers for callbacks, struct pointers for state). Risk is low — the FFI layer is the same, only the library differs.

### h2o evloop shutdown not designed for library use

Mitigated by implementing shutdown ourselves (listener close → drain in-flight → break loop → dispose context). This gives better control and is ~15 lines of code.

### h2o availability as a system package

- macOS: `brew install h2o` ✓
- Debian/Ubuntu: `apt install libh2o-evloop-dev` ✓
- Alpine/other: may need to build from source
- Same situation as libcurl — it's a C library that needs to be installed

### h2o_send buffer lifetime

Buffers passed to `h2o_send()` must remain valid until the `proceed` callback fires. For buffered responses this is trivial (send and forget). For streaming, each chunk's byte array must not be GC'd until h2o is done with it. Since we copy response bytes into native memory (via `Zone` or `malloc`) before calling `h2o_send()`, this is safe.

### CFuncPtr on h2o event loop thread

The h2o handler callback is a C function pointer registered with h2o. It fires on the event loop thread (a `java.lang.Thread` we create). Scala Native's `CFuncPtr` callbacks already work this way for curl (callbacks fire on the curl event loop thread). Same mechanism, same guarantees.

---

## Implementation Progress

- [x] Phase 1: C Bridge (`h2o_wrappers.c`) — created, ~280 lines
- [x] Phase 2: Scala Bindings (`H2oBindings.scala`) — created, ~120 lines
- [x] Phase 3: Server Backend (`H2oServerBackend.scala`) — created, ~380 lines
- [x] Phase 4: Streaming Generator Callbacks — integrated into Phase 1 & 3
- [x] Phase 5: Build Configuration — `-lh2o-evloop` replaces `-lmicrohttpd`
- [x] Phase 6: Platform Backend Wiring — `HttpPlatformBackend` updated, test updated
- [ ] Phase 7: Client Improvements
- [x] Phase 8: All tests pass (2091/2092 — 1 unrelated curl client failure)

### Files Created
- `native/.../resources/scala-native/h2o_wrappers.c`
- `native/.../kyo/internal/H2oBindings.scala`
- `native/.../kyo/http2/internal/H2oServerBackend.scala`
- `native/.../test/.../H2oServerBackendTest.scala`

### Files Modified
- `native/.../kyo/http2/internal/HttpPlatformBackend.scala` — server = H2oServerBackend
- `build.sbt` — `-lh2o-evloop` replaces `-lmicrohttpd`

### Files Deleted
- `native/.../resources/scala-native/mhd_wrappers.c`
- `native/.../kyo/internal/MhdBindings.scala`
- `native/.../kyo/http2/internal/MhdServerBackend.scala`
- `native/.../test/.../MhdServerBackendTest.scala`
- `native/.../test/.../MhdBridgeTest.scala`

### Resolved Issues
- `h2o_handler_t.data` field: uses global `g_server` pointer instead (one server per process)
- `h2o_add_header_by_str`: verified correct API signature
- `h2o_mem_alloc_pool`: h2o 2.2.6 takes `(pool, size)` not `(pool, type, count)` — fixed
- `H2O_SOCKET_FLAG_DONT_READ`: exists in `h2o/socket/evloop.h` — need `#define H2O_USE_LIBUV 0`
- `fcntl`/`O_NONBLOCK`: `<fcntl.h>` included
- `sendChunkNative`: wrapped in `Zone { ... }`
- `inFlight` double-decrement: guarded with `AtomicBoolean`
- CFuncPtr capture: moved all state to companion object (statically reachable)
- `CLong` vs `Long`: changed to `int`/`CInt` for stream IDs (portable across all platforms)
- Build portability: `pkg-config` discovers h2o/curl paths on any OS

---

## Implementation Plan

### Phase 1: C Bridge (`h2o_wrappers.c`)

Implement the minimal C bridge:

1. **Server struct and lifecycle**
   - `kyo_h2o_start()`: Initialize `h2o_globalconf_t`, register host at `"default"`, register catch-all handler at `"/"`, create listener socket with `bind()`/`listen()`/`getsockname()`, create response pipe, register pipe read-end with `h2o_evloop_socket_create()` + `h2o_socket_read_start()`, initialize `h2o_context_t` and `h2o_accept_ctx_t`
   - `kyo_h2o_run()`: Loop calling `h2o_evloop_run()` while `running` flag is set
   - `kyo_h2o_stop()`: Set `running = false`, close listener, write to response pipe to wake event loop
   - `kyo_h2o_port()`: Return `actual_port` from `getsockname()`

2. **Accept callback**
   - `on_accept()`: Call `h2o_evloop_socket_accept()` then `h2o_accept()`
   - Standard pattern from h2o examples

3. **Handler callback (C side)**
   - Minimal C handler that calls a Scala-registered function pointer
   - Pass through the `h2o_req_t*` — all request extraction happens in Scala
   - Handler returns `0` (the Scala side decides to respond immediately or async)

4. **Response pipe callback**
   - `on_response_pipe_readable()`: Read and discard pipe bytes (just a wakeup signal), then call a Scala-registered function pointer to drain the response queue

5. **Response helpers** (called from Scala via `@extern`)
   - `kyo_h2o_req_method(req)` → pointer to method string
   - `kyo_h2o_req_path(req)` → pointer to path string + length
   - `kyo_h2o_req_header_count(req)` → int
   - `kyo_h2o_req_header_name(req, i)` / `kyo_h2o_req_header_value(req, i)` → strings
   - `kyo_h2o_req_body(req)` → pointer + length
   - `kyo_h2o_send_buffered(req, status, header_names, header_values, header_count, body, body_len)`
   - `kyo_h2o_send_error(req, status)` — for 404/405/400/500 immediate responses
   - `kyo_h2o_start_streaming(req, status, header_names, header_values, header_count)` → generator pointer
   - `kyo_h2o_send_chunk(req, generator, data, len, is_final)`

6. **Configuration mapping**
   - `max_request_entity_size` ← `config.maxContentLength`
   - `listen()` backlog ← `config.backlog`
   - Bind address ← `config.host` + `config.port`

### Phase 2: Scala Bindings (`H2oBindings.scala`)

`@extern` declarations for all `kyo_h2o_*` functions from the C bridge. Thin layer — just type signatures mapping Scala Native types (`Ptr[Byte]`, `CInt`, `CString`, `CSize`, `CFuncPtr`) to the C functions.

### Phase 3: Server Backend (`H2oServerBackend.scala`)

1. **`bind()` implementation**
   - Create `HttpRouter` from handlers
   - Call `H2oBindings.start()` with config
   - Set up handler callback (CFuncPtr) and response pipe callback (CFuncPtr)
   - Start event loop thread
   - Return `Binding` with port, host, close, await

2. **Handler callback** (`onRequest`)
   - Extract method, path from `h2o_req_t` via bindings
   - Call `router.find(method, path)`
   - For errors (404, 405): send immediate response via `H2oBindings.sendError()`
   - For matched routes: extract headers and body, decode via RouteUtil, launch fiber

3. **Fiber handler execution**
   - Run handler: `Abort.run[Any](handler(request))`
   - On success: `RouteUtil.encodeResponse()` with three callbacks:
     - `onEmpty`: enqueue `Buffered(req, status, headers, emptyBody)`
     - `onBuffered`: enqueue `Buffered(req, status, headers+contentType, body)`
     - `onStreaming`: enqueue `StreamStart(req, status, headers+contentType, streamCtx)`, then `stream.foreach { chunk → streamCtx.enqueueChunk(chunk); wakeEventLoop() }`, then `streamCtx.enqueueChunk(empty, isFinal=true); wakeEventLoop()`
   - On failure: map to error status, enqueue error response
   - On Halt: use halt response status and headers

4. **Response queue drain** (`drainResponses`)
   - Process `Buffered` → `H2oBindings.sendBuffered()`
   - Process `StreamStart` → `H2oBindings.startStreaming()` + `streamCtx.tryDeliver()`
   - Process `StreamChunk` → `streamCtx.tryDeliver()`

5. **StreamContext** (streaming response state machine)
   - Two-state: `ReadyForData` / `WaitingForProceed`
   - `chunkQueue`: `ConcurrentLinkedQueue[(Array[Byte], Boolean)]`
   - `onProceed()`: called from C generator proceed callback (on event loop thread)
   - `tryDeliver()`: called from drain callback (on event loop thread)
   - `enqueueChunk()`: called from Kyo fiber (any thread)
   - `onStop()`: called from C generator stop callback — signal stream cancellation

6. **Graceful shutdown**
   - `AtomicInteger` in-flight counter (inc in handler callback, dec on response send)
   - `close(gracePeriod)`: close listener → wait for in-flight to reach 0 (with timeout) → set `running = false` → wake event loop → join thread → dispose

7. **`await` implementation**
   - `Async.sleep(Duration.Infinity)` — same as current MHD backend

### Phase 4: Streaming Generator Callbacks (C bridge addition)

The generator `proceed` and `stop` callbacks need to route back to Scala:

1. **C side**: Allocate a struct containing `h2o_generator_t` + a Scala-side ID (long). The `proceed` callback looks up the ID and calls a Scala function pointer. Same pattern as curl transfer callbacks.

2. **Scala side**: `ConcurrentHashMap[Long, StreamContext]` maps IDs to stream state. The proceed callback calls `streamCtx.onProceed()`. The stop callback calls `streamCtx.onStop()`.

### Phase 5: Build Configuration

1. Add `libh2o-evloop` to native link flags (replace `libmicrohttpd`)
2. Add `h2o_wrappers.c` to native sources
3. Remove `mhd_wrappers.c`
4. Update CI to install `libh2o-evloop-dev`

### Phase 6: Platform Backend Wiring

1. Update `HttpPlatformBackend.scala`: `new MhdServerBackend` → `new H2oServerBackend`
2. Update test: `MhdServerBackendTest` → `H2oServerBackendTest`, assert `isInstanceOf[H2oServerBackend]`

### Phase 7: Client Improvements

Independent of server work:

1. **Pool bypass**: Add `pooled: Boolean` to `HttpBackend.Client`, override to `false` in `CurlClientBackend`, add `if` in `HttpClient.poolWith()`
2. **Pause resume queue**: Replace `checkPausedTransfers()` iteration with `ConcurrentLinkedQueue[Long]` drain in `CurlEventLoop2`
3. **Callback routing**: Pack `(loopId << 32 | transferId)` into `CURLOPT_WRITEDATA`/`CURLOPT_READDATA`/`CURLOPT_HEADERDATA` userdata pointers

### Phase 8: Testing

1. Run all shared `HttpServerTest` tests — these exercise the server through the `HttpBackend.Server` interface and should pass without modification
2. Verify streaming responses (SSE, NDJSON, byte streams) actually stream incrementally (not buffered)
3. Verify concurrent request handling (the test with 50+ parallel requests)
4. Verify port 0 assignment
5. Verify graceful shutdown
6. Verify 413 for oversized request bodies
7. Verify client improvements don't regress any client tests

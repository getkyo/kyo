# I/O Suspension Patterns: How Runtimes Connect Socket Readiness to Fiber/Coroutine Resumption

## Executive Summary

Every high-performance concurrent I/O system must solve the same fundamental problem: **how to suspend a lightweight computation (fiber/goroutine/coroutine/virtual thread) when I/O is not ready, and resume it when I/O becomes ready, without blocking an OS thread.**

The universal pattern has three components:
1. **A poller** — a thread (or thread-integrated loop) that calls `epoll_wait`/`kqueue`/`IOCP`
2. **A mapping** — a data structure connecting file descriptors to suspended computations
3. **A resumption mechanism** — how the poller schedules the computation back onto a worker

The key design variation is **who owns the event loop**: a dedicated reactor thread, the worker threads themselves, or the language runtime.

---

## 1. Pure Scala/Functional HTTP Servers from Raw Sockets

### http4s Blaze — NIO1 Backend (the gold standard for "pure Scala from sockets")

**Architecture**: Pipeline of typed stages, with a SelectorLoop at the bottom.

**The exact flow**:
```
Client code calls readRequest(size) on a pipeline stage
  -> NIO1HeadStage creates a Promise[ByteBuffer]
  -> Attempts synchronous read via performRead()
  -> If data available: complete promise immediately (fast path)
  -> If no data: store promise, call setOp(OP_READ) to register with Selector
  -> SelectorLoop thread calls selector.select() (blocks)
  -> Selector fires, SelectorLoop calls NIO1HeadStage.opsReady()
  -> opsReady() reads data, completes the stored Promise
  -> Future resolves, next pipeline stage runs
```

**Key abstraction** — the `Selectable` trait:
```scala
trait Selectable {
  def opsReady(scratch: ByteBuffer): Unit  // called by SelectorLoop when fd is ready
}
```

**Design pattern**: Promise-as-bridge. The pipeline returns `Future[ByteBuffer]` immediately. The SelectorLoop completes the Promise when the Selector reports readiness. All state (readPromise, writePromise) is confined to the SelectorLoop thread, so no synchronization is needed.

**Write path** mirrors read: optimistic write, if incomplete, register OP_WRITE, SelectorLoop calls `writeReady()` which retries in a tail-recursive `writeLoop()`.

Source: [NIO1HeadStage.scala](https://github.com/http4s/blaze/blob/main/core/src/main/scala/org/http4s/blaze/channel/nio1/NIO1HeadStage.scala), [SelectorLoop.scala](https://github.com/http4s/blaze/blob/main/core/src/main/scala/org/http4s/blaze/channel/nio1/SelectorLoop.scala)

### fs2-io / http4s Ember

**Architecture**: Cats Effect fibers + Java NIO channels, abstracted through `SocketGroup`.

**Key abstraction**:
```scala
trait SocketGroup[F[_]] {
  def client(to: SocketAddress[Host]): Resource[F, Socket[F]]
  def server(address: SocketAddress[Host]): Stream[F, Socket[F]]
}
```

`Socket[F]` provides `read: F[Option[Chunk[Byte]]]` and `write(bytes): F[Unit]`.

On JVM, this wraps `AsynchronousSocketChannel` with NIO2's `CompletionHandler` converted to `F[_]` via `Async[F].async_`. On Scala Native, it uses the polling system (see section 5).

Source: [fs2 I/O docs](https://fs2.io/), [SocketGroup javadoc](https://www.javadoc.io/static/co.fs2/fs2-docs_3/3.9.2/fs2/io/net/SocketGroup.html)

### NIO Reactor Pattern in Scala (minimal version)

The minimal pure-Scala NIO server is ~50 lines:
```scala
trait SelKeyAttm { def run(): Try[Unit] }

// The event loop:
@tailrec
final def selectorLoop(iterFn: JavaIter[SelectionKey] => Unit): Nothing = {
  selector.select()      // block until I/O ready
  iterFn(selector.selectedKeys().iterator())
  selectorLoop(iterFn)
}

// Handler registration:
selKey = channel.register(sel, SelectionKey.OP_READ)
selKey.attach(handler)   // handler implements SelKeyAttm
sel.wakeup()
```

Source: [NIO-based Reactor in Scala](https://blog.genuine.com/2023/01/nio-based-reactor-in-scala/)

---

## 2. Go's Netpoller Design

**The exact mechanism** connecting `net.Conn.Read()` to goroutine suspension:

### Call chain
```
net.Conn.Read()
  -> netFD.Read()
    -> poll.FD.Read()
      -> syscall read() on non-blocking fd
      -> if EAGAIN: runtime_pollWait()
        -> netpollblock()
          -> atomic CAS: set gpp from pdNil to pdWait
          -> gopark(netpollblockcommit, gpp, waitReasonIOWait)
          -> goroutine is now OFF the run queue
```

### Data structures
- **`pollDesc`**: Per-fd structure holding two goroutine pointers (one for read waiter, one for write waiter). States: `pdNil` (no waiter), `pdWait` (goroutine parked), `pdReady` (I/O ready), or a `*g` pointer (the actual goroutine).
- **`pollCache`**: Free-list allocator for `pollDesc` structs.

### The poller side
```
Dedicated thread OR scheduler's findrunnable():
  -> netpoll(block=true)
    -> epoll_wait() / kevent()  (platform-specific)
    -> for each ready event:
      -> look up pollDesc from epoll event data
      -> netpollready(): atomic swap gpp from pdWait to pdReady
        -> extract goroutine pointer
        -> add to "ready list"
    -> return list of runnable goroutines
  -> scheduler puts them back on run queues
```

### Integration with the scheduler
- `runtime.findrunnable()` calls `netpoll()` when no goroutines are runnable
- A separate `sysmon` thread calls `netpoll()` every 10ms even if workers are busy
- This ensures I/O-bound goroutines don't starve

### Key design insight
Go does NOT use callbacks or futures. The goroutine's entire stack is preserved in place. `gopark` yields the goroutine's M (OS thread) to the P (processor), which picks up another G (goroutine). When `goready` fires, the goroutine is placed back on a P's run queue and resumes exactly where it left off. **Zero allocation on the suspend/resume path.**

Source: [Go netpoll source](https://go.dev/src/runtime/netpoll.go), [Morsing's blog](https://morsmachine.dk/netpoller), [Go networking internals](https://goperf.dev/02-networking/networking-internals/)

---

## 3. Fiber-Based I/O Across Ecosystems

### Erlang/BEAM

**Pattern**: Port drivers + reduction-based preemption.

The BEAM scheduler does NOT park processes on I/O directly. Instead:
1. Process calls `gen_tcp:recv()` which sends a message to the TCP port
2. The port driver (native C code) registers the fd with the VM's I/O poller (`erl_check_io.c`)
3. The poller uses `epoll`/`kqueue` (concurrent versions, thread-safe)
4. When fd is ready, the port driver receives an event and sends a message to the waiting Erlang process
5. The process, which was blocked in `receive`, becomes runnable when the message arrives
6. The scheduler picks it up from the run queue

**Key insight**: Everything is message-passing. There is no "suspend on I/O" — there is "wait for a message from the port driver, which happens to be triggered by I/O readiness." The scheduler treats I/O-waiting processes identically to message-waiting processes.

**Run queue structure**: 4 priority queues for processes + 1 queue for ports, with the scheduler alternating between process and port execution.

Source: [BEAM Book scheduling chapter](https://github.com/happi/theBeamBook/blob/master/chapters/scheduling.asciidoc), [Erlang scheduler deep dive](https://blog.appsignal.com/2024/04/23/deep-diving-into-the-erlang-scheduler.html), [erl_check_io.c](https://github.com/erlang/otp/blob/maint-28/erts/emulator/sys/common/erl_check_io.c)

### Kotlin Coroutines (Ktor CIO engine)

**Pattern**: `suspendCancellableCoroutine` + NIO Selector.

```kotlin
// The bridge function:
suspend fun readFromSocket(channel: SocketChannel): ByteBuffer =
    suspendCancellableCoroutine { cont ->
        // Register with NIO selector for OP_READ
        selectorManager.registerInterest(channel, OP_READ) {
            // Called when selector fires
            val buf = ByteBuffer.allocate(1024)
            channel.read(buf)
            cont.resume(buf)
        }
        cont.invokeOnCancellation {
            selectorManager.unregisterInterest(channel, OP_READ)
        }
    }
```

The `SelectorManager` is a dedicated thread running a `select()` loop. It bridges between NIO's selector-based readiness and Kotlin's `Continuation` objects. When a coroutine suspends, its `Continuation` is stored; when I/O is ready, `continuation.resume(value)` dispatches the coroutine back to the `CoroutineDispatcher`.

**Key design**: Ktor's CIO engine uses a single `SelectorManager` thread for I/O multiplexing, but dispatches coroutine resumption to a thread pool. This separates the I/O detection thread from the computation threads.

### Swift Concurrency + SwiftNIO

**Pattern**: NIO event loop threads are SEPARATE from Swift's cooperative thread pool.

SwiftNIO uses dedicated event loop threads that block on `epoll_wait`/`kqueue`. These are NOT part of Swift Concurrency's cooperative pool. `NIOAsyncChannel` bridges between the two worlds:

```
NIO Event Loop Thread (blocks on epoll_wait)
  -> Channel becomes readable
  -> NIO handler fires
  -> NIOAsyncChannel writes to AsyncStream
  -> Swift Concurrency task awaiting the stream is resumed
  -> Task runs on the cooperative thread pool
```

**Critical constraint**: "This thread pool must not be blocked by any operation, because doing so will starve the pool." NIO's event loop threads are explicitly outside Swift's cooperative pool precisely because they must block on I/O syscalls.

Source: [SwiftNIO GitHub](https://github.com/apple/swift-nio), [Swift concurrency adoption guidelines](https://www.swift.org/documentation/server/guides/libraries/concurrency-adoption-guidelines.html)

---

## 4. Java 21 Loom + Blocking Socket I/O

### Does Loom make NIO unnecessary?

**Yes, for most use cases.** The JVM automatically converts blocking socket operations to non-blocking under the hood.

### The exact mechanism

```
Virtual thread calls socket.read()
  -> NioSocketImpl.implRead()
    -> Sets socket to non-blocking mode
    -> Calls native read()
    -> If returns EAGAIN:
      -> NioSocketImpl.park()
        -> Registers fd with the JVM's Poller (epoll/kqueue/wepoll)
        -> VirtualThread.park()
          -> Continuation.yield()  (unmounts from carrier thread)
          -> Carrier thread is free to run other virtual threads

Poller thread (Read-Poller or Write-Poller):
  -> Runs epoll_wait() / kevent()
  -> Maintains HashMap<fd, VirtualThread>
  -> When fd ready: virtualThread.unpark()
    -> Continuation.run()  (mounts onto a carrier thread)
    -> socket.read() retries and succeeds
```

### Architecture
- **Two dedicated platform threads**: `Read-Poller` and `Write-Poller`
- **Fd-to-VirtualThread mapping**: `HashMap<Integer, VirtualThread>` per poller
- **Carrier thread pool**: ForkJoinPool with work-stealing
- **No user-visible event loop**: The JVM handles everything

### Performance

Helidon Nima (virtual-thread-based HTTP server) demonstrates the model:
- **Thread-per-connection**: One virtual thread per HTTP/1.1 connection, one per HTTP/2 stream
- **Performance**: Comparable throughput to minimalist Netty server
- **Memory**: Significantly less memory than Netty (virtual threads are ~1KB vs Netty's buffer pools)
- **Latency**: ~1ms less response time than Netty in some benchmarks
- **Simplicity**: Blocking code with full stack traces, no callback hell

**Caveats**:
- `synchronized` blocks can pin the carrier thread (use `ReentrantLock` instead)
- Native methods that do blocking I/O (JNI) will also pin
- The ForkJoinPool carrier has a fixed size; if all carriers are pinned, throughput drops

Source: [Inside.java - Networking I/O with Virtual Threads](https://inside.java/2021/05/10/networking-io-with-virtual-threads/), [Chris Hegarty's Loom gist](https://gist.github.com/ChrisHegarty/0689ae92a01b4311bc8939f33fde9fd9), [Helidon Nima](https://medium.com/helidon/helidon-n%C3%ADma-helidon-on-virtual-threads-130bb2ea2088)

---

## 5. Scala Native Socket I/O Patterns

### Current state of the art

Nobody has built a high-performance HTTP server on Scala Native from raw sockets without C libraries. All existing approaches delegate to native libraries:

1. **SNUnit**: Delegates to NGINX Unit (C library)
2. **epollcat / Cats Effect Native**: Wraps `epoll`/`kqueue` syscalls via Scala Native FFI to implement a `PollingExecutorScheduler`
3. **Kyo's current H2oServerBackend**: Delegates to libh2o (C library)
4. **cb372's example**: Uses libuv (C library)

### epollcat / Cats Effect PollingSystem (the closest to "pure")

**Design pattern**: Worker threads integrate polling into their idle loop.

```
Cats Effect PollingSystem API (abstract):
  type Poller                                    // thread-local polling state
  def makePoller(): Poller                       // create per-worker-thread instance
  def closePoller(poller: Poller): Unit
  def poll(poller: Poller, timeout: Duration): Boolean  // block until event or timeout
  def interrupt(thread: Thread, poller: Poller): Unit   // wake a blocked poll()
  def needsPoll(poller: Poller): Boolean                // optimization: skip if no fds registered

Worker thread loop:
  1. Check local work queue for runnable fibers
  2. Try work-stealing from other workers
  3. If nothing to do: call poll(myPoller, timeout)
     -> On Linux: epoll_wait(myEpollFd, events, timeout)
     -> On macOS: kevent(myKqueueFd, events, timeout)
  4. For each ready event: resume the associated fiber callback
  5. Go back to step 1
```

**Per-worker-thread epoll instance**: Each WorkerThread has its own epoll/kqueue fd. When a fiber registers interest in an fd, it does so on the current worker's poller. This avoids cross-thread synchronization on the hot path.

**Overhead issue**: Early versions (CE 3.6.0) polled even when no fds were registered, causing 70%+ CPU overhead for pure-computation workloads. Later versions added `needsPoll` gating.

**The fd registration bridge** (for Scala Native):
```scala
// In fs2-io on Native:
def read(socket: Int, buf: Array[Byte]): IO[Int] =
  IO.async_ { cb =>
    poller.registerRead(socket) {  // register with the worker's epoll/kqueue
      val n = posix.read(socket, buf, buf.length)
      cb(Right(n))
    }
  }
```

Source: [Cats Effect PollingSystem API](https://typelevel.org/cats-effect/api/3.x/cats/effect/unsafe/PollingSystem.html), [Cats Effect I/O integrated runtime discussion](https://github.com/typelevel/cats-effect/discussions/3070), [epollcat](https://github.com/armanbilge/epollcat), [Typelevel Native blog](https://typelevel.org/blog/2022/09/19/typelevel-native.html)

### Kyo's H2oServerBackend pattern (for reference)

Kyo Native currently uses a C-library event loop (libh2o) with a pipe-based wake mechanism:
```
h2o event loop thread (C):
  -> blocks on epoll_wait in h2o's evloop
  -> when request arrives: calls handlerCallback into Scala
    -> Scala extracts request data
    -> launches Kyo fiber (Fiber.initUnscoped)
    -> fiber processes request
    -> enqueues response to ConcurrentLinkedQueue
    -> calls H2oBindings.wake() (writes to pipe fd)
  -> h2o evloop wakes, calls drainCallback
    -> Scala drains response queue
    -> calls h2o_send() for each response
```

---

## 6. Transport Abstraction Designs

### Tokio (Rust) — The Layered Reactor

**Three layers**:

1. **mio** — cross-platform selector abstraction
   ```rust
   trait Source {
       fn register(&mut self, registry: &Registry, token: Token, interests: Interest);
       fn reregister(...);
       fn deregister(...);
   }
   ```

2. **PollEvented<T>** — bridges mio Source to async
   ```rust
   // Wraps any mio type and provides:
   async fn readable(&self) -> io::Result<()>
   async fn writable(&self) -> io::Result<()>
   ```

3. **AsyncRead / AsyncWrite** — user-facing traits
   ```rust
   trait AsyncRead {
       fn poll_read(self: Pin<&mut Self>, cx: &mut Context, buf: &mut ReadBuf) -> Poll<io::Result<()>>;
   }
   trait AsyncWrite {
       fn poll_write(self: Pin<&mut Self>, cx: &mut Context, buf: &[u8]) -> Poll<io::Result<usize>>;
       fn poll_flush(...) -> Poll<io::Result<()>>;
       fn poll_shutdown(...) -> Poll<io::Result<()>>;
   }
   ```

**The flow**:
```
Future::poll() calls poll_read()
  -> PollEvented checks readiness state
  -> If not ready: registers Waker with the reactor
    -> Reactor maps: Token -> ScheduledIo -> Waker
  -> Returns Poll::Pending (future is suspended)

Reactor thread:
  -> mio::Poll::poll() blocks on epoll_wait/kqueue
  -> For each ready event: lookup Token in Slab<ScheduledIo>
  -> Call waker.wake() -> task is rescheduled on executor
  -> Executor calls Future::poll() again
  -> This time TcpStream::read() succeeds
```

**Key data structure**: `HashMap<RawFd, Waker>` or `Slab<ScheduledIo>` connecting file descriptors to task wakers.

**Critical design choice**: Tokio uses **readiness-based** I/O (poll-then-try), not completion-based (submit-then-wait). This means the user code must retry the I/O operation after wakeup, because the wakeup only means "the fd might be ready."

Source: [Tokio I/O docs](https://docs.rs/tokio/latest/tokio/io/), [Tokio internals deep dive](https://cafbit.com/post/tokio_internals/), [Rust async socket flow](https://dev.to/_56d7718cea8fe00ec1610/understanding-async-socket-handling-in-rust-from-tcp-request-to-waker-wake-up-19le)

### Trio (Python) — The Checkpoint Model

**Core abstractions**:

1. **IOManager** — platform-specific, provides raw fd monitoring
   ```python
   # Low-level (trio.lowlevel):
   async def wait_readable(fd)   # suspends task until fd is readable
   async def wait_writable(fd)   # suspends task until fd is writable
   ```

2. **Stream** — high-level transport trait
   ```python
   class Stream(ABC):
       async def send_all(self, data: bytes) -> None
       async def receive_some(self, max_bytes: int) -> bytes
       async def aclose(self) -> None

   class HalfCloseableStream(Stream):
       async def send_eof(self) -> None
   ```

3. **SocketStream** wraps a raw socket into a Stream
4. **SSLStream** wraps any Stream into an encrypted Stream (composable)

**Checkpoint model**: Tasks can only be suspended at "checkpoints" (calls to `await`). Every I/O operation is a checkpoint. The event loop runs: check timers -> run idle callbacks -> poll for I/O -> dispatch ready tasks.

### libuv — Handles and Requests

**Two abstractions**:

- **Handles**: Long-lived objects (TCP server, timer, pipe). Represent ongoing I/O interest.
  ```c
  uv_tcp_t server;              // Handle: lives for the server's lifetime
  uv_tcp_init(loop, &server);
  uv_tcp_bind(&server, addr);
  uv_listen(&server, backlog, on_connection_cb);
  ```

- **Requests**: Short-lived operations (write, connect, DNS lookup).
  ```c
  uv_write_t req;               // Request: lives for one write operation
  uv_write(&req, stream, bufs, nbufs, write_cb);
  ```

**Event loop phases** (per iteration):
1. Run due timers
2. Run pending callbacks (I/O callbacks deferred from previous iteration)
3. Run idle handles
4. Run prepare handles
5. **Poll for I/O** (epoll_wait/kqueue with calculated timeout)
6. Run check handles
7. Run close callbacks

**The polling integration**: libuv calculates the poll timeout as `min(next_timer_deadline, infinity_if_no_timers)`. If `UV_RUN_NOWAIT`, timeout is 0. The poll collects ready events and queues their callbacks for the next iteration's pending phase.

Source: [libuv design overview](https://docs.libuv.org/en/v1.x/design.html)

---

## The Minimal Abstraction (Synthesis)

After studying all these systems, the minimal abstraction for connecting I/O readiness to fiber suspension has exactly **three components**:

### 1. The Poller (platform-specific)
```
trait Poller:
  def register(fd: Int, interest: Interest, callback: () => Unit): Registration
  def unregister(reg: Registration): Unit
  def poll(timeout: Duration): Unit  // blocks, invokes callbacks for ready fds
```

### 2. The Suspension Bridge (runtime-specific)
```
// This is what varies most between runtimes:

// Go: gopark/goready (goroutine stack stays in place)
// Loom: Continuation.yield/run (virtual thread stack stays in place)
// Tokio: Waker.wake() -> executor reschedules task
// Cats Effect: IO.async_ { cb => poller.register(fd, cb) }
// Kotlin: suspendCancellableCoroutine { cont -> ... cont.resume(value) }
// BEAM: Port driver sends message to waiting process
// Blaze: Promise/Future (SelectorLoop completes Promise)
```

### 3. The Worker Integration (determines architecture)

**Option A: Dedicated reactor thread** (Tokio, Blaze, Ktor, SwiftNIO)
```
Reactor thread: epoll_wait -> wake tasks
Worker threads: run fibers/futures
Communication: via Waker/Promise/Continuation
```

**Option B: Worker threads poll during idle** (Go, Cats Effect Native)
```
Worker thread: run fibers -> when idle -> poll -> run fibers
No dedicated reactor thread
Lower latency (no cross-thread hop), but more complex
```

**Option C: Runtime-integrated poller** (Loom, BEAM)
```
Poller is part of the VM/runtime itself
User code is oblivious to the mechanism
The runtime "just handles it"
```

### For Kyo specifically

Kyo's IOTask already has the suspension mechanism: when a fiber awaits an `IOPromise`, it stores a callback (`onComplete`) and yields. The `Scheduler` reschedules the task when the promise completes. The minimal addition for raw socket I/O would be:

```
// Register interest in fd readiness:
fd.whenReadable { promise.complete(()) }  // inside the poller

// In fiber code:
val ready: Unit < Async = IO.Unsafe.promise[Unit]  // create promise
poller.registerRead(fd, promise)                    // register with poller
ready.map { _ => channel.read(buf) }                // resume and read
```

This is structurally identical to how `NettyServerBackend` already works (Netty's event loop completes promises), just with a custom poller instead of Netty.

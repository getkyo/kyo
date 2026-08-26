# kyo http benchmarks: kqueue selection, numbers, and allocation mechanisms

Scope: the four kyo rows of the kyo-bench http arena (`HttpClientBench`, `HttpClientContentionBench`,
`HttpServerBench`, `HttpServerContentionBench`, method `forkKyo` only), run on macOS aarch64 with the
posix kqueue transport, JMH `-wi 13 -i 5 -r 1 -w 1 -f 1 -t 1`. The client rows drive `HttpClient`
against a vertx server forked as a separate process; the server rows round-trip kyo's `HttpServer`
through kyo's client; the contention rows fan out one request per core.

## Artifacts

Per-benchmark JFR recordings (allocation samples with full stacks; open in JMC or IntelliJ):

```
kyo-bench/.jvm/kyo.bench.arena.HttpClientBench.forkKyo-Throughput/profile.jfr
kyo-bench/.jvm/kyo.bench.arena.HttpClientContentionBench.forkKyo-Throughput/profile.jfr
kyo-bench/.jvm/kyo.bench.arena.HttpServerBench.forkKyo-Throughput/profile.jfr
kyo-bench/.jvm/kyo.bench.arena.HttpServerContentionBench.forkKyo-Throughput/profile.jfr
```

Each carries ~1,500 `jdk.ObjectAllocationSample` events with stacks. `jdk.ExecutionSample` counts are
low (14 to 189): these JVMs are mostly idle per request (the JMH thread blocks on the fiber, the
poll carrier parks in kevent, the vertx server is another process), JFR samples only on-CPU threads,
and macOS undersamples on top. For the cpu shape use the async-profiler itimer flamegraphs generated
alongside, in the same directories (`flame-itimer-forward.html`, kqueue run) and the preserved
`*-nio/` siblings from the accidental NIO run.

## Why the benches were not on kqueue, and the fix

The first profiling pass silently ran the NIO floor. Chain, each link verified:

1. sbt runs `Jmh/run` through its background-job service, which re-materializes the classpath:
   every internal class directory is replaced by that module's `packageBin` jar (captured from the
   live forked JVM's `-cp`).
2. kyo-net's main jar deliberately carries no natives (P2b, the netty-style classifier
   distribution): `Compile / packageBin / mappings` strips `META-INF/native/**`.
3. The backend registry demotes silently on a missing shim, so kqueue lost to the always-available
   NIO floor. Forcing `-Dkyo.net.backend=kqueue` produced the loud report: `native library
   'kyonet_posix_uring' is not bundled for darwin-aarch64`.

Fix (committed): kyo-bench puts kyo-net's `all-natives` classifier jar on the `Jmh` classpath,
which is the documented production setup (main jar plus platform classifier). A forced-kqueue run
now selects the posix backend with no overrides. The profiling runs keep the force so a future
regression fails loudly instead of flooring.

Production-facing note, separate from the benches: a plain Maven/sbt dependency without the
classifier gets the NIO floor with only an info-level startup line naming the selected backend.
The transport shim is 34KB per platform; the README calls the native backend the primary. Whether
the main jar should bundle the tiny transport shims (keeping BoringSSL classifier-only) is an open
product decision.

## Numbers (kqueue, quiet machine)

| row | throughput | alloc |
|---|---|---|
| HttpClientBench | 22,951 ± 2,621 ops/s | 5,088 B/op |
| HttpClientContentionBench (1 req/core) | 6,490 ± 1,536 ops/s | 59,255 B/op (~4.9KB/request) |
| HttpServerBench (kyo server + kyo client) | 19,547 ± 1,033 ops/s | 8,229 B/op |
| HttpServerContentionBench | 5,329 ± 598 ops/s | 91,030 B/op (~7.6KB/request) |

Reading: a full client GET round trip is ~44µs wall and ~5KB allocated; putting kyo's server behind
it adds ~3.1KB/op for the server side. Contention rows scale linearly per request, with no
allocation amplification under fan-out. The earlier NIO-floor numbers (client 19.1k, server 12.7k)
were measured under heavy parallel container load with very wide error bars, so they are not a
clean cross-transport comparison; the flamegraph pairs are the honest comparison artifact.

## Allocation mechanisms (from the JFR stacks, each at its line)

Sampled weight shares are directional (sampling), but every mechanism below is an exact stack.

1. `Ffi.load` per poll cycle. `KqueuePollerBackend.kq` is a `def`:
   `private def kq(using AllowUnsafe): KqueueBindings = Ffi.load[KqueueBindings]`
   (kyo-net/shared/src/main/scala/kyo/net/internal/posix/KqueuePollerBackend.scala:41). Every poll
   cycle re-runs the load: a `ClassTag` summon whose `ClassValue` cache allocates a `WeakReference`,
   plus a lambda, per cycle, on the io-driver hot loop (`PollerIoDriver.runCycle` ->
   `poll` -> `pollWithData` -> `kq.kevent`). The two entries together are the largest kyo-owned
   share in the server recording (~278MB + ~259MB of ~2.9GB sampled weight). Fix shape: bind once
   (a cached val on the backend object); same pattern worth auditing in the epoll and io_uring
   backends and every other `Ffi.load` in a loop.

2. A closure per FFI syscall. The generated `KqueueBindingsImpl.kevent` allocates a
   MethodHandle-spun lambda per call (`DirectMethodHandle.allocateInstance` under
   `KqueueBindingsImpl.kevent:42`), and the socket bindings show the same shape
   (`SocketBindingsImpl` lambdas throughout the recording). One allocation per syscall on the
   read/write/poll paths. The fix belongs in the kyo-ffi codegen: emit call shapes that do not
   close over per-call state, or reuse a per-carrier instance.

3. A `Panic(Interrupted(frame))` per request on the happy path. `Fiber.Unsafe.interrupt()`
   allocates a fresh `Interrupted` plus `Panic` (kyo-core/shared/src/main/scala/kyo/Fiber.scala:454),
   and the client calls it for every completed request to cancel its `Async.timeoutWithError`
   watchdog (HttpClientBackend's timeout wrapper, kyo-http/.../HttpClientBackend.scala:1158-1163).
   ~213MB sampled weight in the server recording. Fix shape: a preallocated cancel error for the
   watchdog-cancel path, or a timeout mechanism that does not require interrupting a fiber per
   request (e.g. a deadline the poll loop already tracks).

4. Expected/neutral: `HttpRequest` and response machinery per request, `IOPromise` per fiber,
   scheduler queue nodes (`LinkedTransferQueue$DualNode`), byte arrays for payloads. The
   `ConcurrentHashMap$ValueIterator` entry is JMH's own harness (`captureUnusedWorkerData`), not
   kyo.

## Suggested next steps (not executed)

1. Cache the FFI binding in `KqueuePollerBackend` (and audit the sibling backends) - smallest
   change, removes the largest kyo-owned allocation source on the poll loop.
2. kyo-ffi codegen: allocation-free call shape for `@Ffi.blocking` bindings on the JVM.
3. Rework the client timeout-cancel path to stop allocating an exception per request.
4. Decide the main-jar-natives question for the published artifact (product call).

## CPU (async-profiler itimer via asprof attach, JFR output, 5ms interval)

Recordings in `~/http-bench-jfr/<Bench>-cpu.jfr` (5,964 to 17,409 execution samples each; the
JMH-integrated `-prof jfr` recordings are unusable for cpu on macOS, 14 to 189 samples, because the
JDK sampler only catches on-CPU Java threads and these JVMs are mostly parked per request).

Leaf-frame shares:

| leaf category | client 1 req | client 12 req | server 12 req |
|---|---|---|---|
| parking machinery (cvwait + cvsignal + timed-park gettimeofday) | 84% | 46% | 28% |
| socket I/O (sendto + recvfrom) | 5% | 35% | 56% |
| kevent poll | 10% | 15% | 13% |
| Java/kyo computation | ~0% | ~4% | ~3% |

The single-request row measures wake-chain latency: each round trip is a chain of thread handoffs
(the caller blocks on the fiber, a worker runs the request and parks, the poll carrier wakes on
kevent, completion signals back), and the syscall plus context-switch cost of those transitions is
the request cost. Under a request per core the profile inverts to real socket I/O, so the transport
amortizes correctly. Every `gettimeofday` sample sits under `Unsafe.park`: macOS computing absolute
deadlines for timed parks, so it belongs to the parking bucket (the workers' timed-park idle
strategy), not to any clock read in kyo code.

Consequence for optimization: at low concurrency the only lever is handoffs per request (inline
continuation on the completing carrier where legal, adaptive spin before parking on the block/join
path, and the worker idle strategy's timed park); the http and eval code paths are invisible at
this workload shape.

# Transport & HTTP Resilience Tests — Working Plan

Living doc for the resilience-test campaign. Kept in sync with the todo list. Status legend: `[ ]` todo, `[~]` in progress, `[x]` done, `[gated]` blocked on explicit user go-ahead.

## Goal

Two comprehensive, generic resilience suites that hammer the full edge-case surface of the transport and HTTP-comms layers and assert **no adverse condition ever closes/wedges the shared driver**. Run over **every backend** (io_uring / epoll / kqueue / nio) in one process. The reported RC5 bug (a per-connection failure marking the whole shared `NioIoDriver` "closed") must fall out as one covered scenario, not be the only thing tested.

- `TransportResilienceTest` (kyo-net) — driver/transport properties, driven at the `Transport`/`Connection`/`Listener` API via `eachBackend`.
- `HttpServerResilienceTest` (kyo-http) — HTTP request/response + client-pool + server-handler properties, driven at the `HttpClient`/`HttpServer` API, fanned over all backends the same way.

## Constraints

- **DO NOT FIX the driver yet.** This campaign is about reproducing + covering. The fix is a separate, user-gated step (see task R10).
- **Generic, all backends.** Not NIO-specific, not one lucky scenario. `eachBackend` fan-out; each scenario ends with a co-tenant liveness probe (`assertAlive`) that fails if the driver wedged.
- **Kyo primitives, no real OS threads, no WireMock.** Cancellation is modeled with `Async.timeout` / `Fiber.interrupt` / `Async.race` / `Scope` teardown. (Real threads were only ever a way of *producing* cancellation via a timeout; the trigger is the cancellation itself.)
- No AI-generation tells; kyo idioms; `Maybe`/`Result`/`Chunk`; explicit return types.

## Verified findings (durable memory — do not re-derive)

**The wedge mechanism (NIO), verified in code:**
- `NioIoDriver.dispatchReadyKeys` (`kyo-net/jvm/.../NioIoDriver.scala:1402`) wraps each key's dispatch in a `try` that catches **only `CancelledKeyException`** (`:1436-1437`). Anything else escapes → `pollOnce` (catches only `ClosedSelectorException`, `:1332`) → `runCycle` catch-all (`:234`) → `terminal()` (`:264`) → `close()` (`:1063`). Whole driver dead, process-wide, permanently.
- `NioIoDriver.closeHandle` (`:1026-1043`) runs **synchronously on whatever carrier completes/interrupts the promise**: `cleanupPending` (removes pending-read/connect map entries) + `key.cancel()` + `NioHandle.close(handle)` + `wakeup()`. It is invoked from `NioTransport` `connPromise.onComplete` failure/interrupt arms (`NioTransport.scala:1210/1215`).
- **Therefore cancellation → cross-carrier race:** an interrupted in-flight read/connect runs `closeHandle` on the caller carrier (closing the channel, yanking the map entry) while the **poll carrier** may be mid-`dispatchReadyKeys` on that same fd. The concurrent close surfaces as `ClosedChannelException` (an `IOException`, not `CancelledKeyException`), NPE, or `IllegalStateException` → escapes the narrow catch → wedge. (Exact escaping exception to be pinned by the reproducing test; the cross-carrier race is structurally confirmed.)
- `dispatchConnect` (`:1852-1876`) DOES catch `IOException` and fails the promise, so a refused connect is *not* the escaping throw. The reporter's "finishConnect failed… Connection refused" text is the *contained* per-connection failure.

**Why kqueue/posix does NOT wedge, verified:**
- `PollerIoDriver` dispatch is **total**: every per-fd failure becomes `promise.completeDiscard(Result.fail(...))` for that one connection; nothing throws into the poll loop. Its cancel/close is submitted as a change command **drained on the poll fiber** (single-carrier-confined), so there is no cross-carrier race. `drainReady` has no per-fd try/catch and does not need one.

**Reproduction matrix (scala-cli, reporter's real-thread + 5s-timeout harness, 16×200 = 3200 invocations, server restart every 25ms):**
| version | backend | result |
|---|---|---|
| snapshot RC5+86 | NIO forced | REPRODUCED 736/3200 |
| snapshot RC5+86 | auto (default) | REPRODUCED 301/3200 (lands on NioIoDriver) |
| current RC5+90 | NIO forced | REPRODUCED 265/3200 |
| current RC5+90 | auto (default) | REPRODUCED 586/3200 (lands on NioIoDriver) |
| current RC5+90 | **kqueue forced** (native jar on cp) | **0/3200 — does NOT wedge** (preflight confirmed PollerIoDriver built + served) |

**Packaging finding (flag to user, separate from tests):** the main `kyo-net` jar carries NO natives (pure-JVM NIO floor by design, `build.sbt:1794`). The posix/kqueue shim (`libkyonet_posix_uring.dylib`) ships only in per-os-arch classifier jars (`kyo-net_3-<os-arch>.jar`, and the `all-natives` aggregate). `PosixBackends.readinessLibraryIds = Chunk("c", "kyonet_posix_uring")` (`:101`); absent shim → posix readiness probe fails → selection degrades to the NIO floor. **So a plain `kyo-http` dependency defaults to the wedge-prone NIO driver on macOS/Linux** unless the native classifier is also on the classpath. This is why the reporter's `auto` run wedged on `NioIoDriver`.

**Existing coverage:** cancellation of in-flight ops IS broadly tested across kyo-net/kyo-http (interrupting parked `awaitConnect`/`awaitAccept`/`inbound.take`, `Async.timeout` on an in-flight HTTP `send`, server-handler interrupt on client disconnect, `assertAlive` liveness probes, per-driver crash-containment). The precise GAP: no test stages **cancellation of an in-flight NIO op concurrently with a peer event on that same fd** (the cross-carrier `closeHandle` vs `dispatchReadyKeys` race) under churn. That collision is the wedge, and it is what these two suites must cover on all backends.

## Cross-backend mechanism for the HTTP test

- `HttpServer.Unsafe.init(transport, config, handlers)` — public `Unsafe` object (`HttpServer.scala:190/202`) builds a server over an explicit transport.
- `HttpClientBackend.init(transport, …)` (via `HttpClient.initUnsafe`, `HttpClient.scala:946`) builds a client over an explicit transport; `HttpClient.let(client){ … }` (`:112`) rebinds the ambient client so the normal `getText*` helpers hit it.
- Per-backend transports come from `kyo.net.TestBackends.all` (each `entry.transport = IoBackendPlatform.registered(_).build()`), the same source `eachBackend` uses.
- `TestBackends`/`kyo.net.Test` are in kyo-net **test** sources → add a `test->test` dep so kyo-http tests can import them. build.sbt `:2137` `.dependsOn(`kyo-net`)` → `.dependsOn(`kyo-net` % "compile->compile;test->test")` (precedented: `kyo-data`/`kyo-schema` use `test->test;compile->compile`). Zero production surface.

## Edge-case matrices

### `TransportResilienceTest` (kyo-net, `eachBackend`)
1. Cancellation of in-flight ops: interrupt parked `connect`, read (`inbound.take`), backpressured write (`outbound.put`), `accept` — via `Fiber.interrupt`, `Async.timeout` (fires), `Async.race` loser, `Scope` teardown. At concurrency.
2. **Cancellation racing a live event on the same fd** (the reported wedge): interrupt an in-flight read/connect at the instant a peer RST/FIN/data lands on that fd. High concurrency + churn.
3. Peer teardown: RST, FIN / half-close, peer close during armed read/write.
4. Local close races: `close()` during armed read/write/connect; double close.
5. Connect failures: connect-refused storm; connect to a closing/just-restarted port; connect timeout firing.
6. Listener churn: open/close churn racing connects; accept racing listener close; port reuse / stale-fd.
7. Backpressure crossed with failure: inbound full + slow consumer while peer RSTs; outbound full while cancelled/closed.
8. Concurrency / isolation: many concurrent ops; mixed healthy + abruptly-failed, healthy must keep working.

### `HttpServerResilienceTest` (kyo-http, HTTP-level `eachBackend`)
1. Request cancellation / timeout: `Async.timeout`/`Fiber.interrupt` on an in-flight request in both connect and response-wait phases, against a slow/absent server, at concurrency; timeout must genuinely fire.
2. **Cancellation under server churn** (reported bug, HTTP level): concurrent requests with per-request timeouts while the server restarts every ~25ms.
3. Connection-pool integrity: a cancelled/timed-out request must not poison a pooled connection (no stale bytes on reuse); reuse after peer went away recovers; server restarts on a new port.
4. Keep-alive across churn: many sequential reused-connection requests during churn.
5. Server handler lifecycle: handler throws/aborts; handler never responds (client cancel → handler interrupted on disconnect); handler failure after client disconnect must not crash the server.
6. Client disconnect / server disappears mid-exchange.
7. Concurrency on the shared default client: N-way load, 2 GETs per invocation (reporter's shape), cancellation as the trigger.

Both suites: every scenario ends with a co-tenant liveness probe asserting the shared driver still round-trips (`assertAlive` / `driverClosedHits == 0` + live GET returns expected body).

## Tasks (mirrors todo list)

- [~] **R1** Add kyo-http `test->test` dep on kyo-net (build.sbt:2137) so `TestBackends`/`eachBackend` are reusable.
- [ ] **R2** Rename `TransportChurnResilienceTest` → `TransportResilienceTest`; `HttpServerReliabilityTest` → `HttpServerResilienceTest`.
- [ ] **R3** `TransportResilienceTest`: cancellation scenarios (interrupt in-flight connect/read/write/accept, all triggers) via `eachBackend`.
- [ ] **R4** `TransportResilienceTest`: cancellation-racing-live-event (reported wedge) — verify it reproduces on `[nio]`, holds elsewhere.
- [ ] **R5** `TransportResilienceTest`: complete the matrix (RST/FIN/half-close, close races, connect-refused, listener churn, backpressure×failure, isolation); audit/keep existing scenarios.
- [ ] **R6** `HttpServerResilienceTest`: HTTP-level `eachBackend` helper (`HttpServer.Unsafe.init` + `HttpClientBackend.init` over transport + `HttpClient.let`).
- [ ] **R7** `HttpServerResilienceTest`: request cancellation/timeout + cancellation-under-churn (reported bug at HTTP level).
- [ ] **R8** `HttpServerResilienceTest`: pool integrity, keep-alive across churn, handler lifecycle, client-disconnect / server-disappears scenarios.
- [ ] **R9** Run both suites across all backends; confirm wedge reproduces on `[nio]`, green elsewhere; record results here.
- [gated] **R10** Driver fix for the NIO cross-carrier cancel-vs-dispatch race (contain per-connection dispatch errors like `PollerIoDriver`). NOT now; user-gated.

## Open questions / decisions

- HTTP test also drives the process-shared default client in one scenario (to mirror the reporter exactly) in addition to the per-backend explicit clients? Leaning yes, as one extra scenario.
- File-naming: `TransportResilienceTest` shares the `Transport` prefix with `Transport.scala`; `HttpServerResilienceTest` shares `HttpServer` with `HttpServer.scala` — both satisfy the prefix rule as aspect tests.

## Log
- (init) Doc created. Findings above verified in code + scala-cli repro matrix. Next: R1 (build dep), then R2 rename.
- Branch `transport-http-resilience-tests` created off main; WIP checkpoint `9ee028fbe7` preserves the draft tests + this doc.
- R1: build.sbt:2137 now `.dependsOn(`kyo-net` % "compile->compile;test->test")`. R2: both files renamed (`TransportResilienceTest`, `HttpServerResilienceTest`) + class names updated. Compile running to validate R1+R2.
- Cancellation idiom confirmed (TransportUnsafeTest:125-131): `Fiber.init(Abort.run[Closed](conn.inbound.safe.take).unit)` then `fiber.interrupt`. R4 races this against a peer poke+close on the same fd.
- R3 added (interrupt parked read; firing Async.timeout on a silent server). Both PASS on nio + kqueue. Good broad cancellation coverage.
- **KEY FINDING (R4): the wedge does NOT reproduce at the raw transport level.** R4 with corrected staging (silent server so reads stay armed + firing `Async.timeout` + churn RST) PASSES on nio and kqueue (18 passed / 0 failed / 18 cancelled all-backends). The existing real-threads transport scenario also PASSES. Diagnosis: interrupting `conn.inbound.safe.take` cancels the stream CONSUMER, not the driver's armed read / `closeHandle` (the ReadPump keeps the arm alive). `closeHandle` (the cross-carrier race site) is reached only by connection `close()` or in-flight connect cancel, and at fiber-scale concurrency the race window with `dispatchReadyKeys` is not hit. The scala-cli HTTP harness reproduces reliably because it adds: pooled/keep-alive connections, a full server restart (listener replaced), a per-request timeout that FIRES on hung requests, and real-thread selector pressure.
- **Consequence:** the reported-bug reproduction belongs in `HttpServerResilienceTest` (R7), modeled on the proven scala-cli harness, NOT in the transport suite. The transport suite stands as broad cancellation/churn coverage (all green = transport is robust to these shapes). Open question for the user: my current HTTP test used fibers with NO firing timeout (false negative, 0 hits); R7 will add per-request firing timeouts + server restart; if fibers still will not trip it, the reproduction may require real threads for that one scenario (the bug demonstrably needs that pressure).

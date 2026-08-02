# Transport & HTTP Resilience Tests — Working Plan

> **HEADLINE (corrected):** The reported driver wedge NEVER reproduced, on either version, once detection was correct.
> With a correct detector (`"driver closed"`) + a hard post-load liveness check, RC5+86 and RC5+90 both show 0 real
> driver-closes and 20/20 liveness under the reporter's exact real-thread harness. Every earlier "reproduced" number
> was a broken detector counting normal per-connection closes (an RST'd read / cancelled connect during churn renders as
> `"NioIoDriver ... is closed"` because the resource is the driver label). The R10 driver fix was reverted (pristine
> driver at HEAD). Still true and valuable: the two resilience suites (green + leak-free, all backends, macOS + podman)
> and the detector fix. See newest log entries.

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

## Decisions (user)
- **Fibers only.** No real OS threads / `runAndBlock` in the reproduction. Remove the real-threads transport scenario in cleanup (R5).
- **Reproduce via podman.** The wedge may be a scheduling-pressure phenomenon hidden on a fast local box; run the suites under `scripts/build.sh --env podman-ci` (4 vCPU, `SBT_TASK_LIMIT=1`, CI heap) to expose it with fibers.

## Open questions / decisions

- HTTP test also drives the process-shared default client in one scenario (to mirror the reporter exactly) in addition to the per-backend explicit clients? Leaning yes, as one extra scenario.
- File-naming: `TransportResilienceTest` shares the `Transport` prefix with `Transport.scala`; `HttpServerResilienceTest` shares `HttpServer` with `HttpServer.scala` — both satisfy the prefix rule as aspect tests.

## Log (newest first)
- **MAJOR CORRECTION: the driver wedge does NOT reproduce on current code, and every prior "reproduced" number was a FALSE POSITIVE from a broken detector.** The detector matched `"is closed" + "Driver"`, but every per-connection `Closed` renders as `"<driver-label> ... is closed"` (the resource IS the driver label), so it counted routine per-connection closes (RST'd reads, cancelled connects) as driver wedges. Corrected to match the whole-driver teardown detail `"driver closed"`. Verified two ways:
  - In-suite (`reproduce (nio)`, pristine driver): hits 5614 -> 0, PASSES. Fiber tests never wedged the driver (liveness always passed, which already said so).
  - scala-cli, current RC5+90, forced nio, reporter's exact real-thread harness + a hard post-load liveness check (20 sequential GETs on a fresh server): OLD-signature=217, REAL `"driver closed"`=0, liveness=20/20. VERDICT: driver HEALTHY, bug did NOT reproduce.
  - The scala-cli repros used the SAME broken detector (`repro.scala:97`), so the earlier matrix ("+90 nio 265/3200", etc.) was per-connection closes, not wedges.
- **Consequences:** (1) The R10 driver fix was chasing a phantom; reverted, driver pristine. (2) The `reproduce (nio)` test is a misnomer (it reproduces nothing) - it is now a resilience assertion that passes; rename/reframe. (3) OPEN: does the reporter's snapshot +86 genuinely wedge (a real bug since FIXED between +86 and +90) or was it also mis-detected? Running repro-verify against +86 to settle it.

- **LEAK VERDICT (verified on podman, not assumed): the 1200-fd leak was TEST HYGIENE, not a driver bug.** With R3b/R4 closing their connections on the timeout path, the transport suite passes podman's strict leak check clean: 24 passed / 0 failed / 8 cancelled on nio+epoll+io_uring, `[success]`, no LeakCheck$Detected. The earlier skip-close run failed the same check with 1200 leaked sockets. So closing the sockets eliminates the leak: it was the tests not closing, not cancellation stranding driver resources. The `close-on-timeout`/`drain` edits are correct hygiene, NOT work-arounds (my earlier label was wrong); they stay. The drain LOOP is fine (no spin); the earlier kqueue timeouts were parallel-scenario interference, fixed by `sequential`.
- Net: transport suite green + leak-free on podman (all 3 Linux drivers). The single real bug is the driver wedge, reproduced by the enabled `reproduce (nio)` HTTP test.

- **REPRODUCED IN THE SUITE (fibers).** Correcting the earlier wrong conclusion: R7 (HTTP pooled client + firing timeout + 25ms server restart) DID wedge nio on a later run (19 driver-closed hits; racy: 0 on an earlier run), clean on kqueue. Then added a DEDICATED tightened reproduction (nio, 10ms churn + 30ms firing timeout, early-exit): reliably wedges in ~204ms with 13 hits. Marked `.ignore` (documented, manual; reproduces an unfixed bug so it is red by design). This confirms the mechanism end-to-end at the HTTP level with fibers: NioIoDriver wedges, PollerIoDriver does not.
- R8 HTTP scenarios added (request-cancellation containment, pooled-connection integrity after a cancelled request, handler-failure isolation): all green nio+kqueue.
- OPEN: R7 (general 'survives churn', eachBackend) is racy-red on nio since it reproduces the same unfixed bug; left unchanged per direction. Stabilizes (goes green) once the driver is fixed (R10). Now that we have a reliable reproduction, R10 (contain per-connection dispatch errors in NioIoDriver.dispatchReadyKeys like PollerIoDriver) is validate-able reproduce-before-fix. R10 is gated (production driver change).

- **R7 (HTTP fibers reproduction, pooled client + firing 50ms timeout + 25ms server restart) PASSES on [nio] at 3s: 0 driver-closed hits.** So fibers+pool do not reproduce the wedge either. R6 smoke also passes (nio+kqueue). Running a 30s variant to give fibers max invocations before concluding. If it stays green, the evidence is conclusive: the wedge needs real-OS-thread selector pressure (16 threads blocking in runAndBlock), which the cooperative fiber scheduler does not create; fibers-only and reproduce-in-suite are then in conflict (user decision). Reproduction test marker to use if it ever reproduces: `.ignore(reason)` (NOT pendingUntilFixed, which runs the body).
- **Honest transport run (work-arounds undone) = 16 passed / 0 failed on nio+kqueue.** The earlier isolation-[kqueue] "hang" was self-inflicted by the single-take-drain + close-on-timeout work-around; reverting removed it. So that candidate finding was a false alarm I introduced, not a transport bug.

- **Work-arounds undone (per user): stop tuning green, expose real behavior as findings.** (1) drain/registeringDrain reverted from single-`take` back to the read LOOP, to expose what `conn.inbound.safe.take` does on peer-close/EOF (a read-only loop that spins there would be a real transport finding; `echo` survives only because its `put` aborts on close). (2) Client close-on-timeout removed (`Async.timeout(...).andThen(conn.close())`, close SKIPPED on timeout-abort), to re-expose the fd leak and determine whether interrupting an in-flight read leaks the DRIVER-SIDE armed read (a real cancellation-cleanup bug) vs merely my unclosed socket. Candidate findings to root-cause next: (A) take-on-EOF read-loop behavior; (B) interrupt/timeout leaves driver arm stranded?; (C) isolation-[kqueue] cumulative strand.

- **Podman transport result: wedge does NOT reproduce even under CI-faithful Linux constraints.** `--env podman-ci` ran nio/epoll/io_uring (kqueue cancels on Linux): 27 passed / 0 failed. So CI scheduling pressure alone does not trip it at the transport level. Reproduction is firmly an HTTP-layer phenomenon (pool + restart + firing timeout).
- **Leak found + fixed.** The podman run's kyo-test leak check caught a 1200-fd leak from my transport scenarios: (1) `Async.timeout(...).andThen(conn.close())` skips the close when the timeout FIRES (abort short-circuit) so R3b/R4 leaked every client fd; (2) silent server handlers never closed accepted connections. Fix: a `drain` server helper (reads+discards, closes on peer-close) replaces the silent handlers, and the client catches the timeout out of the abort so `conn.close` always runs. NOTE: the local macOS run did NOT catch this (fd-leak check is Linux-strict); podman is the gate for leak validation.
- Real-threads transport scenario removed (fibers-only).
- R6: opaque `HttpClient` bridged via a cast (`asInstanceOf[HttpClient]`) since it is `opaque type HttpClient = HttpClientBackend` with no public per-transport factory. Smoke scenario added.

- R6 in progress: `HttpServerResilienceTest` rewritten with an HTTP-level `eachBackend` (one `HttpClientBackend.init` client per backend over the shared `TestBackends` transport, never closed; per-scenario servers via `HttpServer.Unsafe.init`; `HttpClient.let` binds the ambient client). Smoke scenario ("healthy GET round-trips") added; compiling + running across backends locally.
- Podman: running `scripts/build.sh --env podman-ci sbt 'kyo-netJVM/testOnly kyo.net.TransportResilienceTest'` to check whether CI-faithful scheduling pressure trips the wedge with fibers (result pending).
- Decision recorded: fibers only; reproduce via podman.

## Log
- (init) Doc created. Findings above verified in code + scala-cli repro matrix. Next: R1 (build dep), then R2 rename.
- Branch `transport-http-resilience-tests` created off main; WIP checkpoint `9ee028fbe7` preserves the draft tests + this doc.
- R1: build.sbt:2137 now `.dependsOn(`kyo-net` % "compile->compile;test->test")`. R2: both files renamed (`TransportResilienceTest`, `HttpServerResilienceTest`) + class names updated. Compile running to validate R1+R2.
- Cancellation idiom confirmed (TransportUnsafeTest:125-131): `Fiber.init(Abort.run[Closed](conn.inbound.safe.take).unit)` then `fiber.interrupt`. R4 races this against a peer poke+close on the same fd.
- R3 added (interrupt parked read; firing Async.timeout on a silent server). Both PASS on nio + kqueue. Good broad cancellation coverage.
- **KEY FINDING (R4): the wedge does NOT reproduce at the raw transport level.** R4 with corrected staging (silent server so reads stay armed + firing `Async.timeout` + churn RST) PASSES on nio and kqueue (18 passed / 0 failed / 18 cancelled all-backends). The existing real-threads transport scenario also PASSES. Diagnosis: interrupting `conn.inbound.safe.take` cancels the stream CONSUMER, not the driver's armed read / `closeHandle` (the ReadPump keeps the arm alive). `closeHandle` (the cross-carrier race site) is reached only by connection `close()` or in-flight connect cancel, and at fiber-scale concurrency the race window with `dispatchReadyKeys` is not hit. The scala-cli HTTP harness reproduces reliably because it adds: pooled/keep-alive connections, a full server restart (listener replaced), a per-request timeout that FIRES on hung requests, and real-thread selector pressure.
- **Consequence:** the reported-bug reproduction belongs in `HttpServerResilienceTest` (R7), modeled on the proven scala-cli harness, NOT in the transport suite. The transport suite stands as broad cancellation/churn coverage (all green = transport is robust to these shapes). Open question for the user: my current HTTP test used fibers with NO firing timeout (false negative, 0 hits); R7 will add per-request firing timeouts + server restart; if fibers still will not trip it, the reproduction may require real threads for that one scenario (the bug demonstrably needs that pressure).

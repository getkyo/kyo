# Failures found by the resource-safety test campaign

Branch `worktree-effervescent-painting-backus`. Every failure below is a `pendingUntilFixed` leaf whose body was seen to fail on
the platforms listed, with the production window named by file and line. Production code was not changed; the fixes are
the user's to rule on and are prepared in a separate worktree for a live review. The audit that seeded the campaign is
`resource-safety-report.md` (findings K, C, D), the audit of the pending leaves is `pending-audit.md`, and the per-spec
outcomes are the review notes in the resource-safety report.

The mechanism behind almost every entry is one: a resource is produced in one step and its release is registered in the
next, and a stop landing on the poll between them abandons the continuation without running that step. The kernel's own
answer to it is `ensureMap`, which applies in the step the value arrives; the sites below either use `map` there or take
the value through a join, which delivers into a step of its own.

## Reproduced, pending

### Kernel and core

| Leaf | Window | Platforms seen | Fix shape |
|---|---|---|---|
| `SyncTest`, "a caller's ensureMap after the region runs when the interrupt lands as the region ends" (K3) | `Sync.ensure` and `Sync.acquireReleaseWith` raise the recorded abort in a `.map` after the bracket region: `Sync.scala:105`, `:162` | JVM, JS, Native, Linux | `.ensureMap` in place of `.map` at both sites |
| `ScopeInterruptTest`, "an interrupt at Scope.run's drain await does not strand the value the body produced" (C1) | `Scope.run`'s clean exit is `close.andThen(await).andThen(Abort.get(result))`, a join with the body's value in flight: `Scope.scala:158-166` | JVM, JS, Native, Linux | deliver the value before or through the drain await without a poll between |
| `AsyncTest`, "a value the shielded body produces is not stranded when the caller is interrupted at the join" (C4) | `Async.uninterruptible` is `initUnscoped(v).map(_.uninterruptible.map(_.get))`: the shielded value arrives through a join: `Async.scala:130-135` | JVM, Native | the masked promise's value handed to the caller's `ensureMap`, or a documented shape |
| `AsyncTest`, "an interrupt landing at the timeout's spawn reaches the guarded computation" (new, C6 family) | `Async.timeout` wires and joins the child in one step; a stop requested during that step is observed in front of the join, before the join links the child: `Async.scala:186-205` | JVM, Native | link the child to the caller before the join is reached, or make the stop check at a join link first |
| `KyoAppTest`, "runAndBlock's timeout does not leave the forked computation running" (T6) | `KyoApp.runAndBlock` forks and reports `Timeout` from `block` without interrupting the fiber: `KyoApp.scala:29-35` | JVM, Native, Linux | `Sync.acquireReleaseWith(initUnscoped(v))(_.interrupt)(_.block(timeout))`, the `Fiber.use` shape |
| `AsyncCombinatorsTest`, "interrupting the caller of async interrupts the effect it registered" (C2) | `Kyo.async` spawns the registered effect with `Fiber.initUnscoped` and never links it: `Constructors.scala:44-50` | JVM, JS, Native, Linux | register `promise.onInterrupt(effFiber.interrupt)` in the spawning step |
| `ScopeTest`, two leaves under "under a handler that replays" | `Scope.run` closes its scope per shot of a replaying handler, so the second shot registers on a closed scope: `Scope.scala:136-166` | JVM, JS, Native, Linux | move the close into the region's `release` hook so a replaying handler holds it |
| `ScopeTest` (#1723) and `StreamCoreExtensionsTest` (#1398) | the recorded decision: no backpressure on abnormal exit, the async release runs on a detached drain | all | a ruling: keep (convert to green pins) or reverse (an awaited drain on the discard path) |
| `FiberTest`, "a carrier spawned from a running computation carries the spawning chain's frames in its failure" | `Fiber.Unsafe.init` captures no trace; the scaladoc at `Fiber.scala:435-436` still promises it | all | a ruling: restore the capture or change the documented behavior |

### Result

| Leaf | Window | Platforms seen | Fix shape |
|---|---|---|---|
| `ResultTest`, "a success carrying a Panic" (four leaves); `AbortTest`, "a Result.Panic the body produces as a value is not taken as the run's own panic" | `Result.Success.apply` boxes a `Failure` into `SuccessError` and lets a `Panic` through unboxed: `Result.scala:190-194` | JVM (JS and Native runs in progress) | box `Panic` the way `Failure` is boxed, and audit the unboxed reads (`flatten`, `foldError`) for the same asymmetry |

This one is independent of the kernel and pre-existing by reading. It surfaced because every leaf that joins an interrupted
fiber inside a run hands a `Result.Panic` on as data.

### Modules

| Leaf | Window | Platforms seen | Fix shape |
|---|---|---|---|
| `SqlClientInterruptTest`, "leases stopped at staggered offsets leave a pool that still serves and closes clean" | a lease reserves a slot in the step that finds the ring empty (`SqlConnectionPool.scala:577`) and releases the reservation two steps later in the `resolvingOnce` around the connect (`:490-493`); sixty stops exhaust a pool of two | JVM, real Postgres | register the reservation's release in the reserving step |
| `HttpServerTest`, "an interrupt landing as the listener binds leaves no listener behind" (D11 server) | `initUnscoped` joins the listen fiber and maps the bound server in a later step; `init`'s `acquireRelease` covers only the last step: `HttpServer.scala:135-148` | JVM, Native, Linux | register the listener's close in the step that delivers it |
| `HttpServerTest`, "an interrupt landing as the client's connection completes leaves no connection behind" (D11 client) | `poolWithImpl` joins the connect fiber and tracks the connection in the step the join delivers it: `HttpClientBackend.scala:1202-1212` | JVM, Native | track in the producing step (the connect callback), as the `takeSlot` custody shape does |
| `JsonRpcTransportUnixTest`, "an interrupt landing as the listener binds leaves no listener or socket file behind" (D13) | `UdsBackend.connect` binds in the step that starts the listen fiber and registers the release after the join: `UdsBackend.scala:21-34` | JVM, JS, Linux | `Scope.acquireRelease(listenUnix(...).safe.get)(...)` where the bind is synchronous, registration in the listen fiber where it is not |
| `CommandTest`, "an interrupt landing during spawn does not orphan the process" (D18) | `Command.spawn` forks in one step and registers the release in the next: `Command.scala:67-79`; `Command.stream` the same | JVM, Native, Linux | make the fork the acquire of `Scope.acquireRelease` |
| `SpawnBackendTest`, "an interrupt landing before the kill is armed does not orphan the worker JVM" (D16) | the worker is spawned, then the aeron connect and the exchange park, then the kill is armed: `SpawnBackend.scala:89-105` | JVM | arm each release in the step that produces its resource |
| `AeronTransportTest`, "a publication the add hands on under a stop is closed by someone" (D9) | the add-deadline guard hands the publication on at its clean end and `Topic`'s `ensureMap` takes it over after `Sync.ensure`'s trailing poll: `Topic.scala:256`, `:429-434` | JVM | the K3 fix closes it |
| `CdpBackendInterruptTest`, "an interrupt landing at the context creation reply still disposes the context" (D7) | `attachAndSetupTab` registers the disposal in the step after the creation reply: `BrowserTab.scala:243-246` | JVM, Native | register through `ensureMap` on the reply |
| `CdpBackendInterruptTest`, "an interrupt landing at the viewport override reply still restores the viewport" (D8) | `withViewport` registers the restore in the step after the override reply: `Browser.scala:2259-2280` | JVM, Native | the same |

## Green pins worth knowing about

`Channel.takeWith` is the ownership boundary and `take` hands the element to a continuation a stop can drop whole;
`Queue.close` survives an interrupted join; `Hub.listen`'s registration and `Hub.use`'s spawn settle their caller;
`HttpServer.init` over a synchronous bind is covered by `acquireRelease`; the Chrome launcher's name sweep kills a Chrome
the stop orphaned; the jsonrpc endpoint's close interrupts a dispatching handler; the flow engine's close stops its
supervisions; aeron connects stopped at staggered offsets leave a driver that closes; the peeled remainder shape is safe at
the kernel and core levels; a fatal releases a fiber's finalizers before the promise settles; `raceFirst` stops a
never-parking loser in 4000 rounds (one loaded run had shown one left spinning, not reproduced since).

## Not reproduced, kept as findings by reading

`mapPar` element fibers, `PubSub` subscriptions, the sql warm-up handover, advisory lock grant and `closeAll`'s ring
extraction (D1: the guarded leaf is green over 40 rounds of stops at 50 microsecond offsets; its first failure was a
stop landing before the close began, which the leaf now excludes), the aeron native client, the http fiber trios and
decoder (no observation point), `Hub.use`'s orphaned publisher (no observation point).

## CI consequences

- Three Linux JVM legs fail the fork-wide descriptor probe on what their pending leaves leak: kyo-jsonrpc and kyo-http on
  the listeners, kyo-sql-postgres on the five established sessions a pool exhausted by lost reservations cannot close
  within the leaf's bound. The leak check works where it runs; it never ran on the macOS machines the branch was validated
  on. All three fixes are the registration shape above.
- The http client leaf reads `/proc/net/tcp` on Linux and `lsof` elsewhere, and cancels where neither exists.
- The `Result` collapse means any leaf or program that passes a fiber's `Result` through a run reads a stop as its own
  panic; the affected test leaves were rewritten to hand a `Boolean` on instead.
- Leaves that land their stop by spinning are JVM and Native only: a spinning test fiber never lets the spawner run on a
  single-threaded runtime. The `.notJs.notWasm` gates are the reason, not a coverage choice.
- A pending leaf whose body passes fails its suite. Every sampled leaf above was rerun three or more times per platform and
  reproduced each time after its interrupter was reshaped; the shapes that missed (timer delays, latch-woken fibers) are
  recorded in the review notes.

## Fix order by dependency

1. `Result.Success.apply` boxing a `Panic`: independent, and it removes a false reading from every other fix's tests.
2. `Sync.ensure` and `Sync.acquireReleaseWith`'s trailing `map`: `SpawnBackend`, `runAndBlock` and the aeron hand-off route
   through it.
3. The `Scope.run` decisions together: the clean-exit handover, the per-shot close, the backpressure ruling.
4. The registration sites, independent of each other: `Command.spawn` and `stream`, `SpawnBackend`, `UdsBackend`,
   `HttpServer.initUnscoped`, `HttpClientBackend.poolWithImpl`, `Kyo.async`, `KyoApp.runAndBlock`, the sql reservation and
   `closeAll`, the two browser sites, `Async.timeout`'s join link, `Async.uninterruptible`.
5. The trace ruling and the `Choice.runStream` order, once decided.

# Audit of the pendingUntilFixed leaves

Branch `worktree-effervescent-painting-backus`, tree as read on 2026-09-18 at commit `645ae4d1de`. Analysis only: nothing was built or run by this audit. Every claim below was checked against the source at the cited line; where a claim would need a run to settle, it is stated as an open question. Run results attributed to the team lead come from their `scripts/build.sh --env podman-ci` runs and the commit messages of `226f5521b0` and `645ae4d1de`, not from anything run here.

Semantics assumed throughout: a kyo-test leaf marked `pendingUntilFixed` runs its body once; a body that fails, times out or cancels reports Pending, and a body that passes fails the suite with "test marked pendingUntilFixed now passes" (`kyo-test/runner/shared/src/main/scala/kyo/test/runner/TestRunner.scala:493-506`). Leaf 12 lives in a ScalaTest `AnyFreeSpec` suite (`kyo-kernel/shared/src/test/scala/kyo/kernel/ContextEffectTest.scala:8`) and uses ScalaTest's block form, which inverts the same way.

The end-of-run probes referred to in every section (d): `SbtRunner.runEndOfRunChecks` (`kyo-test/runner/jvm/src/main/scala/kyo/test/runner/internal/SbtRunner.scala:112-163`) runs once per forked JVM. A category runs when any suite in the fork enables it (`SbtRunner.scala:120-123`); every category defaults to on (`kyo-test/api/shared/src/main/scala/kyo/test/RunConfig.scala:92-96`). The descriptor probe reads `/proc/self/fd` and is a no-op elsewhere (`LeakCheck.scala:48-51`); a socket target is reported only under the socket category (`LeakCheck.scala:299-300`). The thread category is not platform gated (`LeakCheck.scala:454-457`). A parked fiber is invisible by design (`LeakCheck.scala:25-26`). `StrandedOpCheck` classifies only kyo-net driver loops whose pending work survives a frozen cycle counter (`StrandedOpCheck.scala:39-44`).

## 1. AsyncCombinatorsTest:123, the Kyo.async orphan

Leaf: `kyo-combinators/shared/src/test/scala/kyo/AsyncCombinatorsTest.scala:123-141`.

**a. Root cause.** `Kyo.async` spawns the registered effect with `Fiber.initUnscoped(eff)` and a second helper fiber `Fiber.initUnscoped(updatePromise)` that wires `onComplete` into `promise` (`kyo-combinators/shared/src/main/scala/kyo/Constructors.scala:44-50`). `Fiber.initUnscoped` is deliberately unparented (`kyo-core/shared/src/main/scala/kyo/Fiber.scala:189-194`). The caller parks on `promise.get` (`Constructors.scala:52`); an interrupt of the caller links the join and cascades into `promise` (`kyo-core/shared/src/main/scala/kyo/Async.scala:830-834`, `IOPromise.scala:63-73`), which completes `promise` with the interrupt and reaches neither fiber. The reason string is accurate. It omits that there are two unparented fibers, the helper as well as the effect's.

**b. Classification.** Pre-existing on `origin/main` by reading: the same lines exist there (`git show origin/main:kyo-combinators/shared/src/main/scala/kyo/Constructors.scala`, lines 41-52 identical), and `initUnscoped` was unparented there as well (`origin/main` `Fiber.scala:174-178` runs `Isolate.internal.runDetached`). Not a kernel finalization change.

**c. Determinism.** Deterministic. `entered.await` (`:135`) proves the registered effect is running and parked on `gate`; nothing links it to the caller, so `released` stays false until the leaf's own `gate.release` at `:139`. The body waits the real 2 seconds of `Async.timeout(2.seconds)` at `:138` and fails. No platform dependence.

**d. CI interaction.** Nothing is left behind: the leaf releases `gate` after the assertion window, the orphan finishes and its finalizer runs. No probe would report anything on any platform.

**e. Fix shape.** In the `registerFn` at `Constructors.scala:44-50`, do the spawn and the two registrations in one unsafe step: spawn the effect's fiber (`Fiber.Unsafe.init` or `evalOrThrow(initUnscoped)`), register `effFiber.onComplete(promise.completeDiscard)`, and register `promise.unsafe.onInterrupt(e => effFiber.interrupt(e))` so the caller's abandonment, which already interrupts `promise` through the join link, reaches the effect. The helper fiber is then unnecessary. About 8 lines. The assertion as written passes afterwards: the effect's `Sync.ensure` release runs on its abandonment walk, `released` becomes true before the 2 second bound, `r.isSuccess` holds.

**f. Test quality.** The name states the property. The assertion is on the concrete finalizer flag. Real clock: the 2 second `Async.timeout` at `:138` is a bound on a condition that will never hold in the failing state, so every pending run costs 2 seconds; acceptable, but note it. No platform gating.

## 2. KyoAppTest:192, the runAndBlock timeout orphan

Leaf: `kyo-core/shared/src/test/scala/kyo/KyoAppTest.scala:192-201`.

**a. Root cause.** `KyoApp.runAndBlock` is `Fiber.initUnscoped(v).map(fiber => fiber.block(timeout).map(Abort.get(_)))` (`kyo-core/shared/src/main/scala/kyo/KyoApp.scala:34-35`). `block` returns `Result.fail(Timeout())` when the deadline is overdue without touching the fiber (`kyo-core/shared/src/main/scala/kyo/scheduler/IOPromise.scala:268-269`). Only the thread-interrupt path interrupts the fiber (`IOPromise.scala:279-284`). The reason string is accurate for the timeout path. It omits the second window: the `.map` at `KyoApp.scala:34` polls after the spawn, so a stop delivered during the spawn orphans the fiber before `block` is reached (the C2 shape of the resource-safety report).

**b. Classification.** Pre-existing on `origin/main` by reading: `KyoApp.scala:29-35` is identical there.

**c. Determinism.** Deterministic once the forked fiber has parked on `gate`. The pass condition is `gate.waiters == 0` observed at any time (`:199`), and a fiber that has not yet reached its park has registered no waiter (`IOPromise.scala:244-252`). The forked fiber has 10 ms of real time to park (`:197`); on a loaded 4 vCPU CI runner, or on Native where the same `Worker` scheduler runs (`kyo-scheduler/jvm-native`), a first sample taken before the park would make the body pass and fail the suite. The risk is small but real. A shape that removes it without production changes: run `started.release.andThen(Sync.ensure(released.set(true))(gate.get))` under `runAndBlock`, loop rounds until one round observed `started`, and in that round assert `assertEventually(released)`. Today `released` never becomes true (deterministic failure); after the fix the fiber is interrupted on timeout and its finalizer runs. A round whose fiber never started samples nothing and is retried, the shape `ScopeInterruptTest:327` already uses.

**d. CI interaction.** The failing body leaves one fiber parked on an in-memory promise for the process's life. Off-scheduler, no thread, no descriptor: no probe reports it on any platform.

**e. Fix shape.** `KyoApp.scala:34-35` becomes `Sync.acquireReleaseWith(Fiber.initUnscoped(v))(_.interrupt)(fiber => fiber.block(timeout).map(Abort.get(_)))`, the shape `Fiber.use` already uses for the same reason (`Fiber.scala:169-171`). This closes both windows: the bracket installs as the spawn's value arrives (`kyo-kernel/CONTRIBUTING.md:160`), and the release interrupts the fiber on every ending, including the timeout. About 4 lines. The assertion as written passes afterwards: interrupting the parked fiber cascades into `gate` through the join link (`Async.scala:832`, `IOPromise.scala:63-73`), which completes it, so `waiters` reads 0. Note the leaf then depends on that cascade rather than on the fiber merely being gone; the shape in (c) asserts the fiber's own release instead.

**f. Test quality.** The name states the property. Real clock: the 10 ms block is intrinsic to `runAndBlock` and the comment at `:186-190` says so; the 2 second bound at `:199` is a real wait on the failing path. Gating: `.notJs.notWasm` is required because `block` parks a thread; Native is covered.

## 3. ScopeInterruptTest:426, the Scope.run clean-exit drain await

Leaf: `kyo-core/shared/src/test/scala/kyo/ScopeInterruptTest.scala:426-447`.

**a. Root cause.** `Scope.run`'s clean path is `.handle(Abort.run[Any]).map { result => finalizer.close(result.error).andThen(finalizer.await).andThen(Abort.get(result)) }.handle(Sync.ensure(finalizer.close))` (`kyo-core/shared/src/main/scala/kyo/Scope.scala:158-166`). `close` spawns the drain in a detached fiber and returns (`Scope.scala:301-331`); `await` is `promise.get` on an uninterruptible promise (`Scope.scala:333`, `:262`). An interrupt while parked there abandons the continuation holding `result`; the backstop finds the queue already claimed and does nothing; `Abort.get(result)` and everything chained after it never run. The reason string is accurate. It omits a second window with the same consequence: the `.map` at `Scope.scala:160` polls before `close`, so a stop delivered between the body's last step and that poll abandons the continuation with `result` captured, the backstop closes the scope, and the value is stranded the same way.

**b. Classification.** Pre-existing on `origin/main` by reading: the clean path there is the same `close.andThen(await).andThen(Abort.get(result))` (`origin/main` `Scope.scala:134-140`), and the old kernel did not resume an interrupted continuation either (`origin/main` `IOTask.scala:72-74, 137-138`). The join is a scheduling round trip, so the window is the same width under both kernels. A run on main would settle whether the old kernel delivered the value some other way; nothing read suggests it did.

**c. Determinism.** Deterministic. `draining.await` (`:439`) holds until the inner finalizer runs on the drain fiber, which proves `close` has been called and the drain is parked on `gate`. Whatever step the outer fiber is on when `fiber.interrupt` lands (`:440`), it is at or after `close`, and every remaining step is a poll or a join, so the continuation is abandoned and the `ensureMap` at `:436` never runs. `closes` stays 0.

**d. CI interaction.** Nothing left behind: `gate.release` at `:441` completes the drain. No probe reports anything.

**e. Fix shape.** No change inside `Scope.run` can hand an arbitrary `A` across a join that an interrupt may abandon: an abandoned parked fiber never resumes (`ScopeInterruptTest:400-421` pins that), and the abandonment delivers nothing by design (`IOTask.scala:383-391`). The honest fix is an owned-handover entry point: a `Scope.run` overload that takes the produced value's release and registers it on the enclosing scope through `ensureMap` in the step the body's value arrives, before `close` and `await`, so the value is owned across the join; the existing `Scope.run` gets a scaladoc sentence saying its clean exit awaits a join with the value in flight and that a value meant to outlive the run must be registered from inside the body on the enclosing scope. About 40 lines in `Scope.scala:136-166` plus the call sites the resource-safety report names under D3 (`kyo-sql` `Runtime.init` and `SqlClient.openScoped`). The technically different alternative, dropping the clean-path `await` and relying on the enclosing scope closing and awaiting its children (`Scope.scala:307-315`), removes the join but changes the documented ordering that #1723 decided to keep on the normal path (`ScopeTest:1370-1376`). Under either fix the leaf as written does not pass: it registers after `Scope.run` returns (`:436`), the shape that cannot be made safe. The leaf should be rewritten to pin the new entry point's shape, or turned into a green pin of the documented limit with the current name changed to state it.

**f. Test quality.** The name states the property. The assertion is concrete (`c == 1`). Real clock: 2 second bound at `:443`. No gating. The leaf's expected behavior is one the current API cannot deliver; see (e).

## 4. ScopeTest:1377, a short-circuited scope and its async release

Leaf: `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1377-1400`.

**a. Root cause.** `Check.require(false, "boom")` (`:1390`) is answered by `Check.runAbort` outside the scope, whose clause never resumes; the discarded continuation's `Sync.ensure(finalizer.close)` region (`Scope.scala:166`) is released with the discard signal, `close` spawns the drain and returns (`Scope.scala:301-331`), and the clean path's `finalizer.await` (`Scope.scala:163`) is part of the discarded continuation, so nothing awaits the drain before the next effect. The reason string is accurate and names the decision.

**b. Classification.** A documented design decision, recorded in commit `cabfe3e1b2` ("By decision the redesign gives no backpressure on abnormal exit ... The resources still close; only the ordering is late") and in the leaf's own comment (`:1370-1376`). It is a consequence of the new finalization model: a discarded region can only run a synchronous release (`kyo-kernel/CONTRIBUTING.md:301`), and the async drain has to be detached. The pending marker is not the right carrier for a decided behavior: the marker announces a fix, and it stays pending only by winning a race every round (see c). The decided behavior already has green pins (`StreamCoreExtensionsTest:985-1000` waits with `assertEventually` for exactly this drain; `ScopeTest:1406-1424` pins the delivered ordering half). If the decision is final, this leaf should be removed or converted to a green pin of "the release completes after the handler returns" with `assertEventually`. If backpressure on abnormal exit is still intended, the leaf is a legitimate tripwire but see (c).

**c. Determinism.** Sampled. Each of 50 rounds (`:1382`) races the detached drain (spawn, `queue.close`, a nested fiber spawn and join, then `released.set`) against the caller's unwind and `released.get` (`:1395`). The body fails if any round reads false. On the JVM the drain needs at least two scheduler hops and the caller needs a handful of steps, so a round reading true is rare and all 50 reading true is negligible; on JS the drain cannot run before the caller's next step, so it is deterministic there. The commit that made this shape (`cabfe3e1b2`) records that the earlier single-round shape flipped red about one run in six, which is why the finalizer joins a fiber first (`:1389`). A deterministic version is not available without production changes: the property is "the next effect ran before the drain finished", and forcing the drain to wait on a latch the test releases after the read makes the fixed behavior deadlock rather than pass, so the tripwire would never fire.

**d. CI interaction.** Nothing left behind: every drain completes shortly after its round. No probe reports anything.

**e. Fix shape.** If the decision is reversed, the discarded-continuation path needs an awaited drain, which the kernel's synchronous release hook cannot provide (`ContextEffect.scala:198`, `:216`; `Bracket.scala` releases are `Unit`). It would need either an async release lane in the kernel or a Scope-level design where the outer handler's own end awaits the child scopes it discarded. Not shaped by anything in the tree today; not sized here.

**f. Test quality.** The name states the property. The assertion is concrete. The loop makes the body's outcome depend on scheduling, which is the point, and is the reason the marker is fragile. No gating.

## 5. ScopeTest:1553 and :1575, Scope.run under a replaying handler

Leaves: `kyo-core/shared/src/test/scala/kyo/ScopeTest.scala:1553-1573` and `:1575-1598`.

**a. Root cause.** `Choice.run` is a `handleCont` (`kyo-prelude/shared/src/main/scala/kyo/Choice.scala:97-103`). Its dump moves the releases of the regions it captures onto the handler's entry and runs them once at its end (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Stack.scala:292-297, 308-312`; `kyo-kernel/CONTRIBUTING.md:152`), so the `Sync.ensure(finalizer.close)` region (`Scope.scala:166`) is held correctly. But the clean-path close is not a release: it is the continuation step `.map { result => finalizer.close(...) ... }` at `Scope.scala:159-164`, replayed once per shot. Shot 1's end closes the queue; shot 2's `Scope.ensure` reaches `ensureUnsafe`, whose `queue.offer` fails, which runs the finalizer detached and throws `Closed` (`Scope.scala:254-272`). The reason strings are accurate.

**b. Classification.** The expectation is set by the new kernel's replay contract (`SyncTest:255-294`, `CONTRIBUTING.md:343`), and the leaf comment says the shape is the same on main (`:1545-1551`). By reading, `origin/main` `Scope.scala:134-140` has the same per-shot close in the continuation, so the refusal predates the branch; a run on main would settle it. This is a gap, not a decision: `Scope.run` is the one holder of a release that does not go through the region model.

**c. Determinism.** Deterministic. The second shot's registration is refused every time; `Abort.run[Closed]` at `:1558` and `:1581` catches it, and `res` is not `succeed(Chunk(1, 2))`. The `assertEventually` at `:1567` and `:1592` holds in the failing state too, because the detached run appends the second entry.

**d. CI interaction.** Nothing left behind: the refused registration runs its finalizer detached. No probe reports anything.

**e. Fix shape.** Move the close into the Scope region's `release` hook (`ContextEffect.handle`'s `release`, `ContextEffect.scala:211-216`), so a replaying handler holds it and runs it once after every shot, and a single-shot run closes at its own clean end before the pop (`Eval.scala:370-387`). Two things have to survive the move: the typed failure the finalizers currently receive through `close(result.error)`, which the region release cannot see because a typed abort caught inside reaches it as a clean end, so the `Abort.run[Any]` stays inside the region and records the error in a per-run slot the release reads, the pattern `Sync.ensure` already uses (`Sync.scala:145-161`); and the clean-path backpressure `await`, which must run only when the release actually ran, since under a held release a per-shot `await` would park forever waiting for a close that happens at the holder's end. A `Finalizer.await` that returns immediately while the queue is still open expresses that. About 40 lines in `Scope.scala:136-166, 262, 333`. Both assertions as written pass afterwards: no refusal, both registrations on one open queue, both released at the holder's end, and `seen` records 0 releases in each branch.

**f. Test quality.** Names state the property. Assertions are concrete and, in the second leaf, cover the live-region half (`:1597`). No real clock beyond `assertEventually`'s bound. No gating.

## 6. StreamCoreExtensionsTest:1033, the take finalizer, #1398 Scope form

Leaf: `kyo-core/shared/src/test/scala/kyo/StreamCoreExtensionsTest.scala:1033-1058`.

**a. Root cause.** `take` ends the source by returning `Loop.done(())` from its clause without resuming (`kyo-prelude/shared/src/main/scala/kyo/Stream.scala:243-257`), which discards the emitter's continuation; the `Scope.run` inside the stream (`:1040`) leaves by the discard path of leaf 4: the `Sync.ensure(finalizer.close)` release spawns the drain, nothing awaits it, and the log is read after `run` returns (`:1050-1053`). The reason string is accurate and names the decision.

**b. Classification.** The same decision as leaf 4 (`cabfe3e1b2`; the leaf comment at `:1028-1032` says so). Same verdict on the carrier: a green pin of the decided behavior exists three leaves above (`:985-1000`); this leaf pins the ordering the decision rejects. Remove or convert unless the decision is reopened.

**c. Determinism.** Sampled, 50 rounds (`:1037`), same race and same reasoning as leaf 4: the finalizer joins a fiber first (`:1042`) so the drain cannot win with one hop; failing is near certain on the JVM and certain on JS.

**d. CI interaction.** Nothing left behind. No probe reports anything.

**e. Fix shape.** As leaf 4: nothing in the tree shapes an awaited drain on a discarded continuation.

**f. Test quality.** The name states the property. The assertion is the ordered log, which is the right criterion (the comment at `:1005-1006` explains why counts are not enough). No gating.

## 7. SyncTest:618, Sync.ensure's trailing poll (K3)

Leaf: `kyo-core/shared/src/test/scala/kyo/SyncTest.scala:618-647`.

**a. Root cause.** `Sync.ensure` is `Bracket.ensuringWith(...) { ... }.map(result => Abort.get(result))` (`kyo-core/shared/src/main/scala/kyo/Sync.scala:145-162`); `acquireReleaseWith` has the same trailing `.map` (`Sync.scala:89-105`). `map` polls before applying its function (`kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala:69-72`), and the region's clean end runs the release before the pop (`Eval.scala:370-387`), so a stop requested while the finalizer runs (`:631`) parks on that poll. The release walk stops at the settled value (`Eval.scala:682-688`). The caller's `ensureMap` at `:634` is chained after the poll and never runs. The reason string is accurate and complete.

**b. Classification.** Caused by the new kernel's composition. On `origin/main` `Sync.ensure` was `Unsafe.defer(Safepoint.ensure(...)(v))` with no trailing map (`origin/main` `Sync.scala:108-111`); the bracket-plus-map shape arrived with commit `15d414b14f` ("Sync.ensure over Bracket.ensuringWith").

**c. Determinism.** Deterministic on every platform: the interrupt is requested from inside the finalizer on the same thread (`:631`), the finalizer runs in an unarmed nested eval that does not consume the stop (`Sync.scala:159`, `Eval.scala:40-42`), and the next poll is the trailing map. Single fiber, no second thread.

**d. CI interaction.** Nothing left behind: the fiber is abandoned with no resource. No probe reports anything.

**e. Fix shape.** Replace `.map(result => Abort.get(result))` with `.ensureMap(result => Abort.get(result))` at `Sync.scala:162` and `:105`, and add a scaladoc sentence to both saying a value a guard hands on at a clean end is owned only from the caller's `ensureMap`. Two production lines. The assertion as written does not pass afterwards. The caller's `ensureMap` body at `:634-636` records ownership with `owned.set(true)`, which is `Sync.Unsafe.defer(unsafe.set(v))` (`kyo-core/shared/src/main/scala/kyo/Atomic.scala:37`), a `Defer` node; the eval's `Defer` arm checks the stop before running a deferral's body (`Eval.scala:72-76`), and the stop is still pending, so the fiber parks in front of the write and `owned` stays false. The registration inside an `ensureMap` must be synchronous, exactly as `Scope.acquireRelease` does and says (`Scope.scala:84-90`): the leaf needs `owned.unsafe.set(true)` under `AllowUnsafe` (or a plain `var`) in the `ensureMap` body, returning the value. With that change and the two-line fix the leaf passes.

**f. Test quality.** The name states the property. The assertion is concrete. No real clock. No gating. The ownership recording is an effect where it must be a synchronous write; without that the leaf cannot detect the fix.

## 8. JsonRpcTransportUnixTest:32, the UDS listener left behind

Leaf: `kyo-jsonrpc/shared/src/test/scala/kyo/JsonRpcTransportUnixTest.scala:32-53`.

**a. Root cause.** `UdsBackend.connect` binds inside one `Sync.Unsafe.defer`, joins the returned `Fiber.Unsafe`, and registers the release in the `.map` after the join (`kyo-jsonrpc/shared/src/main/scala/kyo/internal/transport/UdsBackend.scala:21-34`). The reason string is right about the shape but wrong about the window on the JVM and Native: `listenUnix` binds, registers with the driver and completes the promise synchronously on the calling thread (`kyo-net/jvm/src/main/scala/kyo/net/internal/NioTransport.scala:1373-1387`; `kyo-net/shared/src/main/scala/kyo/net/internal/posix/PosixTransport.scala:927-954`), so there is no join wait; the exposure is the poll of the `.map` at `UdsBackend.scala:27` after the producing step (the C2 shape), which means the stop has to be delivered while the bind is executing. On JS the listen is asynchronous (`kyo-net/js-wasm/src/main/scala/kyo/net/internal/JsTransport.scala:832-838`) and the join is real, so there the reason string is exact.

**b. Classification.** Pre-existing on `origin/main` by reading: `UdsBackend.scala:20-29` is identical there.

**c. Determinism.** Sampled, and this is the leaf most likely to pass by accident. Rounds with a 0 ms delay (`:44`, 14 of 40) request the interrupt in the same step as the spawn; if the spawned task has not started, the stop is consumed before any step runs (`Eval.scala:52-54`, `IOTask.scala:278-282`) and nothing binds; if it has started, the interrupt lands anywhere in a window of a few hundred microseconds (open, register, bind, accept-loop start) before the poll. Rounds with 1 ms or 2 ms delays almost always land at `Async.never` after the registration, where the scope's own close removes the file. So the failing outcome depends on the 0 ms rounds racing the scheduler's dispatch latency, which is the same order of magnitude as the window. On JS, the 0 ms rounds interrupt before the fiber's first slice (single thread, no yield before `fiber.interrupt`) and the 1 ms and 2 ms rounds race Node's listen callback, which usually fires sooner. A 40 round body passing on a CI runner is a real risk, and when it passes the suite fails. A deterministic shape without production changes: none through the API, since the stop must land inside the transport's bind step and the `Defer` arm refuses to run a deferral once a stop is pending (`Eval.scala:72-76`), so a self-interrupt requested before the bind parks in front of it. The only in-process seam that fires inside the JVM window is the debug log line at `NioTransport.scala:1383`; a leaf that installs a `Log` sink requesting the fiber's own interrupt when that line is seen would land the stop deterministically on the JVM, at the cost of hanging a test on a log message.

**d. CI interaction.** The leaf ends at its first leaking round (`:48` throws), so a failing body leaves exactly one bound listener behind for the process's life: an open `ServerSocketChannel` (`NioTransport.scala:1374-1377`) whose accept loop is armed on the process-shared driver, reachable by nobody. The socket file itself is removed when the leaf's `Path.tempDir` finalizer runs at the leaf's `Scope.run` (`kyo-system/shared/src/main/scala/kyo/Path.scala:615-618`; the runner discharges `Scope` per leaf, `TestRunner.scala:381`). On the Linux JVM leg the descriptor probe sees a `socket:[inode]` that was not in the baseline (`LeakCheck.scala:283-291`); no suite in kyo-jsonrpc turns the socket category off (the only overrides are in `kyo-jsonrpc-http`, `kyo-pod`, `kyo-browser`, `kyo-ui`, `kyo-stats-otlp`, `kyo-slack`, `kyo-flow`, `kyo-caliban`), so the fork throws `LeakCheck.Detected` (`SbtRunner.scala:146`) and the kyo-jsonrpc JVM test task fails regardless of the leaf's pending status. Confirmed by a run the team lead reported from `scripts/build.sh --env podman-ci`: the kyo-jsonrpc JVM leg fails its fork with `file-descriptor leak (1): socket:[...] [unix /tmp/kyo-jsonrpc-uds-.../test.sock]` while the leaf itself reports pending. The consequence: on Linux CI this leaf makes the module red whether or not its body reproduces, since a body that leaks trips the probe and a body that does not leak trips the marker. Open question for JS: a leaked Node server handle keeps the event loop alive; whether the Scala.js test adapter tears the process down regardless was not checked.

**e. Fix shape.** On the JVM and Native, where the acquire is a single step, `Scope.acquireRelease(NetPlatform.transport.listenUnix(...).safe.get)(listener => close wire, close listener, remove file)` at `UdsBackend.scala:27-33` registers through `ensureMap` as the value arrives (`Scope.scala:84-91`), the shape `HttpServer.init` uses and that `HttpServerTest` pins (resource-safety report, D11 server half). About 6 lines. On JS, where the listen parks, that shape is the documented non-atomic case (report T31), and the complete fix is the custody shape: register the scope finalizer before calling `listenUnix`, fill a cell from the fiber's `onComplete` in the delivery step, and let whichever of finalizer and delivery runs second close the listener (the `takeSlot`/`withSlot` CAS custody at `kyo-sql` `SqlConnectionPool.scala:380-451`, and `db/Connection.scala:395-407` for the cell-before-open shape). About 25 lines. Either way the assertion as written passes afterwards on the platforms the fix covers, because an abandoned fiber's backstop closes the scope, whose finalizer removes the file within the 2 second bound.

**f. Test quality.** The name states the property. The assertion is on the socket file, which is what the listener's release removes; it is checked before the temp directory cleanup, so it is valid. Real clock: `Async.delay((i % 3).millis)` at `:44` samples wall-clock offsets, and the 2 second bound at `:46`. `assumeUnixSockets` cancels on platforms without AF_UNIX, which the runner reports as Pending under the marker (`TestRunner.scala:503`), fine. No `.notJs`-style gating hides coverage. The reason string should say "step" rather than "join" for JVM and Native.

## 9. CommandTest:290, the Command.spawn orphan

Leaf: `kyo-system/shared/src/test/scala/kyo/CommandTest.scala:284-321`, as reshaped by commit `645ae4d1de` (the marker moved from `:289` to `:290`).

**a. Root cause.** `Command.spawn` runs `self.unsafe.spawn()` inside `Sync.Unsafe.defer` and registers `Scope.acquireRelease` in the `.map` after it (`kyo-system/shared/src/main/scala/kyo/Command.scala:67-79`); `Command.stream` has the same shape (`Command.scala:96-106`). A stop delivered during the fork/exec makes the `.map` poll defer and the abandonment walk stop at the settled process. The reason string is accurate.

**b. Classification.** Pre-existing on `origin/main` by reading: `Command.scala:67-70, 86-88, 99` identical there, and the old kernel polled before `map`'s function too (`origin/main` `Pending.scala:78`).

**c. Determinism.** Sampled, but the sampling now targets the window directly. The spawner sets a plain `AtomicBoolean` in the step before the fork (`:305`); the leaf's own fiber, already running on another worker, spins on that flag with a 200 ms bound (`:307-308`), spins on to a staggered offset of `(i % 40) * 100` microseconds (`:309-310`), and requests the stop directly with `fiber.unsafe.interrupt()` (`:311`). The comment at `:283-289` records why: a fork takes a fraction of a millisecond on Native and a few milliseconds on the JVM (`ProcessPlatformSpecific.scala:196`), below what a timer lands in, and a fiber woken by a latch the spawner releases is queued behind the fork on the same worker. A stop that lands before the fork's `Defer` is refused by the eval's stop check and parks in front of it (`Eval.scala:72-76`), so nothing leaks; one that lands after the registration is handled by the scope's close; only a landing inside `spawn()` leaks, and the 0 to 3.9 ms sweep in 100 microsecond steps covers a fork of either platform's duration. The commit message records pending in three of three JVM runs and two of two Native runs. One landing in 80 fails the body. A shape that lands inside `spawn()` deterministically needs a seam inside the transport's fork, which the API does not expose; this is the honest form.

**d. CI interaction.** During the body, orphaned `sleep` processes exist; the leaf finds them by their unique argv and kills them (`:296-298, :318`), so no process outlives the leaf. The JVM side of each killed process's stdio pipes (`ProcessPlatformSpecific.scala:229, 245, 262`) is closed by the JDK's process reaper once the child exits: the team lead reports the kyo-system Linux JVM leg passing its end-of-run probes under `scripts/build.sh --env podman-ci` with this leaf pending. No thread is involved: the stdin feed stop actions are fibers (`ProcessPlatformSpecific.scala:14-25`).

**e. Fix shape.** Make the spawn the acquire of `Scope.acquireRelease`: `Scope.acquireRelease(Sync.Unsafe.defer(Abort.get(self.unsafe.spawn().map(_.safe))))(release)`, so the registration runs through `ensureMap` as the value arrives with no poll between; the `Result.map(_.safe)` is pure and stays inside the defer. Same at `Command.scala:96-106` for `stream`. About 12 lines. The assertion as written passes afterwards: a stop during the fork/exec parks after the registration, the backstop closes the scope, `destroyForcibly` runs, and `pgrep` finds nothing within the 5 second bound.

**f. Test quality.** The name states the property. The observation is the operating system's view through `pgrep -f` with a unique argv (`:296-298`), which is the right observer for a process. The interrupter busy-spins on a scheduler worker thread (`:307-310`), a deliberate deviation from the module's no-blocking rule, bounded at 200 ms per round and normally microseconds; the comment gives the reason and the scheduler's `BlockingMonitor` treats a spinning thread as running, not blocked (`kyo-scheduler/jvm-native/src/main/scala/kyo/scheduler/BlockingMonitor.scala:12-17`), so no worker is re-routed around it. Real clock: the nanosecond offsets and the 5 second bound (`:315`). Dependencies: `pgrep` and `kill` on the runner. Gating `.notJs.notWasm` (`:292`) matches the platforms that spawn processes.

## 10. SpawnBackendTest:303, the worker JVM orphan

Leaf: `kyo-compiler/jvm/src/test/scala/kyo/SpawnBackendTest.scala:296-334` (the brief cited `:301`; the marker is at `:303` on the tree as read).

**a. Root cause.** `SpawnBackend.init` spawns the worker at `kyo-compiler/jvm/src/main/scala/kyo/internal/SpawnBackend.scala:90` and arms the kill inside `Sync.ensure` at `:99-104`, after `aeronClient(driver)` (`:91`, an `Async` connect, `:198-199`) and `connect` (`:92`, `Exchange.initUnscoped`, `:206-213`). Every `.map` between them polls. The comment at `:93-96` promises the kill "on failure or interrupt during the readiness probe", which holds only from `:99` on. `spawnWorker` itself polls once after the spawn (`:172-176`, the `.map` after `spawnUnscoped`), a window inside the acquire. The reason string is accurate.

**b. Classification.** Pre-existing on `origin/main` by reading: `SpawnBackend.scala:90-106` identical there.

**c. Determinism.** Sampled, with a sweep dense enough that missing the window is unlikely. Sixty-four rounds (`:307`, as reshaped by commit `226f5521b0`): the first 48 interrupt at 0, 250, ..., 11750 microseconds and the remaining 16 at 12, 16, ..., 72 ms (`:323`). The window opens when `spawnWorker` returns and closes when the kill is armed a few milliseconds later, after the aeron connect; the comment at `:296-302` says so. The commit message records pending in three of three runs, where the earlier 4 ms steps missed one run in two. One leak fails the body (the assertion at `:331` ends the loop). The interrupt is still timer driven (`Async.delay`), so a machine whose fork takes longer than 12 ms falls to the coarse steps; the leaf 9 shape (spin to the offset from a flag the spawner sets, then `unsafe.interrupt()`) would remove the timer's granularity here too.

**d. CI interaction.** Worker JVMs that outlive the 10 second bound are killed by `pkill` (`:314, :329`). Two things the leaf does not clean: the aeron client connected by `aeronClient(driver)` when the stop lands after that connect and before `:99`, and the exchange's reader fiber. The client is a native handle (`kyo-aeron/shared/src/main/scala/kyo/internal/AeronPlatformTransport.scala:32-37`); the suite's own comment claims a leaked client's conductor thread is non-daemon and keeps the fork alive (`SpawnBackendTest.scala:59-60`). If that is so, the thread category, which runs on every platform, would report it at the fork's end; commits `8722d2a111` and `226f5521b0` record the suite green on the JVM with this leaf pending, which suggests it did not, but nothing read here settles which. Open question requiring a run with the leak check on. Pipes of the killed workers: closed by the reaper, as leaf 9's Linux run showed.

**e. Fix shape.** Arm the kill in the step that produces each resource, with `Sync.acquireReleaseWith` (the `Fiber.use` shape, `Fiber.scala:169-171`): the worker's acquire becomes a single unsafe step (`spawnWorker` at `:150-176` returning through `Abort.get` inside the defer, no `.map` after the spawn), released by `destroyForcibly` unless `started`; the aeron client the same with `close` unless `started`; the `started` flag stays. About 30 lines across `:89-110` and `:150-176`. Depends on leaf 7's fix for the value handover at each bracket's clean end. The assertion as written passes afterwards for the worker; the aeron client's own connect join (`AeronPlatformTransport.scala:32-37`, report D10) stays exposed and the leaf does not observe it.

**f. Test quality.** The name states the property. The observer is `pgrep -f` on a unique `-Wconf` token (`:309-312`). Real clock: `Async.delay` offsets (`:323`) and a 10 second bound per leaking round (`:328`). JVM only by module. The aeron client is not asserted.

## 11. FiberTest:727, the spawn trace

Leaf: `kyo-core/shared/src/test/scala/kyo/FiberTest.scala:727-757`.

**a. Root cause.** `Fiber.Unsafe.init` is `IOTask.detached(Sync.defer(v))` (`kyo-core/shared/src/main/scala/kyo/Fiber.scala:456`) and captures no trace. The kernel's trace is rebuilt at the failure from what the child's own evaluator holds (`kyo-kernel/shared/src/main/scala/kyo/kernel/internal/EffectTrace.scala:13-19`): `guarded` attaches the child's stack regions (`Eval.scala:591-596`, `EffectTrace.scala:59-62`), and the child's only region is the fiber boundary, whose continuation carries `Frame.internal` (`IOTask.scala:139`) and is skipped (`EffectTrace.scala:161`), leaving a region element with the tag's name and no `@` (`EffectTrace.scala:174-176`). So the assertion at `:746` fails. The reason string is incomplete: it is not only the spawning chain that is missing, the child's own `Sync.defer` frame is not carried either, because a deferral's node is not part of what `attach(ex, stack)` walks. And the scaladoc at `Fiber.scala:435-436` still promises the old behavior ("Trace captured. The current execution trace IS snapshotted ... enriches its stack trace with the spawning frame chain"), which the code contradicts.

**b. Classification.** Caused by the new kernel: the leaf is green on `origin/main` (`origin/main` `FiberTest.scala:725`, no marker), where `Fiber.Unsafe.init` passed `Trace.saved()` into the task (`origin/main` `Fiber.scala:418`) and `Trace.saved` snapshotted the safepoint's frames (`origin/main` `kyo-kernel/.../Trace.scala:34`). The old `Trace` was removed with the old kernel (`959554afd0`). Commit `72756d3d14` marks it "pending on the EffectTrace fidelity ruling", so it is an undecided design point, not a documented decision.

**c. Determinism.** Deterministic by reading: no element with a callee reaches the child's exception. It would flip to passing if any region continuation the child holds carried a non-internal frame with a callee name, since the check is for any `@` (`:746`), not for the spawner's frames.

**d. CI interaction.** Nothing left behind. No probe reports anything.

**e. Fix shape.** Two honest options, and the ruling picks one. Restore the documented behavior: capture the spawner's reconstructed frames at `Fiber.Unsafe.init` and `IOTask.detached` and prepend them when the child's trace is attached, which needs the current evaluator's stack to be reachable from an unsafe call inside a deferral (the old kernel had `Safepoint.saveTrace()`; the new `Safepoint` slot carries no stack), so a kernel change of the order of a hundred lines across `EffectTrace`, `Safepoint` and `IOTask`. Or accept the new model: fix the scaladoc at `Fiber.scala:435-436`, and replace the leaf with a green pin of what the child's exception does carry. The leaf as written would pass under the first option.

**f. Test quality.** The name refers to `Trace.saved`, an API that no longer exists on the branch, and the comment at `:730-735` describes the old mechanism; the name should state the property (a child fiber's failure carries the frames of the chain that spawned it). The assertion checks a rendering detail (`@` in the class name) rather than the presence of a specific spawner frame, so a future child-side frame would satisfy it without the spawner's chain. No real clock. No gating.

## 12. ContextEffectTest:585, crossing shot bindings

Leaf: `kyo-kernel/shared/src/test/scala/kyo/kernel/ContextEffectTest.scala:583-598`.

**a. Root cause.** `hooked` installs a `ContextEffect.handle` with a `release` hook (`:416-424`) that logs "done" on a clean ending. Under the twice-resuming `handleCont` at `:590-593`, the dump moves the crossed regions' releases onto the handler's entry (`Stack.scala:292-297, 308-312`), which runs them once at its end. The body asserts the hook ran twice (`:595`). It runs once. The reason string ("a crossing shot does not complete the bindings it re-installs") describes the behavior as a gap.

**b. Classification.** A documented design decision, not a gap. `kyo-kernel/CONTRIBUTING.md:152` ("the handler ... runs them once at its end, so a clause that resumes more than once runs every shot against the live resource"), `:301` ("`release` runs once when the region ends"), and `:343` ("A multi-shot `handleCont` clause is not that case ... released once where the handler ends"). The sibling leaf immediately above pins exactly that with the same body: `"each shot of a crossing drains the debts it re-installs"` asserts `done outer 1` once and `release outer 1` zero times (`:426-441`). The pending leaf asserts the contradiction of a green pin in the same file. The disabled leaves around it say why it exists: they need "ContextEffect.handle's done hook plus a completing helper, which this kernel does not provide (it has release instead)" (`:534`, `:541`, and the others). This leaf tests a per-shot completion notification through the exactly-once release hook. The marker is the wrong carrier: a fix that made it pass would break `:437` and the documented rule. Remove it, or park it with its disabled siblings under the same "needs a done hook" note if a per-shot notification is still wanted as a separate hook.

**c. Determinism.** Deterministic; single-threaded kernel test.

**d. CI interaction.** Nothing left behind.

**e. Fix shape.** None under the current model. A per-shot `done` hook distinct from `release` would be a new `ContextEffect.handle` parameter and a new lane in `Eval`'s clean-end arm (`Eval.scala:370-387`), which the disabled siblings already describe as absent.

**f. Test quality.** The name uses "completes", the `done` concept the kernel replaced. ScalaTest's block form is used inside `in` (`:585`) rather than the name decorator, which is fine for ScalaTest but differs from every other leaf here. No real clock.

## 13. ChoiceTest:432, nested choice point streaming order

Leaf: `kyo-prelude/shared/src/test/scala/kyo/ChoiceTest.scala:432-441`.

**a. Root cause.** `Choice.runStream` works level by level: each loop turn partitions the pending computations into settled and unsettled, emits every settled one, then expands every unsettled one by one choice point (`kyo-prelude/shared/src/main/scala/kyo/Choice.scala:115-140`). For `Choice.eval(1, 2).map(a => if a == 1 then Choice.eval(10, 11) else a)`, the turn after the first expansion holds the branch for 1 (unsettled at its nested choice) and the value 2 (settled), emits 2, and emits 10 and 11 a turn later: `2, 10, 11`. `Choice.run` recurses depth first (`Choice.scala:97-103`): `10, 11, 2`. The leaf asserts the depth-first order at `:440`. The reason string calls it a known gap; nothing documents an order for either (`Choice.scala:105-114`, `kyo-prelude/README.md:325, 367`), so "undocumented divergence" is the accurate description.

**b. Classification.** Pre-existing on `origin/main` by reading: the loop there is the same partition-and-expand with `handleFirst` in place of `handleFirstRepeated` (`git show origin/main:kyo-prelude/shared/src/main/scala/kyo/Choice.scala`, the `runStream` body), so the order is the same. Not a kernel change. Whether `runStream` must match `run`'s order is a design choice nobody has recorded; the leaf is the first statement of it. The marker is a reasonable carrier if that is the intended contract; if breadth-first emission is intended (a settled outcome is emitted as soon as it is known, which is what the scaladoc's "as they become available" suggests), the leaf should pin `Chunk(2, 10, 11)` green and the scaladoc should say the order differs from `run`.

**c. Determinism.** Deterministic; pure computation.

**d. CI interaction.** Nothing left behind.

**e. Fix shape.** If depth-first order is wanted: replace the level partition with an ordered worklist, taking the head each turn, emitting it if settled and otherwise expanding it and prepending its branch continuations, so the leftmost branch is exhausted before its sibling is touched; still incremental. About 12 lines at `Choice.scala:117-139`. Both assertions as written pass afterwards.

**f. Test quality.** The name states the property. The first assertion at `:439` compares against `run`, the second pins the concrete order; both are needed. No real clock. No gating.

## Closing

### Table

| Leaf | Classification | Deterministic | Leaves resources behind | Linux probe would report | Fix size |
|---|---|---|---|---|---|
| 1 AsyncCombinatorsTest:123 | pre-existing on main (C2 shape) | yes | no | no | 8 lines |
| 2 KyoAppTest:192 | pre-existing on main (C2 shape) | yes, once the fiber has parked; a first sample before the park passes the body | no (one parked fiber) | no | 4 lines |
| 3 ScopeInterruptTest:426 | pre-existing on main by reading (C1) | yes | no | no | 40 lines plus D3 call sites; the leaf cannot pass as written |
| 4 ScopeTest:1377 | documented decision (`cabfe3e1b2`) | no, 50 sampled rounds | no | no | none under the decision |
| 5 ScopeTest:1553, :1575 | gap; same shape on main by reading | yes | no | no | 40 lines |
| 6 StreamCoreExtensionsTest:1033 | documented decision (`cabfe3e1b2`) | no, 50 sampled rounds | no | no | none under the decision |
| 7 SyncTest:618 | new kernel composition (K3) | yes | no | no | 2 lines plus 3 in the leaf |
| 8 JsonRpcTransportUnixTest:32 | pre-existing on main (C2 shape) | no, 40 sampled rounds, real chance of passing | yes, one bound listener | yes, confirmed under podman-ci: the kyo-jsonrpc JVM fork fails | 6 lines JVM and Native, 25 for JS |
| 9 CommandTest:290 | pre-existing on main (C2 shape) | no, 80 rounds sweeping the fork by a spinning interrupter; 3 of 3 JVM and 2 of 2 Native runs pending | transiently, killed by the leaf | no, confirmed under podman-ci: probes pass | 12 lines |
| 10 SpawnBackendTest:303 | pre-existing on main (C2 shape) | no, 64 rounds sweeping 12 ms in 250 microsecond steps; 3 of 3 runs pending | transiently, killed by the leaf; abandoned aeron client open question | open question (thread category, any OS) | 30 lines, after leaf 7 |
| 11 FiberTest:727 | new kernel, ruling pending | yes | no | no | doc fix, or about 100 kernel lines |
| 12 ContextEffectTest:585 | documented decision (`CONTRIBUTING.md:152, 301, 343`) | yes | no | no | none; remove the leaf |
| 13 ChoiceTest:432 | pre-existing on main, undocumented | yes | no | no | 12 lines |

### Mis-marked leaves

- **12** contradicts a documented rule and a green pin in the same file (`ContextEffectTest.scala:426-441`). Remove it, or disable it with the "needs a done hook" note its siblings carry.
- **4 and 6** carry a recorded decision as a sampled pending marker whose stability is a race; the decided behavior is already pinned green (`StreamCoreExtensionsTest.scala:985-1000`, `ScopeTest.scala:1406-1424`). Convert to green pins, or record the intent to reverse the decision somewhere other than a race.
- **11** names a removed API and tests a rendering detail; its reason string names a narrower cause than the one the code has, and the scaladoc at `Fiber.scala:435-436` contradicts it. Rename to the property, or convert per the ruling.
- **7** cannot detect its own fix: the ownership write inside the `ensureMap` is an effect that the pending stop parks (`Eval.scala:72-76`). Change it to a synchronous write; the marker and reason are otherwise right.
- **3** asserts a shape no fix can deliver (registration after `Scope.run` returns, across a join). Keep the finding, rewrite the leaf to the owned-handover shape once it exists, or pin the limit green with a name that says so.
- **8** is the only leaf that can fail the Linux JVM leg both ways: by leaking a descriptor the fork-wide probe reports, which the podman-ci run confirmed, and by passing when its rounds miss the window. It must not reach CI in this form; either the fix lands with the marker removed, or the leaf is made deterministic through a seam, or it is gated off the Linux JVM leg until then.
- **2** should observe the fiber's own release rather than `waiters == 0`, which a not-yet-parked fiber also satisfies.
- **13** is a pin of an undocumented choice; correct as a pending leaf only if depth-first order is the decided contract.

### Fix order by technical dependency

1. Leaf 7 (`Sync.scala:105, :162`): the value handover at a bracket's clean end. Leaves 10 and 2 route their fixes through `Sync.acquireReleaseWith` and depend on it.
2. Settle the decision behind leaves 4 and 6, because it constrains what `Scope.run`'s clean path may await; then leaf 5 (`Scope.scala:136-166`): the close moves into the region's release hook. Leaf 3 touches the same lines and is designed together with it (the owned-handover entry point), followed by the kyo-sql D3 call sites.
3. The C2 instances, independent of each other: leaf 9 (`Command.scala:67-79, 96-106`), then leaf 10 (`SpawnBackend.scala:89-110, 150-176`, which builds on leaf 9's `Command` and leaf 7), leaf 8 (`UdsBackend.scala:21-34`), leaf 1 (`Constructors.scala:44-50`), leaf 2 (`KyoApp.scala:34-35`).
4. Leaf 13 (`Choice.scala:117-139`), independent, once the order is decided.
5. Leaf 11, once the EffectTrace ruling is made; the scaladoc at `Fiber.scala:435-436` is wrong under either outcome until then.
6. Leaf 12: remove; no dependency.

### Open questions that need a run

- Leaf 8 on JS: whether the Node process exits with a leaked server handle, or the test adapter tears it down. The Linux JVM half is settled: the podman-ci run reports the listener as a `socket:[inode]` leak and fails the kyo-jsonrpc fork.
- Leaf 10 with the leak check on: whether an aeron client abandoned between its connect and `SpawnBackend.scala:99` leaves a non-daemon thread the thread category reports on every OS, as the suite's comment at `SpawnBackendTest.scala:59-60` claims. The suite has been reported green on the JVM with the leaf pending (commits `8722d2a111`, `226f5521b0`), which argues against it, but neither commit says whether the leak check ran in a fork.
- Leaves 1, 2, 3, 5, 9, 10, 13 on `origin/main`: the "pre-existing" classification is by reading the identical shapes; a run of each leaf against main would make it a fact.

## Review notes

Applied on the branch after this audit, all in tests:

- Leaf 7 (`SyncTest`): the ownership mark inside the `ensureMap` is now a synchronous `owned.unsafe.set(true)`, so the leaf detects its fix. Still pending on the JVM, JS and Native.
- Leaf 12 (`ContextEffectTest`): removed. It asserted the per-shot `done` the kernel replaced, against the green pin at `ContextEffectTest.scala:426-441` and the rule at `kyo-kernel/CONTRIBUTING.md:152, 343`.
- Leaf 2 (`KyoAppTest`): the body now releases a latch as it starts and owes a finalizer; the check is on the finalizer once the body is known to have started, and a body the timeout stopped before it started owes nothing. Still pending on the JVM and Native.
- Leaf 11 (`FiberTest`): renamed to the property ("a carrier spawned from a running computation carries the spawning chain's frames in its failure"); the comment no longer describes the removed `Trace.saved`. The scaladoc at `Fiber.scala:435-436` is left for the ruling, since it is the target of one of the two fixes.
- Leaf 8 (`JsonRpcTransportUnixTest`): the reason string no longer says the window is a join; it is the step after the join, a poll away where the bind is synchronous.

Where this review differs from the audit:

- Leaf 3: "asserts a shape no fix can deliver" is too strong. A clean exit that masks the drain await, or completes the join without abandoning the continuation (deferred completion), delivers the body's value to the caller's `ensureMap`; the owned-handover entry point the audit proposes is one fix shape, not the only one. The leaf states the property and stays.
- Leaves 4 and 6: the marker is kept. The race is stacked by two scheduler hops against a read in the same slice, so a body that passes needs fifty consecutive wins; the marker records that the ordering the decision rejects is still wanted for #1398 and #1723. Converting it is the user's call.
- Leaf 13 predates this work; its classification is recorded as the audit states it.

Added after the audit, from the runs it asked for:

- D11, server half (`HttpServerTest`, "an interrupt landing as the listener binds leaves no listener behind"): the audit did not cover it because it was a green pin. Under `podman-ci` the leaf failed on a held port and the fork's descriptor probe reported the listener; the acquire inside `Scope.acquireRelease` joins the listen fiber and maps the result in a step of its own, which the `ensureMap` at the end does not cover. The leaf now spins to sub-millisecond offsets as leaf 9 does and is pending; it carries the same fork-wide probe consequence as leaf 8 on the kyo-http Linux JVM leg.
- `FiberTest`, "raceFirst" › "n": one JVM run on macOS ended with the fork's fiber probe reporting a busy worker at that leaf's `loop`, the losing spinner still running long after the race completed. A new leaf, "interrupts a losing computation that never parks", races an immediate winner against a spinning loser 500 times and bounds the loser's finalizer; its outcome is recorded with the run results in the resource-safety report.

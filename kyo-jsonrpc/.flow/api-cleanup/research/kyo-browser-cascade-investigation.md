# kyo-browser JVM test cascade investigation

## 1. Cascade mechanism — confirmed

**Shared state owner:** `kyo.internal.SharedChrome` (object) at
`kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala:19`.

Two `private val`s, both unsafe statics, live for the JVM's lifetime:

- `cachedUrl: Promise.Unsafe[String, Abort[BrowserSetupException]]`
  (`SharedChrome.scala:22-26`) — caches the **Chrome WebSocket URL** as a once-
  completed promise.
- `initStarted: AtomicBoolean.Unsafe` (`SharedChrome.scala:28-32`) — a one-shot
  CAS that gates the launch fork.

`SharedChrome.init` (`SharedChrome.scala:35-36`):
```
ensureStarted.andThen(cachedUrl.safe.get)
```

`ensureStarted` (`SharedChrome.scala:38-78`):
- If `initStarted.compareAndSet(false, true)`: sweep orphan Chromes, fork a
  detached fiber that launches Chrome, completes `cachedUrl` with the URL,
  then `Async.never` to hold the scope.
- Else: no-op. Every subsequent caller awaits the **same** `cachedUrl`.

**There is no reset path.** Once `cachedUrl` is completed with a `Success(url)`,
that `url` is returned forever — the launch fiber never re-runs; the promise is
never re-armed. If Chrome dies, the URL persists.

**The cascade vector:** Every JVM test uses `withBrowser` / `withBrowserOnLocalhost`
(`BrowserTest.scala:112-129`), which call `SharedChrome.init.map(url => Browser.run(url)(...))`.
`Browser.run(wsUrl)` (`Browser.scala:276-279`) calls `CdpBackend.init(wsUrl, LaunchConfig.default)`,
which opens a fresh WS connection (`CdpBackend.scala:189-193`) and runs the
Q-002 probe (`CdpBackend.scala:236-248`). When Chrome has died:
- WS handshake/connect succeeds against the OS port (Chrome process may still
  be reachable at TCP level), or returns immediate refuse.
- The probe `Browser.getVersion` either round-trips on a half-dead session and
  the response stream closes (`Closed` → `BrowserConnectionLostException`) or
  fails the WS open (`HttpException` → `BrowserConnectionLostException` via
  `CdpBackend.scala:189-190`).
- Either way, `CdpBackend.scala:236-242` recovers `BrowserReadException` into
  `BrowserSetupFailedException("WS handshake failed: probe call returned Closed")`.

Direct lifecycle tests bypass `withBrowser` but the same trap applies:
`CdpBackendLifecycleTest.scala:26, 51, 92, 125, 179, 223, 260, 297, 448, 508`
all call `SharedChrome.init.map { wsUrl => ... }` and feed the dead URL into
`CdpBackend.init` / direct send paths. Same shared `cachedUrl`, same dead URL,
same instant failure.

**Why first failure ≈ 30s, rest are 1-5ms.** The first failure is a live op
hitting an actually-up-but-stalled Chrome (`fill` waited 30s for a CDP reply
on `Page.evaluate` that never came, or the mutation-settlement loop chewed
through its 30s budget while the Exchange closed mid-call — see
`final-green-jvm-kyo-browser-001.log:111-159`). After that point, the WS is
fully closed; every subsequent `Browser.run(url)` discovers the closed state
immediately at the Q-002 probe in `CdpBackend.scala:244-247`, which fails in
single-digit ms because the WS connect either refuses or the channel is
already-Closed before the first send completes.

## 2. First-failure root cause — pre-existing-environment, not a campaign regression

**Wire-format check:** Phase 03 (`b8798e08e`) renamed `JsonRpcId` → `JsonRpcEnvelope.Id`.
The Schema is hand-written, not derived (`JsonRpcEnvelope.scala:67-79`). I
diffed the pre-Phase-03 `JsonRpcId.scala` (`git show 3f66991cd:.../JsonRpcId.scala`)
against the current `JsonRpcEnvelope.Id` Schema — **byte-for-byte identical
writeFn/readFn**: bare `long`/`string`, no record wrapping. CDP wire format
preserved. Same conclusion holds for the four other Phase 03 nested types
(`MessageGate`, `IdStrategy`, etc.): they are policy types, never serialized to
the wire.

Phase 03's only change to `CdpBackend.scala` (`git show b8798e08e -- .../CdpBackend.scala`)
is the rename `JsonRpcId.Num(...)` → `JsonRpcEnvelope.Id.Num(...)` at
`CdpBackend.scala:602` (handling dialog ids), plus a scaladoc word swap
`HandlerCtx` → `JsonRpcMethod.Context`. No semantic shift.

No other Phase 03-touched file in `kyo-browser` changes runtime behavior.

**30s timeout origin (best evidence).** `LaunchConfig.default.requestTimeout = 60.seconds`
(`Browser.scala:3117`); `SessionConfig.default` mutation/assertion timeouts cap at
8s (`Browser.scala:3250-3251`); the 30s observed is most likely the OS-level TCP
half-close timeout on the Chrome WebSocket combined with the in-flight
`Page.evaluate` for `Browser.fill`. No campaign code touches this path.

**Verdict:** First failure looks environmental (Chrome OOM, OS resource
exhaustion, or Chrome crash during the long test run). The campaign changes
are pure syntactic renames on a hand-written CDP wire path; they cannot
plausibly produce a 30s `Connection lost`. If the user wants stronger
confidence, the reproduction protocol in §5 isolates campaign-cause vs
environmental-cause definitively.

## 3. Architectural fix proposal — health-check-then-relaunch on SharedChrome

### Recommended: option B (health-check + reset)

Add a probe-and-reset path to `SharedChrome`. On every `init` call:
1. If `cachedUrl` is not yet completed, run the existing launch fork.
2. If it is completed: open a lightweight `CdpClient`-less HTTP probe against
   `http://host:port/json/version` (the DevTools HTTP endpoint, same host:port
   as the WS URL). If it returns 200 in <1s, return the cached URL. If it
   fails or times out, atomically reset `cachedUrl` + `initStarted` and re-run
   the launch fork.

**Why this option over the others:**

| option | impl LoC | runtime cost / test | reliability | masks real bugs? |
| --- | --- | --- | --- | --- |
| A. Per-test fresh Chrome | ~30 LoC + delete SharedChrome | +2.8 s × 1363 = +63 min | bulletproof | no |
| **B. Health check + relaunch (recommended)** | **~60 LoC, single file** | **~+5 ms healthy / +2.8 s unhealthy** | **strong** | **no** |
| C. Per-test BrowserContext on shared Chrome | ~40 LoC across Browser + tests | ~ baseline | partial — does not help when Chrome process itself dies | partial |
| D. Fail-fast skip with INFRASTRUCTURE_BROKEN marker | ~20 LoC | ~ baseline | hides root cause forever | yes |

Option C does not help in the actual failure mode observed: the **Chrome
process / WebSocket dies**, not a per-context state corruption. Cookie /
storage isolation between tests is already provided by Chrome's incognito
contexts (`Browser.run` opens one per call — see `Browser.scala:60-67`); the
cascade is not happening at the context layer.

Option D fails the "preserves real-bug detection" criterion.

Option A is the heaviest but most correct. Defer to it only if option B
proves insufficient under load (e.g. repeated brownouts within one test class).

**Affected files for option B:**
- `kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala` — add
  `healthCheck` private method + `reset` helper; wrap `init` with the probe.
- (optional) `kyo-browser/shared/src/test/scala/kyo/internal/SharedChromeTest.scala`
  — a focused test using a fake URL that exercises the reset path.

**Sketch:**
```scala
def init(using Frame): String < (Async & Abort[BrowserSetupException]) =
    Sync.Unsafe.defer {
        if !cachedUrl.done() then ensureStarted.andThen(cachedUrl.safe.get)
        else cachedUrl.safe.get.map { url =>
            healthCheck(url).map {
                case true  => url
                case false => resetAndRelaunch.andThen(cachedUrl.safe.get)
            }
        }
    }
```

The `healthCheck` calls `GET http://host:port/json/version` via
`kyo.HttpClient` with a 1-second deadline. The DevTools HTTP endpoint is the
same `host:port` parsed from the WS URL (`SharedChrome.scala` already
does this kind of parsing in `BrowserTest.scala:125`).

Atomic reset means: complete the old promise (or swap to a fresh
`Promise.Unsafe.init`), reset `initStarted` to `false`, and run the launch
flow. Concurrent racers must converge on a single new URL — the
existing `initStarted.compareAndSet` pattern already provides that.

## 4. First-failure targeted fix

**No campaign-side fix needed.** Phase 03's renames are byte-identical on the
wire. If the user wants a defensive change:

- Bump `LaunchConfig.requestTimeout` for tests from 60s → 15s
  (`BrowserTest`-side override) so a stalled CDP call surfaces as a typed
  `BrowserConnectionLostException` faster, letting option B's health-check
  kick in sooner.
- Investigate Chrome resource exhaustion: 1363 tests × per-call Chrome context
  on shared Chrome creates many `Target.createBrowserContext` calls. Chrome
  retains context state for the lifetime of the process; under load this can
  accumulate. Option B's relaunch path mitigates this organically.

## 5. Reproduction protocol

```sh
# Step 1: solo-class run; the cascade requires multiple consumers of SharedChrome.
sbt 'kyo-browser/testOnly kyo.BrowserMutationTest'
# Expected: 0 failures or all-30s-each. Single class → only one shared-URL
# consumer chain → cascade has nothing to cascade through.

# Step 2: same class twice in one sbt invocation (cascade WITHIN a class).
sbt 'kyo-browser/testOnly kyo.BrowserMutationTest kyo.BrowserMutationTest'
# Tests cannot register twice but this re-uses the JVM. Inconclusive on its own.

# Step 3: the diagnostic — two classes back-to-back, second one watches the cascade.
sbt 'kyo-browser/testOnly kyo.BrowserMutationTest kyo.BrowserCoreTest'
# If class 1 passes and class 2 fails 100% with BrowserSetupFailedException
# in 1-5ms each, cascade is confirmed (option B mandatory).
# If both classes pass, the original failure was load-dependent.

# Step 4: confirm option B's fix shape works in isolation (after implementation).
# Simulate dead Chrome by killing the process mid-suite:
sbt 'kyo-browser/testOnly kyo.BrowserMutationTest' &
sleep 30
pkill -f 'kyo-browser-' # kills the SharedChrome process
# Expect: next test calling SharedChrome.init detects unhealthy URL, relaunches,
# resumes green.
```

The key signal: **per-failure duration**. 30s first + ≤5ms rest = cascade. All
~equal durations = independent failures (different root cause).

## 6. Verification plan for option B

1. **Solo BrowserMutationTest** (`sbt 'kyo-browser/testOnly kyo.BrowserMutationTest'`):
   green, no health-check trips (since no Chrome death in a single class).

2. **Multi-class with forced kill** (the cascade reproduction in §5 step 4):
   the test immediately after the kill must show `+2.8s` boot cost (health
   check detected dead URL, relaunch fired) and then succeed. Subsequent
   tests must run at baseline cost (no health-check trip; URL is fresh).

3. **Full suite stability** (`sbt 'kyo-browser/test'`): 1363/1363 green across
   three consecutive runs. The health-check adds ~5 ms × 1363 ≈ 7 s to total
   suite wall-clock; acceptable.

4. **Regression guard test** in `SharedChromeTest.scala`:
   - Seed `cachedUrl` with a fake URL pointing to `127.0.0.1:0` (closed
     port). Call `SharedChrome.init`. Verify the health check fires, the
     relaunch path runs, and the returned URL is the real Chrome URL (not
     the seeded fake).

5. **No-cascade invariant**: introduce a synthetic test class that
   `pkill`s Chrome inside its `beforeAll`, then asserts the next test class
   in the suite still passes. Add to the JVM-only test set; run via the
   manifest in `kyo-jsonrpc/.flow/api-cleanup/end/runs/`.

---

## File reference index

- `kyo-browser/shared/src/main/scala/kyo/internal/SharedChrome.scala:19-84` — shared-state owner
- `kyo-browser/shared/src/main/scala/kyo/Browser.scala:276-279` — `Browser.run(wsUrl)`
- `kyo-browser/shared/src/main/scala/kyo/Browser.scala:294-303` — `Browser.runShared`
- `kyo-browser/shared/src/main/scala/kyo/Browser.scala:3115-3120` — `LaunchConfig.default`
- `kyo-browser/shared/src/main/scala/kyo/Browser.scala:3237-3252` — `SessionConfig.default`
- `kyo-browser/shared/src/main/scala/kyo/internal/CdpBackend.scala:168-249` — `CdpBackend.init` + Q-002 probe
- `kyo-browser/shared/src/test/scala/kyo/Test.scala:39-115` — test base
- `kyo-browser/shared/src/test/scala/kyo/BrowserTest.scala:112-129` — `withBrowser` / `withBrowserOnLocalhost`
- `kyo-browser/shared/src/test/scala/kyo/internal/CdpBackendLifecycleTest.scala:26..698` — direct `SharedChrome.init` consumers
- `kyo-jsonrpc/shared/src/main/scala/kyo/JsonRpcEnvelope.scala:60-80` — `Id` Schema (post-rename, byte-identical to pre-rename)
- `kyo-jsonrpc/.flow/api-cleanup/end/runs/final-green-jvm-001.log:1879` — first failure: `isolated-world contexts (30s)`
- `kyo-jsonrpc/.flow/api-cleanup/end/runs/final-green-jvm-kyo-browser-001.log:111` — first failure: `fill preserves backslash (30s)`

# kyo-pod Improvements Plan

Status: **DRAFT — awaiting user approval**
Scope: 17 improvements to kyo-pod module across correctness, observability, performance, and documentation.
Owner: supervisor (agents execute).
Worktree: `.claude/worktrees/lexical-humming-bear` (branch `kyo-container`).

---

## 1. Executive Summary

An in-depth analysis of kyo-pod identified 16 actionable improvements and 10 deferred items. This plan groups them into 11 execution phases that follow the kyo-repo bug-fix discipline: **failing tests before fixes**, separate commits per phase, no existing tests weakened.

Total delta expected: ~235 LOC implementation + ~375 LOC tests, net additive. No existing tests modified. 4 existing production files modified, 3 new files added.

This plan requires user approval before Stage 2 (execution) starts.

---

## 2. Verified Findings (with file:line references)

Every item below was verified against the actual source at HEAD.

### 2.1 Bug-category items (tests must fail today)

| ID | File:line | Verified problem |
|---|---|---|
| B1 | `shared/src/main/scala/kyo/Container.scala:659` | `HealthCheck.exec(expected = Present("ok"))` uses `.contains` — passes output `"not ok"`. |
| B2 | `shared/src/main/scala/kyo/Container.scala:101-120` | `isHealthy` wraps `hc.check` in `Retry[ContainerException](hc.schedule)` — blocks for ~full schedule (~15 s default). |
| B3 | `shared/src/main/scala/kyo/Container.scala:1407-1425` | `runHealthCheck` inner state check: `Result.Failure(NotFound)` falls into `case _` → continues retrying until schedule exhausts. |
| B4 | `shared/src/main/scala/kyo/internal/ShellBackend.scala:1847-1900`; HTTP `withErrorMapping` callers at `HttpContainerBackend.scala:822, 980, 1175, 1252, 1310, 1360` | `mapError` uses `args.lastOption` as the NotFound target. HTTP `withErrorMapping` passes operation names (`"list"`, `"imageList"`, etc.) that become `Container.Id("list")` on 404. |
| B5 | `shared/src/main/scala/kyo/Container.scala:340-345` | Scope cleanup path with `stopSignal = Present(sig)` sends the signal then immediately `remove(force=true)`. `stopTimeout` is ignored. |
| B6 | `shared/src/main/scala/kyo/ContainerImage.scala:81-89` | `ContainerImage.parse("alpine:")` produces `tag = Present("")`. |
| B7 | `shared/src/main/scala/kyo/ContainerImage.scala:363` | `case class RegistryAuth(auths: Map[Registry, String])` default `toString` leaks base64-encoded credentials. |
| B8 | `shared/src/main/scala/kyo/internal/ShellBackend.scala:1143-1149`, `HttpContainerBackend.scala:868-875` | `attachById` rebuilds `Config` with only `image` + `name`, discarding ports, mounts, env, labels, resource limits, capabilities. |

### 2.2 Correctness items (test + fix, still one-test-first-then-fix)

| ID | File:line | Verified problem |
|---|---|---|
| C1 | `shared/src/main/scala/kyo/internal/ShellBackend.scala:707-713` | `logs` returns `outEntries.concat(errEntries)` — stdout/stderr interleaving lost. |
| C2 | `shared/src/main/scala/kyo/internal/ShellBackend.scala:586-590, 786-799`; `HttpContainerBackend.scala:430-434, 1402-1456` | Streaming call sites use `text.split("\n")` per byte chunk. Lines straddling chunk boundaries are split into distinct `LogEntry`s. |

### 2.3 Cleanup items

| ID | File:line | Verified problem |
|---|---|---|
| D1 | `shared/src/main/scala/kyo/Container.scala:197-210` | `attachWebSocket` unconditionally returns `NotSupported("WebSocket attach", "Not supported by the shell backend")` — dead API. |
| D2 | `shared/src/main/scala/kyo/internal/ShellBackend.scala:487-493` | `exec` panic case does `throw e`, inconsistent with `run` at `:1822-1828` which uses `Abort.fail(General(...))`. |
| D3 | `shared/src/main/scala/kyo/Container.scala:1399` | Health-check error messages truncated at 100 chars; Docker errors routinely exceed this. |

### 2.4 Observability / documentation items

| ID | File:line | Verified problem |
|---|---|---|
| O1 | `shared/src/main/scala/kyo/Container.scala:342-345` | `Abort.run[ContainerException](b.stop(...)).unit` in scope cleanup silently drops failures. |
| Doc1 | `shared/src/main/scala/kyo/Container.scala` `BackendConfig` scaladoc | HTTP backend falls back to CLI for `imagePull` (HttpContainerBackend.scala:886), `imagePullWithProgress` (:943), `imageBuildFromPath` (:1082), `execStream` (:426); `copyTo`/`copyFrom` require `tar` (:626, :684). Undocumented. |

---

## 3. Detailed Specifications

Each entry specifies: **Change** (what replaces what), **Test** (failing test written before the fix), **Risk**, and **LOC estimate**.

### B1 — HealthCheck.exec equality

**Change**: In `Container.scala:659`, replace
```
if !result.stdout.trim.contains(e) then ...
```
with
```
if result.stdout.trim != e then ...
```

**Test** (containerOnly): Create a container with `HealthCheck.exec(Command("echo", "not ok"), expected = Present("ok"), schedule = Schedule.fixed(100.millis).take(3))`. `Container.init` MUST fail with `ContainerException.General`. Currently passes because `"not ok".contains("ok")` is true.

**Risk**: existing tests that depend on `.contains` semantics — none found in grep for `HealthCheck.exec`. Tests use exact output (`"ok"`) so equality works.
**LOC**: 1 line impl, ~15 lines test.

### B2 — isHealthy single-shot

**Change**: In `Container.scala:101-120`, replace the Retry block with a single `hc.check(this)` attempt:
```
case Present(hc) =>
    Abort.recover[ContainerException] { (_: ContainerException) =>
        healthState.set(ContainerHealthState(false, Present(hc))).andThen(false)
    } {
        hc.check(this).andThen {
            healthState.set(ContainerHealthState(true, Present(hc))).andThen(true)
        }
    }
```
Add new method `awaitHealthy(using Frame): Unit < (Async & Abort[ContainerException])` immediately after `isHealthy` that runs the retry schedule for users who want the old behavior:
```
def awaitHealthy(using Frame): Unit < (Async & Abort[ContainerException]) =
    healthState.get.map { state =>
        state.check match
            case Absent     => ()
            case Present(hc) => Retry[ContainerException](hc.schedule)(hc.check(this)).andThen(
                healthState.set(ContainerHealthState(true, Present(hc))).unit
            )
    }
```

**Test** (containerOnly): Container with custom healthcheck tied to file `/tmp/healthy`. After `exec("rm", "/tmp/healthy")`, time `c.isHealthy`. MUST return `false` in < 500 ms. Currently takes ~2 s on the test schedule (fixed 200 ms × 10 = 2 s).

**Risk**: Behavior change visible to callers. The existing test `"ongoing — isHealthy tracks health continuously"` (`ContainerTest.scala:855-873`) runs `isHealthy` twice; the second call assertion `!h2` will now return in 1 attempt instead of 10. Timing still assertable. No test modification needed.
**LOC**: ~10 impl, ~20 test.

### B3 — runHealthCheck NotFound short-circuit

**Change**: In `Container.scala:1407-1425`, change
```
Abort.runWith[ContainerException](container.backend.state(container.id)) {
    case Result.Success(st) if st != State.Running && st != State.Created =>
        ()
    case _ =>
        Clock.now.map { now => ... retry loop ... }
}
```
to
```
Abort.runWith[ContainerException](container.backend.state(container.id)) {
    case Result.Success(st) if st != State.Running && st != State.Created =>
        ()
    case Result.Failure(_: ContainerException.NotFound) =>
        ()
    case _ =>
        Clock.now.map { now => ... retry loop ... }
}
```

**Test** (containerOnly): `Container.init` with `command("true").autoRemove(true)` + `healthCheck(HealthCheck.exec(Command("true"), schedule = Schedule.fixed(500.millis).take(30)))`. The container exits and auto-removes immediately. Init MUST complete in < 2 s. Currently waits ~15 s for schedule to drain.

**Risk**: Low. The explicit Failure(NotFound) case is strictly narrower than the existing catch-all.
**LOC**: 2 impl, ~20 test.

### B4 — mapError target tracking

**Change**: Introduce a private resource-context type and thread it through error mapping. In `ShellBackend.scala`:

1. Add private enum:
```
private sealed trait ResourceContext
private object ResourceContext:
    case class Container(id: Container.Id) extends ResourceContext
    case class Image(ref: String) extends ResourceContext
    case class Network(id: Container.Network.Id) extends ResourceContext
    case class Volume(id: Container.Volume.Id) extends ResourceContext
    case class Op(name: String) extends ResourceContext
```

2. Change `mapError(output: String, args: Seq[String])` signature to `mapError(output: String, ctx: ResourceContext)`. Pattern-match on `ctx` for NotFound/NetworkNotFound/VolumeNotFound/ImageNotFound/AlreadyExists/AlreadyRunning/AlreadyStopped/PortConflict/AuthenticationError/VolumeInUse branches. For `Op(name)`, 404-ish errors become `General(s"Operation $name failed", output)`.

3. Change private `run(args: String*)` signature to `run(ctx: ResourceContext, args: String*)`. Every caller (~30 call sites in ShellBackend) passes the correct context.

4. In `HttpContainerBackend.scala`, change `withErrorMapping[A](id: String, mapNotFound: Maybe[String => ContainerException])` to `withErrorMapping[A](ctx: ResourceContext)`. Internally, `ctx` drives the NotFound/AlreadyExists/AlreadyStopped subtype. Callers like `withErrorMapping("list")` become `withErrorMapping(ResourceContext.Op("list"))`; container-scoped callers use `ResourceContext.Container(id)`.

5. Expose the enum as `private[internal] sealed trait ResourceContext` in a shared location (new file `shared/src/main/scala/kyo/internal/ResourceContext.scala`).

**Test** (containerOnly):
- Test 1 (ShellBackend): `Container.init(alpine).map(c => Abort.run[ContainerException](c.copyFrom(Path("/nonexistent"), Path("/tmp/dst"))))` where the container is valid but the path isn't. Assert it's NOT a NotFound error (currently the args.lastOption is "/tmp/dst" or similar — check what kind of failure mapError produces). More direct: call `Container.attach(Container.Id("bogus-id-xyz")).map(_.copyFrom(Path("/a"), Path("/tmp/dst")))` → should be `NotFound(Id("bogus-id-xyz"))`, currently `NotFound(Id("/tmp/dst"))` or similar host-path id.
- Test 2 (HTTP backend only, httpBackendOnly): `Container.withBackend(_.UnixSocket(Path(socket)))` then force a 404 by hitting a container-listing endpoint with an invalid filter. Confirm the resulting error is a `General` with operation-name context, not `NotFound(Id("list"))`.

**Risk**: Largest change in the plan. Touches ~30 call sites in ShellBackend + ~20 in HttpContainerBackend. Test coverage exists for most happy paths. Regression risk: an incorrectly-threaded context would demote an error type. Mitigation: Phase 2 also runs the full existing test suite, not just the new B4 test.
**LOC**: ~15 new enum, ~60 call-site threading, ~40 test.

### B5 — stopTimeout honored with stopSignal

**Change**: In `Container.scala:340-345`, replace:
```
val shutdown = config.stopSignal match
    case Present(signal) =>
        Abort.run[ContainerException](b.kill(cid, signal)).unit
    case Absent =>
        Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).unit
shutdown.andThen(Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).unit)
```
with:
```
val shutdown = config.stopSignal match
    case Present(signal) =>
        Abort.run[ContainerException](b.kill(cid, signal)).andThen(
            Abort.run[ContainerException](
                Async.timeout(config.stopTimeout)(b.waitForExit(cid))
            )
        ).unit
    case Absent =>
        Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).unit
shutdown.andThen(Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).unit)
```

**Test** (containerOnly): Container with `stopSignal(SIGUSR1).stopTimeout(2.seconds).command("sh", "-c", "trap 'sleep 3; exit 0' USR1; sleep infinity")`. Inside `Container.use(config) { c => () }`, measure elapsed wall-clock. MUST be ≥ 2 s (timeout reached before container exits gracefully) AND < 5 s (remove happens after timeout). Currently elapsed is ~0 ms (signal sent, immediately force-removed).

**Risk**: Low. Behavior change for a currently-unused code path (nobody tests this today).
**LOC**: ~6 impl, ~25 test.

### B6 — ContainerImage.parse empty tag

**Change**: In `ContainerImage.scala:81-89`, change:
```
val (beforeTag, tagPart) =
    if digestPart.nonEmpty then (beforeDigest, Absent: Maybe[String])
    else
        val lastColon = beforeDigest.lastIndexOf(':')
        if lastColon == -1 then (beforeDigest, Absent: Maybe[String])
        else
            val afterColon = beforeDigest.substring(lastColon + 1)
            if afterColon.contains('/') then (beforeDigest, Absent: Maybe[String])
            else (beforeDigest.substring(0, lastColon), Present(afterColon))
        end if
```
to add an empty-tag guard:
```
val (beforeTag, tagPart) =
    if digestPart.nonEmpty then (beforeDigest, Absent: Maybe[String])
    else
        val lastColon = beforeDigest.lastIndexOf(':')
        if lastColon == -1 then (beforeDigest, Absent: Maybe[String])
        else
            val afterColon = beforeDigest.substring(lastColon + 1)
            if afterColon.isEmpty then (beforeDigest.substring(0, lastColon), Absent: Maybe[String])
            else if afterColon.contains('/') then (beforeDigest, Absent: Maybe[String])
            else (beforeDigest.substring(0, lastColon), Present(afterColon))
        end if
```
Then `finalTag` at line 112-114 already defaults `Absent` to `Present("latest")`. Empty colon → no tag → default latest.

**Test** (unit): `ContainerImage.parse("alpine:").getOrThrow.tag == Present("latest")`. Currently `Present("")`.
Also keep round-trip: `parse("alpine:3.19").getOrThrow.tag == Present("3.19")`.

**Risk**: Trivial.
**LOC**: 2 impl, ~10 test.

### B7 — RegistryAuth.toString redaction

**Change**: In `ContainerImage.scala:363`, change:
```
case class RegistryAuth(auths: Map[ContainerImage.Registry, String]) derives CanEqual
```
to:
```
case class RegistryAuth(auths: Map[ContainerImage.Registry, String]) derives CanEqual:
    override def toString: String =
        s"RegistryAuth(registries=${auths.keys.toSeq.map(_.value).mkString(",")}, credentials=<redacted>)"
```

**Test** (unit): Construct `val auth = RegistryAuth("user", "verysecretpass")`. Assert `auth.toString` does not contain `"user"`, `"verysecretpass"`, or the base64 encoding `"dXNlcjp2ZXJ5c2VjcmV0cGFzcw=="` (computed: base64 of `"user:verysecretpass"`).

**Risk**: Breaks `CanEqual`? No — `derives CanEqual` uses structural equality, not toString. No existing code relies on `toString` format.
**LOC**: 3 impl, ~10 test.

### B8 — attachById returns full Config

**Change**: Both backends currently:
```
def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Config) < ... =
    inspect(idOrName).map { info =>
        val config = new Config(
            image = info.image,
            name = if info.name.nonEmpty then Present(info.name) else Absent
        )
        (info.id, config)
    }
```
Replace with full conversion from `Container.Info` to `Container.Config`:
```
def attachById(idOrName: Container.Id)(using Frame): (Container.Id, Config) < ... =
    inspect(idOrName).map { info =>
        val config = new Config(
            image = info.image,
            command = /* Command from info.command split + info.env */,
            name = if info.name.nonEmpty then Present(info.name) else Absent,
            labels = info.labels,
            ports = info.ports,
            mounts = info.mounts,
            /* other fields from Info where present */
        )
        (info.id, config)
    }
```

Since `Container.Info` doesn't carry all Config fields (no hostname, user, dns, resource limits in Info currently — need to verify), this fix may require extending `Container.Info` OR accepting partial config reconstruction. **Decision: reconstruct only fields present in Info. If Info is missing a field, leave Config default.**

Specific mapping (from `Container.Info` fields that exist):
- `image` ← `info.image`
- `name` ← `Present(info.name)` if non-empty
- `labels` ← `info.labels`
- `ports` ← `info.ports`
- `mounts` ← `info.mounts`
- `command` ← `Command.apply(info.command.split(" "): _*)` then `.envAppend(info.env)` (best-effort)
- `restartPolicy` ← `No` (Info doesn't expose this; acceptable default)

**Test** (containerOnly): Create container with `port(80, 8080).label("k", "v").bind(/tmp/x, /container/x)`. Then `Container.attach(id)`. The re-attached container's `config` field must contain the port binding, label, and bind mount.

**Risk**: Medium. Partial reconstruction is visible in the API. Document in scaladoc: "Config is reconstructed from inspect; some fields (resource limits, network mode, stop signal) are not preserved and fall back to defaults."
**LOC**: ~25 impl each backend, ~30 test.

### C1 — Log stdout/stderr ordering (Shell backend)

**Change**: In `ShellBackend.scala:707-713`, force internal timestamps when both streams requested. Approach:

```
def logs(
    id: Container.Id, stdout: Boolean, stderr: Boolean,
    since: Instant, until: Instant, tail: Int, timestamps: Boolean
)(using Frame): Chunk[Container.LogEntry] < ... =
    val needMerge = stdout && stderr
    val effectiveTimestamps = timestamps || needMerge
    val args = /* existing args, but -t if effectiveTimestamps */
    // ... spawn command ...
    yield
        val outStr = /* ... */
        val errStr = /* ... */
        if !exitCode.isSuccess then Abort.fail(...)
        else
            val outEntries = if stdout then parseLogLines(outStr, Stdout, effectiveTimestamps) else Chunk.empty
            val errEntries = if stderr then parseLogLines(errStr, Stderr, effectiveTimestamps) else Chunk.empty
            val merged = if needMerge then
                Chunk.from((outEntries ++ errEntries).toSeq.sortBy(_.timestamp.map(_.toJava.toEpochMilli).getOrElse(0L)))
            else outEntries.concat(errEntries)
            // Strip timestamps if user didn't ask for them
            if !timestamps && effectiveTimestamps then merged.map(_.copy(timestamp = Absent)) else merged
```

**Test** (containerOnly): Container running
```
sh -c "trap 'exit 0' TERM; echo o1; sleep 0.2; echo e1 >&2; sleep 0.2; echo o2; sleep 0.2; echo e2 >&2; sleep infinity"
```
Call `c.logs(stdout=true, stderr=true)`. Entries MUST appear in order `o1, e1, o2, e2` by source tag. Currently returns `o1, o2, e1, e2`.

**Risk**: Sort stability when timestamps are equal (sub-millisecond precision) — use stable sort.
**LOC**: ~15 impl, ~30 test.

### C2 — Line assembly across chunks

**Change**: New file `shared/src/main/scala/kyo/internal/LineAssembler.scala`:
```
package kyo.internal

import kyo.*

/** Accumulates partial-line text across stream chunks, emitting only complete \n-terminated lines. */
private[kyo] final class LineAssembler:
    private val buffer = new StringBuilder

    /** Feed a chunk. Returns complete lines; partial trailing text is retained for the next call. */
    def feed(text: String): Chunk[String] =
        buffer.append(text)
        val acc = Chunk.newBuilder[String]
        var start = 0
        var i = 0
        val len = buffer.length
        while i < len do
            if buffer.charAt(i) == '\n' then
                acc.addOne(buffer.substring(start, i))
                start = i + 1
            end if
            i += 1
        end while
        if start > 0 then buffer.delete(0, start)
        acc.result()
    end feed

    /** Flush any buffered residual (called when stream ends). */
    def flush: Maybe[String] =
        if buffer.isEmpty then Absent
        else
            val s = buffer.toString
            buffer.setLength(0)
            Present(s)
end LineAssembler
```

Wire into 4 call sites:
- `ShellBackend.scala:586-590` (execStream) — `.mapChunk` becomes stateful via closure over a `LineAssembler` instance
- `ShellBackend.scala:786-799` (logStream)
- `HttpContainerBackend.scala:430-434` (execStream CLI fallback)
- `HttpContainerBackend.scala:1402-1456` (parseRawLogStream + demuxStream line parsing)

For stream site wiring, use kyo's stream-state pattern (looking at `Stream.mapChunk` behavior; may need custom Stream operator if `mapChunk` lambdas can't hold state). Fallback: use `Stream` with explicit state via `Loop` + `Emit`.

Exact wiring per call site to be determined during implementation; the LineAssembler is the primitive.

**Tests**:
- Unit (`LineAssemblerTest`): 8 tests covering: empty feed; single complete line; partial line held; multi-line in one feed; line straddling two feeds; line straddling three feeds; trailing newline behavior; flush returns residual.
- Integration (containerOnly): Container emits a single 128 KB line via `yes | head -c 131072 | tr -d '\n'; echo`. Stream via `logStream`, assert a single `LogEntry` whose content length is 131072.

**Risk**: New internal state in streams — stream fibers can run concurrently. LineAssembler is per-stream-invocation (closure-scoped), so no cross-fiber sharing.
**LOC**: ~30 impl (utility) + ~30 wiring, ~100 test (unit + integration).

### D1 — Remove attachWebSocket

**Change**: Delete `Container.scala:197-210` (both overloads). No replacement — method had no working implementation.

**Test**: Compile check.
**Risk**: Public API break. Grep confirms no internal callers. External users who called this got `NotSupported` anyway.
**LOC**: -14 (deletion).

### D2 — ShellBackend exec panic consistency

**Change**: In `ShellBackend.scala:487-493`, change:
```
case Result.Panic(e)   => throw e
```
to:
```
case Result.Panic(e)   => Abort.panic(e)
```
Matches the panic handling in `run` at `:1822-1828`.

**Test**: Implicit via existing tests; no new test (panic is hard to induce deterministically).
**Risk**: Trivial.
**LOC**: 1.

### D3 — Health check error message truncation

**Change**: In `Container.scala:1399`, change `e.getMessage.take(100)` → `e.getMessage.take(500)`.

**Test**: None (cosmetic). Verified by eye.
**Risk**: None.
**LOC**: 1.

### O1 — Log cleanup failures

**Change**: In `Container.scala:342-345`, change:
```
val shutdown = config.stopSignal match
    case Present(signal) =>
        Abort.run[ContainerException](b.kill(cid, signal)).unit
    case Absent =>
        Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).unit
shutdown.andThen(Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).unit)
```
to:
```
def logFailure(op: String)(r: Result[ContainerException, Unit]): Unit < Sync = r match
    case Result.Success(_) => ()
    case Result.Failure(e) => Log.warn(s"Container ${cid.value} $op failed: ${e.getMessage}")
    case Result.Panic(e)   => Log.warn(s"Container ${cid.value} $op panicked: ${e.getMessage}")

val shutdown = config.stopSignal match
    case Present(signal) =>
        Abort.run[ContainerException](b.kill(cid, signal)).map(logFailure("kill"))
    case Absent =>
        Abort.run[ContainerException](b.stop(cid, config.stopTimeout)).map(logFailure("stop"))
shutdown.andThen(Abort.run[ContainerException](b.remove(cid, force = true, removeVolumes = false)).map(logFailure("remove")))
```

Combine with B5 (stopTimeout) — same code block; both changes land together.

**Test**: Manual only. Induce daemon failure is not CI-reliable.
**Risk**: Low. `Log.warn` is already in kyo core.
**LOC**: ~15 (combined with B5 rewrite).

### Doc1 — HTTP backend CLI dependencies

**Change**: In `Container.scala` `BackendConfig` enum scaladoc (around line 920), add:
```
/** Controls which container runtime backend to use.
  *
  * NOTE: The UnixSocket backend still shells out to the `docker`/`podman` CLI for
  *   - image pull (`imagePull`, `imagePullWithProgress`, `imageEnsure`)
  *   - image build (`imageBuildFromPath`)
  *   - exec streaming (`execStream`)
  * The CLI must be on PATH. `copyTo` / `copyFrom` additionally require `tar`.
  */
```
Also add to each per-case doc.

**Test**: None (documentation).
**Risk**: None.
**LOC**: ~10 doc.

---

## 4. Deferred Items (Explicit Rationale)

| ID | Item | Why deferred |
|---|---|---|
| X1 | Stop merging stderr into stdout for output-parsing commands (`ShellBackend.scala:1815-1816`) | Reproduction only fires on bare-metal Linux with kernel warnings. Cannot write a deterministic CI-green failing test. Touches ~30 call sites. Deserves its own PR with specific reproduction on a WSL/Linux-kernel environment. |
| X2 | Unify `Network.Id` across backends (Shell returns name, HTTP returns ID) | Breaking behavior change across a backend switch. Nobody persists Network.Id across backend swaps in current tests; defer to separate proposal with migration note. |
| X3 | Split `AttachSession` into `ReadOnlyAttachSession` / `BidirectionalAttachSession` | Public API break. Deserves its own proposal. |
| X4 | Streaming `copyTo` / `copyFrom` on HTTP backend | Requires `HttpClient.putBinaryStream` + tar stdin piping. Substantial; defer. |
| X5 | Native HTTP impls for `imagePull`, `imageBuild`, `execStream` | CLI fallbacks work. Doc1 documents current state; native impl deferred. |
| X6 | Delete `ContainerImage.build(stream)` stream variant | Public API removal. Keep `NotSupported` + add doc. Doc1 partially addresses. |
| X7 | `statsStream` double round-trip (state + stats per sample) | Performance only. Defer. |
| X8 | `ContainerImage.parse` dead branch cleanup (`ContainerImage.scala:104-106`) | No behavior change. Pure cosmetic. |
| X9 | `parseDurationSeconds` integer truncation (`ShellBackend.scala:1984-1985`) | Docker's stop/restart API uses integer seconds natively. Not a bug. |
| X10 | Cache backend detection across `withBackend` calls | Introduces module-level mutable state (ConcurrentHashMap). Library should stay stateless. Users who need caching can wrap a single `BackendConfig` in their own scope. |

---

## 5. Phase Plan

Each phase has:
- **Goal**: single-sentence objective
- **Files read**: inputs for the implementing agent
- **Files produced/modified**: deliverables
- **New tests**: count + list
- **Verification command**: exact sbt invocation
- **Supervisor checks**: what I verify post-agent
- **Bug-fix gate**: for test-phases, confirms tests FAIL; for fix-phases, confirms tests now PASS

### Phase 1 — Failing integration tests for B1-B8

**Goal**: Write 8 failing tests proving each B-item's bug exists.
**Files read**:
- `shared/src/main/scala/kyo/Container.scala` (grep only, don't load full file)
- `shared/src/main/scala/kyo/ContainerImage.scala`
- `shared/src/test/scala/kyo/ContainerTest.scala` (test patterns)
- `shared/src/test/scala/kyo/ContainerUnitTest.scala` (unit test patterns)
**Files produced/modified**:
- Append to `shared/src/test/scala/kyo/ContainerTest.scala`: new `"improvements (failing)"` section with tests for B1, B2, B3, B4, B5, B8
- Append to `shared/src/test/scala/kyo/ContainerUnitTest.scala`: new tests for B6, B7
**New tests**: 8
1. B1: `HealthCheck.exec with 'not ok' output does not match 'ok'`
2. B2: `isHealthy returns false quickly (<500ms) when check fails`
3. B3: `init completes quickly (<2s) when autoRemove container exits during health check`
4. B4: `NotFound error references container id, not host path (copyFrom)`
5. B5: `scope cleanup waits stopTimeout when stopSignal is Present`
6. B6: `parse("alpine:") defaults to tag latest`
7. B7: `RegistryAuth.toString does not expose credentials`
8. B8: `attachById preserves ports, labels, and mounts in Config`
**Verification command**: `sbt 'kyoPodJVM/testOnly kyo.ContainerTestDocker -- -z "improvements (failing)"' 2>&1 | tail -20`
**Supervisor checks**:
- All 8 tests compile.
- All 8 tests FAIL when run (bug-fix gate: supervisor runs `sbt testOnly` and greps for `FAILED` or `*** FAILED ***`).
- No existing tests modified (`git diff --stat -- '**/*Test*' | grep -v improvements` returns nothing material).
**Expected outcome**: 8 tests added, 8 failing. Phase 2 then makes them pass.

### Phase 2 — Fixes B1-B8

**Goal**: Apply minimal impl changes so Phase 1 tests pass. Touches only implementation files; tests from Phase 1 stay untouched.
**Files read**:
- This plan (Section 3 B1-B8 specs)
- `shared/src/main/scala/kyo/Container.scala` (grep for specific line ranges only)
- `shared/src/main/scala/kyo/ContainerImage.scala`
- `shared/src/main/scala/kyo/internal/ShellBackend.scala`
- `shared/src/main/scala/kyo/internal/HttpContainerBackend.scala`
**Files produced/modified**:
- Modify `Container.scala` (B1, B2, B3, B5)
- Modify `ContainerImage.scala` (B6, B7)
- Create `shared/src/main/scala/kyo/internal/ResourceContext.scala` (B4)
- Modify `ShellBackend.scala` (B4, B8)
- Modify `HttpContainerBackend.scala` (B4, B8)
**Verification command**: `sbt 'kyoPodJVM/testOnly kyo.ContainerTestDocker kyo.ContainerUnitTest' 2>&1 | tail -10`
**Supervisor checks**:
- Phase 1 tests now PASS (bug-fix gate).
- Full existing test suite still PASSES (no regressions).
- No tests modified (`git diff --stat -- '**/*Test*'` shows only the Phase 1 additions).
- `git diff --stat -- '**/*.scala' | grep -v Test` shows ~5 prod files touched.
- Platform compile: `sbt kyoPodJS/compile kyoPodNative/compile 2>&1 | tail -5` succeeds.
**Expected outcome**: All B1-B8 tests green. No regressions.

### Phase 3 — Failing test for C1 (log ordering)

**Goal**: Write a failing integration test proving log stdout/stderr ordering is lost.
**Files read**: `shared/src/test/scala/kyo/ContainerTest.scala`
**Files modified**: append to `ContainerTest.scala` under "improvements (failing)"
**New tests**: 1
- C1: `logs interleaves stdout and stderr in emission order`
**Verification command**: `sbt 'kyoPodJVM/testOnly kyo.ContainerTestDocker -- -z "interleaves stdout"' 2>&1 | tail -10`
**Supervisor checks**: test FAILS today.

### Phase 4 — Fix C1

**Goal**: Implement timestamp-based merge-sort for `logs` when both streams requested.
**Files modified**: `ShellBackend.scala` (lines 671-725 rewritten).
**Verification command**: same as Phase 3; test now passes.
**Supervisor checks**: C1 test passes; existing log tests (`"stdout=false excludes..."`, `"tail limits..."`, etc.) still pass.

### Phase 5 — Failing tests for LineAssembler (C2)

**Goal**: Unit tests + integration test for line assembly.
**Files produced**: `shared/src/test/scala/kyo/internal/LineAssemblerTest.scala` (8 unit tests).
**Files modified**: append integration test for 128 KB-line streaming to `ContainerTest.scala`.
**New tests**: 9 (8 unit + 1 integration)
**Verification command**:
- `sbt 'kyoPodJVM/testOnly kyo.internal.LineAssemblerTest' 2>&1 | tail -10` — all 8 fail to compile (LineAssembler doesn't exist yet).
- `sbt 'kyoPodJVM/testOnly kyo.ContainerTestDocker -- -z "128 KB"' 2>&1 | tail -10` — fails (current code splits long lines).
**Supervisor checks**: unit tests fail to compile (class missing); integration test fails at runtime.

### Phase 6 — Implement LineAssembler and wire into streaming

**Goal**: Create utility + wire into 4 streaming call sites.
**Files produced**: `shared/src/main/scala/kyo/internal/LineAssembler.scala`.
**Files modified**: `ShellBackend.scala` (2 call sites), `HttpContainerBackend.scala` (2 call sites).
**Verification command**: same as Phase 5; tests pass.
**Supervisor checks**:
- All 9 Phase-5 tests pass.
- Existing streaming tests pass (`execStream`, `logStream`).
- Multi-platform compile: JS + Native.

### Phase 7 — Cleanups (D1, D2, D3)

**Goal**: Delete dead code, consistency tweaks.
**Files modified**: `Container.scala`.
**New tests**: None (compile check only).
**Verification command**: `sbt kyoPodJVM/compile 2>&1 | tail -5`.
**Supervisor checks**:
- `git grep -n 'attachWebSocket' kyo-pod/` returns nothing.
- `git grep -n 'throw e' kyo-pod/shared/src/main/scala/kyo/internal/ShellBackend.scala` shows 0 occurrences (replaced by `Abort.panic`).
- `git grep -n 'take(100)' kyo-pod/` in the modified line context shows `take(500)`.
- All existing tests pass.

### Phase 8 — O1 (log cleanup failures)

**Goal**: Replace silent `.unit` in scope cleanup with `Log.warn` calls.
**Files modified**: `Container.scala:342-345` (combined with B5 if both landed — re-verify).
**New tests**: None (induction requires daemon failure; verified by review).
**Verification command**: `sbt kyoPodJVM/compile 2>&1 | tail -5`.
**Supervisor checks**: grep for `Log.warn` in the modified block; existing tests pass; no silently-swallowed failures in the scope-ensure block.

### Phase 9 — Documentation (Doc1)

**Goal**: Scaladoc on `BackendConfig` documenting CLI fallbacks.
**Files modified**: `Container.scala` (scaladoc only).
**New tests**: None.
**Verification command**: `sbt kyoPodJVM/doc 2>&1 | tail -10`.
**Supervisor checks**: `sbt doc` succeeds; grep for the documented keywords shows up in scaladoc.

### Phase 10 — Audit (opus agent)

**Goal**: Cross-check entire plan against produced code.
**Files read**: all modified files + this plan.
**Deliverables**: audit report as `TaskCreate` entries for each finding.
**Scope**:
- Verify every IN item (16 items) has a corresponding code change.
- Verify every test enumerated in Section 6 exists.
- Verify no existing tests were modified (`git diff -- '**/*Test*'` bounded to additions only).
- Verify CONTRIBUTING.md alignment (Section 7).
- Verify JVM + JS + Native compile.
- Verify full test suite green on JVM.
**Exit criteria**: zero unresolved audit tasks.

---

## 6. Test Scenario Enumeration

Total new tests: **26**. Reconciling: 8 B + 1 C1 + 9 C2 = 18 new behavior tests + 8 unit (B6, B7, and 8 LineAssembler unit tests listed below).

### Behavior/integration tests (containerOnly or httpBackendOnly)

1. **B1** — HealthCheck.exec expected mismatch fails init (containerOnly).
2. **B2** — isHealthy returns false in < 500 ms when check fails (containerOnly).
3. **B3** — init completes in < 2 s when autoRemove + fast exit (containerOnly).
4. **B4a** — copyFrom on nonexistent container → NotFound with correct id (containerOnly).
5. **B4b** — HTTP backend 404 on collection endpoint emits General, not NotFound (httpBackendOnly).
6. **B5** — scope cleanup waits stopTimeout with stopSignal (containerOnly).
7. **B8** — attachById preserves ports, labels, mounts (containerOnly).
8. **C1** — logs interleave in emission order (containerOnly).
9. **C2-integration** — 128 KB single line arrives intact via logStream (containerOnly).

### Unit tests (no container runtime)

11. **B6** — `parse("alpine:")` yields `tag = Present("latest")`.
12. **B7** — `RegistryAuth.toString` does not expose user, password, or base64 creds.
13-20. **C2-unit** — LineAssembler (8 tests):
- empty feed → empty chunk
- single complete line → single entry, buffer empty
- partial line held in buffer
- multi-line one feed
- line straddling two feeds
- line straddling three feeds
- trailing newline produces entry with empty continuation
- flush returns residual

---

## 7. CONTRIBUTING.md Alignment

Plan verified against `/Users/fwbrasil/workspace/kyo/CONTRIBUTING.md`:

- **Core Principles**: all changes preserve effect-typed error handling, no AllowUnsafe introduced, Scope-based resource safety maintained, existing test patterns followed.
- **API Design**: `awaitHealthy` (new method in B2) is named as a verb-noun following existing pattern (`waitForExit`, `unpause`). `LineAssembler` is internal (`kyo.internal` package). `ResourceContext` is `private[internal]`.
- **Type conventions**: use `Maybe`, `Chunk`, `Result` (no Option/Seq/Either in new code). Span used in existing streaming paths; LineAssembler uses String/StringBuilder (internal).
- **Method conventions**: `.andThen`, `.unit`, `.map` only. No symbolic operators introduced.
- **File organization**: public API first (Container.scala ordering preserved); internals in `kyo.internal`.
- **Unsafe boundary**: no AllowUnsafe; `Log.warn` in scope cleanup is at a system boundary (daemon failure observation).
- **Testing**: test-first discipline; no test weakening; failing tests committed first in a separate phase.

CONTRIBUTING verification runs as part of Phase 10 audit (opus).

---

## 8. Supervision Plan

Per-phase monitoring and verification (extracted from the Phase table for quick reference):

- **Phase 1 (B-item failing tests)**: TaskList for subtasks; post-agent `git diff --stat -- '**/*Test*'` should show only added tests; `sbt testOnly | grep FAILED` confirms all 8 fail.
- **Phase 2 (B-item fixes)**: design verification agent checks code at Container.scala:101, 340, 659, 1407; ShellBackend.scala:1847 call sites; HttpContainerBackend.scala withErrorMapping usages. Post-agent full test run. Bug-fix gate: previously failing tests must now pass.
- **Phases 3-4**: same pattern for C1.
- **Phases 5-6**: LineAssembler unit tests first; integration test separately. Design agent checks all 4 wiring sites.
- **Phases 7-9**: small; compile + test passes.
- **Phase 10 (audit, opus)**: full review; creates follow-up tasks for any drift.

Steering file: `kyo-pod/STEERING.md` (created at Stage-2 start).

If an agent reward-hacks (modifying a test to pass rather than fixing impl): `git diff -- '**/*Test*'` reveals it, agent is killed, changes reverted.

---

## 9. Rollback Strategy

Each phase is a separate commit on the `kyo-container` branch. If a phase introduces a regression discovered in a later phase:
1. `git revert <commit>` on that phase.
2. Update PROGRESS.md with the rollback reason.
3. Re-design affected phase and re-run.

Cross-phase dependencies:
- Phase 2 depends on Phase 1 (tests need to exist and fail).
- Phase 4 depends on Phase 3.
- Phase 6 depends on Phase 5.
- Phase 8 (O1) may overlap with Phase 2 (B5) — both touch `Container.scala:340-345`. If B5 lands first, O1 adapts; if both in one edit, merge carefully.
- Phase 10 depends on all others.

---

## 10. Open Questions / Decisions for User

None mandatory — the plan is self-contained. Optional:

1. **Deferred items X1-X9**: if you want any promoted to IN, say which and I'll re-plan.
2. **B5 test determinism**: the stopTimeout test relies on wall-clock timing (≥ 2 s). Acceptable flakiness target is ± 500 ms. If tighter is required, the test needs `Clock.now` instrumentation rather than wall-clock.
3. **B8 partial Config reconstruction**: the reconstructed Config is a lossy round-trip. Acceptable, or should I also extend `Container.Info` to include the missing fields (hostname, user, dns, resource limits) for a full round-trip? (This would bloat Info and affect both backends' inspect mapping.)
4. **D1 public API break** (`attachWebSocket` removal): if anyone outside this worktree depends on the signature, removal breaks their compile. Alternative: deprecate with `@deprecated` first.

Please respond with **"Approved"** (or a list of modifications / decisions on the open questions) before I proceed to Stage 2.

---

## 11. Appendix: File Change Manifest

**Modified files** (5 production + 2 test):
- `kyo-pod/shared/src/main/scala/kyo/Container.scala` — B1 (1 line), B2 (~10 lines), B3 (2 lines), B5 (~6 lines), D1 (-14), D3 (1 line), O1 (~15 lines), Doc1 (~10 lines)
- `kyo-pod/shared/src/main/scala/kyo/ContainerImage.scala` — B6 (2 lines), B7 (3 lines)
- `kyo-pod/shared/src/main/scala/kyo/internal/ShellBackend.scala` — B4 (~60 lines call-site threading + delete duplicate `parseLogLine`), B8 (~25 lines), C1 (~15 lines), D2 (1 line), C2 wiring (~20 lines)
- `kyo-pod/shared/src/main/scala/kyo/internal/HttpContainerBackend.scala` — B4 (~30 lines), B8 (~25 lines), C2 wiring (~20 lines)
- `kyo-pod/shared/src/test/scala/kyo/ContainerTest.scala` — +9 integration tests
- `kyo-pod/shared/src/test/scala/kyo/ContainerUnitTest.scala` — +2 unit tests
- `kyo-pod/shared/src/test/scala/kyo/internal/LineAssemblerTest.scala` (new) — 8 unit tests

**New files**:
- `kyo-pod/shared/src/main/scala/kyo/internal/ResourceContext.scala` — B4 enum (~20 lines)
- `kyo-pod/shared/src/main/scala/kyo/internal/LineAssembler.scala` — C2 utility (~30 lines)
- `kyo-pod/shared/src/test/scala/kyo/internal/LineAssemblerTest.scala` — see above

**Deleted code ranges**:
- `Container.scala:197-210` — `attachWebSocket` (both overloads, D1)

Total approximate delta:
- +235 LOC implementation (including new files)
- +375 LOC tests
- -14 LOC (attachWebSocket)
- Net: ~+600 LOC across 8 files.

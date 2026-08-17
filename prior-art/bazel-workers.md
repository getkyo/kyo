# Prior art: Bazel persistent workers

Sources: [Persistent Workers](https://bazel.build/remote/persistent), [Creating Persistent Workers](https://bazel.build/remote/creating), [Multiplex Workers](https://bazel.build/remote/multiplex), [`worker_protocol.proto`](https://github.com/bazelbuild/bazel/blob/master/src/main/protobuf/worker_protocol.proto), [Dynamic Execution](https://bazel.build/remote/dynamic); reliability findings additionally grounded in bazelbuild/bazel issues [#1868](https://github.com/bazelbuild/bazel/issues/1868), [#4505](https://github.com/bazelbuild/bazel/issues/4505), [#4897](https://github.com/bazelbuild/bazel/issues/4897), [#5767](https://github.com/bazelbuild/bazel/issues/5767), [#10288](https://github.com/bazelbuild/bazel/issues/10288), [#11948](https://github.com/bazelbuild/bazel/issues/11948), [#12165](https://github.com/bazelbuild/bazel/issues/12165), [#614](https://github.com/bazelbuild/bazel/issues/614), and the [process-wrapper writeup](https://jmmv.dev/2019/11/bazel-process-wrapper.html).

## 1. What it is

A Bazel *persistent worker* is a long-running process the Bazel server starts once and then reuses across many build actions of the same kind, instead of forking a fresh process per action. The worker is "a long-running process started by the Bazel server that acts as a wrapper around a tool (typically a compiler) or functions as the tool itself." The motivating cost is process-startup and warm-up overhead: JIT compilation, AST/symbol-table caching, classloading. A worker amortizes that across many requests instead of paying it per action.

Bazel talks to the worker over the worker's stdin/stdout using a fixed request/response protocol (protobuf by default, JSON opt-in). The worker process itself is otherwise opaque to Bazel: it is any executable that speaks the protocol.

## 2. Unit of work (a WorkRequest)

The unit of work Bazel hands to a worker is one `WorkRequest`: an argv-like argument list plus a declared input set, executed and answered with a single `WorkResponse`. There is no notion of a multi-step job or a pipeline at the protocol level; each request is a complete, self-contained invocation of the tool (e.g. "compile these files with these flags").

## 3. Grouping / keying (WorkerKey)

Workers are not interchangeable pools; they are grouped by identity. Each combination of **mnemonic** (action type, e.g. `Javac`) and **start-up flags** is combined into a `WorkerKey`, and Bazel may create up to `--worker_max_instances` worker processes *per key* (default 4). Passing `--worker_extra_flag=javac=--debug` creates a distinct `WorkerKey` (and therefore a distinct pool of processes) from plain `javac` workers — different start-up flags fork the pool even for the same mnemonic.

For multiplex workers, the key's hash is "composed of environment variables, the execution root, and the mnemonic," and a rule can additionally set `worker-key-mnemonic` in `execution_requirements` "if you're reusing the executable for multiple action types and want to distinguish actions by this worker" — i.e. force two logically-different action kinds that happen to share a binary into separate keys/pools.

This is a direct analogue of `Task.Queue` identity in `Workers`: `WorkerKey` = mnemonic + startup flags + env (+ sandbox), our queue identity = `(name, classpath, jvmOptions, env)`. Both exist to answer the same question — "which warm process pool does this task belong to?"

## 4. Declarative config / requirements model

A spawn does not become worker-mode by default; a rule opts in per action via `execution_requirements`, a string-keyed map attached to the action:

- `"supports-workers": "1"` — declares the action *can* run via a persistent worker (Bazel may still fall back to a one-shot local execution).
- `"supports-multiplex-workers": "1"` — opts into multiplex mode; takes precedence over `supports-workers` if both are set.
- `"requires-worker-protocol": "json"` or `"proto"` — selects wire format (protobuf is the default if omitted).
- `"worker-key-mnemonic": "<name>"` — overrides the mnemonic component of `WorkerKey` for a shared binary serving multiple action types.
- `"supports-worker-cancellation": "1"` — opts the action into cancellation support (paired with the `--experimental_worker_cancellation` flag).

The first request against a fresh `WorkerKey` starts the process by executing the action's ordinary command line plus a `--persistent_worker` flag appended: "the first use of this action would start with executing the command line `/bin/some_compiler -max_mem=4G --persistent_worker`." The worker binary itself must branch on that flag: "only make itself persistent if that flag is passed, otherwise it must do a one-shot compilation and exit" — so the same binary is both the one-shot tool and the daemon, gated by a single flag it interprets itself. Per-request arguments are conventionally passed via an `@flagfile` argument (a args-file), keeping the worker's own start-up argv fixed and small while per-call arguments vary.

## 5. Worker / process model

This is the section most directly relevant to `Workers`' stateful queue design.

**Warm/persistent.** A worker is spawned once per `WorkerKey` (up to the instance cap) and stays alive across many `WorkRequest`s, not just across requests of one build but across builds until explicitly told to quit (`--worker_quit_after_build`) or evicted.

**Singleplex vs multiplex.**
- *Singleplex* (the default): "Each worker can currently only process one request at a time." `request_id` is `0` and requests to that worker are strictly serial.
- *Multiplex* (`supports-multiplex-workers`): one physical worker process serves many logical clients concurrently. Architecturally, Bazel hands each caller a `WorkerProxy` rather than a direct handle to the process; the proxy "forwards requests to the worker process sequentially along with a `request_id`," and a shared `WorkerMultiplexer` demultiplexes: "the worker process processes the request and sends responses to the `WorkerMultiplexer`. When the `WorkerMultiplexer` receives a response, it parses the `request_id` and then forwards the responses back to the correct `WorkerProxy`." "The server guarantees that a given worker receives requests with either only `request_id` 0 or only `request_id` greater than zero" — a worker process is singleplex or multiplex for its whole lifetime, never mixed. Multiplex pushes the concurrency obligation onto the tool: "whenever the worker process parses a request from the stream, it should handle the request in a new thread. Because different threads could complete and write to the stream at the same time, the worker process needs to make sure the responses are written atomically." The tool must be safely concurrent (or serialize itself internally); the protocol gives no free concurrency, only routing.

**Keying and reuse.** Reuse is strictly scoped to workers sharing a `WorkerKey`; there is no cross-key sharing or spillover. `--worker_max_instances` bounds instances *per key*, not globally — a build with many distinct mnemonics/flag-sets can spawn far more worker processes in aggregate than any single number suggests. A 2020 tracking issue (bazelbuild/bazel#12165) notes this gap explicitly: no global cap exists, and "the ideal behavior for worker management would involve maintaining a list of workers capped at a certain number with an LRU-type scheme to shut down older workers" — a feature Bazel did not have. This is the single clearest place where Bazel's model is *weaker* than what `Workers` (a global `Config(parallelism=N)` cap across all queues) is explicitly designed to fix.

**Sandboxing.** By default the `worker` strategy runs unsandboxed, "similar to the `local` strategy" — the worker sees the real execution root. `--worker_sandboxing` gives each *request* its own sandbox subdirectory under `<outputBase>/bazel-workers`. For multiplex, the request carries its own scratch path: `WorkRequest.sandbox_dir` — the worker must "use the `sandbox_dir` field from the `WorkRequest` and use that as a prefix for all file reads and writes" so concurrently-served requests on one process don't collide on disk. Bazel is explicit that this is a weaker isolation guarantee than a true sandbox: "the tool may keep other internal state that has been affected by previous requests" — sandboxing covers the filesystem, not the process's in-memory state.

**Lifecycle / crash handling / eviction.** Thin by design, and thin in practice — see the dedicated reliability section (§7) for the full, citation-heavy picture: no heartbeat, lazy crash detection, documented orphan leaks, and only a static (not memory-adaptive) per-key instance cap.

## 6. Scheduling & concurrency control

- `--worker_max_instances` (default 4): hard cap on live processes per `WorkerKey`.
- `--worker_max_multiplex_instances`: caches the number of `WorkerProxy`s (logical concurrent clients) sharable per multiplex worker process — but the *process* count is still bounded by `--worker_max_instances`; "the total number of workers, including regular workers and `WorkerProxy`s, is still limited by `--worker_max_instances`." So multiplexing raises concurrency without raising the process count: many logical clients timeshare a small number of OS processes.
- `--high_priority_workers`: names a mnemonic that "should be run in preference to normal-priority mnemonics" — a coarse, mnemonic-level priority lane, not a per-task priority.
- No global scheduler-wide concurrency cap exists across all `WorkerKey`s; concurrency control is local to each key.

## 7. Worker reliability

This is the section most useful for stress-testing `Workers`' own reliability model, precisely because Bazel's answer is thin — the gaps are as informative as the mechanisms. Findings below are grounded in the protocol spec, the docs, and Bazel's own issue tracker (which is where the honest, load-bearing detail actually lives; the docs pages stay quiet about failure modes).

**7.1 Liveness / health detection.** There is no heartbeat, ping, or health-check message in the protocol (`worker_protocol.proto` has exactly two request kinds: a normal request and a `cancel`; nothing else). Bazel does not poll a worker to ask "are you alive" between requests. The only signal Bazel gets that a worker is unhealthy is the absence or corruption of the `WorkResponse` it is already blocked waiting for.

**7.2 Hung / stuck worker detection and recovery.** None, concretely and by report. bazelbuild/bazel#10288 documents a real, reproduced-in-production multiplex deadlock: "the Bazel server and worker process become mutually blocked" — the server stuck in `WorkerMultiplexer.waitResponse()` reading the worker's stdout, the worker stuck reading Bazel's stdin, both parked in `FileInputStream.readBytes()` with nothing arriving on either side. The reporter states plainly: "we have not been able to reproduce this bug consistently... it seems to be due to a race condition," and the issue documents no timeout, watchdog, or deadlock-breaker that would have unstuck it — the build simply hangs until a human kills it. Separately, action-level `timeout` execution requirements are the standard per-action deadline mechanism in Bazel, but nothing in the docs or the worker protocol ties a request-level deadline to the `WorkRequest`/`WorkResponse` exchange itself; the request/response round trip has no protocol-visible expiry.

**7.3 Crash detection and automatic restart.** Detection is reactive, not proactive, and happens in exactly two ways: (a) a malformed stdout write is reported as "Worker process returned an unparseable WorkResponse!" (a well-known trap: any stray `println`/`console.log` from the wrapped tool corrupts the stream, per bazelbuild/bazel#4897 and #5767), or (b) the process exits without ever writing a response, reported as "Worker process did not return a WorkResponse" (bazelbuild/bazel#11948). In both cases the *in-flight action fails* — there is no automatic re-execution of that same request. What Bazel *does* automatically restart a worker for is narrower and specific: a worker whose `WorkerKey` becomes stale because its own tool inputs changed. Bazel's own integration test suite names this exact case, `test_worker_restarts_when_worker_binary_changes()` (`src/test/shell/integration/bazel_worker_test.sh`) — rebuilding the compiler the worker wraps invalidates the key's tool-input hash and the next request against that key gets a freshly spawned process. That is a *content-change* restart, not a *crash-recovery* restart; a worker that dies mid-request for any other reason (OOM kill, uncaught fatal exception, SIGKILL from the OS) simply fails the action in front of it, with recovery left to the next `bazel build` invocation re-driving the (now again empty) `WorkerKey` pool from scratch.

**7.4 Orphan prevention.** This is a documented, long-standing weak spot, not a design that was tried and found adequate. bazelbuild/bazel#1868, "`bazel shutdown` doesn't kill persistent workers," reports exactly the failure mode the name suggests — after `bazel shutdown` the server process exits but roughly eight Javac/Closure worker processes were left running — filed as P1 and assigned, with no evidence in the tracked thread of a documented resolution mechanism (process-group binding, a parent-death signal, or a reaper) being described. A related issue, bazelbuild/bazel#4505, generalizes the complaint: `bazel shutdown` only stops workers belonging to the *current* workspace, so workers from other workspaces on the same machine are left running indefinitely regardless. Contrast this with Bazel's *other* subprocess-management path: the `process-wrapper` tool used for one-shot sandboxed actions explicitly places its child "under a new process group" specifically so it can "terminate this whole group in unison," guaranteeing transitive children die with the parent. That mechanism is real, shipped, and works — but per its own documentation it applies only to one-shot sandboxed actions, not to the persistent worker process itself. The stronger orphan-prevention machinery Bazel built exists right next to persistent workers and simply was not extended to cover them.

**7.5 Graceful vs forced shutdown.** No protocol-level "please shut down cleanly" message exists in `worker_protocol.proto` — there is no third request type beyond a normal request and a `cancel`. Shutdown of a whole worker process is OS-signal-based, external to the protocol: `--worker_quit_after_build` forces every worker to exit once a build finishes, and Bazel separately added a generic `supports-graceful-termination` execution requirement/tag (Bazel 3.6) that, for tagged actions, sends `SIGTERM` first and a delayed `SIGKILL` only if the process hasn't exited by the grace-period deadline — giving the process a chance to clean up. That mechanism is generic to actions/tests, not specific to (or documented as integrated with) the worker request/response protocol: a worker mid-request receiving `SIGTERM` has no protocol-level way to finish or fail cleanly the specific in-flight `WorkRequest` it holds; the shutdown signal and the request/response conversation are two unrelated layers.

**7.6 Resource-leak avoidance and recycling.** `--worker_max_instances` (default 4, per `WorkerKey`) is a static ceiling set once at flag-parse time, not an adaptive recycler. Bazel does expose per-worker memory via `WorkerMetrics.WorkerStats.worker_memory_in_kb` (surfaced through `--experimental_worker_metrics` and the JSON trace profiler), but nothing found in the docs or the sourced issues confirms this metric drives automatic recycling or eviction — bazelbuild/bazel#12165 (2020) explicitly requests exactly that ("maintaining a list of workers capped at a certain number with an LRU-type scheme to shut down older workers") as a *missing* feature, and a companion mailing-list thread ("Memory leak with persistent workers?") corroborates that unbounded worker memory growth over a long-lived build is a known, user-reported pain rather than something the system self-heals. The practical guidance the docs themselves give is manual: "lowering the value of `--worker_max_instances` might help to reduce the amount of memory used by persistent workers" — a human tuning a static cap, not the system recycling on a threshold.

**7.7 Delivery / execution guarantees when a worker dies mid-task.** Bazel is not a message queue and doesn't offer message-queue vocabulary here (no at-least-once/at-most-once/exactly-once framing exists in its docs) — the real guarantee comes from a different place: hermetic, content-addressed action caching. An action's outputs are only considered valid if the action ran to completion and produced the declared output set; a worker that dies mid-`WorkRequest` produces no valid outputs, so the *action* fails and the *build* fails for that target — there is no silent partial result and no duplicate/side-effecting re-execution to reason about, because Bazel never marks an action done until its outputs are in place. Recovery is coarse and manual: the user reruns `bazel build`, and Bazel's dependency/cache graph naturally skips every action whose outputs are already valid and re-attempts only the one(s) that failed — effectively an idempotent, external, whole-build retry rather than an in-flight reschedule of the specific dead request. Strategy lists such as `--strategy=Javac=worker,local` are commonly misread as a crash-time failover; they are not — "Bazel picks the first strategy from the given list that claims to be able to execute the action" is a static *eligibility* choice made before execution starts, not a runtime fallback triggered by a worker crashing mid-request. Separately, dynamic execution (local-vs-remote racing) has a documented, named gap here too: "Bazel is currently unable to interrupt actions executed by a worker" — so even when dynamic execution's remote branch wins the race first, "we'll have to complete the worker action anyway" rather than cancelling it, meaning worker-backed actions do not get the redundancy/early-cancellation benefit dynamic execution otherwise provides.

**7.8 Backpressure / overload protection.** Coarse and structural rather than adaptive: `--worker_max_instances` bounds concurrently-live processes per key, and any action beyond that bound simply queues in Bazel's local resource/action scheduler (the same scheduler that honors `--jobs` and `--local_resources` generally) until an instance frees up. There is no protocol-level "I'm busy, try later" response a worker can send back — a worker either accepts a request into its serial/multiplex processing or the request waits upstream of the process entirely. Multiplexing raises the number of logical clients a fixed number of OS processes can serve, which is itself a form of amortizing overload, but it is the only knob; there is no dynamic, load-adaptive admission control.

**7.9 Cancellation.** Opt-in via `supports-worker-cancellation` + `--experimental_worker_cancellation`, both still marked EXPERIMENTAL in the proto years after multiplexing shipped. A cancel is a `WorkRequest` with `cancel = true` set and only `request_id` populated (arguments/inputs omitted); the worker replies with a `WorkResponse` carrying `was_cancelled = true`. The protocol is strict about bookkeeping: "each non-cancel `WorkRequest` message must be answered exactly once, whether or not it was cancelled," workers must ignore cancels for requests already completed or unknown, and once any response is sent for a request "the worker must not touch the files in its working directory" tied to that request (the server may now sandbox-clean them). But cancellation is cooperative and best-effort throughout — the worker decides whether and when to actually stop work; the protocol only standardizes the acknowledgment shape, not the guarantee. bazelbuild/bazel#614 ("Workers need to support cancellations (e.g. to honor Ctrl+C)") is itself evidence this was a real, felt gap for years before `cancel` existed at all.

**7.10 Protocol break (recap).** Any malformed stdout write (an accidental `println`, a truncated OOM-kill write) kills usability of that worker instance outright; recovery is "the next request against that `WorkerKey` gets a freshly spawned process," not resumption or resync of the broken one. There is no resync handshake — the protocol has no recovery path short of full process replacement.

## 8. Results (WorkResponse)

Verbatim proto shape (`worker_protocol.proto`):

```proto
message Input {
  string path = 1;   // execroot-relative or absolute path
  bytes digest = 2;   // content hash, may be empty
}

message WorkRequest {
  repeated string arguments = 1;
  repeated Input inputs = 2;   // inputs the worker is allowed to read
  int32 request_id = 3;        // 0 for singleplex, unique per outstanding request for multiplex
  bool cancel = 4;              // EXPERIMENTAL: cancel a previously-sent request
  int32 verbosity = 5;          // >0 requests extra debug output
  string sandbox_dir = 6;       // multiplex sandbox scratch prefix
}

message WorkResponse {
  int32 exit_code = 1;
  string output = 2;            // combined stdout+stderr of the wrapped tool, UTF-8
  int32 request_id = 3;         // must match the request it answers
  bool was_cancelled = 4;       // EXPERIMENTAL: true if this responds to a cancel
}
```

A response is deliberately minimal: exit code, a single opaque text blob for human-readable output, the correlating id, and a cancellation flag. There is no structured error type, no partial-result channel, no side-channel for produced-artifact metadata — output artifacts are expected to already exist on disk (declared via the build graph), and `output` is diagnostic text only.

## 9. Transport / protocol

Stdin/stdout, chosen specifically because it is universally available and already how Bazel talks to an ordinary subprocess:

- **Wire format**: protobuf binary by default; JSON is opt-in and experimental via `requires-worker-protocol: json` / `--experimental_worker_allow_json_protocol` (camelCase field names in JSON vs snake_case in proto).
- **Framing**: "the protobuf wire format is not self-delimiting; consumers need to know exactly how many bytes to read before attempting to parse the bytes as a proto." Bazel writes a varint length prefix before each serialized `WorkRequest`/`WorkResponse` (`MessageLite.writeDelimitedTo()` convention). JSON messages carry no length prefix (JSON is self-delimiting via brace matching).
- **Channel discipline is absolute**: "the worker reads WorkRequests from its stdin. It writes WorkResponses (and only WorkResponses) to its stdout." Any tool output must be captured and routed into `WorkResponse.output`, never written directly to the worker's own stdout — "writing it to the `stdout` of the worker process is unsafe, as it will interfere with the worker protocol." Writing to the worker's own stderr is safe but out-of-band: it lands in a per-worker log file (`<outputBase>/bazel-workers/worker-1-<Mnemonic>.log`), not attributed to any individual request.
- **Correlation**: purely by `request_id` echoed back; no separate session/connection concept — one stdin/stdout pipe pair carries an entire multiplexed conversation.

## 10. Lessons for `Workers`

Mapping Bazel's vocabulary onto ours:

| Bazel | `Workers` |
|---|---|
| `WorkerKey` (mnemonic + startup flags + env) | `Task.Queue` identity (`name`, `classpath`, `jvmOptions`, `env`) |
| warm/persistent worker process | stateful queue instance, `warmState` |
| stdin/stdout protobuf/JSON `WorkRequest`/`WorkResponse` | our IPC Exchange/Envelope |
| multiplex (`WorkerProxy` + `WorkerMultiplexer`, shared `request_id` space) | `maxWorkers` concurrent slots on a stateful queue |
| `cancel` / `was_cancelled` | `Fiber` interrupt propagating into the worker-side request |
| `--worker_max_instances` (per-key, no global cap) | `Task.Queue.maxWorkers` (per-queue) + `Config(parallelism=N)` (global) |
| `execution_requirements` (`supports-workers`, `requires-worker-protocol`, ...) | declarative `Task` fields (`locks`, `weight`, `isolation`, `timeout`, `retry`, `env`) opting a task into queue behavior |

Reliability model specifically (§7 findings mapped to what `Workers` should guarantee instead):

| Bazel gap (§7) | `Workers` should guarantee |
|---|---|
| Worker dies mid-request → action just fails; recovery is a manual, whole-build rerun (§7.3, §7.7) | Task `retry` fires automatically on worker-death-mid-task, bounded (max attempts / backoff), surfaced as a normal `Fiber` failure only after the bound is exhausted — no silent hang, no manual re-invocation required |
| `bazel shutdown` / server death leaves worker processes running (§7.4, bazelbuild/bazel#1868, #4505) | Orphan-free kill: every worker process is provably reaped when its owning `Workers.run` scope or fiber exits, by construction (process-group binding + a parent-liveness mechanism), not by best-effort signal delivery |
| No request-level deadline; a stuck worker can deadlock the caller indefinitely (§7.2, bazelbuild/bazel#10288) | Task `timeout` is enforced at the request boundary, not just at the whole-action boundary — a hung worker fails the specific in-flight `Fiber` on schedule, it does not hang the caller |
| `--worker_max_instances` is a static cap; no memory-based recycling despite exposed metrics (§7.6, bazelbuild/bazel#12165) | Stateful queues recycle a worker (graceful drain + respawn) on a configured threshold (age, request count, memory), closing the loop the metric-without-action gap leaves open in Bazel |

**Steal:**

- **The strict stdout/stderr discipline.** "Only protocol messages on stdout, everything else routed through the envelope or logged separately" is a load-bearing correctness rule, not a style nit — Bazel's own issue tracker shows how badly things fail without it (silent hangs, unparseable-response errors that don't point at the real cause). `Workers` should make this structurally hard to violate (e.g. redirect the worker JVM's actual stdout/stderr to a log sink, and have the IPC layer own a dedicated channel/fd) rather than relying on worker-author discipline the way Bazel does.
- **Length-prefixed framing over a byte stream.** Simple, robust, no self-delimiting-format assumption needed; worth keeping for any raw stdio-pipe transport we support.
- **Request/response correlation by an id round-tripped verbatim.** Minimal and sufficient for both serial and concurrent (multiplex) modes with the same message shape — no separate protocol for singleplex vs multiplex.
- **Keying identity that folds in *both* mnemonic/name and startup configuration**, so two logically-different configurations of "the same" pool never silently share warm state. Directly validates `Task.Queue`'s `(name, classpath, jvmOptions, env)` identity design.
- **Declarative execution-requirements as the opt-in surface**, keeping the default (no worker) safe and requiring explicit per-action opt-in — matches `task.queue(q)` being opt-in per task rather than implicit.

**Avoid:**

- **No global concurrency cap.** Bazel's `--worker_max_instances` is per-`WorkerKey` only; the project's own tracker (bazelbuild/bazel#12165) documents this as a real operational pain (aggregate worker count and memory footprint are unbounded across keys, with no LRU eviction). `Workers.run(Config(parallelism=N))` is explicitly the right design specifically because it caps *globally*, not per-queue; keep it that way and do not regress to a per-queue-only cap.
- **Weak isolation semantics under sandboxing.** Bazel is honest that `--worker_sandboxing` covers only the filesystem view, not a worker's retained in-process state between requests. If `Workers`' `isolation` field is meant to mean anything stronger, define it precisely (state reset guarantee, not just fs sandboxing) rather than inheriting Bazel's fuzzier notion.
- **Lazy, undocumented failure detection.** Bazel discovers a dead/corrupted worker only when the next response fails to parse or arrive, with no heartbeat and no protocol-level resync — recovery is "let the next call fail, then respawn." `Workers` should have explicit worker health/liveness (heartbeat or connection-close detection) so a crashed stateful worker is detected and its in-flight `Fiber`s failed/retried promptly, not left hanging until a caller times out.
- **Cooperative-only, experimental cancellation.** Both `cancel` and `was_cancelled` are marked EXPERIMENTAL in the proto years after multiplexing shipped, and cancellation is purely advisory (the worker decides if/when to actually stop) — dynamic execution's own docs admit "Bazel is currently unable to interrupt actions executed by a worker." `Fiber` interrupt in `Workers` should be a first-class, non-experimental contract from the start, with a defined bound on how the outer `Fiber` behaves if the worker-side task ignores/delays the interrupt (timeout-then-kill), rather than leaving it open-ended the way Bazel does.
- **No orphan-prevention guarantee for the warm process itself.** Bazel's own process-wrapper proves the right primitives (process-group + transitive-child kill) exist in the codebase, but they were never wired to persistent workers — hence years-old, still-relevant reports of worker processes surviving `bazel shutdown` (#1868) and surviving across workspaces entirely (#4505). `Workers` must bind every spawned worker's lifetime to its owning coordinator by construction (process group, and a liveness/parent-death mechanism appropriate to the target OS), not merely attempt a signal on the happy-path shutdown route.
- **No request-level deadline in the protocol.** The worker wire protocol has no timeout/expiry field at all, and Bazel has at least one confirmed, unresolved deadlock report (#10288) with no documented mitigation. `Task.timeout` in `Workers` needs to be enforced at the IPC layer around each individual request, independent of whatever the worker process itself is doing, so a hung worker fails its caller's `Fiber` on schedule rather than blocking it indefinitely.
- **Metrics without a closed control loop.** Bazel exposes per-worker memory (`WorkerMetrics`) but, per the project's own open feature request (#12165), never turned that signal into automatic recycling — the fix is left to a human manually lowering `--worker_max_instances`. `Workers`' `warmState`/stateful queues should close this loop themselves: a configurable recycle threshold (age, requests served, memory) that triggers a graceful drain-and-respawn without operator intervention.

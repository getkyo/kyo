# Contributing to kyo-ai

Module-specific guide for kyo-ai. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-ai: the explicit-instance model, the `LLM` `ArrowEffect` and its threaded `State`, the per-run owner and cross-run guard, the unified enablement surface, the scope/instance env-merge rule, the eval and stream loops, the `Compactor` automatic context-compaction mechanism, the provider wire layer (including the embeddings split), and the test conventions.

**The headline invariant:** a program typed `A < LLM` is a pure value. `LLM` is a custom `ArrowEffect[LLM.internal.Op, Id]`, NOT a subtype of `Async` ([`LLM.scala:26`]). Its ops carry data and read/append per-instance conversation histories in one threaded `State`. The single op that reaches the world is `Gen` (and its sibling `Stream`): its handler interpretation runs the eval loop, and that is the ONLY place `Async & Abort[AIGenException]` enter, riding out on `LLM.run`'s residual ([`LLM.scala:125-132`], [`LLM.scala:386-395`]). A row `A < LLM` must never include `Async`; no change to the eval loop should push `Async` into the `LLM` row. `LLMTest` pins this with `summon[NotGiven[LLM <:< Async]]` in two places, once as a standalone compile probe and once alongside a live `< LLM` ascription ([`LLMTest.scala:477-485`], [`LLMTest.scala:828-836`]).

**A second, equally binding invariant (USER DIRECTIVE -- prompt caching must work well):** when a `Compactor` is enabled, its projected view of the transcript is **byte-stable between updates** ("freeze-once"): two renders between updates produce byte-identical output, and only an update changes bytes, from its edit point forward. Every deep edit (a summary or an omission) is gated by a cache-economics check before it is applied, so a rendering never mutates just because it *could* look better; it changes only when the edit is worth its cache-invalidation cost. `Compactor.render`'s scaladoc states this as the mechanism's purpose ([`Compactor.scala:16-18`]); `LLMTest` pins the default-off path against a committed golden byte string ([`LLMTest.scala:674-695`]) and `CompactorTest` pins the enabled path's byte-stability directly, including a case where background results (embeddings, verdicts) land in the cell BETWEEN two renders and the view is still byte-identical ([`CompactorTest.scala:756-780`]). Treat any change that makes the view churn between updates, or that applies a deep edit the cache gate would have blocked, as a defect, not a nit.

## What kyo-ai is

kyo-ai is a typed effect for first-class conversations with a language model, including automatic context compaction for long-running conversations. The public surface, all top-level in `package kyo`:

- `LLM`, the `ArrowEffect`, and its `run` (the discharge boundary).
- `AI`, the first-class conversation instance (both the identity value `ai` and the namespace of operations).
- `Agent`, an opaque alias over `kyo.Actor` that pairs the LLM surface with the actor model.
- `Prompt`, `Tool`, `Thought`, `Mode`, `Compactor`, the five composable enablement kinds (`AI.Enablement`).
- `AIEnv` and `AISession`, the generation environment and the per-instance state record.
- the `AIException` hierarchy (`AIGenException`, `AIStreamException`, and their leaves).

The settings/content value types `Config`, `Context`, `Image`, `Embedding` live in package `kyo.ai`, but `Config`/`Context`/`Image` are surfaced into `kyo` so `import kyo.*` reaches everything: the `AI` companion `export`s `kyo.ai.Config` / `Context` / `Image` ([`AI.scala:36-38`]), so a user writes `AI.Config`, `AI.Context`, `AI.Image`; `LLM` also `export`s `Config` ([`LLM.scala:31`]). `Embedding` (the `Completion.embed` result type) is reached via `kyo.ai.Embedding`.

All code lives in `shared/src/main/scala` (`kyo/` for the effect surface, `kyo/ai/` for the value types, `kyo/ai/completion/` for the wire backends). Tests default to `shared/src/test/scala` and run cross-platform (JVM, Scala.js, Scala Native, Wasm; four platforms, `build.sbt:1222-1224`). One exception exists: `jvm/src/test/scala/kyo/CompactorPruneTest.scala` (see [Test patterns](#test-patterns)) is the sanctioned platform split for a property `WeakReference.clear()` cannot express on Scala.js/Wasm. The `js/`, `native/`, and `wasm/` trees carry no `.scala` source.

## The explicit-instance model

There is no ambient "current" instance and no `AI.use`. A behavior receives its instance explicitly and calls `self.gen`; the eval loop threads the target `AI` explicitly (`reads ai.context, appends to ai`), so nothing is ambient ([`LLM.scala:471-475`]). Two surfaces exist:

- **One-shot.** `AI.gen[A]` mints a fresh ephemeral instance, generates against it, then discards its slot via `ai.reset` on success, so two one-shots never share state ([`AI.scala:80-84`]). `AITest` pins that a successful one-shot leaves `State.instances` empty (and stays empty under concurrent gens, and a transport abort fails the run so nothing leaks) ([`AITest.scala:274-303`]).
- **Named instance.** `AI.init` mints a persistent slot whose conversation, enablements, and config override survive across turns within one `LLM.run`; `ai.gen[A]` runs against that slot ([`AI.scala:55-56`], [`AI.scala:170-173`]).

`AI` is a reference object, NOT an `opaque type` over a `Long`:

```scala
final class AI private[kyo] (private[kyo] val id: Long, private[kyo] val owner: AnyRef):
    private[kyo] val ref: LLM.internal.AIRef = new LLM.internal.AIRef(this)
```

([`AI.scala:16-17`]). The id is drawn from the run's threaded `State` counter (no process-global mutable state), so identity is scoped to one `LLM.run` and restarts per run; `LLMTest` pins that within a run successive `init` ids are `0, 1, ...` and a fresh run restarts at `0` ([`LLMTest.scala:568-581`]). Every method on `AI` is a thin value over the `LLM` effect surface: `AI` summons no `ArrowEffect` op directly, only `LLM`'s `private[kyo]` interface ([`AI.scala:170-238`], [`LLM.scala:401-424`]).

### Per-run owner and the cross-run guard

Each instance remembers the run that created it (`owner`, a fresh `AnyRef` per run, object identity, no counter) ([`AI.scala:16`], [`LLM.scala:56-60`]). Using an instance inside a different `LLM.run` is misuse: it cannot address that run's slots. `crossRunFailure` inspects every op that targets an instance and, when `ai.owner ne state.owner`, returns `AICrossRunException`; the panic is the handler arm's result, so it rides `runWith`'s residual `Abort` row (not the `LLM` continuation) and aborts the whole computation ([`LLM.scala:65-80`], [`LLM.scala:94-95`]). `AICrossRunException`'s message points the user at `ai.snapshot` / `AI.recover` ([`AIException.scala:88-93`]). `LLMTest` pins that the guard fires for EVERY targeting op (read, set, gen, stream, discard, session, enable), not just one ([`LLMTest.scala:599-618`]).

To carry an instance across runs deliberately, capture it with `ai.snapshot` (returns its `AISession`) and restore it with `AI.recover(session)` in the new run ([`AI.scala:236-237`], [`AI.scala:70-71`]); `AITest` pins a round-trip of history + an enabled tool + a config override across two runs ([`AITest.scala:201-226`]).

## The LLM effect

### Effect definition and the op GADT

`LLM` is `sealed trait LLM extends ArrowEffect[LLM.internal.Op, Id]` ([`LLM.scala:26`]). The op GADT `LLM.internal.Op[A]` indexes each op's reply by `A`, so the handler continuation needs no reply-side cast ([`LLM.scala:583`]). It has exactly **13** subclasses ([`LLM.scala:584-599`]); field-less ops are `case object`s, the rest carry data:

| Op | Carries | Reply (`Op[A]`) |
|----|---------|-----------------|
| `Read(target: AI)` | target | `Context` |
| `Add(target: AI, message: Message)` | target, message | `Unit` |
| `Set(target: AI, context: Context)` | target, context | `Unit` |
| `Init` (`case object`) | nothing | `AI` |
| `Env` (`case object`) | nothing | `AIEnv` |
| `Gen[A](target: AI, schema: Schema[A])` | target, schema | `A` |
| `Stream[A](target: AI, schema: Schema[A], emitTag: Tag[Emit[Chunk[A]]])` | target, schema, emitTag | `Stream[A, Async & Scope & Abort[AIStreamException]]` |
| `SetEnv(env: AIEnv)` | env | `AIEnv` (the previous env) |
| `Discard(target: AI)` | target | `Unit` |
| `GetState` (`case object`) | nothing | `LLM.State` |
| `SetState(state: LLM.State)` | state | `Unit` |
| `GetSession(target: AI)` | target | `AISession` |
| `SetSession(target: AI, session: AISession)` | target, session | `Unit` |

There is **no `SetCurrent`** op and no ambient-current concept; there is also no compaction-specific op. `Compactor` is wired entirely through the existing `Env`/`SetEnv`/`GetSession`/`SetSession` ops (see [The compaction seam](#the-compaction-seam-llmeval--streamagainst)); `LLMTest` pins the count directly by constructing all 13 subclasses and asserting no 14th exists ([`LLMTest.scala:697-716`]). `Gen` is the one op that reaches the world; its arm runs `genLoop` under a nested `runWith` against the live state, and `Async & Abort[AIGenException]` enter there ([`LLM.scala:125-132`]). `Stream`'s arm projects the SSE response and is read-only for the target's own context (no instance write-back beyond what `genLoop`/`streamAgainst` themselves perform), so the threaded state passes through unchanged in the handler loop itself ([`LLM.scala:133-141`]).

### Threaded state: `LLM.State`

`State` is the single record threaded through `ArrowEffect.handleLoop` ([`LLM.scala:40-44`], [`LLM.scala:83-86`]):

```scala
final case class State private[kyo] (
    instances: Dict[internal.AIRef, AISession],
    nextId: Long,
    owner: AnyRef,
    env: AIEnv
)
```

- `instances` is keyed by `internal.AIRef`, a `WeakReference[AI]` whose equality/hash are by the AI's stable `id`, so a dropped `AI` becomes GC-reclaimable while its key still matches its slot ([`LLM.scala:605-612`]). `State.pruned` sweeps slots whose `AI` was collected, run when minting a new instance so an unbounded mint stream never accumulates dead slots ([`LLM.scala:53`], [`LLM.scala:102-107`]).
- `nextId` is the monotonic id counter `Init` draws from; `SetState` never lowers it (`math.max`), so a `forget`/`fresh` rollback keeps the high-water id and a slot key is never reused ([`LLM.scala:116-120`]).
- `owner` stamps every instance for the cross-run guard ([`LLM.scala:56-60`]).
- `env` is the scope `AIEnv` (see below), read by `Op.Env` and replaced by `Op.SetEnv` ([`LLM.scala:108-111`]).

`State.empty(config)` seeds a `Present(config)` scope env and a fresh `owner` ([`LLM.scala:56-60`]).

### `run` and its residual

`LLM.run[A, S](v: A < (LLM & S)): A < (S & Async & Abort[AIGenException])` threads a fresh `State.empty(config)` through `runWith` and discards the final state ([`LLM.scala:386-395`]). Three overloads (`run(v)`, `run(f: Config => Config)(v)`, `run(config)(v)`) all funnel through `runWith`; the first two resolve `Config.default` under `Sync` first ([`LLM.scala:386-395`]). `runTuple` keeps the final state with the value for tests and transcript access (`private[kyo]`) ([`LLM.scala:397-399`]). `runWith` is NOT inline ([`LLM.scala:82-86`]).

### The eval loop

`genLoop(ai, schema)` is the `Gen` interpretation ([`LLM.scala:471-510`]). It:

1. Merges the instance's own env onto the scope env for the duration of the eval, then restores it (so the effective surface is `scope ++ instance`): config is `session.env.config.orElse(scopeEnv.config)`, the compactor is `session.env.compactor.orElse(scopeEnv.compactor)` (instance-over-scope, single active policy, last-wins, never a pipeline), and the instance's prompt/tools/thoughts/modes are layered on ([`LLM.scala:476-485`]). This is the merge that makes a `Present` instance config (or compactor) override the scope's; an `Absent` instance value inherits the scope's. `LLMTest` pins the compactor half of this merge directly (env-only, no generation) ([`LLMTest.scala:741-788`]) and end to end, against a live outbound request, that the instance compactor's rendering (not the scope's) reaches the wire ([`LLMTest.scala:790-826`]).
2. Enables `Prompt.internal.defaultGuidance` (generic structured-output guidance, not part of the empty prompt) ([`LLM.scala:486`], [`Prompt.scala:90-102`]).
3. Loops: each iteration calls `eval[A](ai, forceResult = iterations >= config.maxIterations)`, wraps transport `HttpException` into `AITransportException`, passes the result through `Mode.internal.handle` (the mode pipeline), and on `Present(r)` yields, on `Absent` re-loops with the seed modulated (`c.seed.map(_ * 31)`) until `iterations >= config.maxIterations * 2`, where it aborts with `AIEvalExhaustedException` ([`LLM.scala:488-503`]).

`eval` posts one completion request: it assembles the tools (plus the recall tool, when a compactor is effective, plus the result tool), the thought-aware result schema, the enriched context (consulting the compactor at the seam described below), runs the provider completion under the config meter and a `Retry[HttpException](config.retrySchedule)`, feeds the completed request's usage back into the compactor's token-estimator calibration, appends the reply, dispatches any tool calls, and extracts the structured result directly from the `result_tool` call arguments ([`LLM.scala:512-576`]). A closed meter under an in-flight gen panics with `AIMeterClosedException` (an impossible-state, off both rows) ([`LLM.scala:538-551`], [`AIException.scala:98-99`]).

### The stream loop

`AI.stream[A]` / `ai.stream[A]` suspend `Op.Stream`, whose handler runs `streamAgainst` ([`LLM.scala:417-421`], [`LLM.scala:133-141`]). `streamAgainst` asks `config.provider.completion.streamFragments` for raw JSON fragments of the `{ resultValue: ... }` envelope and accumulates the fragments. For `String`, it emits decoded text chunks whose concatenation is the final text. For other result types, it emits each complete decoded element from the result array exactly once ([`LLM.scala:188-242`]). HTTP providers implement fragments with SSE result-tool deltas; command harnesses use their native event or stream-json output. The returned `Stream` carries its failures typed in its element row as `Abort[AIStreamException]`: a malformed delta is `AIStreamDeltaException`, an end without a decodable value `AIStreamIncompleteException` ([`LLM.scala:305-383`]), a transport error `AITransportException` (mapped at the SSE fetch site, [`kyo/ai/completion/Completion.scala:102-103`]). A missing API key is the one failure raised eagerly (before the `Stream` value), as `AIMissingApiKeyException` on the run boundary ([`LLM.scala:193-195`]).

### The `LLM.isolate` given

`given isolate: Isolate[LLM, Async, LLM]` lets `Async.fill`/`foreach`/`race` fork over a bare `LLM` row ([`LLM.scala:443-463`]). `Keep = Async` is exact: the in-tree parallel sites require `Isolate[LLM, Abort[E] & Async, LLM]`, and for the `E = Nothing` body (whose transport errors the eval loop already recovers) that reduces to `Isolate[LLM, Async, LLM]`, which a wider `Keep` (`Abort[Any] & Async`) cannot satisfy by `Keep` contravariance ([`LLM.scala:435-442`]). `capture` reads the live `State` via `Op.GetState`; `isolate` discharges `runWith`'s residual `Abort[AIGenException]` inside the fork with `getOrThrow`, so an unrecovered fork generation failure surfaces as a fiber panic; `restore` merges fork-born instance contexts back via `mergeInstance` (prefix-aware `Context.merge`, parent env kept), skipping GC'd slots ([`LLM.scala:443-463`], [`LLM.scala:465-469`]). `Compactor`'s own background dispatch reuses this SAME isolate to fork detached judge/embed/summary work (see [Background work never blocks the turn](#background-work-never-blocks-the-turn)). `LLMTest` pins both the fork resolution ([`LLMTest.scala:511-523`]) and the unrecovered-fork panic ([`LLMTest.scala:525-538`]).

## `AIEnv` and `AISession`: the env-merge rule

`AIEnv` is the generation environment: a config, an optional compactor, plus the enablements layered for a scope or instance ([`AIEnv.scala:12-19`]):

```scala
case class AIEnv(
    config: Maybe[Config],
    prompt: Prompt[Any],
    tools: Chunk[Tool[Any]],
    thoughts: Chunk[Thought[Any]],
    mode: Chunk[Mode[Any]],
    compactor: Maybe[Compactor] = Absent
)
```

`config` is `Maybe[Config]`: the SCOPE env always holds `Present` (set at `LLM.run`), while an INSTANCE env holds `Absent` to inherit the scope config or `Present` to override it ([`AIEnv.scala:9-11`]). `compactor` follows the identical shape: `Absent` inherits, `Present` overrides, single active policy, last-wins, never a pipeline ([`AIEnv.scala:23-26`]). `AIEnv.empty` and `AISession.empty` both carry an `Absent` config and an `Absent` compactor ([`AIEnv.scala:52`], [`AISession.scala:31`]); `AIEnvTest`/`AISessionTest` pin that `config(cfg)` sets `Present`, `mapConfig` is a no-op while `Absent`, and the empty session has no override ([`AIEnvTest.scala:9-15`], [`AISessionTest.scala:8-20`]).

`AISession(context: Context, env: AIEnv)` is one instance's full state: its conversation plus its env override and enablements ([`AISession.scala:12`]). It is both the value `State.instances` holds per instance and the snapshot `ai.snapshot` returns / `AI.recover` restores. It holds code (tool runners, effectful prompts, modes, the compactor reference), so it is in-memory only and not serializable; only the `session.context` slice is serializable (`Context derives Schema`) ([`AISession.scala:6-10`]).

**The override-merge rule** (`genLoop`, [`LLM.scala:476-485`]; pinned in `LLMTest`):

- An instance `Present` config override beats the scope config in the request ([`LLMTest.scala:393-409`]).
- A scope `AI.withConfig` wrapped around a gen is SHADOWED by the instance config override (the override wins) ([`LLMTest.scala:430-446`]).
- A mode's `AI.withConfig` (applied after the merge, inside the mode pipeline) DOES reach the request even on a config-overridden instance, layering on top of the override ([`LLMTest.scala:411-428`]).
- The same instance-over-scope precedence applies to `compactor`: a scope compactor and an instance compactor can both be enabled; the instance's wins ([`LLMTest.scala:741-826`]).

## The enablement surface

The five composable kinds, `Tool`, `Prompt`, `Thought`, `Mode`, `Compactor`, all extend `AI.Enablement[-S]`, whose two `private[kyo]` methods say how the kind layers itself onto a scope env or an instance session ([`AI.scala:40-53`], [`Tool.scala:23-26`], [`Prompt.scala:40-43`], [`Thought.scala:26-29`], [`Mode.scala:24-27`], [`Compactor.scala:92-99`]). `private[kyo]` so only the module's five kinds implement it; users compose, never extend. There are **no** per-type `enable` binders (no `Tool.enable`, `Thought.enable`, `Prompt.enable`, `Mode.enable`, or `Compactor.enable`). Enabling is unified:

- **Scope.** `AI.enable[A, S](enablements: Enablement[S]*)(v): A < (S & LLM)` folds each enablement's `enableIn(AIEnv)` over the scope env via `LLM.updateEnv` (on top of the scope's current enablements); empty varargs is a no-op ([`AI.scala:120-126`]). The capability `S` rides the row, unified across the varargs to their intersection, until discharged at the run boundary.
- **Instance.** `ai.enable[S](enablements: Enablement[S]*): AI < (S & LLM)` folds each `enableIn(AISession)` onto the named instance ([`AI.scala:228-229`]).

Both take varargs or a `Seq` (a `DummyImplicit` differentiates the erased `Seq[T]` signatures) and accept a mix of kinds in one call ([`AI.scala:131-132`], [`AI.scala:232-233`]). Enabling a compactor works identically: `Compactor.init.map(ai.enable(_))` or `AI.enable(compactor)(v)` ([`Compactor.scala:12-13`]).

Config is scoped by `AI.withConfig` (NOT `LLM.withConfig`), built on `LLM.updateEnv(_.mapConfig(f))` ([`AI.scala:113-118`], [`LLM.scala:426-431`]). `updateEnv` brackets a transform of the scope `AIEnv` over `v`: get, modify, set, run, restore, written once and reused by the `enable` methods and `withConfig`.

### Forget and fresh

`AI.forget` snapshots `State`, runs `v`, then restores ALL instances' conversations (a scope-wide rollback) ([`AI.scala:137-138`]); the `forget(ais*)` form rolls back ONLY the named instances, other writes persist ([`AI.scala:143-146`]). `AI.fresh` runs `v` with conversations blanked (enablements, config, and compactor kept), then restores ([`AI.scala:151-155`]). `AITest` pins `reset` removes the slot ([`AITest.scala:102-113`]) and `forget(ais*)` rolls back only the named instance ([`AITest.scala:184-199`]). `Compactor`'s own background judge/summarizer/embed dispatch runs its request under `AI.fresh` so its prompt never touches the caller's transcript (see below).

### `Tool`

`Tool.init[In][Out, S]` builds a tool from a name, optional description, prompt, and run function, plus two compaction-supersession metadata parameters: `kind: Tool.Kind = Tool.Kind.Read` and `compactionKey: In => Maybe[String] = _ => Absent` ([`Tool.scala:46-56`]). `Tool.Kind` is a closed two-case enum, `Read` and `Write` ([`Tool.scala:36-37`]); a tool supplying a `compactionKey` extractor opts a call's unit into key-based supersession (a later same-key unit supersedes an earlier one, and `kind` decides whether a re-read supersedes a prior read or a write supersedes any prior same-key unit); the keyless default never supersedes and is never superseded ([`Tool.scala:41-44`]). Both metadata params are optional, so every pre-compaction call site keeps compiling unchanged; `ToolTest` pins the default (Read, keyless) ([`ToolTest.scala:177-182`]), a custom write key surfacing on `Info` ([`ToolTest.scala:184-193`]), the closed two-case enum ([`ToolTest.scala:195-199`]), and that a legacy no-metadata call site still compiles and behaves unchanged ([`ToolTest.scala:201-221`]). The internal `resultToolInfo` is the dynamic `result_tool` the eval loop adds to every request: its run is a no-op, and the eval loop extracts the call's arguments directly (no capturing run, no ref); it takes its `Kind`/`compactionKey` defaults, so the result tool never supersedes ([`Tool.scala:101-117`], [`LLM.scala:567`]). Tool-call dispatch contains ANY throw from user code as a tool message and never lets it escape the eval loop ([`Tool.scala:119-176`]).

### `Thought`

`Thought[A]` injects a typed reasoning field into the result schema: an `opening` field precedes `resultValue`, a `closing` follows it, so field ORDER frames the answer and drives autoregressive generation ([`Thought.scala:9-17`], [`Thought.scala:96-112`]). The thought name is the type's compile-time unqualified name via `Schema.structure.name` ([`Thought.scala:63`]). No reasoning is woven in by default; `Thought.reflective` (a `Reflect` opening + a `Check` closing) is the built-in scaffold, enabled explicitly ([`Thought.scala:53-57`], [`Thought.scala:86-87`]). Each thought's `process` hook fires on the decoded typed value after generation; an unrecognized field name is `AIInvalidThoughtException`, an undecodable field or result `AIDecodeException` ([`Thought.scala:117-150`]).

### `Prompt`

`Prompt[-S]` splits guidance into primary instructions (added at the context start, as SEPARATE system messages so providers can cache individual blocks) and reminders (floated at the context end, immediately before generation) ([`Prompt.scala:7-14`], [`Prompt.scala:104-142`]). `andThen` merges and `.distinct`-deduplicates both lists ([`Prompt.scala:22-35`]). `Prompt.init[S]` is `inline` and requires the prompt and reminder bodies to be `< (LLM & S)` ([`Prompt.scala:63-67`]). The `p` string interpolator normalizes per-line leading whitespace (`\n\s+` -> `\n`) and trims; use it for multi-line prompt literals ([`Prompt.scala:47-53`]).

### `Mode`

`Mode[-S]` is generation-interception middleware; enabled modes form a pipeline applied in registration order ([`Mode.scala:5-28`], [`Mode.scala:49-61`]). Its method is:

```scala
def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using Frame): Maybe[A] < (LLM & Async & Abort[AIGenException] & S)
```

([`Mode.scala:20-22`]). The `gen` parameter carries its failures typed as `Abort[AIGenException]` and the mode receives the target `ai` (so it can read/write that instance's conversation around the gen). `Mode.init[S]` builds a mode from a polymorphic transform, the convenient alternative to `new Mode[S]` ([`Mode.scala:40-47`]).

## The `Compactor` mechanism

`Compactor` is automatic context compaction, wired in as a fifth `AI.Enablement`: enable it (`Compactor.init.map(ai.enable(_))` or scope-wide via `AI.enable`), and `LLM.eval` / `streamAgainst` consult it at one seam between the context read and request assembly, returning a bounded, projected VIEW of the transcript the model actually sees ([`Compactor.scala:9-24`]). With no compactor enabled, a generation is byte-identical to today: default-off ([`Compactor.scala:14`], [`LLMTest.scala:674-716`]).

### Immutable transcript, projected view

`AISession.context` (the transcript) is NEVER mutated by compaction; it stays the complete, append-only record. `Compactor.render` reads it and returns a separate `Context` value (the view); nothing it does ever writes back to `ai.context` ([`Compactor.scala:9-16`], [`Compactor.scala:42-68`]). `CompactorTest` pins byte-identical transcript-before/-after across an update ([`CompactorTest.scala:84-102`, grep the "transcript immutable across update" leaf]) and that the eval seam never shrinks the transcript slot even when compaction is active ([`LLMTest.scala:717-738`]). Recovering original content (see [Recall re-enters at tail](#recall-re-enters-at-tail-never-un-freezes-the-prefix)) always slices the live, complete transcript, never a stored copy.

### Derive-don't-store: the persisted state shape

`Compactor.internal.CompactorState` persists exactly five fields; everything else the mechanism needs (grouping, edges, supersession, scores, occupancy) is RECOMPUTED fresh every render, never cached across renders:

```scala
final case class CompactorState(
    renderings: Dict[Int, Rendered],
    vectors: Dict[Int, Embedding],
    verdicts: Dict[Int, Verdict],
    prepared: Dict[Int, Rendered],
    book: Book
)
```

([`Compactor.scala:1270-1276`]). `renderings`/`vectors`/`verdicts`/`prepared` are the four derived-RESULT caches (a decision, a landed embedding, a landed judge verdict, a prepared-but-not-yet-adopted summary); `book` is bookkeeping (`seen`, the calibrated `tokensPerByte`, and the three in-flight dedup sets) ([`Compactor.scala:1253-1265`]). `group` (unit fusion), `deriveGraph` (Adj/Ref/Sem edges), `supersession`, and `score` (Personalized PageRank) all take the transcript and the persisted caches as INPUT and return a fresh value every call; none of their outputs is stored ([`Compactor.scala:108-135`], [`Compactor.scala:139-211`], [`Compactor.scala:219-237`], [`Compactor.scala:279-307`]). `CompactorTest` pins this twice: that the recomputed derived state is genuinely absent from the persisted `CompactorState` after a restart-rebuild ([`CompactorTest.scala:680-690`, the "no derived state persisted" leaf]), and that `CompactorState` itself carries exactly the prescribed fields by asserting `productArity` on `Segment`/`Rendered`/`CompactorState`/`Book` ([`CompactorTest.scala:747-754`]). The state shape is rebuildable from the transcript alone: a fresh `CompactorState.empty` plus the live transcript reproduces the same view, so the transcript is the only durable artifact a contributor must worry about losing ([`CompactorTest.scala:691-703`, the "rebuild state from transcript" leaf]). A non-append guard resets to `CompactorState.empty` whenever the transcript shrinks below the last-seen length (`setContext`/`forget`/`fresh` rewrote it), which is safe precisely because the state is always rebuildable ([`Compactor.scala:867-885`], [`CompactorTest.scala:704-745`]).

### Freeze-once byte-stability and the cache gate (headline, USER DIRECTIVE)

Every render first computes synchronous, model-free steps (`group`, queue new-unit embeddings) WITHOUT touching `renderings`; any background results already landed since the previous render (embeddings, judge verdicts) surface automatically through `stateFor`'s cell read into the SAME `state` value `queueEmbeddings` receives, so the view stays byte-stable at this step regardless of how much background work has landed ([`Compactor.scala:42-68`]). Occupancy against the transcript's projected view then picks one of three paths ([`Compactor.scala:55-64`]):

- **Fast path** (below `updateTriggerFraction * effectiveLength`): emit the current renderings unchanged, cache warm. Two consecutive fast-path renders are byte-identical, even when a background result lands in the cell between them ([`CompactorTest.scala:756-780`]).
- **Update** (between the trigger and `hardWindowFraction * window`): may demote units one ladder step, gated by the cache-economics check below.
- **Forced** (at or above `hardWindowFraction * window`): a no-model, deterministic Omit pass with NO cache gating (the sole exemption from the two-touch/cache-gate rule); if even the unshrinkable roots cannot fit the hard window, `Compactor.render` aborts loudly with `AIContextOverflowException` rather than send an over-limit request ([`Compactor.scala:534-572`], [`AIException.scala:60-69`], [`CompactorTest.scala:1060-1077`]).

A **deep edit** (ladder level 3, a prepared summary, or level 4, an omission) is gated by `cacheGatePasses`, which compares the tokens the edit would save against the tokens of the rendered tail AFTER the edit point (`L_cut`, the portion whose cache the edit actually invalidates), scaled by a fixed cached-read discount and write premium over a configurable horizon ([`Compactor.scala:1053-1056`], [`Compactor.scala:449-472`]). A blocked deep edit is simply not applied that pass; nothing is recorded, because the next pass's fresh derivation re-evaluates it under the then-current occupancy ([`Compactor.scala:450-454`]). `CompactorTest` pins that a shallow edit (large saving, small tail) passes and a deep edit into a large frozen prefix defers ([`CompactorTest.scala:959-968`]), and that `L_cut` is bound to the true POST-edit suffix, not the whole view (so a big frozen prefix does not spuriously block an edit whose actual cache blast radius is small) ([`CompactorTest.scala:970-991`]). A **rot rule** is the sole escape hatch for a deferred deep edit: it fires once re-fetch calls for that unit (via `recall`, counted from the transcript AFTER the unit's last edit) reach `refetchThreshold`, OR once occupancy reaches the effective budget; answer quality is NEVER a trigger ([`Compactor.scala:1058-1061`], [`CompactorTest.scala:992-1045`]). Every non-deep edit (levels 1-2: compress, elide, mask, or dedup-to-reference) applies unconditionally within a pass, subject only to the **two-touch rule**: a unit reduced THIS pass is never deepened further in the SAME pass; deepening (adopting a re-validated prepared summary, or omitting) requires the unit to have stood since a PRIOR update ([`Compactor.scala:489-519`], [`CompactorTest.scala:857-877`], [`CompactorTest.scala:803-828`]).

A change that makes two fast-path renders differ, that applies a deep edit the gate would have blocked, or that deepens a unit reduced in the same pass, is a regression against this invariant, not a style nit.

### The compaction seam (`LLM.eval` / `streamAgainst`)

The seam is the SAME shape on both the gen and stream paths, resolved with the identical instance-over-scope precedence `genLoop` already applies to config: `Absent` leaves the view byte-unchanged (a literal pass-through, `Kyo.lift(ctx)`), `Present` runs `compactor.render(ai, ctx)`. No new `Op` was minted for this; the seam sits entirely inside the existing `eval`/`streamAgainst` bodies, between the context read and `enrichedContext`:

- **`eval`** (the gen path): `env.compactor.map(_.render(ai, ctx)).getOrElse(Kyo.lift(ctx))`, plus the recall tool (bound to the calling instance) registered when a compactor is effective, plus post-request usage fed back to `compactor.calibrate` ([`LLM.scala:520-524`], [`LLM.scala:529-532`], [`LLM.scala:554-557`]).
- **`streamAgainst`** (the stream path): reads the target's session and scope env directly (rather than through `genLoop`'s merged env, since streaming does not run `genLoop`) to resolve the same `session.env.compactor.orElse(scopeEnv.compactor)` precedence, then renders ([`LLM.scala:210-220`]).

`LLMTest` pins the seam is a true no-op on the Absent path against a committed golden request byte string ([`LLMTest.scala:674-695`]), that it mints no new `Op` and never shrinks the transcript slot ([`LLMTest.scala:697-716`]), and end to end that an instance compactor's rendering reaches the outbound wire request ([`LLMTest.scala:790-826`]).

### `genLoop`'s env-merge and post-request calibration

`genLoop` merges the compactor the identical way it merges config, instance-over-scope: `scopeEnv.copy(compactor = session.env.compactor.orElse(scopeEnv.compactor))` ([`LLM.scala:476-485`]). `Compactor.calibrate` runs after every completed request: it reads the provider-reported input-token usage (`Completion.Usage`, [`kyo/ai/completion/Completion.scala:58-63`]) against the byte size of the actual enriched request, and EWMA-blends the observed bytes-per-token ratio into the persisted `book.tokensPerByte` (smoothing factor 0.3, so a single outlier request nudges, never swings, future occupancy estimates) ([`Compactor.scala:70-90`], [`Compactor.scala:1086-1088`]). A no-op when usage is `Absent` (the estimator keeps its prior calibration). `CompactorTest` pins both the blend arithmetic and the no-op case ([`CompactorTest.scala:1360-1388`]).

### Background work never blocks the turn

Embedding new units, judging the ambiguous band, and preparing a summary are ALL dispatched on detached fibers through `LLM.isolate` and `Fiber.initUnscoped`, never awaited by the render call that dispatched them; their results land in the compactor's own atomic cell, never in `LLM.State` ([`Compactor.scala:759-768`]). Every backend failure inside a background job (a missing key, a transport error, an undecodable reply) is contained and discarded there; a background failure just leaves structural-only edges or an un-judged band, never blocks or fails the turn ([`Compactor.scala:760-762`]). Each of the three job kinds is deduplicated by its own in-flight set (`inflight` for the judge band, `embedInflight` for embeddings, `summaryInflight` for run summaries) so a slow endpoint is never re-issued the same paid batch every render, and each is cleared on EVERY outcome (success or failure), not only success ([`Compactor.scala:576-616`], [`Compactor.scala:693-757`]). The judge and summarizer both dispatch under `AI.fresh` with `AI.withConfig(judgeCfg)`, so their prompts never touch the caller's transcript ([`Compactor.scala:707-741`]). `CompactorTest` pins that a never-completing embedder still returns a correct, non-blocking view ([`CompactorTest.scala:1079-1094`]), that the judge sees no root/system content ([`CompactorTest.scala:1096-1132`]), that landed background results reach the cell only, leaving `LLM.State`/the instance context untouched ([`CompactorTest.scala:1162-1178`]), and that a landed verdict is a CACHE consulted by the fresh score, never authority over it ([`CompactorTest.scala:1180-1188`]).

The default judge/summarizer inherits the ACTIVE chat config's transport (credentials, `apiUrl`) and swaps in only the provider's cheap-tier model via `chatCfg.modelFrom(chatCfg.provider.small)`, so it dispatches a real authenticated request rather than a credential-less catalog literal that would fail before egress; `Compactor.Config.judge: Maybe[kyo.ai.Config]` overrides this wholesale ([`Compactor.scala:707-712`], [`kyo/ai/Config.scala:54-60`]). `CompactorTest` pins both the default-path authenticated-request behavior ([`CompactorTest.scala:1134-1160`]) and that a `Config.judge` override is honored ([`CompactorTest.scala:1344-1358`]).

### Recall re-enters at tail, never un-freezes the prefix

`Compactor` registers ONE tool, `recall`, per calling instance (bound at the `eval` seam, so it resolves against only that instance's own cell entry and transcript, never another session's) ([`Compactor.scala:1303-1310`], [`LLM.scala:520-524`]). Its input is `Recall(id: Int)`, an object-wrapped id so the wire tool schema is `{"id":{"type":"integer"}}` (providers reject a bare non-object parameter schema) ([`Compactor.scala:1297-1301`]). Every mask/elide/omit marker the ladder emits mechanically names the unit's id and instructs `recall(<id>)` ([`Compactor.scala:1044-1051`]); `recall`'s run slices the LIVE transcript for that unit and returns the verbatim content as a FRESH `ToolMessage` appended at the tail. It never edits the frozen prefix and never duplicates a historical call id ([`Compactor.scala:1306-1310`]). `recall` is `Tool.Kind.Read` with NO `compactionKey`: a re-fetch is a rot-rule signal (see the cache gate above), never a supersession trigger ([`Compactor.scala:1303-1305`]). `CompactorTest` pins the marker text points at `recall(<id>)` rather than un-freezing the prefix ([`CompactorTest.scala:782-791`]), an end-to-end recall landing at the tail with the original content restored ([`CompactorTest.scala:1210-1227`]), that recall resolves against the CALLING instance only when one scope compactor serves two sessions with colliding unit ids ([`CompactorTest.scala:1233-1261`]), and a typed no-such-region message for an unknown or non-demoted id ([`CompactorTest.scala:1262-1273`]).

### The rendering ladder and the graph

Five ladder levels (0 Verbatim .. 4 Omitted) walk a unit from its original messages toward smaller renderings: L1 Compressed (whitespace/repeat collapse, never applied to diffs/patches), L2 Elide (head/tail with a middle marker, for oversized content) or Mask (a mechanically-assembled marker, no model text), L3 a re-validated prepared Summary (background-authored, extractive, adopted only after standing since a prior update), L4 Omitted (a mask marker only) ([`Compactor.scala:1006-1051`], [`Compactor.scala:489-519`]). Liveness is scored by one power iteration of Personalized PageRank over a row-stochastic transition matrix built from three edge kinds: Adjacency (each unit to its predecessor), Reference (a later mention of an identifier-like token, weighted by structure and hub-discounted by mention count, pointed at the CURRENT post-supersession target), and Semantic (symmetric mutual-kNN over landed embeddings above a cosine floor, decayed by index gap) ([`Compactor.scala:139-211`], [`Compactor.scala:273-307`]). Key-based supersession (from a tool's `compactionKey`/`Kind`) is applied as a multiplicative score PENALTY plus an index repoint, OUTSIDE the walk: it never deletes or rewires an edge, so `W` stays row-stochastic ([`Compactor.scala:213-237`], [`CompactorTest.scala:181-207`]). `Tool.Kind` is load-bearing here: a re-read supersedes a prior read, a write supersedes any prior same-key unit, but a READ AFTER A WRITE does NOT supersede (the write stays the authoritative state-establishing record) ([`Compactor.scala:228-231`], [`CompactorTest.scala:162-179`]). Roots (leading system, first/latest user, unresolved tool-call pairs, the recent tail) are pinned verbatim and never reach the judge or the demote candidates ([`Compactor.scala:970-980`]).

## `Agent`

`Agent[+Error, In, Out]` is `opaque type Agent[+Error, In, Out] = Actor[Error, Agent.internal.Message[In, Out], Any]` ([`Agent.scala:28`]). `ask` sends a typed input and awaits the reply under `Async & Abort[Closed | Error]`: a closed mailbox surfaces as `Abort[Closed]`, the behavior's typed error as `Abort[Error]`, never a throw ([`Agent.scala:41-43`]).

`run` mints ONE stable `AI` instance for the agent and hands it to the behavior as its `self`; the behavior calls `self.gen` explicitly, and because the actor's parked continuation keeps the `LLM.State` alive, that instance's conversation persists across asks ([`Agent.scala:15-18`]). All four creation overloads (`run` / `runBehavior`, with and without a leading config + enablements param list) funnel through the private `runImpl` ([`Agent.scala:217-240`]):

```scala
val llmRun =
    config match
        case Present(c) => LLM.run(c)(AI.initWith(behavior))
        case Absent     => LLM.run(AI.initWith(behavior))
Actor.run(Abort.run[AIGenException](llmRun).map(_.getOrThrow))
```

([`Agent.scala:235-239`]). So the wiring is: `LLM` is discharged by `LLM.run`, `Abort[AIGenException]` is re-thrown as a panic (`AIGenException <: Throwable`), and `Async` is consumed by `Actor.run`. The enablements are layered around the behavior in argument order via `AI.enable`, including a `Compactor` ([`Agent.scala:136-138`]). `AgentTest` pins cross-ask conversation persistence (the second ask's gen sees the first ask's turn) ([`AgentTest.scala:263-292`]) and that a failing behavior gen does not strand the asker ([`AgentTest.scala:185-202`]).

## `Config`

`Config` is an immutable copy-on-write settings record; every builder returns a modified copy ([`kyo/ai/Config.scala:17-32`]). Its constructor is `private`, so a config is built via `Config.init`, `Config.default`, or a provider catalog literal, never `new`.

- **Temperature is opt-in.** `temperature` is `Maybe[Double] = Absent`; it is OMITTED from the request when unset (the model uses its own default) and clamped to `[0, 2]` when set (`temperature.max(0).min(2)`) ([`kyo/ai/Config.scala:24`], [`kyo/ai/Config.scala:36`]). There is no `forcedTemperature` / `effectiveTemperature` and no model-name heuristic.
- **Optional builders.** `maxTokens(Int)` and `seed(Int)` are also `Maybe`; an internal `seed(Maybe[Int])` exists for cross-run seed derivation ([`kyo/ai/Config.scala:25-26`], [`kyo/ai/Config.scala:37-38`], [`kyo/ai/Config.scala:52`]).
- **`embedder`.** `embedder(config: Config): Config` pairs a DIFFERENT provider config for embeddings. `Completion.embed` itself does not read this field; the RESOLUTION lives in `Compactor.queueEmbeddings`, which resolves `chatCfg.embedder.getOrElse(chatCfg)` once and dispatches through THAT provider's own backend, never the chat provider's backend with the embedder config as an argument ([`kyo/ai/Config.scala:44-49`], [`Compactor.scala:587-590`]). `Absent` (the default) embeds with the chat config itself.
- **`modelFrom`.** `private[kyo]` -- adopts another config's model identity (name + token cap) while keeping THIS config's transport (provider, credentials, `apiUrl`); the compactor uses it to resolve its judge/summarizer onto the active chat config's live credentials rather than a credential-less catalog literal ([`kyo/ai/Config.scala:54-60`]).
- **Default selection.** `Config.default` probes provider markers and API keys (system properties first, then env vars) via `kyo.System`, never raw `sys.props` / `sys.env`, and falls back to Anthropic ([`kyo/ai/Config.scala:82-99`]).

There are **nine** providers in `Provider.all`: `Anthropic`, `OpenAI`, `DeepSeek`, `Gemini`, `Groq`, `Baseten`, `OpenRouter`, `ClaudeCode`, `Codex` ([`kyo/ai/Config.scala:135-136`]). `Config.default` checks the provider marker/key names in default-candidate order, preferring `CLAUDE_CODE`, then `CODEX`, then HTTP/API provider keys ([`kyo/ai/Config.scala:139-140`]). Each provider exposes its model catalog as named pure `Config` constants (key absent, filled at use), and `default` points at the recommended entry ([`kyo/ai/Config.scala:160-300`]).

### `Provider.small`: the cheap-tier selector

`Provider` also exposes `def small: Config = default`, a CONCRETE method with a `= default` fallback, mirroring the existing `def default: Config` accessor ([`kyo/ai/Config.scala:127-131`]). Concrete (not abstract) is deliberate: `Config.Provider` is a public, unsealed `abstract class` with a public constructor that external code CAN subclass (a self-hosted OpenAI-compatible endpoint passing `Completion.openAI`), and a NEW abstract member would break every such subclass at source level; an unaware subclass instead degrades safely by running its judge on `default` (costlier, never wrong). Each of the nine built-in catalog objects overrides it with its real cheap entry (`Anthropic.small = haiku_4_5`, `OpenAI.small = gpt_5_nano`, `Gemini.small = gemini_2_5_flash_lite`, and so on through all nine) ([`kyo/ai/Config.scala:172`], [`kyo/ai/Config.scala:194`], [`kyo/ai/Config.scala:219`], [`kyo/ai/Config.scala:233`], [`kyo/ai/Config.scala:245`], [`kyo/ai/Config.scala:269`], [`kyo/ai/Config.scala:283`], [`kyo/ai/Config.scala:299`], [`kyo/ai/Config.scala:206`]). `Compactor.dispatchBackground` is the sole in-tree caller, via `chatCfg.modelFrom(chatCfg.provider.small)` ([`Compactor.scala:712`]). When adding a tenth catalog provider or a new catalog entry, add the matching `override def small` alongside `default`; a subclass that forgets it merely runs its judge more expensively, never incorrectly.

## `Context` and `Message`

`Context` IS the conversation: an ordered `Chunk[Message]`, immutable, `derives Schema` (so it is the serializable slice of an `AISession`) ([`kyo/ai/Context.scala:22`]). `systemMessage`, `userMessage`, and `assistantMessage` skip degenerate inputs (blank content; a user message with neither content nor image; an assistant message with neither content nor calls); `toolMessage` always appends unconditionally, since a tool result is never degenerate the way a model-authored message can be ([`kyo/ai/Context.scala:28-45`]). `merge` is prefix-aware: it finds the common prefix shared with the argument and appends only the non-common suffix, so cross-fork merges never duplicate shared history ([`kyo/ai/Context.scala:50-53`]). `Role` carries the exact lowercase wire strings providers require (`system`/`user`/`assistant`/`tool`), surfaced via `role.name` ([`kyo/ai/Context.scala:63-68`]). `Context`, `Role`, `CallId`, and the `Message` trait all `derives CanEqual` so the equality-based merge compiles under strict equality.

## Wire layer: `Completion`

`Completion` is the provider-backend contract ([`kyo/ai/completion/Completion.scala:22-29`]):

```scala
def apply(config: Config, context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]], resultSchema: Maybe[JsonSchema] = Absent)(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException])
```

`Completion.Reply(messages: Chunk[Message], usage: Maybe[Usage])` widens the old bare-`Chunk[Message]` result with provider-reported usage, which `Compactor.calibrate` consumes to keep its token estimator accurate ([`kyo/ai/completion/Completion.scala:58-68`]). `usage`/`cachedInputTokens` are `Absent` when a backend does not report them.

HTTP providers implement `apply` by posting their native request and decoding the reply; command harnesses implement it through their native event or stream-json output. Returning `Chunk[Message]` lets command harnesses append the transcript delta they produced, while the HTTP providers still return a singleton assistant message. Transport failures surface as `Abort[HttpException]`, never `Abort[Throwable]`; a missing key or undecodable reply as the typed `Abort[AIGenException]` leaves ([`kyo/ai/completion/Completion.scala:9-20`]).

Three implementation families, reached through `Config.Provider.completion`, never constructed by users ([`kyo/ai/completion/Completion.scala:70-83`]):

- `OpenAICompletion`: `POST {apiUrl}/chat/completions`, `content-type: application/json`, `Authorization: Bearer <key>` (plus `OpenAI-Organization` when present); covers OpenAI and the five compatible providers (DeepSeek/Gemini/Groq/Baseten/OpenRouter) ([`kyo/ai/completion/OpenAICompletion.scala:11`], [`kyo/ai/completion/OpenAICompletion.scala:30-58`]).
- `AnthropicCompletion`: `POST {apiUrl}/messages`, `x-api-key: <key>`, `anthropic-version: 2023-06-01`.
- `ClaudeCodeCompletion` and `CodexCompletion`: command-backed harness adapters sharing the `HarnessCompletion` base class ([`kyo/ai/completion/HarnessCompletion.scala:10`]). Claude Code receives SDK `stream-json` input and emits `stream-json` output, exposing enabled Kyo tools through a private localhost MCP bridge that calls back into Kyo tool handlers. Codex uses `codex app-server`, injecting prior context with `thread/inject_items` and reading turn events until `turn/completed`.

The result tool has the reserved name `Completion.resultToolName` (`"result_tool"`); when `resultSchema` is `Present`, the backend substitutes it for the tool's opaque `Structure.Value` input schema so the wire parameter schema exposes the real thought-aware properties ([`kyo/ai/completion/Completion.scala:84-87`]).

### `Completion.embed` and the Q-002 provider split

`Completion.embed(config, inputs)` is a trait method with a DEFAULT that fails `Abort[AIEmbeddingUnsupportedException]`; only a backend whose provider genuinely exposes an embeddings endpoint overrides it ([`kyo/ai/completion/Completion.scala:31-40`]). `OpenAICompletion.embed` overrides it, but gates the override PER PROVIDER, not per backend family: only `Config.OpenAI` and `Config.Gemini` are `embeddingsCapable` (compared by `.name`, a plain `String`, since `Config.Provider` carries no `CanEqual` under this build's `-language:strictEquality`); DeepSeek/Groq/Baseten/OpenRouter share the SAME `OpenAICompletion` backend for chat but fall through to the trait default for `embed`, because none of them documents an `/embeddings` route ([`kyo/ai/completion/OpenAICompletion.scala:60-96`]). This split was verified against each provider's current documentation (kyo-ai's research artifacts record the per-provider basis) and is NOT a per-backend-family shortcut: adding a new OpenAI-compatible provider to the catalog does not automatically grant it `embed`; it must be added to `embeddingsCapable` only if its documented surface actually has the route. `AnthropicCompletion`, `ClaudeCodeCompletion`, and `CodexCompletion` never override `embed`, so they always return `AIEmbeddingUnsupportedException`. `Compactor` never calls `embed` directly on the chat provider's backend without first resolving `Config.embedder` (see above); pairing a chat provider with no embeddings route to `Config.embedder = Absent` is a supported, silent-degrade configuration (no semantic edges, structural edges only), not an error ([`Compactor.scala:591-613`], [`CompactorTest.scala:1190-1208`, the pre-embed structural-only half]).

`embed`'s successful result is `Chunk[Embedding]`, one vector per input in order, each tagged `(modelName, dim)` so two embeddings are comparable only within the same space; a `(model, dim)` mismatch is a no-edge, never a cross-space comparison ([`kyo/ai/Embedding.scala:23-46`]).

## The exception hierarchy

`AIException` is a `sealed abstract class ... extends KyoException`, organized by the two operations that produce failures ([`AIException.scala:18-19`]):

- `AIGenException` (sealed trait): the failure set of a generation, the row of `LLM.run`'s residual; raised while the `Gen` op's eval loop runs ([`AIException.scala:22`]).
- `AIStreamException` (sealed trait): the failure set of a stream, carried inside the returned `Stream`'s effect row; raised lazily as the stream is consumed ([`AIException.scala:25`]).

A leaf mixes in every operation it can occur in. `AIMissingApiKeyException` and `AITransportException` mix in BOTH (either operation reaches the provider) ([`AIException.scala:27-38`]). Gen-only leaves: `AIEvalExhaustedException`, `AIInvalidThoughtException`, `AIDecodeException`, `AIContextOverflowException` (the forced compaction path fails loudly rather than sending an over-limit request), `AIEmbeddingUnsupportedException` ([`AIException.scala:40-52`], [`AIException.scala:60-75`]). Stream-only leaves: `AIStreamDeltaException`, `AIStreamIncompleteException` ([`AIException.scala:77-83`]). `AICrossRunException` (misuse) and `AIMeterClosedException` (impossible-state) are `AIException`s but in NEITHER operation set: they panic rather than ride a row ([`AIException.scala:85-99`]). `AIExceptionTest` pins the full lattice with `summon[... <:< ...]`, including the two compaction leaves, and asserts cross-run/meter-closed are not gen/stream failures ([`AIExceptionTest.scala:4-51`]).

When adding a failure, add a leaf to `AIException.scala` under the right operation trait(s) and route to it; keep every message string on the leaf, and never let a non-module exception (a raw `HttpException`) ride a public row, map it to `AITransportException` at the eval/stream boundary ([`LLM.scala:490`], [`kyo/ai/completion/Completion.scala:102-103`]).

## Unsafe boundary

This module has no `AllowUnsafe` sites and no `import AllowUnsafe.embrace.danger` in its sources. Ids come from the run's threaded `State.nextId`, never a process-global counter ([`LLM.scala:102-107`]), so there is no module-level mutable state to bridge. The only `java.lang.ref` use is `internal.AIRef extends WeakReference[AI]`, a GC-reclaimability mechanism for both `LLM.State.instances` keys and `Compactor.internal.CompactorState` cell keys, not an unsafe-tier API ([`LLM.scala:605-612`]). `Compactor`'s own per-instance state lives in a `private[kyo] AtomicRef` (`cell`), read/written only through `landWith`'s single read-modify-write closure, never a blind overwrite (see [The public-surface lock](#the-public-surface-lock-whats-locked-what-stays-privatekyo) below); this is ordinary lock-free atomic state, not an unsafe-tier bridge. When threading new state, keep it in `State`/`CompactorState` and the `Op` GADT; do not introduce a process-global counter or an `AllowUnsafe` parameter.

## The public-surface lock: what's locked, what stays `private[kyo]`

The compaction campaign's design gate fixed a hard line between the USER-FACING lock and the mechanism's internals, and that line is binding for future contributions, not merely a historical design note. What IS public: the enablement itself (`Compactor` + `Compactor.init`), its config (`Compactor.Config`, `Compactor.Config.SeedWeights`), the env carrier (`AIEnv.compactor`), the embedding subsystem (`Completion.embed`, `kyo.ai.Embedding`, `Config.embedder`), the cheap-tier selector (`Config.Provider.small`), the tool supersession metadata (the widened `Tool.init`, `Tool.Kind`), usage capture (`Completion.Usage`, `Completion.Reply`), and the two new `AIException` leaves. What stays `private[kyo]`: every state internal the mechanism carries -- `Compactor.internal.Segment` (named to avoid shadowing `scala.Unit`), `Rendered`, `Book`, `CompactorState`, `Verdict`, `Graph`/`Edge`/`EdgeKind`, `Recall`, and the atomic `cell` field itself ([`Compactor.scala:1234-1341`], [`Compactor.scala:26`]). A change that promotes one of these internals to a public return type, or that adds a persisted field to `CompactorState`/`Book`/`Rendered` beyond what `CompactorTest`'s state-shape leaf already asserts ([`CompactorTest.scala:747-754`]), is a design-surface change: raise it, do not slip it in as an implementation detail. Two-arity `Compactor.init` (defaults, or a config transform) is the only constructor surface; there is no convenience alias ([`Compactor.scala:1152-1164`]).

## Test patterns

All tests extend `kyo.test.Test[Any]` ([`LLMTest.scala:9`], and every `*Test.scala` in the suite). Tests follow the 1:1 source-to-test rule (`LLM.scala` -> `LLMTest.scala`, `Compactor.scala` -> `CompactorTest.scala`, `Agent.scala` -> `AgentTest.scala`, etc.); the module-wide invariants are folded into the per-source `*Test.scala` files. There is no `LLMInvariantsSpec` (a stale `LLMInvariantsTest.xml` may persist as a build artifact under `jvm/target`; ignore it).

### `TestCompletionServer`

Tests drive a real in-process HTTP server implementing the OpenAI and Anthropic completion wire protocols plus an embeddings route, bound on an OS-assigned ephemeral port within a `Scope` ([`TestCompletionServer.scala:6-26`], [`TestCompletionServer.scala:94-125`]). It serves `POST /v1/chat/completions`, `POST /v1/messages`, and (on the non-streaming server) `POST /v1/embeddings`, each reading the raw request JSON, capturing it, and returning the next scripted response ([`TestCompletionServer.scala:104-120`]). Two modes: `TestCompletionServer.run` (non-streaming JSON) and `runStreaming` (SSE on both chat endpoints) ([`TestCompletionServer.scala:94-102`]). Scripting is deterministic and per-test: `server.enqueueBody(json)` / `server.enqueueStream(chunks)` before the client call, popped one per request ([`TestCompletionServer.scala:29-34`], [`TestCompletionServer.scala:130-142`]). `server.captured` reads the ordered `Captured(path, body)` records for wire-shape assertions ([`TestCompletionServer.scala:37-38`], [`LLMTest.scala:399-404`]).

`server.awaitCaptured(pred)` suspends until a captured request satisfying `pred` arrives, backed by a `Channel[Captured]` every route offers onto in capture order, never a poll of `captured` or a race against a background dispatch fiber ([`TestCompletionServer.scala:47-50`]). This is the sanctioned way to assert something about a request a `Compactor` background fiber (judge, embed, summary) sends concurrently with the main gen request: race conditions in fiber-timing tests are a correctness bug, not flakiness to tolerate, and `awaitCaptured` is how kyo-ai avoids them. `CompactorTest` uses it to observe the judge's own request deterministically by its unique system-prompt substring, rather than asserting over every captured body (which would be over-broad, since the concurrent embed fork legitimately embeds root content too) ([`CompactorTest.scala:1096-1132`], [`CompactorTest.scala:1134-1160`]).

### Pointing tests at the server and scripting a reply

`LLMTest`/`CompactorTest` define the shared helpers (each per-source test that needs them redeclares its own):

```scala
def serverConfig(baseUrl: String): Config =
    Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 128000).apiUrl(baseUrl)

def resultToolBody(envelopeJson: String): String = // an OpenAI body whose assistant calls result_tool with envelopeJson
```

([`LLMTest.scala:18-30`]). The eval loop always extracts from `result_tool`, so script the structured reply by wrapping it (`{"resultValue": <value>}` for a plain field, with `openingThoughts`/`closingThoughts` keys when thoughts are enabled) ([`LLMTest.scala:28-30`], [`Thought.scala:107-111`]). `CompactorTest.embedBody(vectors)` scripts an OpenAI-shape embeddings response for the `/v1/embeddings` route ([`CompactorTest.scala:33-38`]).

### Deterministic `Latch`/`Channel` idioms (never a sleep)

kyo-ai's tests never use `Thread.sleep` or a poll loop to wait for a concurrent event; two Kyo-native idioms cover it:

- **`Latch`** signals "reached this point" across concurrent branches. `LLMTest`'s cancellation test forks a branch that mutates isolated state, releases a `Latch.init(1)` the instant it has, then suspends forever (`Async.never`); the racing branch awaits the latch before winning the race, guaranteeing the mutation happened before the interrupt fires, with no timing assumption ([`LLMTest.scala:197-221`]).
- **`Channel`** is the multi-event version: `TestCompletionServer`'s `arrivals: Channel[Captured]` (see `awaitCaptured` above) lets a test wait for the Nth (or a specific) HTTP arrival among several concurrent requests, deterministically, in arrival order ([`TestCompletionServer.scala:24`], [`TestCompletionServer.scala:47-50`]).

Reach for `Latch` for a single one-shot signal and `Channel` for an ordered stream of events; neither ever substitutes a `Clock.sleep`/`Async.sleep` guess for a real synchronization point.

### The JVM-only forced-GC prune test: the sanctioned platform split

`Compactor`'s per-instance cell prunes entries whose `AI` was collected, every render, via `d.filter((ref, _) => ref.isValid)` mirroring `LLM.State.pruned` ([`Compactor.scala:867-874`]). The KEEP half of this (a live entry survives the prune) is exercised in the shared, cross-platform `CompactorTest` ([`CompactorTest.scala:1320-1342`]). The DROP half (a collected ref's entry is actually removed) needs a way to simulate collection deterministically; `java.lang.ref.WeakReference.clear()` does that on the JVM, but Scala.js (and therefore both the JS and Wasm targets) does not implement it. `CompactorPruneTest` therefore lives under `jvm/src/test/scala/kyo/`, the ONE `.scala` file in any of kyo-ai's platform-specific trees, calling `deadRef.clear()` to simulate a real GC deterministically before asserting the drop ([`CompactorPruneTest.scala:1-41`]). This is the sanctioned shape for a genuine platform split: shared-first by default, a platform tree used ONLY when a JVM/JS/Native primitive has no cross-platform Kyo wrapper for the specific property under test, with the KEEP half proven cross-platform and only the DROP half gated. Do not follow this precedent to move an otherwise-portable test into a platform tree for convenience.

### The validation-first replay harness

`CompactorReplayHarness` is a test-only, model-free measurement of a dumb keep-the-tail-plus-one-summary BASELINE against the full `Compactor` design over six synthetic sessions, embedded as literal `Chunk[Message]` data (no recorded corpus, no file I/O, no network) ([`CompactorReplayHarness.scala:7-20`]). Both arms drive through the SAME driver and score through IDENTICAL metric functions (task-success predicates, integer token cost, re-fetch rate) ([`CompactorReplayHarness.scala:130-137`], [`CompactorReplayHarness.scala:331-357`]), so the comparison is not rigged toward the full design: session 6 is a short linear axis where recency alone suffices and the baseline ties the full design ([`CompactorReplayHarness.scala:303-321`]). `decide` folds the six scoreboards into a genuinely two-valued `GoNoGo` verdict: Go only when the full design never loses task-success where the baseline succeeds, stays within an epsilon token budget, never regresses re-fetch, and strictly wins somewhere ([`CompactorReplayHarness.scala:146-168`]). `CompactorReplayTest` pins each session's specific claim (supersession beats a stale re-serve, an oversized result is elided and recoverable, a frontier-drift session fits the forced path under its window, a non-root unit survives by graph-liveness not root-pinning, the short session ties) plus a REACHABLE no-go branch (a synthetic baseline-wins scoreboard) and the real six-session verdict ([`CompactorReplayTest.scala:8-120`]). If a genuinely code-level change regresses the real verdict to `NoGo`, that is the harness doing its job, not a flaky test to relax; the harness's own doc names the design's ship-baseline-plus-recall escape hatch as the sanctioned response, routed to a human decision, never a silent test weakening ([`CompactorReplayHarness.scala:25-26`]).

### What to assert

Assert concrete values, not just types or non-emptiness: the scripted reply round-trips to a concrete `A` ([`LLMTest.scala:498-509`]), and the captured request body contains the override temperature and not the scope temperature ([`LLMTest.scala:393-409`]). Compile-time row ascriptions (`val x: Int < LLM`, `def y: Int < (Async & Abort[AIGenException])`) are the proof that the rows are exact ([`LLMTest.scala:498-509`]).

## Conventions

### Cross-platform discipline

Source and tests default to `shared/`. `CompactorPruneTest.scala` under `jvm/src/test` is the ONE justified exception (see [above](#the-jvm-only-forced-gc-prune-test-the-sanctioned-platform-split)); the `js/`, `native/`, and `wasm/` trees carry no `.scala` source. Never move a test into a platform subtree to dodge a platform cost; a platform split is justified only by a primitive with no cross-platform Kyo wrapper for the specific property under test.

### Effect-row precision

Never widen the effect row of a `< LLM` computation to include `Async`; `Async` belongs only on `LLM.run`'s residual (the `Gen`/`Stream` interpretation) ([`LLM.scala:125-141`]). A new op goes in `LLM.internal.Op`, gets a `runWith` arm that introduces no `Async` for any op but `Gen`/`Stream`, and is suspended via `ArrowEffect.suspend` ([`LLM.scala:403-424`]). Compaction adds NO new op; a new compaction-adjacent capability should extend `Compactor`'s own methods (still `< (LLM & Async & Abort[AIGenException])` at most, matching `render`'s row) rather than mint an `Op`, unless it genuinely needs to thread through `LLM.State` itself ([`Compactor.scala:42-44`]). A `Mode.apply`'s `gen` parameter row is exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`, not wider ([`Mode.scala:20-22`]).

### `private[kyo]` over `protected`

Use `private[kyo]` for cross-package visibility; there is no `protected` in this module. `AI`'s constructor and fields, the `LLM` op interface, `State`'s constructor, and every `Compactor.internal` state type are all `private[kyo]`.

### Kyo types

| Use this | Not this |
|----------|----------|
| `Maybe` | `Option` |
| `Result` | `Either` / `Try` |
| `Chunk` / `Dict` | `List` / `Seq` / `Map` (in public APIs) |
| `Span[Float]` | `Array[Float]` (`Embedding.vector`, allocation-free and cross-platform) |

### Test file naming

Follow the 1:1 rule; the module-wide invariants live in the per-source `*Test.scala` files, not a separate spec. `Compactor.scala` splits its harness/replay concern into two files by aspect (`CompactorReplayHarness.scala`, the construction/scoring machinery; `CompactorReplayTest.scala`, the actual test leaves), which is the sanctioned "split by aspect keeping the source as the prefix" case, not an orphan. No orphan or scratch test files in a finished change.

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-aiJVM/test'

# A single test class
sbt 'kyo-aiJVM/testOnly kyo.LLMTest'
sbt 'kyo-aiJVM/testOnly kyo.CompactorTest'
sbt 'kyo-aiJVM/testOnly kyo.CompactorPruneTest'
```

Building auto-formats; re-read any file you edit after building, formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

## Decision checklist: before adding or changing X (kyo-ai)

In addition to the root checklist:

1. **New `Op`.** Is it a final `case class` (data) or `case object` (field-less)? Does its `runWith` arm thread `State` and introduce no `Async` (only `Gen`/`Stream` may)? Does the reply type in `Op[A]` match the `ArrowEffect.suspend` return? Does it appear in `crossRunFailure` if it targets an instance? [`LLM.scala:65-95`, `LLM.scala:583-599`]
2. **Eval-loop change.** Does every non-`Gen` path stay `< LLM` (no `Async`)? Does the `Gen` residual stay `A < (LLM & Async & Abort[AIGenException])`? Does `summon[NotGiven[LLM <:< Async]]` still hold? [`LLMTest.scala:477-485`, `LLMTest.scala:828-836`]
3. **New enablement kind or call.** Does it implement `AI.Enablement[S]`'s two `enableIn` methods (`AIEnv` and `AISession`)? Is it reached only through `AI.enable` / `ai.enable` (never a per-type binder)? Does the row carry `S` until the run boundary? [`AI.scala:40-53`, `AI.scala:120-126`]
4. **Config or compactor override.** Is an instance override a `Maybe[T]` (`Absent` = inherit, `Present` = override)? Does `genLoop`'s merge keep `Present` winning over the scope, while a mode `withConfig` still layers on top (config only)? [`LLM.scala:476-485`, `LLMTest.scala:393-428`, `LLMTest.scala:741-826`]
5. **New `Mode`.** Is `apply`'s `gen` row exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`? Does the mode receive `ai` and read/write that instance only? [`Mode.scala:20-22`]
6. **New `Agent` overload.** Does it delegate to `runImpl`, minting ONE stable instance via `AI.initWith(behavior)`, discharging with `LLM.run`, re-throwing `Abort[AIGenException]` via `getOrThrow`, and handing to `Actor.run`? [`Agent.scala:217-240`]
7. **New completion backend.** Does it match the result tool by `Completion.resultToolName` and substitute `resultSchema` when `Present`? Does it surface transport failures as `Abort[HttpException]`, never `Abort[Throwable]`? If it exposes a real embeddings route, does it override `embed` and gate it by the exact provider(s) that document the route (never a whole backend family)? Is it reached through a new `Config.Provider` in `Provider.all`, with a matching `override def small`? [`kyo/ai/completion/Completion.scala:70-87`, `kyo/ai/completion/OpenAICompletion.scala:60-96`, `kyo/ai/Config.scala:135-136`]
8. **New failure.** Is there a leaf in `AIException.scala` under the right operation trait(s), with its message on the leaf, mapped from any raw `HttpException` at the eval/stream boundary? Is a misuse/impossible-state panic kept off both rows? [`AIException.scala`, `LLM.scala:490`]
9. **Compactor internal-state change.** Does it stay `private[kyo]`? Is any new persisted field genuinely underivable from the transcript (not something `group`/`deriveGraph`/`score` could recompute)? Does `CompactorTest`'s state-shape (`productArity`) leaf get updated in the SAME change? [`Compactor.scala:1270-1276`, `CompactorTest.scala:747-754`]
10. **Compactor rendering/gating change.** Does a fast-path render stay byte-identical between updates? Is any deep edit (L3/L4) still routed through `cacheGatePasses` or the rot rule, never applied unconditionally? Does the forced path stay the sole cache-gate exemption? [`Compactor.scala:1053-1061`, `CompactorTest.scala:756-780`, `CompactorTest.scala:959-991`]
11. **New test.** Does it extend `kyo.test.Test[Any]`, use `TestCompletionServer` (not a live endpoint), assert concrete values, place each `enqueueBody`/`enqueueStream` before the consuming client call, wait via `Latch`/`Channel`/`awaitCaptured` (never a sleep), and live in `shared/src/test` unless it needs a primitive `CompactorPruneTest`-style exception? [`TestCompletionServer.scala`, `LLMTest.scala`, `CompactorPruneTest.scala`]

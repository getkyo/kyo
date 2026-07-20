# Contributing to kyo-ai

Module-specific guide for kyo-ai. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-ai: the explicit-instance model, the `LLM` `ArrowEffect` and its threaded `State`, the per-run owner and cross-run guard, the unified enablement surface, the scope/instance env-merge rule, the eval and stream loops, the `Compactor` automatic context-compaction mechanism, the provider wire layer (including the embeddings split), and the test conventions.

**The headline invariant:** a program typed `A < LLM` is a pure value. `LLM` is a custom `ArrowEffect[LLM.internal.Op, Id]`, NOT a subtype of `Async` ([`LLM.scala:26`]). Its ops carry data and read/append per-instance conversation histories in one threaded `State`. The single op that reaches the world is `Gen` (and its sibling `Stream`): its handler interpretation runs the eval loop, and that is the ONLY place `Async & Abort[AIGenException]` enter, riding out on `LLM.run`'s residual ([`LLM.scala:125-132`], [`LLM.scala:393-403`]). A row `A < LLM` must never include `Async`; no change to the eval loop should push `Async` into the `LLM` row. `LLMTest` pins this with `summon[NotGiven[LLM <:< Async]]` in two places, once as a standalone compile probe and once alongside a live `< LLM` ascription ([`LLMTest.scala:477-485`], [`LLMTest.scala:830-838`]).

**A second, equally binding invariant, prompt caching must work well:** `Context` carries two lists, `raw` (the complete, append-only transcript) and `compacted` (exactly what providers are sent). Below the compaction seam's occupancy trigger, `compacted` is re-served as the SAME value, untouched: no render call happens at all, so the request is byte-identical between updates by construction, not merely by convention ([`LLM.scala:485-501`]). At a boundary, a `Compactor` rebuilds `compacted` as a pure, deterministic function of `Context`: same `(raw, compacted, config)` in, same rebuilt list out, every time ([`Compactor.scala:144-175`], [`CompactorTest.scala:68-75`], [`CompactorTest.scala:559-568`]). Within a rebuild, each proposed demotion is gated by a cache-economics check (`cacheGatePasses`) that compares the tokens it would save against the tokens of the rendered tail its edit point actually invalidates, so a demotion lands only when it is worth the cache it costs ([`Compactor.scala:453-468`]). The one exemption is the forced path (over the hard window), a deterministic, ungated omit-until-it-fits pass ([`Compactor.scala:470-496`]). Treat any change that renders below the trigger, that demotes without going through the cut's cache check, or that widens the forced path's exemption, as a regression against this invariant, not a style nit.

## What kyo-ai is

kyo-ai is a typed effect for first-class conversations with a language model, including automatic context compaction for long-running conversations. The public surface, all top-level in `package kyo`:

- `LLM`, the `ArrowEffect`, and its `run` (the discharge boundary).
- `AI`, the first-class conversation instance (both the identity value `ai` and the namespace of operations).
- `Agent`, an opaque alias over `kyo.Actor` that pairs the LLM surface with the actor model.
- `Prompt`, `Tool`, `Thought`, `Mode`, `Compactor`, the five composable enablement kinds (`AI.Enablement`).
- `AIEnv` and `AISession`, the generation environment and the per-instance state record.
- the `AIException` hierarchy (`AIGenException`, `AIStreamException`, and their leaves).

The settings/content value types `Config`, `Context`, `Image`, `Embedding` live in package `kyo.ai`, but `Config`/`Context`/`Image` are surfaced into `kyo` so `import kyo.*` reaches everything: the `AI` companion `export`s `kyo.ai.Config` / `Context` / `Image` ([`AI.scala:36-38`]), so a user writes `AI.Config`, `AI.Context`, `AI.Image`; `LLM` also `export`s `Config` ([`LLM.scala:31`]). `Embedding` is reached via `kyo.ai.Embedding`.

All code lives in `shared/src/main/scala` (`kyo/` for the effect surface, `kyo/ai/` for the value types, `kyo/ai/completion/` for the wire backends). ALL tests live in `shared/src/test` and run cross-platform (JVM, Scala.js, Scala Native, Wasm; four platforms, `build.sbt:1222-1224`). There is no platform-specific test file in kyo-ai. Do not reintroduce a platform split unless a genuinely platform-only primitive needs it.

## The explicit-instance model

There is no ambient "current" instance and no `AI.use`. A behavior receives its instance explicitly and calls `self.gen`; the eval loop threads the target `AI` explicitly (`reads ai.context, appends to ai`), so nothing is ambient ([`LLM.scala:503-517`]). Two surfaces exist:

- **One-shot.** `AI.gen[A]` mints a fresh ephemeral instance, generates against it, then discards its slot via `ai.reset` on success, so two one-shots never share state ([`AI.scala:78-82`]). `AITest` pins that a successful one-shot leaves `State.instances` empty (and stays empty under concurrent gens, and a transport abort fails the run so nothing leaks) ([`AITest.scala:274-317`]).
- **Named instance.** `AI.init` mints a persistent slot whose conversation, enablements, and config override survive across turns within one `LLM.run`; `ai.gen[A]` runs against that slot ([`AI.scala:56-57`], [`AI.scala:171-174`]).

`AI` is a reference object, NOT an `opaque type` over a `Long`:

```scala
final class AI private[kyo] (private[kyo] val id: Long, private[kyo] val owner: AnyRef):
    private[kyo] val ref: LLM.internal.AIRef = new LLM.internal.AIRef(this)
```

([`AI.scala:16-17`]). The id is drawn from the run's threaded `State` counter (no process-global mutable state), so identity is scoped to one `LLM.run` and restarts per run; `LLMTest` pins that within a run successive `init` ids are `0, 1, ...` and a fresh run restarts at `0` ([`LLMTest.scala:568-581`]). Every method on `AI` is a thin value over the `LLM` effect surface: `AI` summons no `ArrowEffect` op directly, only `LLM`'s `private[kyo]` interface ([`AI.scala:171-239`], [`LLM.scala:409-441`]).

### Per-run owner and the cross-run guard

Each instance remembers the run that created it (`owner`, a fresh `AnyRef` per run, object identity, no counter) ([`AI.scala:16`], [`LLM.scala:56-60`]). Using an instance inside a different `LLM.run` is misuse: it cannot address that run's slots. `crossRunFailure` inspects every op that targets an instance and, when `ai.owner ne state.owner`, returns `AICrossRunException`; the panic is the handler arm's result, so it rides `runWith`'s residual `Abort` row (not the `LLM` continuation) and aborts the whole computation ([`LLM.scala:65-80`], [`LLM.scala:94-95`]). `AICrossRunException`'s message points the user at `ai.snapshot` / `AI.recover` ([`AIException.scala:88-93`]). `LLMTest` pins that the guard fires for EVERY targeting op (read, set, gen, stream, discard, session, enable), not just one ([`LLMTest.scala:599-618`]).

To carry an instance across runs deliberately, capture it with `ai.snapshot` (returns its `AISession`) and restore it with `AI.recover(session)` in the new run ([`AI.scala:237-238`], [`AI.scala:70-72`]); `AITest` pins a round-trip of history + an enabled tool + a config override across two runs ([`AITest.scala:201-226`]).

## The LLM effect

### Effect definition and the op GADT

`LLM` is `sealed trait LLM extends ArrowEffect[LLM.internal.Op, Id]` ([`LLM.scala:26`]). The op GADT `LLM.internal.Op[A]` indexes each op's reply by `A`, so the handler continuation needs no reply-side cast ([`LLM.scala:611`]). It has exactly **13** subclasses ([`LLM.scala:612-627`]); field-less ops are `case object`s, the rest carry data:

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

There is **no `SetCurrent`** op and no ambient-current concept; there is also no compaction-specific op. `Compactor` is wired entirely through the existing `Env`/`SetEnv`/`GetSession`/`SetSession` ops and the seam inside `eval`/`streamAgainst` (see [The compaction seam](#the-compaction-seam)); `LLMTest` pins the count directly by constructing all 13 subclasses and asserting no 14th exists ([`LLMTest.scala:697-716`]). `Gen` is the one op that reaches the world; its arm runs `genLoop` under a nested `runWith` against the live state, and `Async & Abort[AIGenException]` enter there ([`LLM.scala:125-132`]). `Stream`'s arm projects the SSE response and is read-only for the target's own context beyond what `genLoop`/`streamAgainst` themselves write, so the threaded state passes through unchanged in the handler loop itself ([`LLM.scala:133-142`]).

### Threaded state: `LLM.State`

`State` is the single record threaded through `ArrowEffect.handleLoop` ([`LLM.scala:40-54`], [`LLM.scala:83-86`]):

```scala
final case class State private[kyo] (
    instances: Dict[internal.AIRef, AISession],
    nextId: Long,
    owner: AnyRef,
    env: AIEnv
)
```

- `instances` is keyed by `internal.AIRef`, a `WeakReference[AI]` whose equality/hash are by the AI's stable `id`, so a dropped `AI` becomes GC-reclaimable while its key still matches its slot ([`LLM.scala:629-640`]). `State.pruned` sweeps slots whose `AI` was collected, run when minting a new instance so an unbounded mint stream never accumulates dead slots ([`LLM.scala:53`], [`LLM.scala:102-107`]).
- `nextId` is the monotonic id counter `Init` draws from; `SetState` never lowers it (`math.max`), so a `forget`/`fresh` rollback keeps the high-water id and a slot key is never reused ([`LLM.scala:116-120`]).
- `owner` stamps every instance for the cross-run guard ([`LLM.scala:56-60`]).
- `env` is the scope `AIEnv` (see below), read by `Op.Env` and replaced by `Op.SetEnv` ([`LLM.scala:108-111`]).

`State.empty(config)` seeds a `Present(config)` scope env and a fresh `owner` ([`LLM.scala:56-60`]).

### `run` and its residual

`LLM.run[A, S](v: A < (LLM & S)): A < (S & Async & Abort[AIGenException])` threads a fresh `State.empty(config)` through `runWith` and discards the final state ([`LLM.scala:393-403`]). Three overloads (`run(v)`, `run(f: Config => Config)(v)`, `run(config)(v)`) all funnel through `runWith`; the first two resolve `Config.default` under `Sync` first ([`LLM.scala:393-403`]). `runTuple` keeps the final state with the value for tests and transcript access (`private[kyo]`) ([`LLM.scala:405-407`]). `runWith` is NOT inline ([`LLM.scala:82-86`]).

### The eval loop

`genLoop(ai, schema)` is the `Gen` interpretation ([`LLM.scala:503-542`]). It:

1. Merges the instance's own env onto the scope env for the duration of the eval, then restores it (so the effective surface is `scope ++ instance`): config is `session.env.config.orElse(scopeEnv.config)`, the compactor is `session.env.compactor.orElse(scopeEnv.compactor)` (instance-over-scope, single active policy, last-wins, never a pipeline), and the instance's prompt/tools/thoughts/modes are layered on ([`LLM.scala:508-517`]). This is the merge that makes a `Present` instance config (or compactor) override the scope's; an `Absent` instance value inherits the scope's. `LLMTest` pins the compactor half of this merge directly (env-only, no generation) ([`LLMTest.scala:741-790`]) and end to end, against a live outbound request, that the instance compactor's rendering (not the scope's) reaches the wire ([`LLMTest.scala:792-828`]).
2. Enables `Prompt.internal.defaultGuidance` (generic structured-output guidance, not part of the empty prompt) ([`LLM.scala:518`], [`Prompt.scala:91-103`]).
3. Loops: each iteration calls `eval[A](ai, forceResult = iterations >= config.maxIterations)`, wraps transport `HttpException` into `AITransportException`, passes the result through `Mode.internal.handle` (the mode pipeline), and on `Present(r)` yields, on `Absent` re-loops with the seed modulated (`c.seed.map(_ * 31)`) until `iterations >= config.maxIterations * 2`, where it aborts with `AIEvalExhaustedException` ([`LLM.scala:520-535`]).

`eval` posts one completion request: it assembles the tools (plus the recall tool, when a compactor is effective, plus the result tool), the thought-aware result schema, the conversation view (consulting the compactor at the seam described below), runs the provider completion under the config meter and a `Retry[HttpException](config.retrySchedule)`, appends the reply, dispatches any tool calls, and extracts the structured result directly from the `result_tool` call arguments ([`LLM.scala:544-604`]). A closed meter under an in-flight gen panics with `AIMeterClosedException` (an impossible-state, off both rows) ([`LLM.scala:570-583`], [`AIException.scala:98-99`]).

### The stream loop

`AI.stream[A]` / `ai.stream[A]` suspend `Op.Stream`, whose handler runs `streamAgainst` ([`LLM.scala:425-429`], [`LLM.scala:133-142`]). `streamAgainst` asks `config.provider.completion.streamFragments` for raw JSON fragments of the `{ resultValue: ... }` envelope and accumulates the fragments. For `String`, it emits decoded text chunks whose concatenation is the final text. For other result types, it emits each complete decoded element from the result array exactly once ([`LLM.scala:189-250`]). HTTP providers implement fragments with SSE result-tool deltas; command harnesses use their native event or stream-json output. The returned `Stream` carries its failures typed in its element row as `Abort[AIStreamException]`: a malformed delta is `AIStreamDeltaException`, an end without a decodable value `AIStreamIncompleteException` ([`LLM.scala:310-391`]), a transport error `AITransportException` (mapped at the SSE fetch site, [`kyo/ai/completion/Completion.scala:102`]). A missing API key is the one failure raised eagerly (before the `Stream` value), as `AIMissingApiKeyException` on the run boundary ([`LLM.scala:206-208`]).

### The `LLM.isolate` given

`given isolate: Isolate[LLM, Async, LLM]` lets `Async.fill`/`foreach`/`race` fork over a bare `LLM` row ([`LLM.scala:443-471`]). `Keep = Async` is exact: the in-tree parallel sites require `Isolate[LLM, Abort[E] & Async, LLM]`, and for the `E = Nothing` body (whose transport errors the eval loop already recovers) that reduces to `Isolate[LLM, Async, LLM]`, which a wider `Keep` (`Abort[Any] & Async`) cannot satisfy by `Keep` contravariance ([`LLM.scala:443-450`]). `capture` reads the live `State` via `Op.GetState` ([`LLM.scala:455-457`]); `isolate` discharges `runWith`'s residual `Abort[AIGenException]` inside the fork with `getOrThrow`, so an unrecovered fork generation failure surfaces as a fiber panic ([`LLM.scala:458-463`]); `restore` merges fork-born instance contexts back via `mergeInstance` (prefix-aware `Context.merge`, parent env kept), skipping GC'd slots ([`LLM.scala:464-471`], [`LLM.scala:473-477`]). `LLMTest` pins both the fork resolution ([`LLMTest.scala:511-523`]) and the unrecovered-fork panic ([`LLMTest.scala:525-538`]).

## `AIEnv` and `AISession`: the env-merge rule

`AIEnv` is the generation environment: a config, an optional compactor, plus the enablements layered for a scope or instance ([`AIEnv.scala:12-19`]):

```scala
case class AIEnv(
    config: Maybe[Config],
    prompt: Prompt[Any],
    tools: Chunk[Tool[Any]],
    thoughts: Chunk[Thought[Any]],
    mode: Chunk[Mode[Any]],
    compactor: Maybe[Compactor[Any]] = Absent
)
```

`config` is `Maybe[Config]`: the SCOPE env always holds `Present` (set at `LLM.run`), while an INSTANCE env holds `Absent` to inherit the scope config or `Present` to override it ([`AIEnv.scala:9-11`]). `compactor` follows the identical shape: `Absent` inherits, `Present` overrides, single active policy, last-wins, never a pipeline ([`AIEnv.scala:18`], [`AIEnv.scala:23-27`]); it holds the erased `Compactor[Any]` carrier, mirroring `Chunk[Mode[Any]]`/`Prompt[Any]`. `AIEnv.empty` and `AISession.empty` both carry an `Absent` config and an `Absent` compactor ([`AIEnv.scala:53`], [`AISession.scala:31`]); `AIEnvTest`/`AISessionTest` pin that `config(cfg)` sets `Present`, `mapConfig` is a no-op while `Absent`, and the empty session has no override ([`AIEnvTest.scala:9-16`], [`AISessionTest.scala:8-21`]).

`AISession(context: Context, env: AIEnv)` is one instance's full state: its conversation plus its env override and enablements ([`AISession.scala:12`]). It is both the value `State.instances` holds per instance and the snapshot `ai.snapshot` returns / `AI.recover` restores. It holds code (tool runners, effectful prompts, modes, the compactor reference), so it is in-memory only and not serializable; only the `session.context` slice is serializable (`Context derives Schema`) ([`AISession.scala:6-10`]).

**The override-merge rule** (`genLoop`, [`LLM.scala:508-517`]; pinned in `LLMTest`):

- An instance `Present` config override beats the scope config in the request ([`LLMTest.scala:393-409`]).
- A scope `AI.withConfig` wrapped around a gen is SHADOWED by the instance config override (the override wins) ([`LLMTest.scala:430-446`]).
- A mode's `AI.withConfig` (applied after the merge, inside the mode pipeline) DOES reach the request even on a config-overridden instance, layering on top of the override ([`LLMTest.scala:411-428`]).
- The same instance-over-scope precedence applies to `compactor`: a scope compactor and an instance compactor can both be enabled; the instance's wins ([`LLMTest.scala:741-828`]).

## The enablement surface

The five composable kinds, `Tool`, `Prompt`, `Thought`, `Mode`, `Compactor`, all extend `AI.Enablement[-S]`, whose two `private[kyo]` methods say how the kind layers itself onto a scope env or an instance session ([`AI.scala:40-54`], [`Tool.scala:23-26`], [`Prompt.scala:41-44`], [`Thought.scala:26-29`], [`Mode.scala:24-27`], [`Compactor.scala:43-46`]). `private[kyo]` so only the module's five kinds implement it; users compose, never extend. There are **no** per-type `enable` binders (no `Tool.enable`, `Thought.enable`, `Prompt.enable`, `Mode.enable`, or `Compactor.enable`). Enabling is unified:

- **Scope.** `AI.enable[A, S](enablements: Enablement[S]*)(v): A < (S & LLM)` folds each enablement's `enableIn(AIEnv)` over the scope env via `LLM.updateEnv` (on top of the scope's current enablements); empty varargs is a no-op ([`AI.scala:121-127`]). The capability `S` rides the row, unified across the varargs to their intersection, until discharged at the run boundary.
- **Instance.** `ai.enable[S](enablements: Enablement[S]*): AI < (S & LLM)` folds each `enableIn(AISession)` onto the named instance ([`AI.scala:229-230`]).

Both take varargs or a `Seq` (a `DummyImplicit` differentiates the erased `Seq[T]` signatures) and accept a mix of kinds in one call ([`AI.scala:129-133`], [`AI.scala:233-234`]). Enabling a compactor works identically to the other four kinds: `ai.enable(Compactor.init)` or `AI.enable(Compactor.init)(v)`; `Compactor.init` is a plain, side-effect-free value, not `< Sync`, so no `.map` is needed to enable it ([`Compactor.scala:54`]).

Config is scoped by `AI.withConfig` (NOT `LLM.withConfig`), built on `LLM.updateEnv(_.mapConfig(f))` ([`AI.scala:114-119`], [`LLM.scala:434-439`]). `updateEnv` brackets a transform of the scope `AIEnv` over `v`: get, modify, set, run, restore, written once and reused by the `enable` methods and `withConfig`.

### Forget and fresh

`AI.forget` snapshots `State`, runs `v`, then restores ALL instances' conversations (a scope-wide rollback) ([`AI.scala:135-139`]); the `forget(ais*)` form rolls back ONLY the named instances, other writes persist ([`AI.scala:141-147`]). `AI.fresh` runs `v` with conversations blanked (enablements, config, and compactor kept), then restores ([`AI.scala:149-156`]). `AITest` pins `reset` removes the slot ([`AITest.scala:102-113`]) and `forget(ais*)` rolls back only the named instance ([`AITest.scala:184-199`]).

### `Tool`

`Tool.init[In][Out, S]` builds a tool from a name, optional description, prompt, and run function, plus two compaction-supersession metadata parameters: `kind: Tool.Kind = Tool.Kind.Read` and `compactionKey: In => Maybe[String] = _ => Absent` ([`Tool.scala:46-56`]). `Tool.Kind` is a closed two-case enum, `Read` and `Write` ([`Tool.scala:36-37`]); a tool supplying a `compactionKey` extractor opts a call's unit into key-based supersession (a later same-key unit supersedes an earlier one, and `kind` decides whether a re-read supersedes a prior read or a write supersedes any prior same-key unit); the keyless default never supersedes and is never superseded ([`Tool.scala:41-44`]). Both metadata params are optional, so every pre-compaction call site keeps compiling unchanged; `ToolTest` pins the default (Read, keyless) ([`ToolTest.scala:250-255`]), a custom write key surfacing on `Info` ([`ToolTest.scala:257-266`]), the closed two-case enum ([`ToolTest.scala:268-272`]), and that a legacy no-metadata call site still compiles and behaves unchanged ([`ToolTest.scala:274-295`]). The internal `resultToolInfo` is the dynamic `result_tool` the eval loop adds to every request: its run is a no-op, and the eval loop extracts the call's arguments directly (no capturing run, no ref); it takes its `Kind`/`compactionKey` defaults, so the result tool never supersedes ([`Tool.scala:134-150`], [`LLM.scala:557`]). Tool-call dispatch contains ANY throw from user code as a tool message and never lets it escape the eval loop: `decodeAndRun`'s `Abort.run[Throwable]` wraps the run body ([`Tool.scala:113-121`]), and `handle` routes a run-body failure to the generic tool-failure message, distinct from the schema-repair message reserved for a malformed-arguments decode failure ([`Tool.scala:152-207`]).

### `Thought`

`Thought[A]` injects a typed reasoning field into the result schema: an `opening` field precedes `resultValue`, a `closing` follows it, so field ORDER frames the answer and drives autoregressive generation ([`Thought.scala:9-17`], [`Thought.scala:106-120`]). The thought name is the type's compile-time unqualified name via `Schema.structure.name` ([`Thought.scala:63`]). No reasoning is woven in by default; `Thought.reflective` (a `Reflect` opening + a `Check` closing) is the built-in scaffold, enabled explicitly ([`Thought.scala:53-57`], [`Thought.scala:93-94`]). Each thought's `process` hook fires on the decoded typed value after generation; an unrecognized field name is `AIInvalidThoughtException`, an undecodable field or result `AIDecodeException` ([`Thought.scala:125-153`]).

### `Prompt`

`Prompt[-S]` splits guidance into primary instructions (added at the context start, as SEPARATE system messages so providers can cache individual blocks) and reminders (floated at the context end, immediately before generation) ([`Prompt.scala:7-14`], [`Prompt.scala:105-145`]). `andThen` merges and `.distinct`-deduplicates both lists ([`Prompt.scala:22-36`]). `Prompt.init[S]` is `inline` and requires the prompt and reminder bodies to be `< (LLM & S)` ([`Prompt.scala:64-68`]). The `p` string interpolator normalizes per-line leading whitespace (`\n\s+` -> `\n`) and trims; use it for multi-line prompt literals ([`Prompt.scala:48-54`]). The enriched request is built over `context.compacted`, never `raw`: `Prompt.internal.enrichedContext` merges the assembled instruction/reminder messages with the compactor's projected view, so what the provider receives is always the seam's output ([`Prompt.scala:105-145`], [`Prompt.scala:139-141`]).

### `Mode`

`Mode[-S]` is generation-interception middleware; enabled modes form a pipeline applied in registration order ([`Mode.scala:5-28`], [`Mode.scala:49-60`]). Its method is:

```scala
def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using Frame): Maybe[A] < (LLM & Async & Abort[AIGenException] & S)
```

([`Mode.scala:20-22`]). The `gen` parameter carries its failures typed as `Abort[AIGenException]` and the mode receives the target `ai` (so it can read/write that instance's conversation around the gen). `Mode.init[S]` builds a mode from a polymorphic transform, the convenient alternative to `new Mode[S]` ([`Mode.scala:40-47`]).

## The `Compactor` mechanism

`Compactor[-S]` is automatic context compaction, wired in as a fifth `AI.Enablement`: enable it (`ai.enable(Compactor.init)` or scope-wide via `AI.enable(Compactor.init)(v)`), and `LLM.eval` / `streamAgainst` consult it at one seam between the context read and request assembly, rebuilding `Context.compacted`, the bounded projected view the model actually sees ([`Compactor.scala:8-24`]). With no compactor enabled, the seam is a no-op and the assembled request is byte-identical: default-off ([`LLM.scala:500`], [`LLMTest.scala:674-695`]).

`Compactor` is a PURE trait, not a class with hidden state, and it carries the enablement family's `[-S]` capability parameter exactly like `Tool`/`Thought`/`Mode`:

```scala
trait Compactor[-S] extends AI.Enablement[S]:
    def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException] & S)
    def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk.empty
```

([`Compactor.scala:25-38`]). `render` takes the WHOLE `Context` (so it can read `raw` for grouping/scoring context) but returns ONLY the rebuilt `compacted` `Chunk[Message]`: `raw` never appears in the return type, so a custom compactor cannot dead-edit it, structurally, not by convention. The seam installs the result itself, via `ctx.copy(compacted = rendered)` ([`Compactor.scala:20-23`], [`LLM.scala:494-497`]). `init` is a NULLARY, side-effect-free accessor, `def init: Compactor[Any] = Default` ([`Compactor.scala:54`]): there is no state to allocate at construction, so it is not `< Sync` the way `AI.init`/`Config.default` are. `ConfigTest` pins that `Compactor.init` returns the shared `Default` singleton by reference identity ([`ConfigTest.scala:197-198`]).

`Compactor` composes with the other four enablement kinds exactly the same way, including the sanctioned erased-carrier discharge every kind uses (`Mode.scala` has the identical `this.asInstanceOf[Mode[Any]]` pattern): because `Compactor` is contravariant in `S`, widening a user's `Compactor[S]` to the `AIEnv`/`AISession` slot's `Compactor[Any]` needs one cast, applied at the boundary, never inside `render` itself:

```scala
final private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
    env.compactor(this.asInstanceOf[Compactor[Any]])
final private[kyo] def enableIn(session: AISession)(using Frame): AISession =
    session.copy(env = enableIn(session.env))
```

([`Compactor.scala:40-46`]).

### `Context(raw, compacted)`: the two-list state model

`Context` carries exactly two `Chunk[Message]` fields, no `Maybe`, no third structure:

```scala
case class Context(raw: Chunk[Message], compacted: Chunk[Message]) derives CanEqual, Schema
```

([`kyo/ai/Context.scala:14`]). `raw` is the complete, append-only transcript; `compacted` is exactly what providers are sent. They start IDENTICAL (structural sharing, zero extra cost at construction: the single-arg factory `Context(messages)` sets `raw = compacted = messages`, [`kyo/ai/Context.scala:68`]) and diverge only at the first boundary render a `Compactor` performs. A summarized or elided entry is an ORDINARY `Message` living inside `compacted`; `Context` itself interprets neither list ([`kyo/ai/Context.scala:6-13`]). `ContextTest` pins the two-field shape, no `Maybe`, and the single-arg factory ([`ContextTest.scala:37-46`]).

`Context` owns exactly ONE behavior that touches both lists, `add`, which appends unconditionally to BOTH (the pairing invariant); every builder (`systemMessage`, `userMessage`, `assistantMessage`, `toolMessage`) delegates to it, skipping only degenerate input at the builder level (blank content; a user message with neither content nor image; an assistant message with neither content nor calls); `toolMessage` always appends, since a tool result is never degenerate the way a model-authored message can be ([`kyo/ai/Context.scala:16-37`]). `ContextTest` pins that `add` appends to both lists unconditionally, even content the skip-builders would drop ([`ContextTest.scala:48-59`]) and that every ordinarily-added message defaults its enrichment fields (see below) to `Absent` ([`ContextTest.scala:123-129`]).

`raw` is append-only under compaction: a `Compactor` rebuilds only `compacted` and structurally cannot touch `raw` (`render`'s return type excludes it). There is exactly ONE sanctioned exception, documented as such, never a precedent to extend casually: tool dispatch reconciles the transient `"Processing tool call: <name>"` placeholder in the tail of BOTH lists symmetrically, replacing it with the real result (or, on a decode failure, removing it and the failing call from the assistant message) ([`kyo/ai/Context.scala:9-12`], [`Tool.scala:152-207`], specifically the three `ctx.copy(raw = ..., compacted = ...)` sites at [`Tool.scala:165-183`], [`Tool.scala:185-191`], [`Tool.scala:192-204`]). No other path rewrites `raw`.

`merge` is both-list, prefix-aware: it finds the common `raw` prefix (compared with `coreEq`, below) shared with the argument fork, then appends only the argument's non-common `raw` SUFFIX to BOTH lists, keeping the RECEIVER's `compacted` prefix untouched:

```scala
def merge(that: Context): Context =
    val n    = raw.zip(that.raw).takeWhile((a, b) => Context.coreEq(a, b)).size
    val tail = that.raw.drop(n)
    Context(raw.concat(tail), compacted.concat(tail))
```

([`kyo/ai/Context.scala:46-50`]). So a fork's frozen `compacted` view is discarded on merge; only the receiver's frozen prefix survives, with the fork's raw suffix appended verbatim to both lists (an uncompacted receiver, whose `compacted == raw`, simply stays that way). `LLM.mergeInstance` (the isolate's `restore`) calls this exact method to fold a parallel fork's writes back into the parent instance ([`LLM.scala:473-477`]). `ContextTest` pins the frozen-prefix-kept / fork-suffix-appended shape ([`ContextTest.scala:72-89`]), a disjoint-context merge ([`ContextTest.scala:91-97`]), and a self-merge producing no duplication ([`ContextTest.scala:99-103`]).

`Context.coreEq` compares two messages on CORE fields only (content/role/image/calls/callId), ignoring the enrichment fields below, so two content-identical messages differing solely in enrichment state compare equal under it even though full-record `==` would not:

```scala
private[kyo] def coreEq(a: Message, b: Message): Boolean =
    (a, b) match
        case (SystemMessage(c1, _, _, _), SystemMessage(c2, _, _, _))               => c1 == c2
        case (UserMessage(c1, i1, _, _, _), UserMessage(c2, i2, _, _, _))           => c1 == c2 && i1 == i2
        case (AssistantMessage(c1, k1, _, _, _), AssistantMessage(c2, k2, _, _, _)) => c1 == c2 && k1 == k2
        case (ToolMessage(id1, c1, _, _, _), ToolMessage(id2, c2, _, _, _))         => id1 == id2 && c1 == c2
        case _                                                                      => false
```

([`kyo/ai/Context.scala:75-81`]). It is `private[kyo]`, used by `Context.merge`'s prefix walk and by the default `Compactor` for dedup (the cache gate's edit-point detection, and the coreEq half of the reduced-reference fold, both below); a custom `Compactor` is not obligated to honor it. `ContextTest` pins that `coreEq` ignores embedding/summary/origin while full-record `==` does not, and that an enrichment-only difference in the receiver's raw prefix still counts as the common merge prefix (no spurious fork) ([`ContextTest.scala:105-121`]).

### Message enrichment: `embedding`, `summary`, `origin`

Every `Message` leaf (`SystemMessage`, `UserMessage`, `AssistantMessage`, `ToolMessage`) carries three trailing DEFAULTED fields, in addition to its content-bearing fields:

```scala
sealed trait Message(val role: Role) derives CanEqual, Schema:
    def content: String
    def embedding: Maybe[Embedding]
    def summary: Maybe[String]
    def origin: Maybe[Context.Origin]
```

([`kyo/ai/Context.scala:102-107`], each leaf at [`kyo/ai/Context.scala:110-142`]). `embedding` and `summary` are once-computed enrichment FACTS the shipped default never populates or reads; they exist as a carrier for a pluggable enrichment mechanism (a richer, user-authored `Compactor`, or code outside the module entirely) to attach a fact to the message that owns it and reuse it later, with NO separate cache structure anywhere in the core: the fact lives on the `Message` value itself and dies with it. `origin` is different: it IS populated by the shipped default, but only on a SYNTHETIC entry a `Compactor` builds to stand for a demoted `raw` range, never on an ordinarily-appended message. All three fields default so every pre-enrichment construction site (every `UserMessage(text, image)`, every test helper) keeps compiling unchanged ([`ContextTest.scala:123-129`]).

`Context.Origin(start: Int, end: Int, since: Int)` is the raw index range a synthetic entry stands for, plus a since-demotion watermark, nested under `Context` (not a third top-level type):

```scala
case class Origin(start: Int, end: Int, since: Int) derives CanEqual, Schema
```

([`kyo/ai/Context.scala:60`]). `start` is the covered unit's id, `end` is exclusive, `since` is the `raw` index at the render boundary that first demoted the unit; `since` is PRESERVED across re-renders of an already-demoted unit rather than re-stamped, so the anti-thrash promotion window (below) does not reset every render ([`Compactor.scala:592-593`], [`Compactor.scala:600-602`], `CompactorTest`'s dedicated regression for this exact bug shape, [`CompactorTest.scala:614-656`]).

Because `Message`'s default case-class equality now includes `embedding`/`summary`/`origin`, any comparison that needs to treat two messages as "the same conversational turn" regardless of enrichment state must go through `coreEq`, never bare `==`: `Context.merge`'s prefix walk and the default `Compactor`'s reduced-reference dedup both do this deliberately (see above and below). `ContextTest` pins the Schema round-trip of every leaf, including `Present` embedding/summary/origin and a divergent `compacted` (a synthetic marker), byte-for-byte ([`ContextTest.scala:131-161`]).

`Embedding` (`kyo.ai.Embedding`) is the type a pluggable enrichment mechanism would populate `Message.embedding` with; it carries the vector plus the `(modelName, dim)` space tag so two embeddings are only ever compared within the same space ([`kyo/ai/Embedding.scala:23-46`]). The shipped default `Compactor` never calls `Completion.embed` and never reads `.embedding`: there is no semantic edge in its graph (see [The lean core mechanism](#the-lean-core-mechanism) below). `Completion.embed` and `Config.embedder` remain a fully public, tested surface for a richer compactor or external code to use directly; they simply have no in-tree caller today.

### `kyo.ai.Config`'s compaction fields and the `Tokenizer` seam

Tuning lives entirely on the ambient `kyo.ai.Config`, not on a standalone `Compactor.Config`: exactly five fields, read live by the default `Compactor` inside `render` via `AI.config`, the same accessor every other model-aware call already uses (never a snapshot captured at `Compactor.init` time, since `init` takes no config at all):

```scala
compactionBudget: Maybe[Int] = Absent,
compactionHighWatermark: Double = 0.7,
compactionLowWatermark: Double = 0.45,
compactionHardLimit: Double = 0.9,
tokenizer: Compactor.Tokenizer = Compactor.Tokenizer.default
```

([`kyo/ai/Config.scala:32-36`]). Each has a builder (`compactionBudget(Int)`, `compactionHighWatermark(Double)`, `compactionLowWatermark(Double)`, `compactionHardLimit(Double)`, `tokenizer(Compactor.Tokenizer)`), all following `Config`'s existing single-arg-named-after-field style ([`kyo/ai/Config.scala:54-77`]):

- **`compactionBudget`.** `Maybe[Int]`, unset by default. `effectiveCompactionBudget` derives the ACTUAL budget when the user hasn't pinned one: `compactionBudget.getOrElse(math.min(modelMaxTokens / 2, 48000))` ([`kyo/ai/Config.scala:83-84`]), so a copy-based model switch (`Config#model`) re-derives the budget against the NEW model's window automatically, while an explicit user pin survives a model switch untouched. `ConfigTest` pins both halves: the derivation scaling with `modelMaxTokens`, and a pinned budget surviving a switch ([`ConfigTest.scala:201-220`]). The builder floors at 1 ([`ConfigTest.scala:222-231`]).
- **`compactionHighWatermark`** and **`compactionLowWatermark`.** Both clamp to `[0, 1]`. The seam's render TRIGGER is always `max(compactionLowWatermark, compactionHighWatermark)` of the budget; the cut's TARGET is always `min(...)` of the budget: swapping the two builder calls in either order can never invert trigger-below-target ([`LLM.scala:491`], [`Compactor.scala:149-150`], [`CompactorTest.scala:337-345`]).
- **`compactionHardLimit`.** Clamps to `[0, 1]`, so it can never be built away to disable the forced-path guard; it is a fraction of the ACTUAL model window, not the budget ([`kyo/ai/Config.scala:70-74`]).
- **`tokenizer: Compactor.Tokenizer`.** Deliberately WITHOUT a `compaction` prefix: it is framed as a model property (how many tokens a message costs on the active model), not compaction-specific tuning, so both the seam's occupancy read and the compactor's own accounting share the identical count ([`kyo/ai/Config.scala:36`], [`kyo/ai/Config.scala:76-77`]).

`Compactor.Tokenizer` is a one-method pure seam:

```scala
trait Tokenizer:
    def count(message: Message): Int
```

([`Compactor.scala:59-60`]). The shipped `Compactor.Tokenizer.default` (`ConservativeTokenizer`) is a deterministic, allocation-cheap character-based estimator, biased to OVER-count (~1 token per 3 chars plus a small per-message envelope, plus a fixed conservative surcharge per user-message image) so that, together with `compactionHardLimit`'s margin, the forced path never lets an over-limit request through ([`Compactor.scala:62-68`], [`Compactor.scala:115-134`]). `CompactorTest` pins the estimator's determinism, its exact formula, and that a plugged custom tokenizer (an exact one-token-per-message stub) genuinely drives occupancy differently ([`CompactorTest.scala:305-323`]), plus the image surcharge ([`CompactorTest.scala:660-671`]). Every graph/scoring/cache tuning value (edge weights, seed shares, the elision threshold, the cache-gate constants, the tail-window sizes) is an INTERNAL constant of the default `Compactor`, documented with its value and rationale at its point of use, never a public field: `referenceWeight = 3.0`, `adjacencyWeight = 1.0`, `restartWeight = 0.15`, `supersessionPenalty = 0.2`, the four seed shares (`seedObjective = 0.35`, `seedTask = 0.20`, `seedTail = 0.25`, `seedUnresolved = 0.15`, `seedSystem = 0.05`), `elisionThreshold = 8000`, `horizonTurns = 10`, `refetchThreshold = 2`, `deepCacheDiscount = 0.1`, `deepWritePremium = 1.25`, `tailTurns = 10`, `tailTokens = 12000` ([`Compactor.scala:72-88`]). The escape hatch for different mechanics is the `Compactor` trait itself: implement `render` fresh, never petition for a further knob.

### The lean core mechanism

`Default.render` (the shipped `Compactor`) runs a fixed pipeline, entirely synchronous and model-free (zero HTTP calls, zero forked fibers) ([`Compactor.scala:144-175`], [`CompactorTest.scala:552-557`]):

1. **Group.** Fuse `raw` into `Segment`s: consecutive messages belonging to one conversational exchange (an assistant call plus its answering tool result, joined by `CallId` regardless of arrival order) collapse into one unit; an assistant call with no matching result yet is `unresolved` and pinned ([`Compactor.scala:179-207`], `CompactorTest`'s unit-fusion and unresolved-pinning leaves, [`CompactorTest.scala:99-120`]).
2. **Structural graph.** Build a row-stochastic `Adj + Ref` graph over units, NO semantic edge: `Adj` links a unit to its immediate predecessor; `Ref` links a unit to the (post-supersession) introducer of an identifier-like token it mentions (backticked, path/dotted/snake/camel-cased, or digit-bearing; no stop-word list, no bare-word matching), hub-discounted by mention count ([`Compactor.scala:254-289`], [`Compactor.scala:661-688`]). Key-based supersession (from a tool's `compactionKey`/`Kind`: a later same-key read supersedes an earlier read, a write supersedes any prior same-key unit, a read after a write does NOT supersede) is applied as a score PENALTY plus a Ref-target repoint, entirely OUTSIDE the graph: it never deletes or rewires an edge, so the transition matrix stays row-stochastic ([`Compactor.scala:209-252`], `CompactorTest` pins the row-stochastic property and the read-after-write non-supersession rule together, [`CompactorTest.scala:149-177`]).
3. **Personalized PageRank.** One fixed 40-iteration power iteration over the graph, seeded by a fixed share split (objective/task/tail/unresolved/system) ([`Compactor.scala:291-345`], [`CompactorTest.scala:192-234`]).
4. **Three-level ladder.** Every demoted unit lands at exactly one of `Verbatim` (not demoted, never in a demotions map), `Reduced`, or `Omitted` ([`Compactor.scala:92-93`], `CompactorTest.scala:369-371`]). The ascending-score cut demotes candidates (never a root, never unresolved, never a currently-promoted unit) to `Reduced` until the view fits the low-watermark target, THEN deepens standing `Reduced` units to `Omitted` under continued pressure (never `Verbatim -> Omitted` directly outside the forced path) ([`Compactor.scala:407-449`]). A `Reduced` unit below the elision threshold masks to a short mechanically-assembled marker; above it, it keeps head/tail content with an elision mark; an `Omitted` unit is a bare recall-recoverable marker ([`Compactor.scala:611-627`], [`CompactorTest.scala:373-393`]).
5. **Reduced-Reference dedup: two independent fold paths**, both mapping a demoted unit to a POINTER at an earlier unit's id rather than a second copy of its content:
   - **`coreEq` fold.** A byte-identical repeat of an earlier unit (same content/role/image/calls/callId via `Context.coreEq`) folds to that earlier unit's id. A near-duplicate (not core-equal) never folds this way ([`Compactor.scala:498-534`], [`CompactorTest.scala:395-462`]).
   - **Origin-identity fold.** A recall exchange (an assistant `recall` call fused with its answering tool result) whose target region is still present folds to THAT region's id. This is the path `coreEq` structurally cannot reach: a recall tail-copy carries a provider-unique `CallId` and a different message shape than the region it reproduces, so it is never core-equal to it ([`Compactor.scala:536-552`], `CompactorTest`'s dedicated leaf proving `coreEq` alone does NOT reach the recall copy, [`CompactorTest.scala:434-460`]).
6. **Forced path.** Entered only when the normal cut's result still exceeds `compactionHardLimit * modelMaxTokens`: a deterministic, NO-model, NO-cache-gating omit pass over non-root candidates (co-pinning anything a live root still references) until the view fits; if even the unshrinkable roots cannot fit, `render` aborts with `AIContextOverflowException` rather than send an over-limit request ([`Compactor.scala:470-496`], [`AIException.scala:60-69`], [`CompactorTest.scala:466-482`]).
7. **Recall.** The default contributes exactly ONE tool, `recall(id: Int)`, bound to the calling instance so it resolves against only that instance's own `raw`, never another session's ([`Compactor.scala:630-647`], `CompactorTest`'s instance-bound leaf, [`CompactorTest.scala:486-525`]). `recall` is `Tool.Kind.Read`, keyless (a re-fetch is a rot-rule signal, below, never a supersession trigger) ([`CompactorTest.scala:527-538`]). A demoted unit recalled `>= refetchThreshold` times SINCE its `origin.since` watermark is PROMOTED (excluded from the next cut, anti-thrash) ([`Compactor.scala:387-405`], [`CompactorTest.scala:614-656`]).
8. **Restoration via `origin.since`.** Region bookkeeping (which units are currently demoted, and since when) is DERIVED at every boundary from `demotedOrigins`, reading `Present(origin)` off the PREVIOUS `compacted` list, never a stored side table ([`Compactor.scala:380-385`]).

**Cut from the core mechanism** (each loss stated honestly, not silently dropped): the judge/verdict subsystem (an LLM-scored ambiguous band) is gone, so there is no cheap-tier-model call anywhere in `render` (`CompactorTest` pins this: an over-budget render completes with no server configured at all, [`CompactorTest.scala:297-303`]); the prepared-summary rung (an LLM-authored extractive summary, background-dispatched and re-validated before adoption) is gone, so the ladder is three levels, not five; the semantic embedding edge (mutual-kNN over landed embeddings) is gone, so the graph is `Adj + Ref` only, with `Present(embedding)` enrichment on a message contributing nothing to it (`CompactorTest` pins that an embedding-carrying transcript produces the identical edge-kind set as one without, [`CompactorTest.scala:179-190`]). What each cut loses: conversational supersession beyond the typed `compactionKey` mechanism, an actual summarized rung between "reduced" and "omitted", and paraphrase-level (non-lexical) liveness detection. The escape hatch for anyone who needs one of these back is implementing `Compactor` directly.

### The LLM seam

The seam lives inside `LLM.eval` (gen) and `streamAgainst` (stream), sharing one private method, `renderView`:

```scala
private def renderView(ai: AI, ctx: Context, config: Config, compactor: Maybe[Compactor[LLM]])(using Frame): Context < (LLM & Async & Abort[AIGenException]) =
    compactor match
        case Present(c) =>
            val budget   = config.effectiveCompactionBudget
            val trigger  = math.max(config.compactionLowWatermark, config.compactionHighWatermark)
            val occupied = ctx.compacted.foldLeft(0)((n, m) => n + config.tokenizer.count(m))
            if occupied >= (trigger * budget) then
                c.render(ctx).map { rebuilt =>
                    val updated = ctx.copy(compacted = rebuilt)
                    ai.setContext(updated).andThen(updated)
                }
            else Kyo.lift(ctx)
        case Absent => Kyo.lift(ctx)
```

([`LLM.scala:485-501`]). Below the occupancy trigger, `ctx` is returned literally UNCHANGED (`Kyo.lift(ctx)`, no render call at all): two consecutive requests below the trigger are byte-identical because they carry the exact same value, not merely an equal one. At or above the trigger, `render` rebuilds `compacted`, the framework installs it (`ctx.copy(compacted = rebuilt)`) and writes it back via `ai.setContext`, so `raw` never appears in the write path and is never shrunk. Both `eval` and `streamAgainst` resolve the compactor with the identical instance-over-scope precedence `genLoop` already applies to config (`session.env.compactor.orElse(scopeEnv.compactor)`) before calling `renderView`, so a named instance compacts identically on `ai.gen` and `ai.stream` ([`LLM.scala:511-512`], [`LLM.scala:199-226`]). The recall tool is registered at the `eval` seam, bound to the calling instance, only when a compactor is effective and the loop is not forcing the result ([`LLM.scala:552-556`]). Every provider backend (HTTP and command-harness) builds its outbound request over `ctx.compacted`, never `raw`: `OpenAICompletion.Request.apply` reads `ctx.compacted` directly ([`kyo/ai/completion/OpenAICompletion.scala:263`]), and `HarnessCompletion.resultInstructions` does the same for the command backends ([`kyo/ai/completion/HarnessCompletion.scala:46`]).

`LLMTest` pins the seam is a true no-op on the Absent path against a committed golden request byte string ([`LLMTest.scala:674-695`]), that it mints no new `Op` and never shrinks the `raw` slot ([`LLMTest.scala:697-738`]), the instance-over-scope precedence both in isolation ([`LLMTest.scala:741-790`]) and end to end against a live outbound request ([`LLMTest.scala:792-828`]). `CompactorTest` pins `render` itself is pure (two renders of the same `Context` produce a byte-identical rebuilt list, [`CompactorTest.scala:68-75`]), that region bookkeeping is genuinely re-derived rather than cached (feeding a render's own output back as `compacted` and re-rendering with the same `raw` reproduces identical decisions, [`CompactorTest.scala:77-87`]), and repeated renders of an identical `(raw, compacted, config)` triple are byte-identical across three consecutive calls ([`CompactorTest.scala:559-568`]).

A change that makes a below-trigger request differ, that demotes a unit without going through `cacheGatePasses` (the normal cut's first-pass Verbatim -> Reduced step) or the forced-path exemption, or that widens what counts as "forced," is a regression against the headline invariant, not a style nit.

## `Agent`

`Agent[+Error, In, Out]` is `opaque type Agent[+Error, In, Out] = Actor[Error, Agent.internal.Message[In, Out], Any]` ([`Agent.scala:29`]). `ask` sends a typed input and awaits the reply under `Async & Abort[Closed | Error]`: a closed mailbox surfaces as `Abort[Closed]`, the behavior's typed error as `Abort[Error]`, never a throw ([`Agent.scala:43-44`]).

`run` mints ONE stable `AI` instance for the agent and hands it to the behavior as its `self`; the behavior calls `self.gen` explicitly, and because the actor's parked continuation keeps the `LLM.State` alive, that instance's conversation persists across asks ([`Agent.scala:15-19`]). All four creation overloads (`run` / `runBehavior`, with and without a leading config + enablements param list) funnel through the private `runImpl` ([`Agent.scala:219-242`]):

```scala
val llmRun =
    config match
        case Present(c) => LLM.run(c)(AI.initWith(behavior))
        case Absent     => LLM.run(AI.initWith(behavior))
Actor.run(Abort.run[AIGenException](llmRun).map(_.getOrThrow))
```

([`Agent.scala:237-241`]). So the wiring is: `LLM` is discharged by `LLM.run`, `Abort[AIGenException]` is re-thrown as a panic (`AIGenException <: Throwable`), and `Async` is consumed by `Actor.run`. The enablements are layered around the behavior in argument order via `AI.enable` (`.handle(AI.enable(enablements*))` inside `runBehavior`), including a `Compactor` ([`Agent.scala:138-139`]). `AgentTest` pins cross-ask conversation persistence (the second ask's gen sees the first ask's turn) ([`AgentTest.scala:263-292`]) and that a failing behavior gen does not strand the asker ([`AgentTest.scala:185-202`]).

## `Config`

`Config` is an immutable copy-on-write settings record; every builder returns a modified copy ([`kyo/ai/Config.scala:17-37`]). Its constructor is `private`, so a config is built via `Config.init`, `Config.default`, or a provider catalog literal, never `new`.

- **Temperature is opt-in.** `temperature` is `Maybe[Double] = Absent`; it is OMITTED from the request when unset (the model uses its own default) and clamped to `[0, 2]` when set (`temperature.max(0).min(2)`) ([`kyo/ai/Config.scala:24`], [`kyo/ai/Config.scala:41`]). There is no `forcedTemperature` / `effectiveTemperature` and no model-name heuristic.
- **Optional builders.** `maxTokens(Int)` and `seed(Int)` are also `Maybe`; an internal `seed(Maybe[Int])` exists for cross-run seed derivation ([`kyo/ai/Config.scala:25-26`], [`kyo/ai/Config.scala:42-43`], [`kyo/ai/Config.scala:93`]).
- **`embedder`.** `embedder(config: Config): Config` pairs a DIFFERENT provider config for embeddings. `Completion.embed` itself does not read this field; a caller that embeds resolves `embedder.getOrElse(this)` itself and embeds through THAT provider's backend ([`kyo/ai/Config.scala:31`], [`kyo/ai/Config.scala:86-90`]). `Absent` (the default) embeds with the chat config itself. Nothing inside kyo-ai calls `Completion.embed` today (the shipped `Compactor` never does); this field is a fully public, tested surface for a richer external compactor.
- **Compaction.** Five fields (`compactionBudget`, `compactionHighWatermark`, `compactionLowWatermark`, `compactionHardLimit`, `tokenizer`); see [`kyo.ai.Config`'s compaction fields](#kyoaiconfigs-compaction-fields-and-the-tokenizer-seam) above.
- **Default selection.** `Config.default` probes provider markers and API keys (system properties first, then env vars) via `kyo.System`, never raw `sys.props` / `sys.env`, and falls back to Anthropic ([`kyo/ai/Config.scala:115-132`]).

There are **nine** providers in `Provider.all` ([`kyo/ai/Config.scala:168-169`]): `Anthropic`, `OpenAI`, `DeepSeek`, `Gemini`, `Groq`, `Baseten`, `OpenRouter`, `ClaudeCode`, `Codex`. `Config.default` checks the provider marker/key names in default-candidate order, preferring `CLAUDE_CODE`, then `CODEX`, then HTTP/API provider keys ([`kyo/ai/Config.scala:172-173`]). Each provider exposes its model catalog as named pure `Config` constants (key absent, filled at use), and `default` points at the recommended entry ([`kyo/ai/Config.scala:193-333`]).

### `Provider.small`: the cheap-tier selector, currently without an in-tree consumer

`Provider` exposes `def small: Config = default`, a CONCRETE method with a `= default` fallback, mirroring the existing `def default: Config` accessor ([`kyo/ai/Config.scala:160-164`]). Concrete (not abstract) is deliberate: `Config.Provider` is a public, unsealed `abstract class` with a public constructor that external code CAN subclass (a self-hosted OpenAI-compatible endpoint passing `Completion.openAI`), and a NEW abstract member would break every such subclass at source level; an unaware subclass instead degrades safely by resolving to `default` (costlier, never wrong). Each of the nine built-in catalog objects overrides it with its real cheap entry (`Anthropic.small = haiku_4_5` [`kyo/ai/Config.scala:205`], `OpenAI.small = gpt_5_nano` [`227`], `DeepSeek.small` [`239`], `Gemini.small = gemini_2_5_flash_lite` [`252`], `Groq.small` [`266`], `Baseten.small` [`278`], `OpenRouter.small` [`302`], `ClaudeCode.small = haiku` [`316`], `Codex.small` [`332`]).

An earlier shipped `Compactor` called `small` to pick its background judge/summarizer's model; that consumer is now CUT along with the judge/summarizer subsystem, so `small` currently has no caller inside kyo-ai. It remains a fully public, tested API for a user's own model-tiering logic (or a richer, user-authored `Compactor` that wants a cheap-tier fallback model) ([`ConfigTest.scala:164-181`]). When adding a tenth catalog provider or a new catalog entry, add the matching `override def small` alongside `default` regardless of whether anything in-tree currently calls it; a subclass that forgets it merely runs more expensively on `default`, never incorrectly.

## Wire layer: `Completion`

`Completion` is the provider-backend contract ([`kyo/ai/completion/Completion.scala:22-29`]):

```scala
def apply(config: Config, context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]], resultSchema: Maybe[JsonSchema] = Absent)(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException])
```

`Completion.Reply(messages: Chunk[Message], usage: Maybe[Usage])` widens the old bare-`Chunk[Message]` result with provider-reported usage ([`kyo/ai/completion/Completion.scala:58-68`]). Every HTTP backend still decodes and populates `usage` (`inputTokens`/`outputTokens`/`cachedInputTokens`) on every reply ([`kyo/ai/completion/OpenAICompletion.scala:49-56`]), but nothing inside kyo-ai reads it today: an earlier usage-calibrated tokenizer estimator is CUT (the shipped tokenizer is purely character-based, no provider feedback loop). `usage`/`cachedInputTokens` stay `Absent` when a backend does not report them; both fields are a fully public, tested surface for external telemetry or a richer compactor, not a dead field slated for removal.

HTTP providers implement `apply` by posting their native request and decoding the reply; command harnesses implement it through their native event or stream-json output. Returning `Chunk[Message]` lets command harnesses append the transcript delta they produced, while the HTTP providers still return a singleton assistant message. Transport failures surface as `Abort[HttpException]`, never `Abort[Throwable]`; a missing key or undecodable reply as the typed `Abort[AIGenException]` leaves ([`kyo/ai/completion/Completion.scala:9-21`]).

Three implementation families, reached through `Config.Provider.completion`, never constructed by users ([`kyo/ai/completion/Completion.scala:70-82`]):

- `OpenAICompletion`: `POST {apiUrl}/chat/completions`, `content-type: application/json`, `Authorization: Bearer <key>` (plus `OpenAI-Organization` when present); covers OpenAI and the five compatible providers (DeepSeek/Gemini/Groq/Baseten/OpenRouter) ([`kyo/ai/completion/OpenAICompletion.scala:11`], [`kyo/ai/completion/OpenAICompletion.scala:30-58`]).
- `AnthropicCompletion`: `POST {apiUrl}/messages`, `x-api-key: <key>`, `anthropic-version: 2023-06-01`.
- `ClaudeCodeCompletion` and `CodexCompletion`: command-backed harness adapters sharing the `HarnessCompletion` base class ([`kyo/ai/completion/HarnessCompletion.scala:10`]). Claude Code receives SDK `stream-json` input and emits `stream-json` output, exposing enabled Kyo tools through a private localhost MCP bridge that calls back into Kyo tool handlers. Codex uses `codex app-server`, injecting prior context with `thread/inject_items` and reading turn events until `turn/completed`.

The result tool has the reserved name `Completion.resultToolName` (`"result_tool"`); when `resultSchema` is `Present`, the backend substitutes it for the tool's opaque `Structure.Value` input schema so the wire parameter schema exposes the real thought-aware properties ([`kyo/ai/completion/Completion.scala:84-87`]).

### `Completion.embed` and the embeddings provider split

`Completion.embed(config, inputs)` is a trait method with a DEFAULT that fails `Abort[AIEmbeddingUnsupportedException]`; only a backend whose provider genuinely exposes an embeddings endpoint overrides it ([`kyo/ai/completion/Completion.scala:31-40`]). `OpenAICompletion.embed` overrides it, but gates the override PER PROVIDER, not per backend family: only `Config.OpenAI` and `Config.Gemini` are `embeddingsCapable` (compared by `.name`, a plain `String`, since `Config.Provider` carries no `CanEqual` under this build's `-language:strictEquality`); DeepSeek/Groq/Baseten/OpenRouter share the SAME `OpenAICompletion` backend for chat but fall through to the trait default for `embed`, because none of them documents an `/embeddings` route ([`kyo/ai/completion/OpenAICompletion.scala:60-96`]). This split was verified against each provider's current documentation and is NOT a per-backend-family shortcut: adding a new OpenAI-compatible provider to the catalog does not automatically grant it `embed`; it must be added to `embeddingsCapable` only if its documented surface actually has the route. `AnthropicCompletion`, `ClaudeCodeCompletion`, and `CodexCompletion` never override `embed`, so they always return `AIEmbeddingUnsupportedException`.

`embed`'s successful result is `Chunk[Embedding]`, one vector per input in order, each tagged `(modelName, dim)` so two embeddings are comparable only within the same space; a `(model, dim)` mismatch is a no-edge, never a cross-space comparison ([`kyo/ai/Embedding.scala:23-46`]). As noted above, no in-tree caller invokes `embed` today: the shipped `Compactor`'s graph is structural (`Adj + Ref`) only. `embed` and `Message.embedding` exist as a matched pair for a richer compactor to populate and reuse an embedding without recomputing it.

## The exception hierarchy

`AIException` is a `sealed abstract class ... extends KyoException`, organized by the two operations that produce failures ([`AIException.scala:18-19`]):

- `AIGenException` (sealed trait): the failure set of a generation, the row of `LLM.run`'s residual; raised while the `Gen` op's eval loop runs ([`AIException.scala:22`]).
- `AIStreamException` (sealed trait): the failure set of a stream, carried inside the returned `Stream`'s effect row; raised lazily as the stream is consumed ([`AIException.scala:25`]).

A leaf mixes in every operation it can occur in. `AIMissingApiKeyException` and `AITransportException` mix in BOTH (either operation reaches the provider) ([`AIException.scala:27-38`]). Gen-only leaves: `AIEvalExhaustedException`, `AIInvalidThoughtException`, `AIDecodeException`, `AIContextOverflowException` (the forced compaction path fails loudly rather than sending an over-limit request), `AIEmbeddingUnsupportedException` ([`AIException.scala:40-52`], [`AIException.scala:60-75`]). Stream-only leaves: `AIStreamDeltaException`, `AIStreamIncompleteException` ([`AIException.scala:77-83`]). `AICrossRunException` (misuse) and `AIMeterClosedException` (impossible-state) are `AIException`s but in NEITHER operation set: they panic rather than ride a row ([`AIException.scala:85-99`]). `AIExceptionTest` pins the full lattice with `summon[... <:< ...]`, including the two compaction leaves, and asserts cross-run/meter-closed are not gen/stream failures ([`AIExceptionTest.scala:4-51`]).

When adding a failure, add a leaf to `AIException.scala` under the right operation trait(s) and route to it; keep every message string on the leaf, and never let a non-module exception (a raw `HttpException`) ride a public row, map it to `AITransportException` at the eval/stream boundary ([`LLM.scala:522`], [`kyo/ai/completion/Completion.scala:102`]).

## Unsafe boundary

This module has no `AllowUnsafe` sites and no `import AllowUnsafe.embrace.danger` in its sources. Ids come from the run's threaded `State.nextId`, never a process-global counter ([`LLM.scala:102-107`]), so there is no module-level mutable state to bridge. The only `java.lang.ref` use is `internal.AIRef extends WeakReference[AI]`, a GC-reclaimability mechanism for `LLM.State.instances` keys, not an unsafe-tier API ([`LLM.scala:629-640`]). `Compactor` holds NO mutable state of its own: `render` is a pure function of its `Context` and `Config` argument, so there is nothing to bridge with `AllowUnsafe` there either. When threading new state, keep it in `State` and the `Op` GADT, or derive it fresh from `(raw, compacted)` the way region bookkeeping already is; do not introduce a process-global counter, an `AllowUnsafe` parameter, or a new stored cache the transcript alone could reproduce.

## The public-surface lock: what's locked, what stays `private[kyo]`

What IS public: the enablement itself (`Compactor[-S]`, `Compactor.init`, `Compactor.Tokenizer`), the five compaction fields plus their builders on `kyo.ai.Config` (`compactionBudget`, `compactionHighWatermark`, `compactionLowWatermark`, `compactionHardLimit`, `tokenizer`, and `effectiveCompactionBudget` as `private[kyo]`), the two-list `Context(raw, compacted)` shape and `Context.Origin`, the `Message` enrichment fields (`embedding`, `summary`, `origin`), the env carrier (`AIEnv.compactor`), the embedding subsystem (`Completion.embed`, `kyo.ai.Embedding`, `Config.embedder`), the cheap-tier selector (`Config.Provider.small`), the tool supersession metadata (`Tool.init`'s `kind`/`compactionKey`, `Tool.Kind`), usage capture (`Completion.Usage`, `Completion.Reply`), and the two compaction `AIException` leaves. What stays `private[kyo]`: every derived-computation internal `Compactor.internal` carries (`group`, `deriveGraph`, `score`, `seedVector`, `roots`, `tailUnits`, `cut`, `cacheGatePasses`, `forced`, `project`, `renderMarker`, `duplicateTargets`, `recallTarget`, `demotedOrigins`, `refetchCount`, `promotionSet`, the `Segment`/`Building`/`Graph`/`Edge`/`EdgeKind`/`Recall`/`Level` types, and the internal tuning constants) ([`Compactor.scala:70-716`]); `Context.coreEq` (used only by `merge` and the default `Compactor`, not a lock symbol a custom `Compactor` is obligated to honor). There is no `Compactor.Config` type at all: every prior standalone tuning knob beyond the five that survived onto `kyo.ai.Config` is now an internal constant with no public accessor. A change that promotes one of these internals to a public return type, or that reintroduces a stored, un-derivable persisted field anywhere in the mechanism, is a design-surface change: raise it, do not slip it in as an implementation detail. `Compactor.init` (nullary) is the only constructor surface; there is no builder-taking overload.

## Test patterns

All tests extend `kyo.test.Test[Any]` ([`LLMTest.scala:9`], and every `*Test.scala` in the suite). Tests follow the 1:1 source-to-test rule (`LLM.scala` -> `LLMTest.scala`, `Compactor.scala` -> `CompactorTest.scala`, `Agent.scala` -> `AgentTest.scala`, etc.); the module-wide invariants are folded into the per-source `*Test.scala` files. There is no `LLMInvariantsSpec` (a stale `LLMInvariantsTest.xml` may persist as a build artifact under `jvm/target`; ignore it).

### `TestCompletionServer`

Tests drive a real in-process HTTP server implementing the OpenAI and Anthropic completion wire protocols plus an embeddings route, bound on an OS-assigned ephemeral port within a `Scope` ([`TestCompletionServer.scala:6-26`], [`TestCompletionServer.scala:94-125`]). It serves `POST /v1/chat/completions`, `POST /v1/messages`, and (on the non-streaming server) `POST /v1/embeddings`, each reading the raw request JSON, capturing it, and returning the next scripted response ([`TestCompletionServer.scala:104-120`]). Two modes: `TestCompletionServer.run` (non-streaming JSON) and `runStreaming` (SSE on both chat endpoints) ([`TestCompletionServer.scala:94-102`]). Scripting is deterministic and per-test: `server.enqueueBody(json)` / `server.enqueueStream(chunks)` before the client call, popped one per request ([`TestCompletionServer.scala:29-34`], [`TestCompletionServer.scala:130-142`]). `server.captured` reads the ordered `Captured(path, body)` records for wire-shape assertions ([`TestCompletionServer.scala:37-38`], [`LLMTest.scala:399-404`]).

`server.awaitCaptured(pred)` suspends until a captured request satisfying `pred` arrives, backed by a `Channel[Captured]` every route offers onto in capture order, never a poll of `captured` ([`TestCompletionServer.scala:47-50`]). This is the sanctioned way to assert something about the outbound gen request when more than one compactor could plausibly reach the wire: `LLMTest` uses it to observe the instance compactor's rendering on the main gen request, disambiguated from the scope compactor by tagging each rendering with a distinct marker string ([`LLMTest.scala:792-828`]).

### Pointing tests at the server and scripting a reply

`LLMTest`/`CompactorTest` define the shared helpers (each per-source test that needs them redeclares its own):

```scala
def serverConfig(baseUrl: String): Config =
    Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 128000).apiUrl(baseUrl)

def resultToolBody(envelopeJson: String): String = // an OpenAI body whose assistant calls result_tool with envelopeJson
```

([`LLMTest.scala:18-30`]). The eval loop always extracts from `result_tool`, so script the structured reply by wrapping it (`{"resultValue": <value>}` for a plain field, with `openingThoughts`/`closingThoughts` keys when thoughts are enabled) ([`LLMTest.scala:28-30`], [`Thought.scala:142-143`]). `CompactorTest`'s own `renderWith` helper runs the default compactor's `render` under `LLM.run(config)` with NO server at all, since `render` is model-free ([`CompactorTest.scala:29-31`]).

### Deterministic `Latch`/`Channel` idioms (never a sleep)

kyo-ai's tests never use `Thread.sleep` or a poll loop to wait for a concurrent event; two Kyo-native idioms cover it:

- **`Latch`** signals "reached this point" across concurrent branches. `LLMTest`'s cancellation test forks a branch that mutates isolated state, releases a `Latch.init(1)` the instant it has, then suspends forever (`Async.never`); the racing branch awaits the latch before winning the race, guaranteeing the mutation happened before the interrupt fires, with no timing assumption ([`LLMTest.scala:197-221`]).
- **`Channel`** is the multi-event version: `TestCompletionServer`'s `arrivals: Channel[Captured]` (see `awaitCaptured` above) lets a test wait for the Nth (or a specific) HTTP arrival among several concurrent requests, deterministically, in arrival order ([`TestCompletionServer.scala:24`], [`TestCompletionServer.scala:47-50`]).

Reach for `Latch` for a single one-shot signal and `Channel` for an ordered stream of events; neither ever substitutes a `Clock.sleep`/`Async.sleep` guess for a real synchronization point.

### The validation-first replay harness

`CompactorReplayHarness` is a test-only, model-free measurement of a dumb keep-the-tail-plus-one-summary BASELINE against the full `Compactor` default over six synthetic sessions, embedded as literal `Chunk[Message]` data (no recorded corpus, no file I/O, no network) ([`CompactorReplayHarness.scala:7-25`]). Both arms drive through the SAME driver (`replay`, [`CompactorReplayHarness.scala:352-372`]) and score through IDENTICAL metric functions (task-success predicates, integer token cost, re-fetch rate) ([`CompactorReplayHarness.scala:121-157`]), so the comparison is not rigged toward the full design: session 6 is a short linear axis where recency alone suffices and the baseline ties the full design ([`CompactorReplayHarness.scala:322-340`]). `decide` folds the six scoreboards into a genuinely two-valued `GoNoGo` verdict: Go only when the full design never loses task-success where the baseline succeeds, stays within an epsilon token budget, never regresses re-fetch, and strictly wins somewhere ([`CompactorReplayHarness.scala:166-188`]). `CompactorReplayTest` pins each session's specific claim (supersession beats a stale re-serve, an oversized result is elided and recoverable, a frontier-drift session fits the forced path under its window, a non-root unit survives by graph-liveness not root-pinning, the short session ties) plus a REACHABLE no-go branch (a synthetic baseline-wins scoreboard) and the real six-session verdict ([`CompactorReplayTest.scala:8-133`]). If a genuinely code-level change regresses the real verdict to `NoGo`, that is the harness doing its job, not a flaky test to relax; the harness's own doc names the design's ship-baseline-plus-recall escape hatch as the sanctioned response, routed to a human decision, never a silent test weakening ([`CompactorReplayHarness.scala:30-32`]).

### What to assert

Assert concrete values, not just types or non-emptiness: the scripted reply round-trips to a concrete `A` ([`LLMTest.scala:498-509`]), and the captured request body contains the override temperature and not the scope temperature ([`LLMTest.scala:400-404`]). Compile-time row ascriptions (`val x: Int < LLM`, `def y: Int < (Async & Abort[AIGenException])`) are the proof that the rows are exact ([`LLMTest.scala:498-509`]).

## Conventions

### Cross-platform discipline

Source and tests default to `shared/`, and today ALL of kyo-ai's tests live there: there is no platform-specific test file in the module. Never move a test into a platform subtree to dodge a platform cost; a platform split is justified only by a primitive with no cross-platform Kyo wrapper for the specific property under test, and it must stay the sanctioned exception, not the norm.

### Effect-row precision

Never widen the effect row of a `< LLM` computation to include `Async`; `Async` belongs only on `LLM.run`'s residual (the `Gen`/`Stream` interpretation) ([`LLM.scala:125-142`]). A new op goes in `LLM.internal.Op`, gets a `runWith` arm that introduces no `Async` for any op but `Gen`/`Stream`, and is suspended via `ArrowEffect.suspend` ([`LLM.scala:409-432`]). Compaction adds NO new op; a new compaction-adjacent capability should extend `Compactor`'s own `render`/`tools` methods (still `< (LLM & Async & Abort[AIGenException] & S)` at most, matching `render`'s own row) rather than mint an `Op`, unless it genuinely needs to thread through `LLM.State` itself ([`Compactor.scala:25-38`]). A `Mode.apply`'s `gen` parameter row is exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`, not wider ([`Mode.scala:20-22`]).

### `private[kyo]` over `protected`

Use `private[kyo]` for cross-package visibility; there is no `protected` in this module. `AI`'s constructor and fields, the `LLM` op interface, `State`'s constructor, and every `Compactor.internal` type are all `private[kyo]`.

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
sbt 'kyo-aiJVM/testOnly kyo.CompactorReplayTest'
```

Building auto-formats; re-read any file you edit after building, formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

## Decision checklist: before adding or changing X (kyo-ai)

In addition to the root checklist:

1. **New `Op`.** Is it a final `case class` (data) or `case object` (field-less)? Does its `runWith` arm thread `State` and introduce no `Async` (only `Gen`/`Stream` may)? Does the reply type in `Op[A]` match the `ArrowEffect.suspend` return? Does it appear in `crossRunFailure` if it targets an instance? [`LLM.scala:65-95`, `LLM.scala:611-627`]
2. **Eval-loop change.** Does every non-`Gen` path stay `< LLM` (no `Async`)? Does the `Gen` residual stay `A < (LLM & Async & Abort[AIGenException])`? Does `summon[NotGiven[LLM <:< Async]]` still hold? [`LLMTest.scala:477-485`, `LLMTest.scala:830-838`]
3. **New enablement kind or call.** Does it implement `AI.Enablement[S]`'s two `enableIn` methods (`AIEnv` and `AISession`)? Is it reached only through `AI.enable` / `ai.enable` (never a per-type binder)? Does the row carry `S` until the run boundary? [`AI.scala:40-54`, `AI.scala:121-127`]
4. **Config or compactor override.** Is an instance override a `Maybe[T]` (`Absent` = inherit, `Present` = override)? Does `genLoop`'s merge keep `Present` winning over the scope, while a mode `withConfig` still layers on top (config only)? [`LLM.scala:508-517`, `LLMTest.scala:393-428`, `LLMTest.scala:741-828`]
5. **New `Mode`.** Is `apply`'s `gen` row exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`? Does the mode receive `ai` and read/write that instance only? [`Mode.scala:20-22`]
6. **New `Agent` overload.** Does it delegate to `runImpl`, minting ONE stable instance via `AI.initWith(behavior)`, discharging with `LLM.run`, re-throwing `Abort[AIGenException]` via `getOrThrow`, and handing to `Actor.run`? [`Agent.scala:219-242`]
7. **New completion backend.** Does it match the result tool by `Completion.resultToolName` and substitute `resultSchema` when `Present`? Does it surface transport failures as `Abort[HttpException]`, never `Abort[Throwable]`? Does it build its outbound request over `context.compacted`, never `raw`? If it exposes a real embeddings route, does it override `embed` and gate it by the exact provider(s) that document the route (never a whole backend family)? Is it reached through a new `Config.Provider` in `Provider.all`, with a matching `override def small`? [`kyo/ai/completion/Completion.scala:70-82`, `kyo/ai/completion/OpenAICompletion.scala:60-96`, `kyo/ai/Config.scala:168-169`]
8. **New failure.** Is there a leaf in `AIException.scala` under the right operation trait(s), with its message on the leaf, mapped from any raw `HttpException` at the eval/stream boundary? Is a misuse/impossible-state panic kept off both rows? [`AIException.scala`, `LLM.scala:522`]
9. **`Compactor` internal-state change.** Does it stay `private[kyo]`? Is any new piece of bookkeeping genuinely underivable from `(raw, compacted)` (not something `group`/`deriveGraph`/`score`/`demotedOrigins` could recompute)? Does it avoid reintroducing a stored side table (a persisted `CompactorState`-shaped record, a per-instance cell, a `Memo`) the current design deliberately eliminated? [`Compactor.scala:70-716`, `kyo/ai/Context.scala:56-60`]
10. **`Compactor` rendering/gating change.** Does the seam stay a literal no-op below the occupancy trigger (same `Context` value, not merely an equal one)? Is every normal-cut `Verbatim -> Reduced` demotion still routed through `cacheGatePasses`, never applied unconditionally? Does the forced path stay the sole ungated exemption, and does an unfittable transcript still abort with `AIContextOverflowException` rather than send? [`LLM.scala:485-501`, `Compactor.scala:407-468`, `CompactorTest.scala:68-75`, `CompactorTest.scala:466-482`]
11. **New test.** Does it extend `kyo.test.Test[Any]`, use `TestCompletionServer` (not a live endpoint) for anything that reaches the wire, assert concrete values, place each `enqueueBody`/`enqueueStream` before the consuming client call, wait via `Latch`/`Channel`/`awaitCaptured` (never a sleep), and live in `shared/src/test`? [`TestCompletionServer.scala`, `LLMTest.scala`]

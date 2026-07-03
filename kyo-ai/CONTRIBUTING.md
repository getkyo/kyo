# Contributing to kyo-ai

Module-specific guide for kyo-ai. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-ai: the explicit-instance model, the `LLM` `ArrowEffect` and its threaded `State`, the per-run owner and cross-run guard, the unified enablement surface, the scope/instance env-merge rule, the eval and stream loops, the agent wiring, the provider wire layer, and the test conventions.

**The headline invariant:** a program typed `A < LLM` is a pure value. `LLM` is a custom `ArrowEffect[LLM.internal.Op, Id]`, NOT a subtype of `Async` ([`LLM.scala:26`]). Its ops carry data and read/append per-instance conversation histories in one threaded `State`. The single op that reaches the world is `Gen` (and its sibling `Stream`): its handler interpretation runs the eval loop, and that is the ONLY place `Async & Abort[AIGenException]` enter, riding out on `LLM.run`'s residual ([`LLM.scala:125-141`], [`LLM.scala:228`]). A row `A < LLM` must never include `Async`; no change to the eval loop should push `Async` into the `LLM` row. `LLMTest` pins this with `summon[NotGiven[LLM <:< Async]]` ([`LLMTest.scala:409-417`]).

## What kyo-ai is

kyo-ai is a typed effect for first-class conversations with a language model. The public surface, all top-level in `package kyo`:

- `LLM`, the `ArrowEffect`, and its `run` (the discharge boundary).
- `AI`, the first-class conversation instance (both the identity value `ai` and the namespace of operations).
- `Agent`, an opaque alias over `kyo.Actor` that pairs the LLM surface with the actor model.
- `Prompt`, `Tool`, `Thought`, `Mode`, the four composable enablement kinds (`AI.Enablement`).
- `AIEnv` and `AISession`, the generation environment and the per-instance state record.
- the `AIException` hierarchy (`AIGenException`, `AIStreamException`, and their leaves).

The settings/content value types `Config`, `Context`, `Image` live in package `kyo.ai`, but they are surfaced into `kyo` so `import kyo.*` reaches everything: the `AI` companion `export`s `kyo.ai.Config` / `Context` / `Image` ([`AI.scala:36-38`]), so a user writes `AI.Config`, `AI.Context`, `AI.Image`; `LLM` also `export`s `Config` ([`LLM.scala:31`]).

All code lives in `shared/src/main/scala` (`kyo/` for the effect surface, `kyo/ai/` for the value types, `kyo/ai/completion/` for the wire backends). All tests live in `shared/src/test/scala` and run cross-platform (JVM, Scala.js, Scala Native, Wasm). The `jvm/`, `js/`, `native/`, and `wasm/` trees carry no `.scala` source.

## The explicit-instance model

There is no ambient "current" instance and no `AI.use`. A behavior receives its instance explicitly and calls `self.gen`; the eval loop threads the target `AI` explicitly (`reads ai.context, appends to ai`), so nothing is ambient ([`LLM.scala:317`]). Two surfaces exist:

- **One-shot.** `AI.gen[A]` mints a fresh ephemeral instance, generates against it, then discards its slot via `ai.reset` on success, so two one-shots never share state ([`AI.scala:80-84`]). `AITest` pins that a successful one-shot leaves `State.instances` empty (and stays empty under concurrent gens, and a transport abort fails the run so nothing leaks) ([`AITest.scala:274-300`]).
- **Named instance.** `AI.init` mints a persistent slot whose conversation, enablements, and config override survive across turns within one `LLM.run`; `ai.gen[A]` runs against that slot ([`AI.scala:55-56`], [`AI.scala:166-167`]).

`AI` is a reference object, NOT an `opaque type` over a `Long`:

```scala
final class AI private[kyo] (private[kyo] val id: Long, private[kyo] val owner: AnyRef):
    private[kyo] val ref: LLM.internal.AIRef = new LLM.internal.AIRef(this)
```

([`AI.scala:16-17`]). The id is drawn from the run's threaded `State` counter (no process-global mutable state), so identity is scoped to one `LLM.run` and restarts per run; `LLMTest` pins that within a run successive `init` ids are `0, 1, ...` and a fresh run restarts at `0` ([`LLMTest.scala:500-513`]). Every method on `AI` is a thin value over the `LLM` effect surface: `AI` summons no `ArrowEffect` op directly, only `LLM`'s `private[kyo]` interface ([`AI.scala:19-26`], [`LLM.scala:243-275`]).

### Per-run owner and the cross-run guard

Each instance remembers the run that created it (`owner`, a fresh `AnyRef` per run, object identity, no counter) ([`LLM.scala:56-59`], [`LLM.scala:106-107`]). Using an instance inside a different `LLM.run` is misuse: it cannot address that run's slots. `crossRunFailure` inspects every op that targets an instance and, when `ai.owner ne state.owner`, raises `AICrossRunException`; the panic is the handler arm's result, so it rides `runWith`'s residual `Abort` row (not the `LLM` continuation) and aborts the whole computation ([`LLM.scala:65-95`]). `AICrossRunException`'s message points the user at `ai.snapshot` / `AI.recover` ([`AIException.scala:62-70`]). `LLMTest` pins that the guard fires for EVERY targeting op (read, set, gen, stream, discard, session, setSession), not just one ([`LLMTest.scala:531-550`]).

To carry an instance across runs deliberately, capture it with `ai.snapshot` (returns its `AISession`) and restore it with `AI.recover(session)` in the new run ([`AI.scala:69-71`], [`AI.scala:230-231`]); `AITest` pins a round-trip of history + an enabled tool + a config override across two runs ([`AITest.scala:201-223`]).

## The LLM effect

### Effect definition and the op GADT

`LLM` is `sealed trait LLM extends ArrowEffect[LLM.internal.Op, Id]` ([`LLM.scala:26`]). The op GADT `LLM.internal.Op[A]` indexes each op's reply by `A`, so the handler continuation needs no reply-side cast ([`LLM.scala:399`]). It has exactly **13** subclasses ([`LLM.scala:400-415`]); field-less ops are `case object`s, the rest carry data:

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

There is **no `SetCurrent`** op and no ambient-current concept. `LLMTest` pins the data-bearing ops carry their fields ([`LLMTest.scala:472-484`]). `Gen` is the one op that reaches the world; its arm runs `genLoop` under a nested `runWith` against the live state, and `Async & Abort[AIGenException]` enter there ([`LLM.scala:125-132`]). `Stream`'s arm projects the SSE response and is read-only (no instance write-back), so the threaded state passes through unchanged ([`LLM.scala:133-141`]).

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

- `instances` is keyed by `internal.AIRef`, a `WeakReference[AI]` whose equality/hash are by the AI's stable `id`, so a dropped `AI` becomes GC-reclaimable while its key still matches its slot ([`LLM.scala:417-428`]). `State.pruned` sweeps slots whose `AI` was collected, run when minting a new instance so an unbounded mint stream never accumulates dead slots ([`LLM.scala:51-53`], [`LLM.scala:102-107`]).
- `nextId` is the monotonic id counter `Init` draws from; `SetState` never lowers it (`math.max`), so a `forget`/`fresh` rollback keeps the high-water id and a slot key is never reused ([`LLM.scala:116-120`]).
- `owner` stamps every instance for the cross-run guard ([`LLM.scala:56-59`]).
- `env` is the scope `AIEnv` (see below), read by `Op.Env` and replaced by `Op.SetEnv` ([`LLM.scala:108-111`]).

`State.empty(config)` seeds a `Present(config)` scope env and a fresh `owner` ([`LLM.scala:56-60`]).

### `run` and its residual

`LLM.run[A, S](v: A < (LLM & S)): A < (S & Async & Abort[AIGenException])` threads a fresh `State.empty(config)` through `runWith` and discards the final state ([`LLM.scala:228-229`]). Three overloads (`run(v)`, `run(f: Config => Config)(v)`, `run(config)(v)`) all funnel through `runWith`; the first two resolve `Config.default` under `Sync` first ([`LLM.scala:228-237`]). `runTuple` keeps the final state with the value for tests and transcript access (`private[kyo]`) ([`LLM.scala:239-241`]). `runWith` is NOT inline ([`LLM.scala:82-83`]).

### The eval loop

`genLoop(ai, schema)` is the `Gen` interpretation ([`LLM.scala:313-351`]). It:

1. Merges the instance's own env onto the scope env for the duration of the eval, then restores it (so the effective surface is `scope ++ instance`): config is `session.env.config.orElse(scopeEnv.config)`, and the instance's prompt/tools/thoughts/modes are layered on ([`LLM.scala:318-326`]). This is the merge that makes a `Present` instance config override the scope config; an `Absent` instance config inherits the scope's.
2. Enables `Prompt.internal.defaultGuidance` (generic structured-output guidance, not part of the empty prompt) ([`LLM.scala:327`], [`Prompt.scala:92-104`]).
3. Loops: each iteration calls `eval[A](ai, forceResult = iterations >= config.maxIterations)`, wraps transport `HttpException` into `AITransportException`, passes the result through `Mode.internal.handle` (the mode pipeline), and on `Present(r)` yields, on `Absent` re-loops with the seed modulated (`c.seed.map(_ * 31)`) until `iterations >= config.maxIterations * 2`, where it aborts with `AIEvalExhaustedException` ([`LLM.scala:329-345`]).

`eval` posts one completion request: it assembles the tools (plus the result tool), the thought-aware result schema, the enriched context, runs the provider completion under the config meter and a `Retry[HttpException](config.retrySchedule)`, appends the reply, dispatches any tool calls, and extracts the structured result directly from the `result_tool` call arguments ([`LLM.scala:353-392`]). A closed meter under an in-flight gen panics with `AIMeterClosedException` (an impossible-state, off both rows) ([`LLM.scala:374-377`], [`AIException.scala:72-76`]).

### The stream loop

`AI.stream[A]` / `ai.stream[A]` suspend `Op.Stream`, whose handler runs `streamAgainst` ([`LLM.scala:259-263`], [`LLM.scala:133-141`]). `streamAgainst` asks `config.provider.completion.streamFragments` for raw JSON fragments of the `{ resultValue: ... }` envelope and accumulates the fragments. For `String`, it emits decoded text chunks whose concatenation is the final text. For other result types, it emits each complete decoded element from the result array exactly once ([`LLM.scala:147-225`]). HTTP providers implement fragments with SSE result-tool deltas; command harnesses use their native event or stream-json output. The returned `Stream` carries its failures typed in its element row as `Abort[AIStreamException]`: a malformed delta is `AIStreamDeltaException`, an end without a decodable value `AIStreamIncompleteException`, a transport error `AITransportException` ([`LLM.scala:181-216`]). A missing API key is the one failure raised eagerly (before the `Stream` value), as `AIMissingApiKeyException` on the run boundary ([`LLM.scala:164-167`]).

### The `LLM.isolate` given

`given isolate: Isolate[LLM, Async, LLM]` lets `Async.fill`/`foreach`/`race` fork over a bare `LLM` row ([`LLM.scala:285-305`]). `Keep = Async` is exact: the in-tree parallel sites require `Isolate[LLM, Abort[E] & Async, LLM]`, and for the `E = Nothing` body (whose transport errors the eval loop already recovers) that reduces to `Isolate[LLM, Async, LLM]`, which a wider `Keep` (`Abort[Any] & Async`) cannot satisfy by `Keep` contravariance ([`LLM.scala:277-284`]). `capture` reads the live `State` via `Op.GetState`; `isolate` discharges `runWith`'s residual `Abort[AIGenException]` inside the fork with `getOrThrow`, so an unrecovered fork generation failure surfaces as a fiber panic; `restore` merges fork-born instance contexts back via `mergeInstance` (prefix-aware `Context.merge`, parent env kept), skipping GC'd slots ([`LLM.scala:289-311`]). `LLMTest` pins both the fork resolution ([`LLMTest.scala:443-455`]) and the unrecovered-fork panic ([`LLMTest.scala:457-470`]).

## `AIEnv` and `AISession`: the env-merge rule

`AIEnv` is the generation environment: a config plus the enablements layered for a scope or instance ([`AIEnv.scala:12-18`]):

```scala
case class AIEnv(config: Maybe[Config], prompt: Prompt[Any], tools: Chunk[Tool[Any]], thoughts: Chunk[Thought[Any]], mode: Chunk[Mode[Any]])
```

`config` is `Maybe[Config]`: the SCOPE env always holds `Present` (set at `LLM.run`), while an INSTANCE env holds `Absent` to inherit the scope config or `Present` to override it ([`AIEnv.scala:8-11`], [`AISession.scala:30-31`]). `AIEnv.empty` and `AISession.empty` both carry an `Absent` config ([`AIEnv.scala:46`], [`AISession.scala:31`]); `AIEnvTest`/`AISessionTest` pin that `config(cfg)` sets `Present`, `mapConfig` is a no-op while `Absent`, and the empty session has no override ([`AIEnvTest.scala:9-15`], [`AISessionTest.scala:8-20`]).

`AISession(context: Context, env: AIEnv)` is one instance's full state: its conversation plus its env override and enablements ([`AISession.scala:12`]). It is both the value `State.instances` holds per instance and the snapshot `ai.snapshot` returns / `AI.recover` restores. It holds code (tool runners, effectful prompts, modes), so it is in-memory only and not serializable; only the `session.context` slice is serializable (`Context derives Schema`) ([`AISession.scala:6-11`]).

**The override-merge rule** (`genLoop`, [`LLM.scala:318-326`]; pinned in `LLMTest`):

- An instance `Present` config override beats the scope config in the request ([`LLMTest.scala:325-341`]).
- A scope `AI.withConfig` wrapped around a gen is SHADOWED by the instance config override (the override wins) ([`LLMTest.scala:362-374`]).
- A mode's `AI.withConfig` (applied after the merge, inside the mode pipeline) DOES reach the request even on a config-overridden instance, layering on top of the override ([`LLMTest.scala:343-360`]).

## The enablement surface

The four composable kinds, `Tool`, `Prompt`, `Thought`, `Mode`, all extend `AI.Enablement[-S]`, whose two `private[kyo]` methods say how the kind layers itself onto a scope env or an instance session ([`AI.scala:48-53`], [`Tool.scala:19-26`], [`Prompt.scala:19-43`], [`Thought.scala:21-28`], [`Mode.scala:15-28`]). `private[kyo]` so only the module's four kinds implement it; users compose, never extend. There are **no** per-type `enable` binders (no `Tool.enable`, `Thought.enable`, `Prompt.enable`, or `Mode.enable`). Enabling is unified:

- **Scope.** `AI.enable[A, S](enablements: Enablement[S]*)(v): A < (S & LLM)` folds each enablement's `enableIn(AIEnv)` over the scope env via `LLM.updateEnv` (on top of the scope's current enablements); empty varargs is a no-op ([`AI.scala:114-126`]). The capability `S` rides the row, unified across the varargs to their intersection, until discharged at the run boundary.
- **Instance.** `ai.enable[S](enablements: Enablement[S]*): AI < (S & LLM)` folds each `enableIn(AISession)` onto the named instance ([`AI.scala:217-227`]).

Both take varargs or a `Seq` (a `DummyImplicit` differentiates the erased `Seq[T]` signatures) and accept a mix of kinds in one call ([`AI.scala:125`], [`AI.scala:225-227`]).

Config is scoped by `AI.withConfig` (NOT `LLM.withConfig`), built on `LLM.updateEnv(_.mapConfig(f))` ([`AI.scala:107-112`], [`LLM.scala:268-273`]). `updateEnv` brackets a transform of the scope `AIEnv` over `v`: get, modify, set, run, restore, written once and reused by the `enable` methods and `withConfig`.

### Forget and fresh

`AI.forget` snapshots `State`, runs `v`, then restores ALL instances' conversations (a scope-wide rollback) ([`AI.scala:130-132`]); the `forget(ais*)` form rolls back ONLY the named instances, other writes persist ([`AI.scala:134-140`]). `AI.fresh` runs `v` with conversations blanked (enablements and config kept), then restores ([`AI.scala:144-162`]). `AITest` pins `reset` removes the slot ([`AITest.scala:102-110`]) and `forget(ais*)` rolls back only the named instance ([`AITest.scala:184-197`]).

### `Tool`

`Tool.init[In][Out, S]` builds a tool from a name, optional description and prompt, and a run `In => Out < S` ([`Tool.scala:34-46`]). The `Isolate[S, LLM, S]` constraint lets the tool's effects be isolated for the mode-pipeline parallel paths. `aggregate` composes tools; `empty` is the no-tool aggregate ([`Tool.scala:48-54`]). The internal `resultToolInfo` is the dynamic `result_tool` the eval loop adds to every request: its run is a no-op, and the eval loop extracts the call's arguments directly (no capturing run, no ref) ([`Tool.scala:72-92`], [`LLM.scala:382-384`]). Tool-call dispatch contains ANY throw from user code as a tool message and never lets it escape the eval loop ([`Tool.scala:110-117`]).

### `Thought`

`Thought[A]` injects a typed reasoning field into the result schema: an `opening` field precedes `resultValue`, a `closing` follows it, so field ORDER frames the answer and drives autoregressive generation ([`Thought.scala:8-17`], [`Thought.scala:97-113`]). The thought name is the type's compile-time unqualified name via `Schema.structure.name` ([`Thought.scala:64`]). No reasoning is woven in by default; `Thought.reflective` (a `Reflect` opening + a `Check` closing) is the built-in scaffold, enabled explicitly ([`Thought.scala:53-56`], [`Thought.scala:87-88`]). Each thought's `process` hook fires on the decoded typed value after generation; an unrecognized field name is `AIInvalidThoughtException`, an undecodable field or result `AIDecodeException` ([`Thought.scala:118-151`]).

### `Prompt`

`Prompt[-S]` splits guidance into primary instructions (added at the context start, as SEPARATE system messages so providers can cache individual blocks) and reminders (floated at the context end, immediately before generation) ([`Prompt.scala:7-14`], [`Prompt.scala:106-144`]). `andThen` merges and `.distinct`-deduplicates both lists ([`Prompt.scala:21-35`]). `Prompt.init[S]` is `inline` and requires `Isolate[S, LLM, S]` ([`Prompt.scala:63-69`]). The `p` string interpolator normalizes per-line leading whitespace (`\n\s+` -> `\n`) and trims; use it for multi-line prompt literals ([`Prompt.scala:47-53`]).

### `Mode`

`Mode[-S]` is generation-interception middleware; enabled modes form a pipeline applied in registration order ([`Mode.scala:5-29`], [`Mode.scala:51-63`]). Its method is:

```scala
def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using Frame): Maybe[A] < (LLM & Async & Abort[AIGenException] & S)
```

([`Mode.scala:21-23`]). The `gen` parameter carries its failures typed as `Abort[AIGenException]` and the mode receives the target `ai` (so it can read/write that instance's conversation around the gen). `Mode.init[S]` builds a mode from a polymorphic transform, the convenient alternative to `new Mode[S]` ([`Mode.scala:42-49`]).

## `Agent`

`Agent[+Error, In, Out]` is `opaque type Agent[+Error, In, Out] = Actor[Error, Agent.internal.Message[In, Out], Any]` ([`Agent.scala:28`]). `ask` sends a typed input and awaits the reply under `Async & Abort[Closed | Error]`: a closed mailbox surfaces as `Abort[Closed]`, the behavior's typed error as `Abort[Error]`, never a throw ([`Agent.scala:40-44`]).

`run` mints ONE stable `AI` instance for the agent and hands it to the behavior as its `self`; the behavior calls `self.gen` explicitly, and because the actor's parked continuation keeps the `LLM.State` alive, that instance's conversation persists across asks ([`Agent.scala:15-19`]). All four creation overloads (`run` / `runBehavior`, with and without a leading config + enablements param list) funnel through the private `runImpl` ([`Agent.scala:57-165`]):

```scala
Actor.run(Abort.run[AIGenException](llmRun).map(_.getOrThrow))
//  where llmRun = LLM.run(c)(AI.initWith(behavior))   (config Present)
//             or  LLM.run(AI.initWith(behavior))       (config Absent, env default)
```

([`Agent.scala:235-239`]). So the wiring is `Actor.run(... Abort.run[AIGenException](... LLM.run(... AI.initWith(behavior)) ...).map(_.getOrThrow))`: `LLM` is discharged by `LLM.run`, `Abort[AIGenException]` is re-thrown as a panic (`AIGenException <: Throwable`), and `Async` is consumed by `Actor.run`. The enablements are layered around the behavior in argument order via `AI.enable` ([`Agent.scala:136-138`]). `AgentTest` pins cross-ask conversation persistence (the second ask's gen sees the first ask's turn) ([`AgentTest.scala:161-183`]) and that a failing behavior gen does not strand the asker ([`AgentTest.scala:185-202`]).

## `Config`

`Config` is an immutable copy-on-write settings record; every builder returns a modified copy ([`Config.scala:17-54`]). Its constructor is `private`, so a config is built via `Config.init`, `Config.default`, or a provider catalog literal, never `new`.

- **Temperature is opt-in.** `temperature` is `Maybe[Double] = Absent`; it is OMITTED from the request when unset (the model uses its own default) and clamped to `[0, 2]` when set (`temperature.max(0).min(2)`) ([`Config.scala:24`], [`Config.scala:35`], [`Config.scala:6-10`]). There is no `forcedTemperature` / `effectiveTemperature` and no `gpt-5` heuristic.
- **Optional builders.** `maxTokens(Int)` and `seed(Int)` are also `Maybe`; an internal `seed(Maybe[Int])` exists for cross-run seed derivation ([`Config.scala:25-26`], [`Config.scala:36-44`]).
- **Default selection.** `Config.default` probes provider markers and API keys (system properties first, then env vars) via `kyo.System`, never raw `sys.props` / `sys.env`, and falls back to Anthropic ([`Config.scala:64-70`]).

There are **nine** providers in `Provider.all`: `Anthropic`, `OpenAI`, `DeepSeek`, `Gemini`, `Groq`, `Baseten`, `OpenRouter`, `ClaudeCode`, `Codex` ([`Config.scala:96-98`]). `Config.default` checks the provider marker/key names in default-candidate order, preferring `CLAUDE_CODE`, then `CODEX`, then HTTP/API provider keys. Each provider exposes its model catalog as named pure `Config` constants (key absent, filled at use), and `default` points at the recommended entry ([`Config.scala:100-228`]). `DeepSeek`, `Gemini`, `Groq`, `Baseten`, and `OpenRouter` all carry `Completion.openAI` as their wire backend; `Anthropic` carries `Completion.anthropic`; `ClaudeCode` and `Codex` carry command-backed harness completions ([`Config.scala:102-228`]). The model catalog is current (Anthropic `claude-opus-4-8` / `claude-sonnet-4-6`, OpenAI `gpt-5.x`, etc.); update the literals here, not in the wire layer.

## `Context` and `Message`

`Context` IS the conversation: an ordered `Chunk[Message]`, immutable, `derives Schema` (so it is the serializable slice of an `AISession`) ([`Context.scala:22`]). Builders (`systemMessage`, `userMessage`, `assistantMessage`, `toolMessage`) log-and-skip degenerate inputs (blank content; a user message with neither content nor image; an assistant message with neither content nor calls) ([`Context.scala:29-45`]). `merge` is prefix-aware: it finds the common prefix shared with the argument and appends only the non-common suffix, so cross-fork merges never duplicate shared history ([`Context.scala:50-54`]). `Role` carries the exact lowercase wire strings providers require (`system`/`user`/`assistant`/`tool`), surfaced via `role.name` ([`Context.scala:62-68`]). `Context`, `Role`, `CallId`, and the `Message` trait all `derives CanEqual` so the equality-based merge compiles under strict equality.

## Wire layer: `Completion`

`Completion` is the provider-backend contract ([`Completion.scala:23-44`]):

```scala
def apply(config: Config, context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]], resultSchema: Maybe[JsonSchema] = Absent)(using Frame): Chunk[Message] < (LLM & Async & Abort[HttpException | AIGenException])
```

plus `streamFragments`, which emits raw JSON fragments for the `{ resultValue: ... }` envelope consumed by `LLM.stream` ([`Completion.scala:33-44`]). HTTP providers implement it by posting their native SSE request and projecting result-tool argument deltas through `Completion.sseFragments`; command harnesses implement it through their native event or stream-json output. Returning `Chunk[Message]` lets command harnesses append the transcript delta they produced, while the HTTP providers still return a singleton assistant message. Transport failures surface as `Abort[HttpException]`, never `Abort[Throwable]`; a missing key or undecodable reply as the typed `Abort[AIGenException]` leaves ([`Completion.scala:9-21`]).

Three implementation families, reached through `Config.Provider.completion`, never constructed by users ([`Completion.scala:55-74`]):

- `OpenAICompletion`: `POST {apiUrl}/chat/completions` with `content-type: application/json` and `Authorization: Bearer <key>` (plus `OpenAI-Organization` when present); covers OpenAI and the five compatible providers ([`OpenAICompletion.scala:27`], [`OpenAICompletion.scala:51-61`]). The SSE stream terminates on a `[DONE]` line ([`OpenAICompletion.scala:70-92`]).
- `AnthropicCompletion`: `POST {apiUrl}/messages` with `x-api-key: <key>` and `anthropic-version: 2023-06-01` ([`AnthropicCompletion.scala:27`], [`AnthropicCompletion.scala:57-64`], [`AnthropicCompletion.scala:76-79`]).
- `ClaudeCodeCompletion` and `CodexCompletion`: command-backed harness adapters sharing the `HarnessCompletion` base class. Claude Code receives SDK `stream-json` input and emits `stream-json` output. Enabled Kyo tools are exposed to Claude Code through a private localhost MCP bridge that calls back into Kyo tool handlers, with ambient MCP, plugins, shell tools, browser tools, and user config disabled. Codex uses `codex app-server`, injects prior context with `thread/inject_items`, starts turns with `turn/start`, and reads turn events until `turn/completed`. Completed Kyo tool-call history is replayed inertly in Claude Code so the CLI does not re-execute old calls; new tool calls are carried by the private bridge and the returned transcript is converted back to Kyo messages.

The result tool has the reserved name `Completion.resultToolName` (`"result_tool"`); when `resultSchema` is `Present`, the backend substitutes it for the tool's opaque `Structure.Value` input schema so the wire parameter schema exposes the real thought-aware properties ([`Completion.scala:65-68`], [`Completion.scala:9-21`]).

### kyo-ai Completion Backends

`kyo-ai` completion backends are an internal implementation detail behind `AI.Config.Provider`. A backend must be transparent to the public API: `AI.gen`, `AI.stream[String]`, `AI.stream[A]`, typed results, images, prompts, thoughts, modes, retained `AI` history, and Kyo tools must behave the same from the caller's perspective across HTTP providers and command harness providers.

Backend implementation rules:

- Implement the full `Completion` contract. Do not add placeholder streaming methods, silent tool rejection, or partial support paths.
- Kyo remains the tool runner for every backend. If a provider agent loop needs synchronous tool execution, use a private bridge that calls back into Kyo tool handlers and return the produced transcript as Kyo `Context.Message` values. Do not expose ambient user MCP servers, provider shell tools, plugins, or unrelated host tools through a completion backend.
- Preserve structured context as far as the provider protocol allows. Use native message, image, function-call, and tool-result protocol items when they exist. If a provider has no supported native injection path for a piece of history, keep the workaround explicit in code and tests, and verify the public behavior it affects.
- Command harnesses must live in `shared/src/main`, unless behavior is genuinely platform-specific. `Command`, `Path`, and the Kyo effects are cross-platform APIs.
- Isolate provider config without losing auth. For command harnesses this means a temporary working directory and an isolated config home that copies only required auth material. Disable user plugins, shell tools, browser/computer tools, and other external provider tools unless they are the explicit backend under test.
- Surface provider unavailability as typed failures, for example auth, quota, rate limit, and network failures should become provider-unavailable exceptions rather than string matching in tests.
- Log backend dispatch through `kyo.Log`, not raw printing. The `LLM` boundary should name the provider, model, message count, tool count, and streaming mode without dumping prompts, API keys, auth files, or full transcripts.

Backend tests must cover the same behavior a user can observe:

- Unit tests for request and response conversion: context messages, images, assistant tool calls, tool results, result tool envelopes, malformed provider output, and streaming fragments.
- Shared live integration tests for command harnesses in `shared/src/test`. Use `assume` when the CLI, auth, quota, account, or network provider is unavailable, and fail on behavioral regressions after the provider is available.
- Live tests must assert that the intended provider and completion backend are actually selected.
- Live tests must exercise `ai.gen`, retained history via `AI.snapshot` and `AI.recover`, image input, Kyo tool calling, `ai.stream[String]`, and object streaming via `ai.stream[A]`.
- Use `KYO_AI_PROVIDER=<provider>` for forked sbt demos; a `-Dkyo.ai.provider=...` argument before the sbt task configures the sbt JVM and may not reach the forked demo process. If a manual program needs visible backend selection, wrap it with `Log.withConsoleLogger(..., Log.Level.debug)` or another debug-enabled logger.

Self-contained demo command shape:

```sh
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=codex sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
```

## The exception hierarchy

`AIException` is a `sealed abstract class ... extends KyoException`, organized by the two operations that produce failures ([`AIException.scala:18-19`]):

- `AIGenException` (sealed trait): the failure set of a generation, the row of `LLM.run`'s residual; raised while the `Gen` op's eval loop runs ([`AIException.scala:21-22`]).
- `AIStreamException` (sealed trait): the failure set of a stream, carried inside the returned `Stream`'s effect row; raised lazily as the stream is consumed ([`AIException.scala:24-25`]).

A leaf mixes in every operation it can occur in. `AIMissingApiKeyException` and `AITransportException` mix in BOTH (either operation reaches the provider) ([`AIException.scala:30-38`]). Gen-only leaves: `AIEvalExhaustedException`, `AIInvalidThoughtException`, `AIDecodeException` ([`AIException.scala:40-52`]). Stream-only leaves: `AIStreamDeltaException`, `AIStreamIncompleteException` ([`AIException.scala:54-60`]). `AICrossRunException` (misuse) and `AIMeterClosedException` (impossible-state) are `AIException`s but in NEITHER operation set: they panic rather than ride a row ([`AIException.scala:62-76`]). `AIExceptionTest` pins the full lattice with `summon[... <:< ...]` and asserts cross-run/meter-closed are not gen/stream failures ([`AIExceptionTest.scala:4-48`]).

When adding a failure, add a leaf to `AIException.scala` under the right operation trait(s) and route to it; keep every message string on the leaf, and never let a non-module exception (a raw `HttpException`) ride a public row, map it to `AITransportException` at the eval/stream boundary ([`LLM.scala:331`], [`LLM.scala:182`]).

## Unsafe boundary

This module has no `AllowUnsafe` sites and no `import AllowUnsafe.embrace.danger` in its sources. The former process-global `AtomicLong` id counter is GONE: ids now come from the run's threaded `State.nextId` ([`LLM.scala:106-107`]), so there is no module-level mutable state to bridge. The only `java.lang.ref` use is `internal.AIRef extends WeakReference[AI]`, a GC-reclaimability mechanism for the `State.instances` keys, not an unsafe-tier API ([`LLM.scala:417-428`]). When threading new state, keep it in `State` and the `Op` GADT; do not introduce a process-global counter or an `AllowUnsafe` parameter.

## Test patterns

All tests extend `kyo.test.Test[Any]` ([`LLMTest.scala:9`], and every `*Test.scala` in the suite). Tests follow the 1:1 source-to-test rule (`LLM.scala` -> `LLMTest.scala`, `Agent.scala` -> `AgentTest.scala`, etc.); the module-wide invariants are folded into the per-source `*Test.scala` files. There is no `LLMInvariantsSpec` (a stale `LLMInvariantsTest.xml` may persist as a build artifact under `jvm/target`; ignore it).

### `TestCompletionServer`

Tests drive a real in-process HTTP server implementing the OpenAI and Anthropic wire protocols, bound on an OS-assigned ephemeral port within a `Scope` ([`TestCompletionServer.scala:6-20`], [`TestCompletionServer.scala:91-104`]). It serves `POST /v1/chat/completions` and `POST /v1/messages`, captures each request body, and returns the next scripted response. Two modes: `TestCompletionServer.run` (non-streaming JSON) and `runStreaming` (SSE on both endpoints) ([`TestCompletionServer.scala:81-89`]). Scripting is deterministic and per-test: `server.enqueueBody(json)` / `server.enqueueStream(chunks)` before the client call, popped one per request ([`TestCompletionServer.scala:27-33`], [`TestCompletionServer.scala:106-118`]). `server.captured` reads the ordered `Captured(path, body)` records for wire-shape assertions ([`TestCompletionServer.scala:35-38`], [`LLMTest.scala:325-341`]). An opt-in `proxyToOpenAI` live path is gated on `KYO_LLM_LIVE_TESTS` + `OPENAI_API_KEY` and skipped by default ([`TestCompletionServer.scala:51-75`]).

### Pointing tests at the server and scripting a reply

`LLMTest` defines the shared helpers (each per-source test that needs them redeclares its own):

```scala
def serverConfig(baseUrl: String): Config =
    Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 128000).apiUrl(baseUrl)

def resultToolBody(envelopeJson: String): String = // an OpenAI body whose assistant calls result_tool with envelopeJson
```

([`LLMTest.scala:13-25`]). The eval loop always extracts from `result_tool`, so script the structured reply by wrapping it (`{"resultValue": <value>}` for a plain field, with `openingThoughts`/`closingThoughts` keys when thoughts are enabled) ([`LLMTest.scala:60-61`], [`Thought.scala:108-112`]).

### What to assert

Assert concrete values, not just types or non-emptiness: the scripted reply round-trips to a concrete `A` ([`LLMTest.scala:430-441`]), and the captured request body contains the override temperature and not the scope temperature ([`LLMTest.scala:325-341`]). Compile-time row ascriptions (`val x: Int < LLM`, `def y: Int < (Async & Abort[AIGenException])`) are the proof that the rows are exact ([`LLMTest.scala:430-441`]).

## Conventions

### Cross-platform discipline

All source and tests live in `shared/`. The `jvm/`, `js/`, `native/`, `wasm/` trees carry no `.scala` source; never move a test to a platform subtree to dodge a platform cost.

### Effect-row precision

Never widen the effect row of a `< LLM` computation to include `Async`; `Async` belongs only on `LLM.run`'s residual (the `Gen`/`Stream` interpretation) ([`LLM.scala:125-141`]). A new op goes in `LLM.internal.Op`, gets a `runWith` arm that introduces no `Async` for any op but `Gen`/`Stream`, and is suspended via `ArrowEffect.suspend` ([`LLM.scala:245-275`]). A `Mode.apply`'s `gen` parameter row is exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`, not wider ([`Mode.scala:21-23`]).

### `private[kyo]` over `protected`

Use `private[kyo]` for cross-package visibility; there is no `protected` in this module. `AI`'s constructor and fields, the `LLM` op interface, and `State`'s constructor are all `private[kyo]`.

### Kyo types

| Use this | Not this |
|----------|----------|
| `Maybe` | `Option` |
| `Result` | `Either` / `Try` |
| `Chunk` / `Dict` | `List` / `Seq` / `Map` (in public APIs) |

### Test file naming

Follow the 1:1 rule; the module-wide invariants live in the per-source `*Test.scala` files, not a separate spec. No orphan or scratch test files in a finished change.

## Building and testing

```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="$JAVA_OPTS"

# All tests on JVM
sbt 'kyo-aiJVM/test'

# A single test class
sbt 'kyo-aiJVM/testOnly kyo.LLMTest'
```

Building auto-formats; re-read any file you edit after building, formatting may have changed it. See the root [CONTRIBUTING.md](../CONTRIBUTING.md) for naming, scaladoc, inline guidelines, `using`-clause ordering, and the pre-submission checklist.

## Decision checklist: before adding or changing X (kyo-ai)

In addition to the root checklist:

1. **New `Op`.** Is it a final `case class` (data) or `case object` (field-less)? Does its `runWith` arm thread `State` and introduce no `Async` (only `Gen`/`Stream` may)? Does the reply type in `Op[A]` match the `ArrowEffect.suspend` return? Does it appear in `crossRunFailure` if it targets an instance? [`LLM.scala:65-95`, `LLM.scala:400-415`]
2. **Eval-loop change.** Does every non-`Gen` path stay `< LLM` (no `Async`)? Does the `Gen` residual stay `A < (LLM & Async & Abort[AIGenException])`? Does `summon[NotGiven[LLM <:< Async]]` still hold? [`LLMTest.scala:409-417`]
3. **New enablement kind or call.** Does it implement `AI.Enablement[S]`'s two `enableIn` methods (`AIEnv` and `AISession`)? Is it reached only through `AI.enable` / `ai.enable` (never a per-type binder)? Does the row carry `S` until the run boundary? [`AI.scala:48-53`, `AI.scala:114-126`]
4. **Config override.** Is an instance config a `Maybe[Config]` (`Absent` = inherit, `Present` = override)? Does `genLoop`'s merge keep `Present` winning over the scope and over a scope `withConfig`, while a mode `withConfig` still layers on top? [`LLM.scala:318-326`, `LLMTest.scala:325-374`]
5. **New `Mode`.** Is `apply`'s `gen` row exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`? Does the mode receive `ai` and read/write that instance only? [`Mode.scala:21-23`]
6. **New `Agent` overload.** Does it delegate to `runImpl`, minting ONE stable instance via `AI.initWith(behavior)`, discharging with `LLM.run`, re-throwing `Abort[AIGenException]` via `getOrThrow`, and handing to `Actor.run`? [`Agent.scala:217-240`]
7. **New completion backend.** Does it match the result tool by `Completion.resultToolName` and substitute `resultSchema` when `Present`? Does it surface transport failures as `Abort[HttpException]`, never `Abort[Throwable]`? If it is command-backed, does it use the harness's native input/output shape and map process or decode failures to `AIDecodeException`? Is it reached through a new `Config.Provider` in `Provider.all`? [`Completion.scala:65-74`, `Config.scala:96-98`]
8. **New failure.** Is there a leaf in `AIException.scala` under the right operation trait(s), with its message on the leaf, mapped from any raw `HttpException` at the eval/stream boundary? Is a misuse/impossible-state panic kept off both rows? [`AIException.scala`, `LLM.scala:331`]
9. **New test.** Does it extend `kyo.test.Test[Any]`, use `TestCompletionServer` (not a live endpoint), assert concrete values, place each `enqueueBody`/`enqueueStream` before the consuming client call, and live in `shared/src/test`? [`TestCompletionServer.scala`, `LLMTest.scala`]

# Contributing to kyo-ai

Module-specific guide for kyo-ai. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming rules, type vocabulary (`Maybe` / `Result` / `Chunk` / `Span`), `using`-clause ordering, Frame/Tag, inline guidelines, scaladoc, visibility tiers, the test framework, cross-platform placement, and the unsafe-tier boundary that apply across all of Kyo. This document records only what is specific to kyo-ai: the explicit-instance model, the `LLM` `ArrowEffect` and its threaded `State`, the per-run owner and cross-run guard, the unified enablement surface, the scope/instance env-merge rule, the eval and stream loops, the agent wiring, the provider wire layer, and the test conventions.

**The headline invariant:** a program typed `A < LLM` is a pure value. `LLM` is a custom `ArrowEffect[LLM.internal.Op, Id]`, NOT a subtype of `Async` ([`LLM.scala`]). Its ops carry data and read/append per-instance conversation histories in one threaded `State`. The ops that reach the world are `Gen` and `Decide` (and `Gen`'s sibling `Stream`): their handler interpretations run the eval loop and the decision glue, and those are the ONLY places `Async & Abort[AIGenException]` enter, riding out on `LLM.run`'s residual. A row `A < LLM` must never include `Async`; no change to the eval loop should push `Async` into the `LLM` row. `LLMTest` pins this with `summon[NotGiven[LLM <:< Async]]` ([`LLMTest.scala`]).

## What kyo-ai is

kyo-ai is a typed effect for first-class conversations with a language model. The public surface, all top-level in `package kyo`:

- `LLM`, the `ArrowEffect`, and its `run` (the discharge boundary).
- `AI`, the first-class conversation instance (both the identity value `ai` and the namespace of operations).
- `Agent`, an opaque alias over `kyo.Actor` that pairs the LLM surface with the actor model.
- `Decider`, the typed decision surface (`check`, `choose`, `score`, `noul`, `query`, `batch`), on the object and on an `AI` instance.
- `Prompt`, `Tool`, `Thought`, `Mode`, `Observe`, the five composable enablement kinds (`AI.Enablement`).
- `AIEnv` and `AISession`, the generation environment and the per-instance state record.
- the `AIException` hierarchy (`AIGenException`, `AIStreamException`, and their leaves).

The settings/content value types `Config`, `Context`, `DeciderConfig`, `Image` live in package `kyo.ai`, but they are surfaced into `kyo` so `import kyo.*` reaches everything: the `AI` companion `export`s `kyo.ai.Config` / `Context` / `DeciderConfig` / `Image` ([`AI.scala`]), so a user writes `AI.Config`, `AI.Context`, `AI.DeciderConfig`, `AI.Image`; `LLM` also `export`s `Config` ([`LLM.scala`]).

All code lives in `shared/src/main/scala` (`kyo/` for the effect surface, `kyo/ai/` for the value types, `kyo/ai/completion/` for the wire backends, `kyo/ai/decider/` for the decision backends). All tests live in `shared/src/test/scala` and run cross-platform (JVM, Scala.js, Scala Native, Wasm). The `jvm/`, `js/`, `native/`, and `wasm/` trees carry no `.scala` source.

## The explicit-instance model

There is no ambient "current" instance and no `AI.use`. A behavior receives its instance explicitly and calls `self.gen`; the eval loop threads the target `AI` explicitly (`reads ai.context, appends to ai`), so nothing is ambient ([`LLM.scala`]). Two surfaces exist:

- **One-shot.** `AI.gen[A]` mints a fresh ephemeral instance, generates against it, then discards its slot via `ai.reset` on success, so two one-shots never share state ([`AI.scala`]). `AITest` pins that a successful one-shot leaves `State.instances` empty (and stays empty under concurrent gens, and a transport abort fails the run so nothing leaks) ([`AITest.scala`]).
- **Named instance.** `AI.init` mints a persistent slot whose conversation, enablements, and config override survive across turns within one `LLM.run`; `ai.gen[A]` runs against that slot ([`AI.scala`]).

`AI` is a reference object, NOT an `opaque type` over a `Long`:

```scala
final class AI private[kyo] (private[kyo] val id: Long, private[kyo] val owner: AnyRef):
    private[kyo] val ref: LLM.internal.AIRef = new LLM.internal.AIRef(this)
```

([`AI.scala`]). The id is drawn from the run's threaded `State` counter (no process-global mutable state), so identity is scoped to one `LLM.run` and restarts per run; `LLMTest` pins that within a run successive `init` ids are `0, 1, ...` and a fresh run restarts at `0` ([`LLMTest.scala`]). Every method on `AI` is a thin value over the `LLM` effect surface: `AI` summons no `ArrowEffect` op directly, only `LLM`'s `private[kyo]` interface ([`AI.scala`], [`LLM.scala`]).

### Per-run owner and the cross-run guard

Each instance remembers the run that created it (`owner`, a fresh `AnyRef` per run, object identity, no counter) ([`LLM.scala`]). Using an instance inside a different `LLM.run` is misuse: it cannot address that run's slots. `crossRunFailure` inspects every op that targets an instance and, when `ai.owner ne state.owner`, raises `AICrossRunException`; the panic is the handler arm's result, so it rides `runWith`'s residual `Abort` row (not the `LLM` continuation) and aborts the whole computation. `AICrossRunException`'s message points the user at `ai.snapshot` / `AI.recover` ([`AIException.scala`]). `LLMTest` pins that the guard fires for EVERY targeting op (read, set, gen, stream, discard, session, setSession), not just one ([`LLMTest.scala`]).

To carry an instance across runs deliberately, capture it with `ai.snapshot` (returns its `AISession`) and restore it with `AI.recover(session)` in the new run ([`AI.scala`]); `AITest` pins a round-trip of history + an enabled tool + a config override across two runs ([`AITest.scala`]).

## The LLM effect

### Effect definition and the op GADT

`LLM` is `sealed trait LLM extends ArrowEffect[LLM.internal.Op, Id]` ([`LLM.scala`]). The op GADT `LLM.internal.Op[A]` indexes each op's reply by `A`, so the handler continuation needs no reply-side cast. It has exactly **14** subclasses; field-less ops are `case object`s, the rest carry data:

| Op | Carries | Reply (`Op[A]`) |
|----|---------|-----------------|
| `Read(target: AI)` | target | `Context` |
| `Add(target: AI, message: Message)` | target, message | `Unit` |
| `Set(target: AI, context: Context)` | target, context | `Unit` |
| `Init` (`case object`) | nothing | `AI` |
| `Env` (`case object`) | nothing | `AIEnv` |
| `Gen[A](target: AI, schema: Schema[A])` | target, schema | `A` |
| `Decide[R](target: AI, plan: Decider.internal.Plan[R], record: Boolean)` | target, plan, record | `R` |
| `Stream[A](target: AI, schema: Schema[A], emitTag: Tag[Emit[Chunk[A]]])` | target, schema, emitTag | `Stream[A, Async & Scope & Abort[AIStreamException]]` |
| `SetEnv(env: AIEnv)` | env | `AIEnv` (the previous env) |
| `Discard(target: AI)` | target | `Unit` |
| `GetState` (`case object`) | nothing | `LLM.State` |
| `SetState(state: LLM.State)` | state | `Unit` |
| `GetSession(target: AI)` | target | `AISession` |
| `SetSession(target: AI, session: AISession)` | target, session | `Unit` |

Two ops reach the world. `Gen`'s arm runs `genLoop` under a nested `runWith` against the live state, and `Async & Abort[AIGenException]` enter there ([`LLM.scala`]); `Decide`'s arm runs `Decider.internal.decide` the same way, so the same row enters there ([`LLM.scala`], see [Decisions](#decisions-decider)). There is **no `SetCurrent`** op and no ambient-current concept. `LLMTest` pins the data-bearing ops carry their fields, `Decide` included ([`LLMTest.scala`]). `Stream`'s arm runs `streamAgainst` under a nested `runWith`: the stream's CREATION applies and restores the target's session env (the same merge `genLoop` performs), so the state threads through net-unchanged, and the recorded turn is written later, at consumption, through ordinary ops.

### Threaded state: `LLM.State`

`State` is the single record threaded through `ArrowEffect.handleLoop` ([`LLM.scala`]):

```scala
final case class State private[kyo] (
    instances: Dict[internal.AIRef, AISession],
    nextId: Long,
    owner: AnyRef,
    env: AIEnv
)
```

- `instances` is keyed by `internal.AIRef`, a `WeakReference[AI]` whose equality/hash are by the AI's stable `id`, so a dropped `AI` becomes GC-reclaimable while its key still matches its slot ([`LLM.scala`]). `State.pruned` sweeps slots whose `AI` was collected, run when minting a new instance so an unbounded mint stream never accumulates dead slots.
- `nextId` is the monotonic id counter `Init` draws from; `SetState` never lowers it (`math.max`), so a `forget`/`fresh` rollback keeps the high-water id and a slot key is never reused ([`LLM.scala`]).
- `owner` stamps every instance for the cross-run guard ([`LLM.scala`]).
- `env` is the scope `AIEnv` (see below), read by `Op.Env` and replaced by `Op.SetEnv` ([`LLM.scala`]).

`State.empty(config)` seeds a `Present(config)` scope env and a fresh `owner` ([`LLM.scala`]).

### `run` and its residual

`LLM.run[A, S](v: A < (LLM & S)): A < (S & Async & Abort[AIGenException])` threads a fresh `State.empty(config)` through `runWith` and discards the final state ([`LLM.scala`]). Three overloads (`run(v)`, `run(f: Config => Config)(v)`, `run(config)(v)`) all funnel through `runWith`; the first two resolve `Config.default` under `Sync` first. `runTuple` keeps the final state with the value for tests and transcript access (`private[kyo]`). `runWith` is NOT inline.

### The eval loop

`genLoop(ai, schema)` is the `Gen` interpretation ([`LLM.scala`]). It:

1. Merges the instance's own env onto the scope env for the duration of the eval, then restores it on both the success and the failure path (so the effective surface is `scope ++ instance`): config is `session.env.config.orElse(scopeEnv.config)`, and the instance's prompt/tools/thoughts/modes are layered on ([`LLM.scala`]). This is the merge that makes a `Present` instance config override the scope config; an `Absent` instance config inherits the scope's.
2. The default structured-output guidance rides the enriched context built per request (`Prompt.internal.enrichedContext`), not the empty prompt, so the merged env stays exactly `scope ++ instance` ([`AISession.scala`]).
3. Builds ONE result tool and `ResultCapture` per generation (`Tool.internal.resultTool`), so the capture accumulates the accepted value and the rejection bookkeeping across iterations ([`LLM.scala`], [`Tool.scala`]).
4. Loops: each iteration calls `eval[A](ai, forceResult = iterations >= config.maxIterations, ...)`, passes the result through `Mode.internal.handle` (the mode pipeline), and on `Present(r)` yields; on `Absent` it re-loops with the seed modulated (`c.seed.map(_ * 31)`). Once the result tool has been forced and the turn STILL yields no result, one informed repair turn past `maxIterations` is granted, then it aborts with `AIEvalExhaustedException` carrying the rejection count and last failure ([`LLM.scala`]).

`eval` posts one completion request ([`LLM.scala`]): it assembles the tools (plus the result tool; on a forced turn user tools are dropped and a finalize directive is added request-scoped), the thought-aware result schema, and the enriched context; runs the provider completion under the config deadline (`Async.timeoutWithError(config.timeout)`, `AICompletionTimeoutException`), with a raw `HttpException` classified into the module's typed leaves by `Completion.classifyHttp` BEFORE the retry clause sees it, under the config meter, a `Completion.awaitRetryAfter` wait for a throttle's `Retry-After`, and `Retry[AITransientException](config.retrySchedule)`; fires the observers on the raw reply; adjudicates a ceiling stop (`AIOutputLimitException`); appends the reply; and dispatches the tool calls through `Tool.internal.handle`, where the result tool's payload is decoded ONCE against its typed envelope schema like any other tool and the capturing run stores the value. A wire that rejects the model's tool call (`AIToolCallRejectedException`) is fed forward as a repair turn rather than failing the generation. A closed meter under an in-flight gen panics with `AIMeterClosedException` (an impossible-state, off both rows) ([`LLM.scala`], [`AIException.scala`]).

### The stream loop

`AI.stream[A]` / `ai.stream[A]` suspend `Op.Stream`, whose handler runs `streamAgainst` ([`LLM.scala`]). `streamAgainst` applies the target's session env for the stream's creation, then `streamUnder` asks `config.provider.completion.streamFragments` for raw JSON fragments of the `{ resultValue: ... }` envelope and accumulates the fragments. For `String`, it emits decoded text chunks whose concatenation is the final text. For other result types, it emits each complete decoded element from the result array exactly once. HTTP providers implement fragments with SSE result-tool deltas; command harnesses use their native event or stream-json output. The returned `Stream` carries its failures typed in its element row as `Abort[AIStreamException]`: a malformed delta is `AIStreamDeltaException`, an end without a decodable value `AIStreamIncompleteException`, a transport error one of the `AIProviderException` leaves `Completion.classifyHttp` produces ([`LLM.scala`], [`Completion.scala`]). A missing API key is the one failure raised eagerly (before the `Stream` value), as `AIMissingApiKeyException` on the run boundary.

### The `LLM.isolate` given

`given isolate: Isolate[LLM, Async, LLM]` lets `Async.fill`/`foreach`/`race` fork over a bare `LLM` row ([`LLM.scala`]). `Keep = Async` is exact: the in-tree parallel sites require `Isolate[LLM, Abort[E] & Async, LLM]`, and for the `E = Nothing` body (whose transport errors the eval loop already recovers) that reduces to `Isolate[LLM, Async, LLM]`, which a wider `Keep` (`Abort[Any] & Async`) cannot satisfy by `Keep` contravariance. `capture` reads the live `State` via `Op.GetState`; `isolate` discharges `runWith`'s residual `Abort[AIGenException]` inside the fork with `getOrThrow`, so an unrecovered fork generation failure surfaces as a fiber panic; `restore` merges fork-born instance contexts back via `mergeInstance` (prefix-aware `Context.merge`, parent env kept), skipping GC'd slots. `LLMTest` pins both the fork resolution ([`LLMTest.scala`]) and the unrecovered-fork panic.

## `AIEnv` and `AISession`: the env-merge rule

`AIEnv` is the generation environment: a config plus the enablements layered for a scope or instance ([`AIEnv.scala`]):

```scala
case class AIEnv(config: Maybe[Config], prompt: Prompt[Any], tools: Chunk[Tool[Any]], thoughts: Chunk[Thought[Any]], mode: Chunk[Mode[Any]], observe: Chunk[Observe[Any]])
```

`config` is `Maybe[Config]`: the SCOPE env always holds `Present` (set at `LLM.run`), while an INSTANCE env holds `Absent` to inherit the scope config or `Present` to override it ([`AIEnv.scala`], [`AISession.scala`]). `AIEnv.empty` and `AISession.empty` both carry an `Absent` config; `AIEnvTest`/`AISessionTest` pin that `config(cfg)` sets `Present`, `mapConfig` is a no-op while `Absent`, and the empty session has no override ([`AIEnvTest.scala`], [`AISessionTest.scala`]).

`AISession(rawContext: Context, env: AIEnv)` is one instance's full state: its bare conversation plus its env override and enablements ([`AISession.scala`]). It is both the value `State.instances` holds per instance and the snapshot `ai.snapshot` returns / `AI.recover` restores. It holds code (tool runners, effectful prompts, modes), so it is in-memory only and not serializable; the serializable slice is `session.rawContext` (`Context derives Schema`). `session.context` is effectful (`Context < LLM`): the conversation as the model receives it, `rawContext` enriched with the effective env's prompt stack, read against the scope env at call time. `effectiveEnv(scope)` is the ONE construction of the scope-plus-instance merge, shared by `genLoop`, the stream path, the decision glue, and `context`, so a captured transcript cannot drift from what generation assembled.

**The override-merge rule** (`genLoop`, [`LLM.scala`]; pinned in `LLMTest`):

- An instance `Present` config override beats the scope config in the request ([`LLMTest.scala`]).
- A scope `AI.withConfig` wrapped around a gen is SHADOWED by the instance config override (the override wins) ([`LLMTest.scala`]).
- A mode's `AI.withConfig` (applied after the merge, inside the mode pipeline) DOES reach the request even on a config-overridden instance, layering on top of the override ([`LLMTest.scala`]).

## The enablement surface

The five composable kinds, `Tool`, `Prompt`, `Thought`, `Mode`, `Observe`, all extend `AI.Enablement[-S]`, whose two `private[kyo]` methods say how the kind layers itself onto a scope env or an instance session ([`AI.scala`], [`Tool.scala`], [`Prompt.scala`], [`Thought.scala`], [`Mode.scala`], [`Observe.scala`]). `private[kyo]` so only the module's five kinds implement it; users compose, never extend. There are **no** per-type `enable` binders (no `Tool.enable`, `Thought.enable`, `Prompt.enable`, or `Mode.enable`). Enabling is unified:

- **Scope.** `AI.enable[A, S](enablements: Enablement[S]*)(v): A < (S & LLM)` folds each enablement's `enableIn(AIEnv)` over the scope env via `LLM.updateEnv` (on top of the scope's current enablements); empty varargs is a no-op ([`AI.scala`]). The capability `S` rides the row, unified across the varargs to their intersection, until discharged at the run boundary.
- **Instance.** `ai.enable[S](enablements: Enablement[S]*): AI < (S & LLM)` folds each `enableIn(AISession)` onto the named instance ([`AI.scala`]).

Both take varargs or a `Seq` (a `DummyImplicit` differentiates the erased `Seq[T]` signatures) and accept a mix of kinds in one call ([`AI.scala`]).

Config is scoped by `AI.withConfig` (NOT `LLM.withConfig`), built on `LLM.updateEnv(_.mapConfig(f))` ([`AI.scala`], [`LLM.scala`]). `updateEnv` brackets a transform of the scope `AIEnv` over `v`: get, modify, set, run, restore, written once and reused by the `enable` methods and `withConfig`.

### Forget and fresh

`AI.forget` snapshots `State`, runs `v`, then restores ALL instances' conversations (a scope-wide rollback) ([`AI.scala`]); the `forget(ais*)` form rolls back ONLY the named instances, other writes persist. `AI.fresh` runs `v` with conversations blanked (enablements and config kept), then restores. `AITest` pins `reset` removes the slot ([`AITest.scala`]) and `forget(ais*)` rolls back only the named instance.

### `Tool`

`Tool.init[In][Out, S]` builds a tool from a name, optional description and prompt, and a run `In => Out < S`; the run's capability row `S` rides the `Tool[S]` and is discharged at the run boundary like any enablement's ([`Tool.scala`]). `initDynamic` builds one from a schema known only at runtime (an MCP server's published tools). `aggregate` composes tools; `empty` is the no-tool aggregate. The internal `resultTool[A](thoughts)` builds the `result_tool` the eval loop adds to every request: a REAL typed tool whose input schema is the result envelope, decoded once by the tool loop like any other tool, and whose run fires the thought hooks, enforces conformance for an open-shape result, and stores the value in a per-generation `ResultCapture` (set-once; rejections are counted for the exhaustion report) ([`Tool.scala`], [`LLM.scala`]). `resultToolDefinition` is the definition-only form (name, description, no dispatch) for session context assembly and streaming. Tool-call dispatch contains ANY throw from user code as a tool message and never lets it escape the eval loop.

### `Thought`

`Thought[A]` injects a typed reasoning field into the result schema: an `opening` field precedes `resultValue`, a `closing` follows it, so field ORDER frames the answer and drives autoregressive generation ([`Thought.scala`]). The thought name is the type's compile-time unqualified name via `Schema.structure.name`. No reasoning is woven in by default; `Thought.reflective` (a `Reflect` opening + a `Check` closing) is the built-in scaffold, enabled explicitly. Each thought's `process` hook fires on its decoded group field from inside the result tool's run (`Thought.internal.handleThoughtGroups`, called before the value is captured): an unrecognized thought name is `AIInvalidThoughtException`, an undecodable thought field `AIDecodeException`; the result value itself is decoded by the tool loop against the typed envelope schema, and a rejection there rides the tool loop's feedback like any other tool's.

### `Prompt`

`Prompt[-S]` splits guidance into primary instructions (added at the context start, as SEPARATE system messages so providers can cache individual blocks) and reminders (floated at the context end, immediately before generation) ([`Prompt.scala`]). `andThen` merges and `.distinct`-deduplicates both lists. `Prompt.init[S]` is `inline` and takes the instructions and the reminder as by-name `String < (LLM & S)`, so a prompt may be computed effectfully at generation time. The `p` string interpolator normalizes per-line leading whitespace (`\n\s+` -> `\n`) and trims; use it for multi-line prompt literals.

### `Mode`

`Mode[-S]` is generation-interception middleware; enabled modes form a pipeline applied in registration order ([`Mode.scala`]). Its method is:

```scala
def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using Frame): Maybe[A] < (LLM & Async & Abort[AIGenException] & S)
```

([`Mode.scala`]). The `gen` parameter carries its failures typed as `Abort[AIGenException]` and the mode receives the target `ai` (so it can read/write that instance's conversation around the gen). `Mode.init[S]` builds a mode from a polymorphic transform, the convenient alternative to `new Mode[S]` ([`Mode.scala`]).

### `Observe`

`Observe[-S]` is the wire-tier, notification-only counterpart of `Mode`: where a mode receives the generation as a value and may replace it, an observer receives each completed turn's `Completion.Reply` (messages, stop reason, usage) and returns `Unit`, so it cannot change control flow. Its method is:

```scala
def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync & S)
```

Observers fire once per completion call the eval loop resolved, on the fiber that ran the turn, on the generation path BEFORE the ceiling adjudication (a turn that aborts at the output ceiling still spent its tokens; a streamed ceiling stop fails the stream and reports nothing) and on both paths before the turn's messages join the instance context. The streaming path fires on full consumption with the recorded synthetic result pair as the reply; an abandoned stream fires nothing, matching the transcript rule. Firing at the source is load-bearing: a fork's turns fire on the fork's own fiber, so a losing `Async.race` branch and a rolled-back `AI.forget` block still count the turns they completed. `Observe.init` builds one from a callback; `Observe.withStats` (both forms) is the provided usage-collecting implementation and is deliberately just a user of the enablement machinery: it allocates its own `AtomicRef` (hence the `Sync` in its row), enables an internal observer over `v`, and reads the cell at scope end. No `LLM` op is involved; the targeted form probes each target through the existing session op so a foreign run's instance fails loud. An observer's `Sync` is honest (both fire sites run under `Async`); an `Abort[E]` in `S` is the guardrail contract: its failure fails the generation it fired in.

## Decisions: `Decider`

`Decider` ([`Decider.scala`]) is the decision surface: `check`, `choose`, `score`, `noul`, `query`, `batch`, as one-shots on the object and as instance methods on `AI`. Its three question kinds are TypeSafe AI's primitives (`noul`, `choice`, `score`) and keep those names. Every public method builds an `internal.Plan` (the encoded questions plus the decoder from their answers to the caller's result) and suspends one `Op.Decide`; the decoder runs in the handler so a bad answer aborts as `AIDecodeException` while the surface stays `< LLM`. The parameter a question is about is the *context* (the wire calls it the `state`).

Three invariants hold the design together:

- **One glue path, one dispatch.** `Decider.internal.decide` validates (1 to 255 options, 2 to 10 levels, unique inferred keys, a `check` threshold within `[0, 1]`; else `AIInvalidQuestionException` before any request; the limits are TypeSafe's, enforced for both backends), resolves the effective config and the conversation, dispatches on `config.decider` (`Absent`: `LLMDecider`, the config's own completion provider; `Present(decider)`: `decider.provider.backend`), checks the answer count, reports the spend, records, and decodes. Nothing in it names a provider.
- **A backend answers only, from what it is handed.** A provider's backend implements `Decider.Backend`; it and `LLMDecider` take the effective `Config` (the merged scope-plus-instance config, so an instance override selects the backend and its transport fallbacks) and the instance's `Context` as the glue resolved it (the prompt-enriched conversation a generation would see, minus tool guidance; a one-shot's context value is its single user message, the way `AI.gen(input)` records an input) and return `internal.Reply(answers, usage)`; a provider's backend also takes the `DeciderConfig`. The conversation is what a decision is about; the TypeSafe backend sends it as the endpoint's `state`. A backend reads no ambient state (`AI.config`, `ai.context`), never leaves messages on the instance and never records the decision; the completion backend generates under `AI.forget`, so its writes roll back. `Reply.usage` is the spend observers have NOT yet seen: the TypeSafe backend reports its request's tokens as one turn, the completion backend reports `AIStats.empty` because its generation fired the observers itself.
- **Reporting and recording are the glue's, identical for both backends.** Every decision fires the effective env's observers once with a `Completion.Reply` of two messages (the fire is bracketed in `LLM.setEnv(env)` so `AI.config` inside a callback reads the decision's config; the env is not installed around the backend call, because the completion backend's generation re-merges the session and would double every instance enablement). An instance decision (`record = true`) then appends the two messages to the instance: a `UserMessage` carrying the questions (`internal.RecordedQuestions`, the TypeSafe wire shape, never the threshold) and an `AssistantMessage` carrying the answers (`internal.RecordedAnswers`, every answer with its distribution). Observers before messages is `genLoop`'s order. A transcript therefore has the same shape whichever backend answered. One-shots run on a fresh ephemeral instance (the context, if any, as its single user message, encoded like `AI.gen(input)`) and record nothing.

Every answer carries its distribution (`internal.Answer`: a noul's probability; a choice's best key, confidence and per-key probabilities; a score's probability-weighted level index, confidence and per-level probabilities), so there is no pick/distribution distinction and nothing a backend refuses. The completion backend ([`LLMDecider.scala`]) answers a whole decision in ONE `ai.gen[Answers]` under `AI.forget(ai)`: the user message (`Asking`) lists every question with the keys it offers (`true`/`false`, the option keys, the level indices, each with the caller's description), the forced result is one probability per offered key per question, and decoding normalizes each distribution, takes the most probable key (first on a tie) for a choice, the probability-weighted index for a score, and one minus the normalized entropy as the confidence; a malformed answer set (wrong count, unknown, missing or duplicate key, probability outside `[0, 1]`, all zero) gets one repair turn naming the problem, then `AIDecodeException`. The TypeSafe backend ([`TypeSafeDecider.scala`]) answers from one `POST /v1/systemone` under `genLoop`'s transport discipline (`Completion.statusFailure` with the response's headers in hand, so a 429's `Retry-After` is waited out under the deadline and the request id is prepended to a rejection body; the decider's own timeout, meter and retry schedule when set, the config's otherwise); its state is `""` for an empty conversation, else the chat log `Chunk[Turn]` (`role`, `content`, an assistant turn's `calls` as id/function/arguments text, a tool turn's `callId`), nothing unwrapped or re-parsed.

Everything sent or recorded is a case class deriving `Schema` (`internal.WireQuestion`/`WireAnswer` shared by the endpoint request, the endpoint response and the recorded messages; `Turn`; `Asking`/`Answers`); `Structure.Value` appears only where a caller's value is genuinely erased (instructions, option descriptions, levels). Option keys and descriptions are inferred from the option's encoding (`internal.keyOf`, `internal.docsOf`): a string is its own key, an enum case is keyed by its case name and described by its `@doc` (else by its fields, if any), anything else `c<i>` with the whole value as the description; a level is described the same way. Configuration is `DeciderConfig` ([`DeciderConfig.scala`]), shaped like `Config`: a `Provider` (name, base URL, key variable, backend) with pure catalog entries as stable `val`s and a `default` (`TypeSafe` is the first provider, with `jevLatest` and `jevPreview`; `Provider.all` lists them), carried on `Config.decider` as a `Maybe` (absent by default: the config's own completion provider decides), selected by `Config.default` the way completion providers are (the first provider in `Provider.all` whose key is present) and credentialed by `Config.credentialed` from the provider's key variable when present. Its `timeout`, `meter` and `retrySchedule` are `Maybe`s: absent means the surrounding `Config`'s.

## `Agent`

`Agent[+Error, In, Out]` is `opaque type Agent[+Error, In, Out] = Actor[Error, Agent.internal.Message[In, Out], Any]` ([`Agent.scala`]). `ask` sends a typed input and awaits the reply under `Async & Abort[Closed | Error]`: a closed mailbox surfaces as `Abort[Closed]`, the behavior's typed error as `Abort[Error]`, never a throw.

`run` mints ONE stable `AI` instance for the agent and hands it to the behavior as its `self`; the behavior calls `self.gen` explicitly, and because the actor's parked continuation keeps the `LLM.State` alive, that instance's conversation persists across asks ([`Agent.scala`]). All four creation overloads (`run` / `runBehavior`, with and without a leading config + enablements param list) funnel through the private `runImpl`:

```scala
Actor.run(Abort.run[AIGenException](llmRun).map(_.getOrThrow))
//  where llmRun = LLM.run(c)(AI.initWith(behavior))   (config Present)
//             or  LLM.run(AI.initWith(behavior))       (config Absent, env default)
```

([`Agent.scala`]). So the wiring is `Actor.run(... Abort.run[AIGenException](... LLM.run(... AI.initWith(behavior)) ...).map(_.getOrThrow))`: `LLM` is discharged by `LLM.run`, `Abort[AIGenException]` is re-thrown as a panic (`AIGenException <: Throwable`), and `Async` is consumed by `Actor.run`. The enablements are layered around the behavior in argument order via `AI.enable` ([`Agent.scala`]). `AgentTest` pins cross-ask conversation persistence (the second ask's gen sees the first ask's turn) ([`AgentTest.scala`]) and that a failing behavior gen does not strand the asker.

## `Config`

`Config` is an immutable copy-on-write settings record; every builder returns a modified copy ([`Config.scala`]). Its constructor is `private`, so a config is built via `Config.init`, `Config.default`, or a provider catalog literal, never `new`.

- **Temperature is opt-in.** `temperature` is `Maybe[Double] = Absent`; it is OMITTED from the request when unset (the model uses its own default) and clamped to `[0, 2]` when set (`temperature.max(0).min(2)`) ([`Config.scala`]). There is no `forcedTemperature` / `effectiveTemperature` and no `gpt-5` heuristic.
- **Optional builders.** `maxTokens(Int)` and `seed(Int)` are also `Maybe`; an internal `seed(Maybe[Int])` exists for cross-run seed derivation ([`Config.scala`]).
- **Default selection.** `Config.default` probes provider markers and API keys (system properties first, then env vars) via `kyo.System`, never raw `sys.props` / `sys.env`, and falls back to Anthropic ([`Config.scala`]).

There are **eleven** providers in `Provider.all`: `Anthropic`, `OpenAI`, `DeepSeek`, `Gemini`, `Groq`, `XAI`, `Moonshot`, `Baseten`, `OpenRouter`, `ClaudeCode`, `Codex` ([`Config.scala`]). `Config.default` checks the provider marker/key names in default-candidate order, preferring `CLAUDE_CODE`, then `CODEX`, then HTTP/API provider keys. Each provider exposes its model catalog as named pure `Config` constants (key absent, filled at use), and `default` points at the recommended entry. `DeepSeek`, `Gemini`, `Groq`, `XAI`, `Moonshot`, `Baseten`, and `OpenRouter` all carry `Completion.openAI` as their wire backend; `Anthropic` carries `Completion.anthropic`; `ClaudeCode` and `Codex` carry command-backed harness completions. The model catalog is current (Anthropic `claude-opus-4-8` / `claude-sonnet-4-6`, OpenAI `gpt-5.x`, etc.); update the literals here, not in the wire layer.

## `Context` and `Message`

`Context` IS the conversation: an ordered `Chunk[Message]`, immutable, `derives Schema` (so it is the serializable slice of an `AISession`) ([`Context.scala`]). Builders (`systemMessage`, `userMessage`, `assistantMessage`, `toolMessage`) log-and-skip degenerate inputs (blank content; a user message with neither content nor image; an assistant message with neither content nor calls). `merge` is prefix-aware: it finds the common prefix shared with the argument and appends only the non-common suffix, so cross-fork merges never duplicate shared history. `Role` carries the exact lowercase wire strings providers require (`system`/`user`/`assistant`/`tool`), surfaced via `role.name`. `Context`, `Role`, `CallId`, and the `Message` trait all `derives CanEqual` so the equality-based merge compiles under strict equality.

## Wire layer: `Completion`

`Completion` is the provider-backend contract ([`Completion.scala`]):

```scala
def apply(config: Config, context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]], resultSchema: Maybe[JsonSchema] = Absent)(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException])
```

where `Completion.Reply(messages, stopReason, usage)` carries the transcript delta the backend produced (command harnesses may append several messages, the HTTP providers return a singleton assistant message), the wire's stop reason (`Completed` or `MaxOutputTokens`, adjudicated by `eval`, never by the backend), and the turn's `AIStats`. `streamFragments` returns a `Stream[Completion.StreamElement, ...]`: `Fragment` elements are raw JSON fragments of the `{ resultValue: ... }` envelope consumed by `LLM.stream`, `Usage` elements are the partial usage reports the wire sends ([`Completion.scala`]). HTTP providers implement it by posting their native SSE request and projecting result-tool argument deltas through `Completion.sseFragments`; command harnesses implement it through their native event or stream-json output. `streamsIncrementally` declares whether the wire delivers deltas (every HTTP family) or only a finished result (the harnesses). Transport failures surface as `Abort[HttpException]`, never `Abort[Throwable]`, and are classified into the module's typed leaves by `Completion.classifyHttp` at the eval and stream boundaries; a missing key or undecodable reply surfaces as the typed `Abort[AIGenException]` leaves.

Three implementation families, reached through `Config.Provider.completion`, never constructed by users ([`Completion.scala`]):

- `OpenAICompletion`: `POST {apiUrl}/chat/completions` with `content-type: application/json` and `Authorization: Bearer <key>` (plus `OpenAI-Organization` when present); covers OpenAI and the five compatible providers ([`OpenAICompletion.scala`]). The SSE stream terminates on a `[DONE]` line.
- `AnthropicCompletion`: `POST {apiUrl}/messages` with `x-api-key: <key>` and `anthropic-version: 2023-06-01` ([`AnthropicCompletion.scala`]).
- `ClaudeCodeCompletion` and `CodexCompletion`: command-backed harness adapters sharing the `HarnessCompletion` base class. Claude Code receives SDK `stream-json` input and emits `stream-json` output. Enabled Kyo tools are exposed to Claude Code through a private localhost MCP bridge that calls back into Kyo tool handlers, with ambient MCP, plugins, shell tools, browser tools, and user config disabled. Codex uses `codex app-server`, injects prior context with `thread/inject_items`, starts turns with `turn/start`, and reads turn events until `turn/completed`. Completed Kyo tool-call history is replayed inertly in Claude Code so the CLI does not re-execute old calls; new tool calls are carried by the private bridge and the returned transcript is converted back to Kyo messages.

The result tool has the reserved name `Completion.resultToolName` (`"result_tool"`); when `resultSchema` is `Present`, the backend substitutes it for the tool's opaque `Structure.Value` input schema so the wire parameter schema exposes the real thought-aware properties ([`Completion.scala`]).

### kyo-ai Completion Backends

`kyo-ai` completion backends are an internal implementation detail behind `AI.Config.Provider`. A backend must be transparent to the public API: `AI.gen`, `AI.stream[String]`, `AI.stream[A]`, typed results, images, prompts, thoughts, modes, retained `AI` history, and Kyo tools must behave the same from the caller's perspective across HTTP providers and command harness providers.

Backend implementation rules:

- **A backend returns messages and tool calls; it never processes tool-call payloads.** Map the provider reply to `Context.Message` values with every tool call's arguments passed through VERBATIM, exactly as `AnthropicCompletion.read` does (`Call(id, name, Json.encode(input))`) ([`AnthropicCompletion.scala`]). This includes the result tool: the backend identifies it by NAME (`Completion.resultToolName`, or a harness's native structured-output tool) or by structural position (a terminal result field), never by inspecting the payload, and forwards the arguments untouched. A backend must NOT decode, validate, reshape, envelope, or fail on a tool-call payload. The only decoding a backend does is the transport/wire format (the provider's response body or the harness's stream-json events) into typed `Message`/`Call` values. All result decoding, `resultValue`-envelope handling, thought extraction, and validation live in `LLM.eval` ([`LLM.scala`]); that is the ONLY place a result payload is decoded.
- **A missing or unusable result is not a backend failure; it is the eval loop's repair signal.** When the model produced no result (only text or non-result tool calls), return the transcript with no `result_tool` call. `eval` then sees no result and re-queries, and on the forced iteration it passes zero user tools so the next request exposes only the result tool and the model must call it ([`LLM.scala`] eval loop, `forceResult`). This repair loop is the harness's substitute for the HTTP `tool_choice` force (`AnthropicCompletion` sets `tool_choice` to the result tool when it is the only tool; `OpenAICompletion` uses `tool_choice:"required"`), which command harnesses like the Claude Code CLI have no equivalent for. A backend that decodes/validates the payload and aborts (e.g. `AIDecodeException` on non-JSON result text) DEFEATS this loop and must not do so. NOTE: `CodexCompletion` currently violates the payload-faithfulness rule (it reshapes through `HarnessCompletion.resultOutput`); this is a known deviation to be fixed, not a pattern to copy.
- Implement the full `Completion` contract. Do not add placeholder streaming methods, silent tool rejection, or partial support paths.
- Kyo remains the tool runner for every backend. If a provider agent loop needs synchronous tool execution, use a private bridge that calls back into Kyo tool handlers and return the produced transcript as Kyo `Context.Message` values. Do not expose ambient user MCP servers, provider shell tools, plugins, or unrelated host tools through a completion backend.
- Preserve structured context as far as the provider protocol allows. Use native message, image, function-call, and tool-result protocol items when they exist. If a provider has no supported native injection path for a piece of history, keep the workaround explicit in code and tests, and verify the public behavior it affects.
- Command harnesses must live in `shared/src/main`, unless behavior is genuinely platform-specific. `Command`, `Path`, and the Kyo effects are cross-platform APIs.
- Isolate provider config without losing auth. For command harnesses this means a temporary working directory and an isolated config home that copies only required auth material. Disable user plugins, shell tools, browser/computer tools, and other external provider tools unless they are the explicit backend under test.
- Surface provider unavailability as typed failures, for example auth, quota, rate limit, and network failures should become provider-unavailable exceptions rather than string matching in tests.
- Log backend dispatch through `kyo.Log`, not raw printing. The `LLM` boundary should name the provider, model, message count, tool count, and streaming mode without dumping prompts, API keys, auth files, or full transcripts.

Backend tests must cover the same behavior a user can observe:

- Unit tests for request and response conversion: context messages, images, assistant tool calls, tool results, and streaming fragments. For the result path, assert VERBATIM passthrough (the result-tool call carries the provider payload unchanged) and the repair paths: a non-JSON result surfaces as a raw result-tool call (no exception), and a turn with no result surfaces with no result-tool call so the eval loop repairs.
- Shared live integration tests for command harnesses in `shared/src/test`. Use `assume` when the CLI, auth, quota, account, or network provider is unavailable, and fail on behavioral regressions after the provider is available.
- Live tests must assert that the intended provider and completion backend are actually selected.
- Live tests must exercise `ai.gen`, retained history via `AI.snapshot` and `AI.recover`, image input, Kyo tool calling, `ai.stream[String]`, and object streaming via `ai.stream[A]`.
- Use `KYO_AI_PROVIDER=<provider>` for forked sbt demos; a `-Dkyo.ai.provider=...` argument before the sbt task configures the sbt JVM and may not reach the forked demo process. If a manual program needs visible backend selection, wrap it with `Log.withConsoleLogger(..., Log.Level.debug)` or another debug-enabled logger.

Self-contained demo command shape:

```sh
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=codex sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
```

## The exception hierarchy

`AIException` is a `sealed abstract class ... extends KyoException`, organized by the two operations that produce failures ([`AIException.scala`]):

- `AIGenException` (sealed trait): the failure set of a generation, the row of `LLM.run`'s residual; raised while the `Gen` op's eval loop runs ([`AIException.scala`]).
- `AIStreamException` (sealed trait): the failure set of a stream, carried inside the returned `Stream`'s effect row; raised lazily as the stream is consumed ([`AIException.scala`]).

Two refinements cut across both: `AIProviderException` (the provider or the account is the problem, not the request: retrying the same request cannot get past it) and, within it, `AITransientException` (temporary by nature, so backoff retry is correct). The eval loop's `Retry` names `AITransientException` alone, so a new transient leaf is retried by mixing in that one trait, never by enumeration.

A leaf mixes in every operation it can occur in. Provider leaves, in BOTH sets: `AIMissingApiKeyException` and `AIProviderAuthException` (provider, not transient); `AITransportException`, `AIProviderUnavailableException` (408 and 5xx) and `AIRateLimitException` (429, carrying a parsed `Retry-After`) (transient). Response-side leaves in both sets, neither provider nor transient: `AIToolCallRejectedException` (a 400 refusing the model's own tool call, fed forward as a repair turn), `AIRequestRejectedException` (any other rejected status), `AICompletionTimeoutException` (the per-call deadline), `AIOutputLimitException` (a ceiling stop with nothing usable), `AIHarnessException` (a command harness malfunction). Gen-only leaves: `AIEvalExhaustedException`, `AIInvalidThoughtException`, `AIDecodeException`, and the decider's `AIInvalidQuestionException`. Stream-only leaves: `AIStreamDeltaException`, `AIStreamIncompleteException`. `AICrossRunException` (misuse) and `AIMeterClosedException` (impossible-state) are `AIException`s but in NEITHER operation set: they panic rather than ride a row. `AIExceptionTest` pins the lattice with `summon[... <:< ...]` and asserts cross-run/meter-closed are not gen/stream failures ([`AIExceptionTest.scala`]).

When adding a failure, add a leaf to `AIException.scala` under the right operation trait(s) (and under `AIProviderException` / `AITransientException` when it qualifies) and route to it; keep every message string on the leaf, and never let a non-module exception (a raw `HttpException`) ride a public row: `Completion.classifyHttp` maps it to the typed leaves at the eval and stream boundaries, with `AITransportException` as the residual for a transport error no status explains ([`LLM.scala`], [`Completion.scala`]).

## Unsafe boundary

This module has no `AllowUnsafe` sites and no `import AllowUnsafe.embrace.danger` in its sources. The former process-global `AtomicLong` id counter is GONE: ids now come from the run's threaded `State.nextId` ([`LLM.scala`]), so there is no module-level mutable state to bridge. The only `java.lang.ref` use is `internal.AIRef extends WeakReference[AI]`, a GC-reclaimability mechanism for the `State.instances` keys, not an unsafe-tier API. When threading new state, keep it in `State` and the `Op` GADT; do not introduce a process-global counter or an `AllowUnsafe` parameter.

## Test patterns

All tests extend `kyo.test.Test[Any]` ([`LLMTest.scala`], and every `*Test.scala` in the suite). Tests follow the 1:1 source-to-test rule (`LLM.scala` -> `LLMTest.scala`, `Agent.scala` -> `AgentTest.scala`, etc.); the module-wide invariants are folded into the per-source `*Test.scala` files. There is no `LLMInvariantsSpec` (a stale `LLMInvariantsTest.xml` may persist as a build artifact under `jvm/target`; ignore it).

### `TestCompletionServer`

Tests drive a real in-process HTTP server implementing the OpenAI and Anthropic wire protocols, bound on an OS-assigned ephemeral port within a `Scope` ([`TestCompletionServer.scala`]). It serves `POST /v1/chat/completions` and `POST /v1/messages`, captures each request body, and returns the next scripted response. Two modes: `TestCompletionServer.run` (non-streaming JSON) and `runStreaming` (SSE on both endpoints). Scripting is deterministic and per-test: `server.enqueueBody(json)` / `server.enqueueStream(chunks)` before the client call, popped one per request. `server.captured` reads the ordered `Captured(path, body)` records for wire-shape assertions ([`TestCompletionServer.scala`], [`LLMTest.scala`]). An opt-in `proxyToOpenAI` live path is gated on `KYO_LLM_LIVE_TESTS` + `OPENAI_API_KEY` and skipped by default.

### `TestDeciderServer`

The decider counterpart, in the same mould ([`TestDeciderServer.scala`]): an in-process server for TypeSafe AI's `POST /v1/systemone`, bound within a `Scope`, exposing `baseUrl` for `DeciderConfig.apiUrl`. It captures every raw request body (`server.captured`, a `Chunk[String]`), so a test asserts the outgoing request end to end (positional `q<i>` ids, the criteria shape, the `""` empty state, a one-shot context sent as given, the chat log's `calls` and `callId`s). Scripting: `enqueueBody(json)`, `enqueueStatus(code, body, headers)` (a non-2xx with response headers, for the request id and `Retry-After` paths), `enqueueNeverRespond` (a client-side timeout). With nothing scripted it answers each question of the request with a fixed, decodable answer of its kind, so a test that only cares about the request shape needs no script; a request it cannot read is a 400 naming the fixture, so a test bug reads as one. `TestDeciderServerTest` pins all of this.

### Pointing tests at the server and scripting a reply

`LLMTest` defines the shared helpers (each per-source test that needs them redeclares its own):

```scala
def serverConfig(baseUrl: String): Config =
    Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 128000).apiUrl(baseUrl)

def resultToolBody(envelopeJson: String): String = // an OpenAI body whose assistant calls result_tool with envelopeJson
```

([`LLMTest.scala`]). The eval loop always extracts from `result_tool`, so script the structured reply by wrapping it (`{"resultValue": <value>}` for a plain field, with `openingThoughts`/`closingThoughts` keys when thoughts are enabled) ([`LLMTest.scala`], [`Thought.scala`]).

### What to assert

Assert concrete values, not just types or non-emptiness: the scripted reply round-trips to a concrete `A` ([`LLMTest.scala`]), and the captured request body contains the override temperature and not the scope temperature. Compile-time row ascriptions (`val x: Int < LLM`, `def y: Int < (Async & Abort[AIGenException])`) are the proof that the rows are exact.

## Conventions

### Cross-platform discipline

All source and tests live in `shared/`. The `jvm/`, `js/`, `native/`, `wasm/` trees carry no `.scala` source; never move a test to a platform subtree to dodge a platform cost.

### Effect-row precision

Never widen the effect row of a `< LLM` computation to include `Async`; `Async` belongs only on `LLM.run`'s residual (the `Gen`, `Decide` and `Stream` interpretations) ([`LLM.scala`]). A new op goes in `LLM.internal.Op`, gets a `runWith` arm that introduces no `Async` for any op but those three, and is suspended via `ArrowEffect.suspend`. A `Mode.apply`'s `gen` parameter row is exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`, not wider ([`Mode.scala`]).

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

1. **New `Op`.** Is it a final `case class` (data) or `case object` (field-less)? Does its `runWith` arm thread `State` and introduce no `Async` (only `Gen`, `Decide` and `Stream` may)? Does the reply type in `Op[A]` match the `ArrowEffect.suspend` return? Does it appear in `crossRunFailure` if it targets an instance? [`LLM.scala`]
2. **Eval-loop change.** Does every non-`Gen` path stay `< LLM` (no `Async`)? Does the `Gen` residual stay `A < (LLM & Async & Abort[AIGenException])`? Does `summon[NotGiven[LLM <:< Async]]` still hold? [`LLMTest.scala`]
3. **New enablement kind or call.** Does it implement `AI.Enablement[S]`'s two `enableIn` methods (`AIEnv` and `AISession`)? Is it reached only through `AI.enable` / `ai.enable` (never a per-type binder)? Does the row carry `S` until the run boundary? [`AI.scala`]
4. **Config override.** Is an instance config a `Maybe[Config]` (`Absent` = inherit, `Present` = override)? Does `genLoop`'s merge keep `Present` winning over the scope and over a scope `withConfig`, while a mode `withConfig` still layers on top? [`LLM.scala`, `LLMTest.scala`]
5. **New `Mode`.** Is `apply`'s `gen` row exactly `Maybe[A] < (LLM & Async & Abort[AIGenException])`? Does the mode receive `ai` and read/write that instance only? [`Mode.scala`]
6. **New `Observe` fire site or `AIStats` field.** Does the site fire once per resolved completion call, before any adjudication that can abort the turn? Does a new `AIStats` field follow the subset convention (shared suffix, `Maybe` when a wire may not report it)? Do all four backends map their wire's fields, with a fixture test each? [`LLM.scala`, `AIStats.scala`]
7. **New `Agent` overload.** Does it delegate to `runImpl`, minting ONE stable instance via `AI.initWith(behavior)`, discharging with `LLM.run`, re-throwing `Abort[AIGenException]` via `getOrThrow`, and handing to `Actor.run`? [`Agent.scala`]
8. **New completion backend.** Does it match the result tool by `Completion.resultToolName` and substitute `resultSchema` when `Present`? Does it surface transport failures as `Abort[HttpException]`, never `Abort[Throwable]`? If it is command-backed, does it use the harness's native input/output shape and map process or decode failures to `AIDecodeException`? Is it reached through a new `Config.Provider` in `Provider.all`? [`Completion.scala`, `Config.scala`]
9. **New failure.** Is there a leaf in `AIException.scala` under the right operation trait(s), with its message on the leaf, mapped from any raw `HttpException` at the eval/stream boundary? Is a misuse/impossible-state panic kept off both rows? [`AIException.scala`, `LLM.scala`]
10. **New test.** Does it extend `kyo.test.Test[Any]`, use `TestCompletionServer` (not a live endpoint), assert concrete values, place each `enqueueBody`/`enqueueStream` before the consuming client call, and live in `shared/src/test`? [`TestCompletionServer.scala`, `LLMTest.scala`]
11. **New question kind or decider change.** Does each backend only answer, from the `Config` and `Context` it is handed (no `AI.config`, no `ai.context`), leaving no message on the instance and recording nothing itself (the completion backend generates under `AI.forget`), and reporting in `Reply.usage` only spend observers have not seen? Does a new question kind offer its keys to the completion backend (`LLMDecider.ask`), encode into `WireQuestion` for the endpoint and the recorded message, and map its `Answer` back to the caller's value in the glue's `Plan` decoder, never in a backend? Is any new wire or recorded shape a case class deriving `Schema`, not a hand-built `Structure.Value`? Is a new provider a `DeciderConfig.Provider` in `Provider.all` with catalog `entries` as stable values and a backend implementing `Decider.Backend`? Do its tests run against `TestDeciderServer` and `TestCompletionServer`? [`Decider.scala`, `DeciderConfig.scala`, `TestDeciderServer.scala`]

## Model facts are data, never inference

A completion implementation must not name a model, a model family, or a model version, and must not
describe how a particular model behaves. This covers comments and scaladoc, not only code: if a reader
can learn from a completion implementation that some named model differs from another, the rule is
broken.

Where models differ, the difference is declared on the catalog entry, alongside the model's name and
its context window, and the implementation reads the declared field without knowing which model it
came from. Adding a model means adding an entry that declares its facts; the constructors take those
facts without defaults, so an undeclared model is a compile error rather than a silent guess.

This replaced a set of predicates that parsed version digits out of model ids to infer what a wire
would accept. That approach put unverifiable model knowledge in code, went stale on every rename, and
sized an output ceiling from a reasoning budget on models whose wire refuses that budget, which stopped
generations early and was diagnosed as a harness failure.

Provider names remain legal in the implementations, which are named after providers, as does a
provider's own wire vocabulary inside the single function that decodes it.

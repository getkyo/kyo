<!-- doctest:default scope=inherited -->

# kyo-ai

<!-- doctest:setup
```scala
import kyo.*
```
-->

`kyo-ai` is an LLM integration where a call to a language model is a typed value you compose, not a request you orchestrate. You describe the result type and the tools the model may call; the module derives the JSON schema, runs the tool-call loop, decodes the reply, threads the conversation, retries transport failures, and parses streaming deltas. The boilerplate that a mainstream SDK leaves to you (schema authoring, the agentic while-loop, message-list threading, SSE parsing) is gone. What is left is the part that carries meaning: the type you want back and the capabilities you grant.

Here is a complete example. A typed result, a tool the model can call, one line to generate:

```scala
case class Question(text: String) derives Schema
case class Fact(topic: String, summary: String) derives Schema
case class Answer(text: String, confidence: Double) derives Schema

val factLookup =
    Tool.init[Question]("fact_lookup", "Look up a fact about a topic") { q =>
        Fact(q.text, s"A concise fact about ${q.text}.")
    }

val research =
    AI.enable(factLookup)(AI.gen[Answer]("What is a CRDT?"))
```

`AI.gen[Answer]` asks the model for an `Answer`; because `Answer derives Schema`, the module derives the result schema, forces the model to produce that exact shape, and decodes the reply into a typed `Answer`. `AI.enable(factLookup)(...)` surfaces the tool to the model. If the model calls `fact_lookup`, the runtime decodes the call arguments into a `Question`, runs your function, feeds the `Fact` back, and re-queries the model, looping until it produces the final `Answer`. You wrote the tool and the result type; the loop is the framework's. The same flow written by hand against a mainstream SDK is the comparison under [What this removes](#what-this-removes).

That value, `research`, is typed `Answer < LLM`: a pure description with no network in it. `LLM.run` is where it reaches the provider.

```scala
def runResearch: Answer < (Async & Abort[AIGenException]) =
    LLM.run(AI.enable(factLookup)(AI.gen[Answer]("What is a CRDT?")))
```

`LLM.run` discharges the `LLM` effect, and the residual gains `Async` (the call is concurrent) and `Abort[AIGenException]` (a generation can fail: transport, eval exhaustion, a malformed result). That is the one boundary where the program talks to the world.

## Choosing between a one-shot, a conversation, and an agent

Three types frame the whole module. They differ by what each adds along a single axis, statelessness to memory to persistence:

- **`LLM` is the effect, the capability.** Every program built with `gen`, tools, prompts, and thoughts is typed `A < LLM`, and `LLM.run` executes it. Each `AI.gen` on the bare effect is an independent one-shot with no memory. Reach for it when a call stands alone.
- **`AI` is a conversation that remembers.** You mint an instance with `AI.init`; every `ai.gen` on that instance accumulates into its own history, so a later turn sees the earlier ones. Memory lasts for one `LLM.run`. Reach for it when one call needs to know what an earlier call said.
- **`Agent` is a persistent, addressable entity.** It lives behind an actor, holds its conversation across many `ask` calls, and processes one input at a time. Reach for it when the conversation must outlive a single `run` and you want a long-lived thing to send inputs to.

The sections below climb that ladder: one-shot `gen` first, then remembering instances, then agents, with the generation-shaping surface (tools, prompts, thoughts, modes, and a compactor) layered in between.

## One-shot generation

The simplest call is a forgetful one-shot. `AI.gen[String](input)` (on the `AI` object, with no instance named) sends `input`, decodes the reply as a `String`, and remembers nothing.

```scala
def greeting: String < (Async & Abort[AIGenException]) =
    LLM.run(AI.gen[String]("Say hello in one sentence."))
```

Inside the `LLM.run` block the program is still data: `AI.gen[String]("...")` is typed `String < LLM`, composable, no I/O. `LLM.run` is the boundary that talks to the model, and the only place `Async & Abort[AIGenException]` enters, riding out on the residual. Nothing inside the block sees it.

`LLM.run` comes in three shapes for choosing the provider configuration. The no-argument form auto-selects a provider by probing for API keys; the function form transforms the auto-selected config; the explicit form takes a `Config` you built.

```scala
def autoProvider: String < (Async & Abort[AIGenException]) =
    LLM.run(AI.gen[String]("Name a primary color."))

def hotter: String < (Async & Abort[AIGenException]) =
    LLM.run(_.temperature(1.2))(AI.gen[String]("Name a primary color."))

def withConfig(config: AI.Config): String < (Async & Abort[AIGenException]) =
    LLM.run(config)(AI.gen[String]("Name a primary color."))
```

## Typed results

`A` does not have to be `String`. Any type with a `Schema` works, and the model is steered to fill that exact shape. This is where the running domain enters and stays: a research assistant over `Question`, `Answer`, `Fact`.

```scala
def graded =
    AI.gen[Answer]("How tall is the Eiffel Tower? Include a confidence score.")
```

The full shape of a single typed generation is `Answer < LLM` before `run` and `Answer < (Async & Abort[AIGenException])` after it. There is no JSON schema to write by hand, no arguments string to dig out of a response, no parse-and-validate step: the module derives the schema from `Schema[Answer]`, forces the structured reply, and decodes it.

`gen` also takes typed inputs. Each input is JSON-encoded into a user message before the request, so you pass structured context instead of pre-rendering it into a string. There are overloads for one through four inputs; multiple inputs fold into a tuple user message.

```scala
def fromQuestion(q: Question) =
    AI.gen[Answer](q)

def compare(a: Question, b: Question) =
    AI.gen[Answer](a, b)
```

> **Note:** every `gen` input is JSON-encoded into a user message, so an input type needs a `Schema`. The running-domain case classes all `derives Schema`.

## Instances that remember

A bare `AI.gen` is forgetful: two in a row do not share memory, because each mints an ephemeral slot, runs, and discards it. To carry a conversation, name an instance. `AI.init` (or `AI.initWith`, which hands the fresh instance to a body) mints a remembering instance; every `ai.gen` on it accumulates into that instance's own history.

```scala
def remembers =
    AI.initWith { ai =>
        for
            _        <- ai.userMessage("My name is Ada.")
            _        <- ai.gen[Answer]
            recalled <- ai.gen[String] // sees the first turn; recalls "Ada"
        yield recalled
    }
```

The instance threads its own `Context` through the run, so the second `gen`'s request carries the first turn's message. The difference between the two `gen` forms is exactly the memory: `AI.gen[A]` (the object) is the forgetful one-shot; `ai.gen[A]` (a named instance) remembers.

Two instances run independent threads with no cross-contamination. Each `gen` targets its own instance.

```scala
def researcherAndCritic =
    AI.init.map { researcher =>
        AI.init.map { critic =>
            for
                r <- researcher.gen[String]("Investigate CRDTs.")
                c <- critic.gen[String]("Critique the approach.")
            yield (r, c)
        }
    }
```

> **Caution:** an `AI` is an identity for one conversation slot, and its conversation lives in the state threaded by one `LLM.run`. An instance minted inside one `LLM.run` reads an empty context inside a different `LLM.run`, because each run threads its own fresh state. Do not cache an `AI` across run boundaries. To carry state across blocks within a run, use `snapshot` / `recover` (covered below).

## Tools and the automatic loop

A tool is a typed function the model may invoke mid-generation: the model decides to call it, the runtime decodes the arguments into your input type, runs your function, and feeds the result back so generation can continue. `Tool.init` builds one from an input type, a name, a description, and a run function; the output type is inferred (`factLookup` above is one).

`AI.enable` (scoped) and `ai.enable` (instance) register one or more enablements (tools, prompts, thoughts, modes, and a compactor, in any mix) over a computation; inside that scope, a `gen` exposes them. You never write the call loop: the eval loop surfaces the tool definition, detects the call, decodes the arguments with the tool's own `Schema`, runs your function, appends the result, and re-queries until the model produces the final answer.

```scala
def withFacts(q: Question) =
    AI.enable(factLookup) {
        AI.gen[Answer](q)
    }
```

`Tool.initSuperseding` is the same constructor with one addition, and it matters only when a compactor is enabled: a `Tool.Supersession(kind, key)` declaring how to read the identity of the state a call touches and whether the call reads or writes it. A later unit reusing the same key supersedes an earlier one (see Automatic context compaction, below). A tool built with plain `Tool.init` takes no part in supersession at all.

`Tool.aggregate` combines several tools into one, and `Tool.empty` is the no-tool aggregate, useful as a default.

```scala
val researchTools =
    Tool.aggregate(
        factLookup,
        Tool.init[Question]("define", "Define a term")(q => s"Definition of ${q.text}")
    )
```

The loop handles both failure modes for you, neither of which escapes the generation. If the model sends arguments that fail to decode, the runtime drops the bad call and injects a corrective system message asking the model to match the schema. If your run function throws, the failure is contained, turned into a tool-result message, and fed back so the model can read the error and retry.

> **Note:** a tool whose run function uses effects needs an `Isolate[S, LLM, S]` in scope; a pure run (the common case) infers `S = Any` and needs no import. The instance form `ai.enable(tool)` layers a tool onto one instance only, on top of the scope's tools.

## Shaping generation: prompts, thoughts, modes

The previous section added a capability the model can call. This section shapes how the model generates in the first place: the standing instructions it follows, the reasoning structure it must produce, and middleware that intercepts the generation.

### Prompts

A `Prompt` is a composable instruction set. A primary instruction is placed at the context start; an optional reminder floats at the context end, immediately before generation, so a long context does not push the critical guidance out of attention. The simplest prompt wraps a static string.

```scala
val precise =
    Prompt.init("You are a precise research assistant. Answer in one sentence.")
```

A prompt body is not limited to a static string. `Prompt.init` takes its instruction (and optional reminder) as a `String < (LLM & S)`, so the text can be computed from an effect, for example reading the active config to name the model it runs on.

```scala
val modelAware =
    Prompt.init(AI.config.map(c => s"You are running on ${c.modelName}. Be concise."))
```

`AI.enable` installs a prompt over a computation (the same `enable` that registers tools). `andThen` merges two prompts, deduplicating their instructions and reminders. The `p` interpolator normalizes per-line leading whitespace, for readable multi-line prompts in source.

```scala
val cited =
    precise.andThen(Prompt.init(p"""
        Cite the topic you were asked about.
        Never claim a confidence higher than the evidence supports.
    """))

def withPrompt(q: Question) =
    AI.enable(cited) {
        AI.gen[Answer](q)
    }
```

### Thoughts

A `Thought` makes the model reason as a structured, typed part of producing its answer, rather than as a separate free-text preamble.

The problem it solves: reasoning before answering (chain of thought) improves quality, but you also want a clean typed result. Prompting "think step by step" buries the reasoning in free text and yields no typed answer; forcing a typed result on its own makes the model jump straight to the answer with no reasoning. A `Thought` gets both, by adding reasoning **fields to the required output schema**, around the result. With one opening thought, the model is no longer asked for just a `resultValue`; it must fill an envelope shaped like this (illustrative):

```text
{ "openingThoughts": { "Reasoning": { "steps": "..." } },   // generated first
  "resultValue":     <Answer> }                              // generated second
```

A model fills the fields in order, top to bottom, so an **opening** thought's field is generated *before* the answer: the model writes its reasoning first, and that reasoning conditions the answer it then commits to. A **closing** thought's field is generated *after* the answer, acting as a self-check. You give the reasoning a shape with a plain type, and its `@doc` annotations become the instructions the model sees for that field:

```scala
import kyo.schema.doc

case class Reasoning(@doc("step-by-step working") steps: String) derives Schema
val reasonFirst = Thought.opening[Reasoning]

def reasoned(q: Question) =
    AI.enable(reasonFirst)(AI.gen[Answer](q))
```

The model must now emit a `Reasoning.steps` string before its `Answer`. The reasoning is typed and decoded like any other field (the thought registers under its type's unqualified name, `Reasoning`), and the schema enforces it, so the model cannot skip it. Opening thoughts steer the answer; closing thoughts review it.

Each thought also carries an optional `process` hook that fires on the decoded reasoning after generation, so you can verify it, record a metric, or drive a follow-up generation. `Thought.aggregate` combines several into one:

```scala
val checkedAnswer =
    Thought.aggregate(
        Thought.opening[Reasoning],
        // the closing hook receives the decoded Answer; verify it, record a metric, or re-generate here
        Thought.closing[Answer](_ => ())
    )
```

> **Note:** no reasoning is woven in by default. A built-in scaffold is available as `Thought.reflective` (a `Reflect` opening, in which the model states its understanding and commits to following the instructions, and a `Check` closing self-check); enable it with `AI.enable(Thought.reflective)(...)`, or compose it with your own via `Thought.aggregate`, when you want that nudge.

### Modes

A `Mode` is generation-interception middleware: it runs before, around, and after a generation, transparently to the caller. Enabled modes form a pipeline applied in registration order, and a mode can switch models, vary parameters, run parallel generations and synthesize them, or post-process.

`Mode.init` builds one from a transform that receives the target instance `ai` and the wrapped generation `gen` as a value (carrying its failures typed as `Abort[AIGenException]`), and returns a transformed generation, doing work before, around, or after it. Because `gen` is a value, a mode can run it zero, one, or many times. This one prepends a guardrail instruction before each generation it wraps:

```scala
val concise =
    Mode.init([A] => (ai, gen) => ai.systemMessage("Answer in one sentence.").andThen(gen))
```

`AI.withConfig` is the lighter sibling: it layers a transformed config for the duration of a body and restores it after, without a full mode.

```scala
def colderHere(q: Question) =
    AI.withConfig(_.temperature(0.1)) {
        AI.gen[Answer](q)
    }
```

### Composing binders

When two or more enablements apply to one generation, pass them to a single `AI.enable`: it takes any mix of tools, prompts, thoughts, modes, and a compactor as varargs (or a `Seq`), applied in argument order, rather than nesting `enable` blocks.

```scala
def fullyShaped(q: Question) =
    AI.enable(precise, factLookup, reasonFirst) {
        AI.gen[Answer](q)
    }
```

## Long-lived agents

An `Agent` is the persistent layer. Where an `AI` instance lives for one `LLM.run`, an agent is an actor-backed entity that holds its conversation across many `ask` calls, processing one input at a time. Its behavior receives its own `self: AI`, and because the parked actor continuation keeps that instance's conversation alive, the thread persists between asks. Reach for it when you want an addressable, long-lived entity rather than a single threaded computation.

`Agent.run[In] { (self, in) => ... }` mints the agent in its ergonomic form. The behavior generates against `self`; `ask` sends a typed input and awaits the typed reply.

```scala
def chatAgent: Answer < (Async & Abort[Closed] & Scope) =
    Agent.run[Question] { (self: AI, q: Question) =>
        self.gen[Answer](q.text)
    }.map { agent =>
        for
            first  <- agent.ask(Question("What is the capital of France?"))
            second <- agent.ask(Question("And its population?")) // remembers the first ask
        yield second
    }
```

`ask` completes a closed mailbox as `Abort[Closed]` and an aborting behavior as the agent's `Abort[Error]`, never a throw. `agent.close` stops the mailbox and returns any inputs still queued.

To supply a config and any mix of enablements, pass them after the type parameter; `Agent.run` enables them in argument order, then runs `LLM`, so the behavior itself stays a plain `gen`.

```scala
def researchAgent(config: AI.Config): Answer < (Async & Abort[Closed] & Scope) =
    Agent.run[Question](config, precise, factLookup, reasonFirst) { (self: AI, q: Question) =>
        self.gen[Answer](q.text)
    }.map { agent =>
        agent.ask(Question("Summarize the Treaty of Westphalia."))
    }
```

For control beyond receive-all, `Agent.runBehavior` runs a custom actor behavior with the same config and enablements, and `Agent.receiveLoop` continues or stops per the outcome of each message.

```scala
def boundedAgent: Result[Closed, String] < (Async & Scope) =
    val behavior: Unit < (Agent.Context[String, String] & LLM) =
        Agent.receiveLoop[String] { (in: String) =>
            if in.toIntOption.exists(_ < 3) then Loop.continue(in.toUpperCase)
            else Loop.done
        }
    Agent.runBehavior[String](_ => behavior).map { agent =>
        Abort.run[Closed](agent.ask("1"))
    }
end boundedAgent
```

## Streaming

`AI.stream[A]` (or `ai.stream[A]`) projects a generation as a `Stream`, in one of two forms inferred from `A`. The result tool rides every streaming request, so the model always has a tool to call.

For a `String`, the stream is incremental text chunks whose concatenation is the final answer. This is the chat-UI, token-by-token case.

```scala
def streamedText: Chunk[String] < (Async & Abort[AIStreamException | AIGenException] & Scope) =
    LLM.run {
        AI.stream[String].map(_.run)
    }
```

For any other type, the stream is object by object: the model produces a sequence of `A`, and each element is emitted once it is complete, never a half-filled value. This is the iterable case, for extracting or generating multiple records.

```scala
def streamedAnswers: Chunk[Answer] < (Async & Abort[AIStreamException | AIGenException] & Scope) =
    LLM.run {
        AI.stream[Answer].map(_.run)
    }
```

A fully consumed stream joins the conversation: the turn is recorded once its elements are drained, so a later `gen` or `stream` can read what was streamed, and a stream abandoned part way records nothing. The element row carries `LLM` for that write-back, so a stream is consumed inside `LLM.run`.

The stream's element row also carries `Scope` because the SSE connection is held open until the stream terminates or errors, so running it adds `Scope` to the residual. You write no SSE parsing, no fragment accumulation, no incremental-decode attempt.

## Parallel generation

`AI.gen` over `< LLM` composes with the structured-concurrency combinators (`Async.foreach`, `Async.fill`, `Async.race`) through one public given. Bring it into scope and fan out.

```scala
import LLM.given

def answerAll(questions: Chunk[Question]) =
    Async.foreach(questions)(q => AI.gen[Answer](q))
```

The given is the asymmetric `isolate`: on join it merges each shared instance's conversation prefix-aware (so the shared history is never duplicated) and adds fork-born instances as-is. You do not manage threads or per-conversation state isolation across branches; the isolate does both.

## Tracking usage

Every completed model turn reports what it spent. `Observe.withStats` collects it over a scope, alongside the result:

```scala
def measured(question: String): (AIStats, String) < (LLM & Sync) =
    Observe.withStats(AI.gen[String](question))
```

`AIStats` carries `inputTokens`, `outputTokens`, and `turns`, plus two subsets the wire may break out: `cachedInputTokens` (part of `inputTokens` served from the provider's cache) and `reasoningOutputTokens` (part of `outputTokens` spent reasoning). The subsets are `Maybe`, so a wire that reports zero stays distinguishable from one that reports nothing; `add` aggregates any two.

Token counts stop being a price once the provider caches prompt prefixes: a large stable prefix is billed at a steep discount, while a small prefix rewritten every turn is billed again and again. `kyo.ai.CacheCost` prices that. `CacheCost.reported(stats)` turns collected `AIStats` into a `CacheCost.Session`, `session.equivalents(CacheCost.Rates())` prices it in uncached-input-token equivalents, `session.hitRate` is the fraction billed at the cached rate, and `session.invalidations` counts the turns that rewrote the prefix instead of extending it, which is what separates a compacting session from an append-only one. `Rates` is a parameter rather than a constant, because the ratios differ per provider. Offline, with no provider in the loop, `CacheCost.estimate` derives the same split from the longest common prefix between consecutive views; it is an estimate and worth labelling as one, but it is still cache-aware, which a bare "tokens sent" number is not.

The varargs form breaks the count down by named instances. Every named instance appears, `AIStats.empty` if it completed no turn; spend by any other instance stays out of the breakdown (wrap in the untargeted form for the scope total):

```scala
def perAgent(researcher: AI, writer: AI): (Dict[AI, AIStats], String) < (LLM & Sync) =
    Observe.withStats(researcher, writer) {
        researcher.gen[String]("Gather the facts.").map(facts => writer.gen[String](facts))
    }
```

Counting is a side effect at the source: each turn is recorded on the fiber that ran it, at the moment the wire reply is read. So a rolled-back `AI.forget` block, a losing `Async.race` branch, and an `AI.gen` one-shot all count the turns they completed, and nothing can un-spend them. A turn interrupted before its reply arrives is uncounted (no number ever reached this side of the wire), and an abandoned stream records nothing. One placement rule for streams: a scope-enabled observer covers a streamed turn only when the stream is consumed inside the enabling bracket, while an instance-enabled observer covers its instance's streams wherever they are consumed.

Underneath sits a sixth enablement kind: `Observe`, a wire-tier counterpart of `Mode` that cannot change control flow. Where a mode receives the generation as a value and returns what the caller sees, an observer receives each completed turn's reply (its messages and usage) and returns `Unit`. Enable one on a scope or an instance like any other enablement:

```scala
def logged[A, S](v: A < (LLM & S)): A < (LLM & S) =
    val log = Observe.init { (ai, reply) =>
        AI.config.map(c => Log.info(s"${c.modelName}: ${reply.usage.totalTokens} tokens"))
    }
    AI.enable(log)(v)
end logged
```

`AI.config` inside the callback is the config the turn ran under, instance overrides included, and `ai.context` is the conversation up to (not including) the turn. An observer whose capability row carries `Abort[E]` is a typed guardrail: its failure fails the generation it fired in, visible in the row at the enable site.

```scala
case class BudgetExceeded(spent: Long)

def capped[A, S](limit: Long)(v: A < (LLM & S)): A < (LLM & S & Abort[BudgetExceeded] & Sync) =
    AtomicRef.init(0L).map { spent =>
        val guard = Observe.init { (_, reply) =>
            spent.updateAndGet(_ + reply.usage.totalTokens).map { total =>
                Abort.when(total > limit)(BudgetExceeded(total))
            }
        }
        AI.enable(guard)(v)
    }
```

## Controlling conversation state

Once a conversation has history, you sometimes need to run something against it without changing it, or run a clean turn that ignores it. `AI.forget` and `AI.fresh` isolate state for a block; each has a whole-scope form and a per-instance form.

`AI.forget(v)` runs `v`, then rolls back conversations to their pre-`v` state, discarding `v`'s writes. The no-argument form rolls back every instance (a scope-wide rollback); `AI.forget(ais*)` rolls back only the named instances, so other instances' writes persist.

```scala
def speculate(ai: AI): String < LLM =
    AI.forget(ai)(ai.gen[String]) // the speculative turn leaves ai's history untouched
```

`AI.fresh(v)` runs `v` with conversations blanked (enablements and config kept), then restores them on exit. The no-argument form blanks every instance; `AI.fresh(ais*)` blanks only the named ones. Use it for a turn that must not be biased by what the conversation said so far.

```scala
def unbiased(ai: AI): String < LLM =
    AI.fresh(ai)(ai.gen[String]) // ai generates with no inherited history
```

To carry a conversation across blocks within a single run, `ai.snapshot` captures an instance's full in-memory state (conversation, enablements, config) as an `AISession`, and `AI.recover(session)` recreates an instance from it.

```scala
def branchAndRestore(ai: AI): String < LLM =
    for
        saved    <- ai.snapshot
        _        <- ai.gen[String]    // a speculative branch
        restored <- AI.recover(saved) // a fresh instance at the saved state
        answer   <- restored.gen[String]
    yield answer
```

An `AISession` holds code (tool runners, effectful prompts, modes), so it is in-memory only and not serializable across runs. The serializable slice is `session.context`, the conversation history (`AI.Context derives Schema`). To persist a conversation across runs, store `session.context` and reseed a fresh instance's history from it.

## Automatic context compaction

Where `forget`, `fresh`, and `snapshot` (above) give you manual control over a conversation's state, a `Compactor` manages a conversation's SIZE automatically. A conversation's raw transcript is the complete record: every message ever sent or received, appended and never rewritten in place. Left unchecked, that transcript grows without bound, and a long session eventually cannot fit inside the model's context window at all, or fits so tightly that a stale message crowds out the tail the model actually needs. A `Compactor` addresses this automatically. Enable one, and the model instead sees a bounded, projected VIEW of the transcript, recomputed at one seam shared by `gen` and `stream`. The view the model reads is bounded, and the transcript itself is bounded too: it is append-only up to a retention cap, past which the oldest already-summarized regions are forgotten wholesale and replaced by a coarse band marker, so a long session cannot accumulate without limit in memory. This pairs naturally with `Agent`: a long-lived agent's conversation grows across every `ask`, which makes it the prime candidate for automatic compaction.

### Enabling one, and switching it off

A compactor is default-off: with none enabled, a generation is byte-identical to one with no compactor. Enable it exactly like any other enablement: `Compactor.init` is a plain, side-effect-free value, not `< Sync`, so there is no `.map` and no extra effect row, just `AI.enable(Compactor.init)(v)` or `ai.enable(Compactor.init)`.

```scala
def keptBounded(ai: AI): String < LLM =
    ai.enable(Compactor.init).map(_.gen[String]("Continue the investigation."))
```

The scoped form `AI.enable(Compactor.init) { ... }` layers a compactor over a computation the same way it layers a tool or a prompt. `Compactor.none` is the explicit pass-through, which is a different thing from enabling none at all: it restores the raw context and runs the session with no compaction and no overflow protection, leaving the caller to own the context bound.

The no-arg `Compactor.init` returns one shared instance, so reusing it across many `LLM.run` calls and many `AI` instances is safe by construction, while `Compactor.init(tuning)` builds a fresh one per call. Either way the compactor itself holds no per-session state: the framework creates one `Compactor.State` per session on first use and releases it at teardown, so a snapshot loses only work that was in flight. What is currently demoted, and since when, is re-derived from the conversation's two lists at every boundary rather than cached.

### What a boundary does

The shipped compactor ranks units structurally, from message adjacency and identifier references, optionally augmented by dependency and relatedness edges a lightweight model analysis pass emits.

Compaction is consulted at one seam between the context read and request assembly, on both the `gen` and `stream` paths. The seam calls `compact` on every turn, so the compactor owns the trigger as well as the rewrite. `Decision.Unchanged` means the framework re-serves the context it passed in, byte for byte, which is what keeps the provider's prefix cache alive; `Decision.Compacted(ctx)` hands back the rewritten context, which the framework installs. A boundary fires at `trigger`, a fraction of the model's window clamped by `contextCeiling`, and renders down to `target`, a fraction of that effective trigger: units are ranked by LIVENESS, a measure of how much the rest of the conversation still refers back to them, and demoted along a four-level detail ladder (verbatim, summary, terse, pointer) in ascending liveness order until the view fits. A demoted span can carry an extractive summary produced by a cheap-tier fill model; below a size threshold a demoted unit renders as a short, mechanically assembled marker instead. Occupancy anchors on the provider's own reported token total once one is available.

Recent content is protected before any of this: the tail band, and any turn whose tool calls are still unresolved, are never eligible for demotion at all, because span formation excludes them. Once occupancy crosses the hard window fraction (`hardLimit`, a fraction of the model window less the output reservation), a deterministic forced path takes over: it demotes every eligible span to a pointer in ascending liveness order, then elides the single largest remaining message. If the view still exceeds `hardLimit`, the turn aborts with `AIContextOverflowException` rather than sending an over-limit request to the provider.

The projected view is byte-identical between updates, so the provider's prompt cache survives across turns that do not trigger an update. An update pays cache invalidation once, from its edit point onward, never for the whole view; `CacheCost.Session.invalidations` counts the turns that paid it (see [Tracking usage](#tracking-usage)).

### Getting demoted content back

A demoted region is never simply lost: a `recall(id)` tool is auto-registered per calling instance (never shared across instances or sessions, since unit ids are transcript-local indices that collide across sessions) and returns a demoted region's full original content verbatim, as a fresh tool result. This is a tool the caller did not register: enabling a compactor silently adds `recall` to every generation in scope, so a reader inventorying their own tool list should expect it to appear.

> **Note:** `recall` is registered only while `Mechanism.Recall` is in `Tuning.mechanisms`; dropping that member removes the tool. It arrives through `Compactor.tools(ai)`, the channel any compactor uses to contribute per-instance tools, so a custom compactor can add its own the same way.

A tool can also opt into compaction-aware supersession by building it with `Tool.initSuperseding` (covered above, in Tools and the automatic loop): a later unit that reuses the same key supersedes an earlier one, except a read that follows a write, which never supersedes the write.

```scala
case class FileArg(path: String) derives Schema

val readFile =
    Tool.initSuperseding[FileArg](
        name = "read",
        supersession = Tool.Supersession(Tool.Kind.Read, (a: FileArg) => Present(a.path))
    )(a => s"contents of ${a.path}")

val writeFile =
    Tool.initSuperseding[FileArg](
        name = "write",
        supersession = Tool.Supersession(Tool.Kind.Write, (a: FileArg) => Present(a.path))
    )(a => s"wrote ${a.path}")
```

> **Caution:** supersession keys share one namespace across every registered tool, not a namespace per tool. Two unrelated tools that happen to emit the same key string will supersede each other's units. Pick keys that are unique across the whole tool set, for example by prefixing the key with the tool's own name.

### Tuning the policy

Tuning lives on the compactor, not on the ambient config: `Compactor.init(Compactor.Tuning(...))`. Ten knobs, and they are all policy a caller has a reason to set.

> **Caution:** the per-field builders (`Compactor.Tuning().trigger(0.6).target(0.5)`) clamp each value into range and keep `prepare` above `target` as they set it, so a fraction that would invert the axis is corrected as you build. The constructor and `copy` do not: they take the pair as written, and `Compactor.init` then rejects an inverted one outright. Either path is safe; the builders adjust, the constructor refuses.

`trigger` is the boundary line, a fraction of the model window, with `contextCeiling` as an absolute clamp on it (`noContextCeiling` drops the clamp). `target` is the render-down depth, a fraction of the effective trigger, and `prepare` is where speculative compaction arms (`1.0` turns it off). `hardLimit` is the overflow backstop, a fraction of the window less the output reservation. `summarizer` pins a `Config` for the summary fill; unset uses the provider's cheap tier degraded. `rawRetentionCap` bounds the raw transcript's memory. `summaryOutputCap` bounds what one fill may spend.

`keepShare` decides what stays verbatim, as a multiple of the uniform liveness share `1/N` rather than an absolute score: liveness is a share of one unit of mass spread over N regions, so an absolute floor is arithmetically certain to exceed every typical score once a session grows, which closes the verbatim channel exactly when it matters.

`mechanisms` is the disable set. Compaction is eight cooperating mechanisms (`Analysis`, `SummaryFills`, `Preparation`, `KeyedSupersession`, `AnalyzedSupersession`, `Repoint`, `ContentReferences`, `Recall`), and dropping a member from the set stops that mechanism's own work: `Compactor.init(Compactor.Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Analysis))` runs everything but the relation pass, and `Mechanism.none` leaves structural ranking and presentation with not one model call.

The per-field builders clamp what they set, and `prepare` is raised above `target` automatically, so an axis built that way stays ordered; the projection onto a particular model's window happens where the compactor uses it, since one tuning serves many models. The token accountant is separate and stays on the config: `config.tokenizer(...)`, taking a `kyo.ai.Tokenizer` (defaulting to a bundled offline tiktoken tokenizer), cleared with `noTokenizer`. The constants behind the ranking itself (edge weights, seed shares, presentation budgets) are internal to the module; the escape hatch for different mechanics is implementing `Compactor`, not another knob.

### Writing your own compactor

`Compactor` is open, and implementing it is the way to different mechanics. The boundary is one decision method plus one opaque state cell:

- `type State` and `initState` are whatever your strategy needs to carry across turns without persisting it. Use `Unit` if that is nothing. The framework creates one per session, hands the same value back on every call, and passes it to `release` at teardown, so `release` must be idempotent.
- `compact(ctx, state)` answers `Decision.Unchanged` or `Decision.Compacted(ctx)`. Answering `Unchanged` is what preserves the provider's prefix cache, and it does so structurally: a compactor that took no boundary has no way to return modified bytes. There is no watermark to consult, because the seam holds no trigger; deciding WHEN to compact is part of your strategy.
- `tools(ai)` contributes per-instance tools, which is the channel the default compactor's `recall` arrives through.

Two rules are easy to miss because the signature expresses neither. The first: a `Decision.Compacted` must preserve `raw`. Every entry of the returned `raw` has to be identical to the entry at that index, or a marker carrying an `origin` for what it replaced, and the length may not change. The framework checks this and fails the turn through `Abort[AIGenException]` rather than continue against a rewritten transcript. Rebuild `compacted`; leave `raw` alone unless you are implementing a retention backstop.

The second: the view you return may not advertise recall over content that is gone. A marker carrying an `origin` has to stand over raw that is still there, or be the forgotten band's own representative. Serve a marker pointing into a band it does not represent and the framework fails the turn the same way, because the alternative is inviting the model to call `recall` on bytes no longer held.

## Configuration and providers

`AI.Config` is an immutable, copy-on-write settings record naming the provider, model, and runtime knobs (temperature, seed, timeout, retry schedule, iteration cap, reasoning). Every builder returns a modified copy.

```scala
val openAiConfig =
    AI.Config.OpenAI.default
        .apiKey("sk-...")
        .temperature(0.2)
```

> **Note:** not every knob reaches every backend, and a knob that does not reach one is dropped silently rather than refused. `seed` is carried only by the OpenAI-compatible backends. `temperature` reaches a wire only where the model's catalog entry declares it accepted, so an entry with `acceptsTemperature = false` ignores it; the Claude Code and Codex harnesses use their own account transports and take neither `temperature` nor `apiKey` nor `apiUrl`; and on Anthropic a temperature reaches the wire only with thinking off, which is not the default.

Reasoning is on by default, so models reason before answering, at the cost of extra output tokens and latency on every generation. `disableReasoning` turns it off. Whether to reason and how much are stated separately, because they are different questions and the endpoints answer them in different vocabularies.

```scala
val budgetedReasoning =
    AI.Config.Anthropic.default.reasoningBudget(20000) // a bound in tokens
val gradedReasoning =
    AI.Config.DeepSeek.default.reasoningLevel("high") // a word from that wire's own levels
val noReasoning =
    AI.Config.Anthropic.default.disableReasoning
```

### Reasoning

How much reasoning a request can state is a fact declared on the catalog entry, never inferred from the model's name. An entry declares one of five encodings:

- a **token budget** that bounds reasoning,
- **self-sized** reasoning the model scales itself,
- a **graded level** drawn from that wire's own set of words,
- **provider-managed** reasoning, with no field to state an amount at all, or
- **none**.

It also declares how its endpoint says "do not reason", which genuinely differs across wires: one omits the activation block, one sends an explicit deactivation object, one sends a level word meaning none, and a harness exports an environment switch.

Levels stay the wire's own words rather than a kyo vocabulary, because the sets do not agree: three endpoints enumerate three different sets, overlapping in the middle and differing at the ends. A level an entry does not declare is reported and still sent, leaving the endpoint the authority, so a stale list never refuses a value the wire would accept.

An untouched config states no amount, so the entry's own default applies. A stated amount an entry's encoding cannot express is named in the log and the request is built as if it were absent, rather than failing, so one config stays re-aimable across providers. While reasoning is off nothing rides, and an amount stated then is held rather than dropped.

Whether a request carries a reasoning activation field also decides the result tool's `tool_choice`: two endpoints refuse a forced tool call while reasoning is active, so an active request leaves the call to the model and the eval loop's repair turn covers the rare reply that skips it. A deactivated request still forces, because a deactivation is not an activation.

### Output ceilings

The reasoning declaration also sizes the request's output ceiling, because reasoning tokens count against it. Where a budget bounds reasoning, the ceiling clears that budget with room for the result. Everywhere else nothing bounds reasoning from this side, so the ceiling defaults to the model's own maximum; a smaller ceiling would let reasoning consume the whole allowance and stop the reply before it produced anything. A level names no token count, so nothing can be added for it: only the endpoint knows what a level costs.

An unset ceiling is the model's own maximum, sent rather than withheld, so the limit in force is the one the entry declares. That holds only where the maximum is the provider's own: an entry declaring `OutputMaximum.Unverified` has no published or probed bound, so nothing is sent and an over-large ask is left for the endpoint to refuse and name the real limit.

A reply that stops at the ceiling with nothing to act on fails with `AIOutputLimitException`, naming the ceiling the request carried. It is not retried, because an identical request spends the whole ceiling again to stop in the same place; the levers are raising `maxTokens` toward the model's maximum, asking for less output, or choosing a model whose reasoning is budget-bounded. A reply that stopped at the ceiling but still carries a usable tool call is delivered as a partial turn instead, and the next turn starts against a fresh ceiling. On the command-harness path the ceiling bounds each of the harness's internal attempts, which it retries, so a call that keeps colliding is billed for several times the ceiling before the failure surfaces.

### Declaring a model

To use a model the catalog does not list, declare its facts with `Config.model(provider, name, contextWindow, outputMaximum, reasoning, acceptsTemperature, acceptsImages)`. To re-point an existing entry at an equivalent id, when a snapshot, fine-tune, or proxy alias shares that entry's capabilities, use `modelName(...)`.

### The providers

The module ships eleven providers, Anthropic, OpenAI, DeepSeek, Gemini, Groq, xAI, Moonshot, Baseten, OpenRouter, Claude Code, and Codex, each available as `AI.Config.Anthropic`, `AI.Config.OpenAI`, and so on, and together as `AI.Config.Provider.all`. Each is a pure catalog whose `.default` you refine with builders.

`AI.Config.default` selects one: it first honors the override flag `kyo.ai.provider` (environment variable `KYO_AI_PROVIDER`), then probes provider markers and keys in order, preferring `CLAUDE_CODE`, then `CODEX`, then API keys such as `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`. Override values are `claude-code`, `codex`, `anthropic`, `openai`, `deepseek`, `gemini`, `groq`, `xai`, `moonshot`, `baseten`, and `openrouter` (`grok` and `kimi` are also accepted for those two families).

`AI.Config.init` builds a config for an API-key provider, reading the key and org from system properties then the environment. It takes the model's declared facts, so a model the catalog does not list is stated rather than guessed.

Each provider also exposes a `.small` catalog entry, its cheap tier. The compactor's summary fill route uses it as the degraded default model when the compactor's `summarizer` tuning is unset, and it is generally useful for cost-sensitive sub-generations.

```scala
def initConfig: AI.Config < Sync =
    AI.Config.init(
        AI.Config.Anthropic,
        "claude-sonnet-4-5-20250929",
        contextWindow = 200000,
        outputMaximum = AI.Config.OutputMaximum.Verified(64000),
        AI.Config.ReasoningEncoding.TokenBudget,
        acceptsTemperature = true,
        acceptsImages = true
    )
```

The no-argument `LLM.run` resolves its config with `AI.Config.default`, which probes provider markers and API keys (system properties first, then environment variables) and selects the first present, falling back to Anthropic. Retries and timeouts are wired into the eval loop, configured here: the completion call is wrapped meter, then retry, then timeout.

```scala
def reliable(q: Question): Answer < (Async & Abort[AIGenException]) =
    LLM.run(_.retrySchedule(Schedule.repeat(3)).timeout(30.seconds)) {
        AI.gen[Answer](q)
    }
```

> **Caution:** `AI.Config.default` is effectful, typed `AI.Config < Sync`, because it probes system properties and environment variables. It is not a pure `val` and must be `.map`ped. The per-provider `.default` values (such as `AI.Config.OpenAI.default`) are pure and safe to use directly.

The `LLM` boundary emits debug logs through `kyo.Log`; enable a debug logger around your program to see the backend that actually ran:

```text
kyo-ai gen backend=Claude Code model=sonnet messages=3 tools=1 thoughts=0 forceResult=false
```

## When a generation fails

The error model is principled and typed. A generation's failures ride `run`'s residual as `Abort[AIGenException]`, a sealed hierarchy whose leaves name the specific failure: a transport error is an `AITransportException` (wrapping the kyo-http `HttpException`), eval-loop exhaustion an `AIEvalExhaustedException`, an invalid thought name an `AIInvalidThoughtException`, an undecodable reply an `AIDecodeException`, a missing API key an `AIMissingApiKeyException`, a view that still exceeds the hard window after the forced path has done everything it can an `AIContextOverflowException`. Streaming failures are typed in the stream's own row as `Abort[AIStreamException]`: a malformed delta is an `AIStreamDeltaException`, a stream that ends without a decodable value an `AIStreamIncompleteException`. Two of the super-types track the OPERATION (`AIGenException`, `AIStreamException`), so a failure shared by both (a missing key, a transport error) belongs to both. Two more track BLAME, and they are the ones retry reads: `AIProviderException` groups the failures where the provider or the account is at fault, and `AITransientException` refines that to the ones that are temporary by nature. The retry clause names `AITransientException` alone, which is why a throttle or a transport blip is retried on `retrySchedule` while a missing key or a rejected request surfaces immediately. Misuse stays off the rows: using an `AI` outside the `LLM.run` that created it panics with `AICrossRunException`.

## Building and persisting a conversation by hand

`AI.Context` is the conversation history: an immutable pair of `Chunk[Message]` lists, `raw` and `compacted`, with builders that append and return a new `AI.Context`. `raw` is the append-only transcript, bounded by the retention cap; `compacted` is the bounded view actually sent to the model, recomputed by a `Compactor` when one is enabled (see Automatic context compaction, above). The two lists are identical whenever no compactor is active. `AI.Context` is what the per-instance histories are made of, and it `derives Schema`, so it can be persisted.

```scala
val transcript =
    AI.Context.empty
        .systemMessage("You are a helpful assistant.")
        .userMessage("What is 2 + 2?")
        .assistantMessage("4")
```

`AI.Context.merge` is prefix-aware: it appends only the non-common suffix of the argument, never duplicating shared history. The message subtypes are `SystemMessage`, `UserMessage`, `AssistantMessage`, and `ToolMessage`, each tagged with a `Role` carrying the exact provider wire-string. `AI.Image` carries a base64 payload for a vision-capable user message, built via `AI.Image.fromBase64` or `AI.Image.fromBytes`.

```scala
val withImage =
    AI.Context.empty.userMessage("What is in this picture?", Present(AI.Image.fromBase64("...")))
```

## What this removes

Two of those removals made concrete, against the manual path a mainstream SDK leaves to you.

The agentic tool-call loop, by hand, is a `while` loop plus per-call dispatch by name, argument parse, the run, error-to-message feedback, and message-list threading, all of which you write and maintain.

```python
messages = [{"role": "user", "content": question}]
while True:
    resp = client.chat.completions.create(model="gpt-5.4", messages=messages, tools=tool_defs)
    msg = resp.choices[0].message
    messages.append(msg)
    if not msg.tool_calls:
        break
    for call in msg.tool_calls:
        fn = registry[call.function.name]
        try:
            args = json.loads(call.function.arguments)
            content = json.dumps(fn(**args))
        except Exception as e:
            content = f"error: {e}"
        messages.append({"role": "tool", "tool_call_id": call.id, "content": content})
final = messages[-1].content
```

In kyo-ai that whole loop is the framework's; you supply the tool and the result type (`factLookup` and `Answer` from the top):

```scala
AI.enable(factLookup)(AI.gen[Answer]("What is a CRDT?"))
```

Streaming, by hand, is SSE plumbing: open the connection, parse each `data:` line, skip `[DONE]`, accumulate the tool-call argument fragments across deltas, and attempt to decode the growing buffer.

```python
stream = client.chat.completions.create(model="gpt-5.4", messages=msgs, stream=True, tools=tool_defs)
buf = ""
for event in stream:
    delta = event.choices[0].delta
    if delta.tool_calls:
        buf += delta.tool_calls[0].function.arguments or ""
        try:
            yield json.loads(buf)
        except json.JSONDecodeError:
            pass
```

In kyo-ai that is a `Stream` of decoded values:

```scala
val answerStream =
    AI.stream[Answer]
```

The pattern holds across the comparisons above: what you write names the result you want, and the mechanics that produce it (the schema, the loop, the incremental decode) are the module's to carry.

## How it works

`LLM` is a custom `ArrowEffect` whose operations carry data: a program typed `A < LLM` is a tree of virtual operations with no `Async` in its row, reading and appending to per-instance conversation histories held in one threaded `State`. The single operation that reaches the world is `Gen`, whose handler runs the eval loop; that is where `Async` and `Abort[AIGenException]` enter, riding out on `run`'s residual. When a compactor is enabled (either on the scope or the instance, instance winning), it is consulted at one seam between the context read and request assembly, on both the `gen` and `stream` paths, returning the bounded view sent to the provider; the transcript held in `State` is never mutated by this seam. The completion call is wrapped meter, then retry, then timeout, and four backend adapters sit behind `AI.Config.Provider`: an OpenAI-compatible HTTP adapter shared by eight providers, an Anthropic HTTP adapter, plus Claude Code and Codex command harness adapters. Each adapter implements the shared `Completion` contract; its `apply` returns a `Completion.Reply`, carrying the reply messages, a `StopReason`, and the turn's `AIStats` usage, which is empty when a provider reports nothing. The eval boundary emits debug logs through `kyo.Log` naming the selected backend, model, message count, tool count, and streaming mode. For the operation GADT, the state-threading handler, and the asymmetric `Isolate` that backs parallel branches, see `kyo-ai/shared/src/main/scala/kyo/LLM.scala` and CONTRIBUTING.md.

## Demos

Runnable end-to-end demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). Run any with `sbt 'kyo-aiJVM/Test/runMain demo.<Name>'`. Select the provider with the `KYO_AI_PROVIDER` environment variable on the command itself, since a `-Dkyo.ai.provider=...` argument before the sbt task configures the sbt JVM and may not reach the forked demo JVM.

- [**TypedGenerationDemo**](shared/src/test/scala/demo/TypedGenerationDemo.scala): schema-derived typed generation into a case class.
- [**ConversationDemo**](shared/src/test/scala/demo/ConversationDemo.scala): one persistent `AI` instance carrying multi-turn history.
- [**ToolCallDemo**](shared/src/test/scala/demo/ToolCallDemo.scala): Kyo tool registration, model tool calls, tool execution, and final typed answer.
- [**StreamingDemo**](shared/src/test/scala/demo/StreamingDemo.scala): text-chunk streaming and object-by-object streaming.
- [**HarnessCompletionDemo**](shared/src/test/scala/demo/HarnessCompletionDemo.scala): command-backed harness providers with image input and retained history. It prints the resolved provider and model before running.
- [**AgentDemo**](shared/src/test/scala/demo/AgentDemo.scala): a small typed `Agent` retaining its own conversation.
- [**SamplingDemo**](shared/src/test/scala/demo/SamplingDemo.scala): parallel sampling and synthesis.
- [**ThoughtsDemo**](shared/src/test/scala/demo/ThoughtsDemo.scala): thought extraction alongside a final answer.
- [**WikiResearchDemo**](shared/src/test/scala/demo/WikiResearchDemo.scala): a richer tool-backed research flow.

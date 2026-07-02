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

`AI.gen[Answer]` asks the model for an `Answer`; because `Answer derives Schema`, the module derives the result schema, forces the model to produce that exact shape, and decodes the reply into a typed `Answer`. `AI.enable(factLookup)(...)` surfaces the tool to the model. If the model calls `fact_lookup`, the runtime decodes the call arguments into a `Question`, runs your function, feeds the `Fact` back, and re-queries the model, looping until it produces the final `Answer`. You wrote the tool and the result type; the loop is the framework's. The same flow in a mainstream SDK is roughly forty lines of orchestration (covered under [What this removes](#what-this-removes)).

That value, `research`, is typed `Answer < LLM`: a pure description with no network in it. `LLM.run` is where it reaches the provider.

```scala
def runResearch: Answer < (Async & Abort[AIGenException]) =
    LLM.run(AI.enable(factLookup)(AI.gen[Answer]("What is a CRDT?")))
```

`LLM.run` discharges the `LLM` effect, and the residual gains `Async` (the call is concurrent) and `Abort[AIGenException]` (a generation can fail: transport, eval exhaustion, a malformed result). That is the one boundary where the program talks to the world.

## The trio: LLM, AI, Agent

Three types frame the whole module. They differ by what each adds along a single axis, statelessness to memory to persistence:

- **`LLM` is the effect, the capability.** Every program built with `gen`, tools, prompts, and thoughts is typed `A < LLM`, and `LLM.run` executes it. Each `AI.gen` on the bare effect is an independent one-shot with no memory. Reach for it when a call stands alone.
- **`AI` is a conversation that remembers.** You mint an instance with `AI.init`; every `ai.gen` on that instance accumulates into its own history, so a later turn sees the earlier ones. Memory lasts for one `LLM.run`. Reach for it when one call needs to know what an earlier call said.
- **`Agent` is a persistent, addressable entity.** It lives behind an actor, holds its conversation across many `ask` calls, and processes one input at a time. Reach for it when the conversation must outlive a single `run` and you want a long-lived thing to send inputs to.

The sections below climb that ladder: one-shot `gen` first, then remembering instances, then agents, with the generation-shaping surface (tools, prompts, thoughts, modes) layered in between.

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
case class Ack(ok: Boolean) derives Schema

def remembers =
    AI.initWith { ai =>
        for
            _        <- ai.userMessage("My name is Ada.")
            _        <- ai.gen[Ack]
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

`AI.enable` (scoped) and `ai.enable` (instance) register one or more enablements (tools, prompts, thoughts, modes, in any mix) over a computation; inside that scope, a `gen` exposes them. You never write the call loop: the eval loop surfaces the tool definition, detects the call, decodes the arguments with the tool's own `Schema`, runs your function, appends the result, and re-queries until the model produces the final answer.

```scala
def withFacts(q: Question) =
    AI.enable(factLookup) {
        AI.gen[Answer](q)
    }
```

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

When two or more enablements apply to one generation, pass them to a single `AI.enable`: it takes any mix of tools, prompts, thoughts, and modes as varargs (or a `Seq`), applied in argument order, rather than nesting `enable` blocks.

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

The stream's element row carries `Scope` because the SSE connection is held open until the stream terminates or errors, so running it adds `Scope` to the residual. You write no SSE parsing, no fragment accumulation, no incremental-decode attempt.

## Parallel generation

`AI.gen` over `< LLM` composes with the structured-concurrency combinators (`Async.foreach`, `Async.fill`, `Async.race`) through one public given. Bring it into scope and fan out.

```scala
import LLM.given

def answerAll(questions: Chunk[Question]) =
    Async.foreach(questions)(q => AI.gen[Answer](q))
```

The given is the asymmetric `isolate`: on join it merges each shared instance's conversation prefix-aware (so the shared history is never duplicated) and adds fork-born instances as-is. You do not manage threads or per-conversation state isolation across branches; the isolate does both.

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

## Configuration and providers

`AI.Config` is an immutable, copy-on-write settings record naming the provider, model, and runtime knobs (temperature, seed, timeout, retry schedule, iteration cap). Every builder returns a modified copy.

```scala
val openAiConfig =
    AI.Config.OpenAI.default
        .apiKey("sk-...")
        .temperature(0.2)
```

The module ships nine providers: Anthropic, OpenAI, DeepSeek, Gemini, Groq, Baseten, OpenRouter, Claude Code, and Codex, each available as `AI.Config.Anthropic`, `AI.Config.OpenAI`, and so on, and as `AI.Config.Provider.all`. `AI.Config.default` first honors the provider override flag `kyo.ai.provider` (environment variable `KYO_AI_PROVIDER`), then probes provider markers and keys in order, preferring `CLAUDE_CODE`, then `CODEX`, then API provider keys such as `ANTHROPIC_API_KEY` and `OPENAI_API_KEY`. Supported override values are `claude-code`, `codex`, `anthropic`, `openai`, `deepseek`, `gemini`, `groq`, `baseten`, and `openrouter`. Each provider exposes a pure catalog `.default` you refine with builders. `AI.Config.init` builds a config for an API-key provider while reading its API key and org from system properties then the environment.

```scala
def initConfig: AI.Config < Sync =
    AI.Config.init(AI.Config.Anthropic, "claude-sonnet-4-5-20250929", 200000)
```

The no-argument `LLM.run` resolves its config with `AI.Config.default`, which probes provider markers and API keys (system properties first, then environment variables) and selects the first present, falling back to Anthropic. Retries and timeouts are wired into the eval loop, configured here: the completion call is wrapped meter, then retry, then timeout.

```scala
def reliable(q: Question): Answer < (Async & Abort[AIGenException]) =
    LLM.run(_.retrySchedule(Schedule.repeat(3)).timeout(30.seconds)) {
        AI.gen[Answer](q)
    }
```

> **Caution:** `AI.Config.default` is effectful, typed `AI.Config < Sync`, because it probes system properties and environment variables. It is not a pure `val` and must be `.map`ped. The per-provider `.default` values (such as `AI.Config.OpenAI.default`) are pure and safe to use directly.

When running forked sbt demos, prefer the `KYO_AI_PROVIDER` environment variable on the command itself. A `-Dkyo.ai.provider=...` argument passed before the sbt task configures the sbt JVM, not necessarily the forked demo JVM. The `LLM` boundary emits debug logs through `kyo.Log`; enable a debug logger around your program to see the backend that actually ran:

```text
kyo-ai gen backend=Claude Code model=sonnet messages=3 tools=1 thoughts=0 forceResult=false
```

The runnable demos at the end of this README print the resolved provider and model so a forked run can be checked directly.

The error model is principled and typed. A generation's failures ride `run`'s residual as `Abort[AIGenException]`, a sealed hierarchy whose leaves name the specific failure: a transport error is an `AITransportException` (wrapping the kyo-http `HttpException`), eval-loop exhaustion an `AIEvalExhaustedException`, an invalid thought name an `AIInvalidThoughtException`, an undecodable reply an `AIDecodeException`, a missing API key an `AIMissingApiKeyException`. Streaming failures are typed in the stream's own row as `Abort[AIStreamException]`: a malformed delta is an `AIStreamDeltaException`, a stream that ends without a decodable value an `AIStreamIncompleteException`. The super-types track operations, the leaves track failures, and a failure shared by both operations (a missing key, a transport error) belongs to both. Misuse stays off the rows: using an `AI` outside the `LLM.run` that created it panics with `AICrossRunException`.

## Conversation data types

`AI.Context` is the conversation history: an ordered `Chunk` of typed `Message` values, immutable, with builders that append and return a new `AI.Context`. It is what the per-instance histories are made of, and it `derives Schema`, so it can be persisted.

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

The selling point is the code delta. Two of the comparisons made concrete, against the manual path a mainstream SDK leaves to you.

The agentic tool-call loop, by hand, is roughly thirty lines you write and maintain: a `while` loop, per-call dispatch by name, argument parse, the run, error-to-message feedback, and message-list threading.

```python
messages = [{"role": "user", "content": question}]
while True:
    resp = client.chat.completions.create(model="gpt-4o", messages=messages, tools=tool_defs)
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
stream = client.chat.completions.create(model="gpt-4o", messages=msgs, stream=True, tools=tool_defs)
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

The categories removed wholesale: JSON-schema authoring and the parse-and-validate of model output; the agentic tool-call loop, dispatch, and error feedback; manual message-list threading for memory; SSE parsing and incremental decode; hand-rolled retry/backoff and transport-versus-domain triage; parallel-sampling and synthesis orchestration; bespoke stateful-conversation services; thread management and per-conversation isolation for parallel work.

## How it works

`LLM` is a custom `ArrowEffect` whose operations carry data: a program typed `A < LLM` is a tree of virtual operations with no `Async` in its row, reading and appending to per-instance conversation histories held in one threaded `State`. The single operation that reaches the world is `Gen`, whose handler runs the eval loop; that is where `Async` and `Abort[AIGenException]` enter, riding out on `run`'s residual. The completion call is wrapped meter, then retry, then timeout, and four backend adapters sit behind `AI.Config.Provider`: an OpenAI-compatible HTTP adapter shared by six providers, an Anthropic HTTP adapter, plus Claude Code and Codex command harness adapters. The eval boundary emits debug logs through `kyo.Log` naming the selected backend, model, message count, tool count, and streaming mode. For the operation GADT, the state-threading handler, and the asymmetric `Isolate` that backs parallel branches, see `kyo-ai/shared/src/main/scala/kyo/LLM.scala` and CONTRIBUTING.md.

## Demos

Runnable end-to-end demos live in [`shared/src/test/scala/demo`](shared/src/test/scala/demo). Run any with `sbt 'kyo-aiJVM/Test/runMain demo.<Name>'`.

- [**TypedGenerationDemo**](shared/src/test/scala/demo/TypedGenerationDemo.scala): schema-derived typed generation into a case class.
- [**ConversationDemo**](shared/src/test/scala/demo/ConversationDemo.scala): one persistent `AI` instance carrying multi-turn history.
- [**ToolCallDemo**](shared/src/test/scala/demo/ToolCallDemo.scala): Kyo tool registration, model tool calls, tool execution, and final typed answer.
- [**StreamingDemo**](shared/src/test/scala/demo/StreamingDemo.scala): text-chunk streaming and object-by-object streaming.
- [**HarnessCompletionDemo**](shared/src/test/scala/demo/HarnessCompletionDemo.scala): command-backed harness providers with image input and retained history. It prints the resolved provider and model before running.
- [**AgentDemo**](shared/src/test/scala/demo/AgentDemo.scala): a small typed `Agent` retaining its own conversation.
- [**SamplingDemo**](shared/src/test/scala/demo/SamplingDemo.scala): parallel sampling and synthesis.
- [**ThoughtsDemo**](shared/src/test/scala/demo/ThoughtsDemo.scala): thought extraction alongside a final answer.
- [**WikiResearchDemo**](shared/src/test/scala/demo/WikiResearchDemo.scala): a richer tool-backed research flow.

Self-contained commands for the harness smoke demo, assuming API keys and command harness auth are already available in the environment:

```sh
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=claude-code sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=codex sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=anthropic sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8" KYO_AI_PROVIDER=openai sbt -Dsbt.server=false 'kyo-aiJVM/Test/runMain demo.HarnessCompletionDemo'
```

Use the same command shape with `demo.ToolCallDemo` for tool calling and `demo.StreamingDemo` for both streaming modes.

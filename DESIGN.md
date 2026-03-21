# AI Builds Software. You Make Decisions.

---

# Part 1: The Vision

---

## The Attention Tax

AI coding agents today are powerful but untrustworthy. Claude Code, Cursor,
Copilot — they can all generate code. But every interaction follows the same
pattern: the AI proposes, the human squints at the output, tries to
determine if it's subtly wrong, and either accepts on faith or spends more
time reviewing than writing it themselves. The productivity gain evaporates
into the review burden.

The failure modes are predictable and well-documented:

**Reward hacking** — the agent writes code that passes tests without solving
the actual problem. TDD enforcement makes this worse, not better, because
the agent sees the tests and writes the minimum code to game them.

**Scope drift** — the agent modifies files outside its task, introduces
unrelated changes, or wanders from the objective.

**Unsafe side effects** — the agent introduces file system access, network
calls, or system operations that weren't intended or authorized.

**Degenerate tests** — the agent writes both tests and implementation,
producing weak tests that its bad implementation conveniently passes.

**False success claims** — the agent reports success when compilation failed,
tests were skipped, or the implementation is incomplete.

**Architectural violations** — the code works but duplicates logic, breaks
module boundaries, or ignores project conventions.

No existing tool addresses these structurally. They rely on the LLM's good
behavior, runtime sandboxing after the fact, or human vigilance — the very
thing the AI was supposed to reduce.

---

## The Inversion

Every existing AI coding tool has the same relationship: **the user watches
the agent.** The agent proposes. The user reviews. The user catches errors.
The user stays vigilant. The agent is the actor. The user is the
supervisor — an unpaid, always-on safety mechanism that degrades with
fatigue, distraction, and the sheer volume of output.

This platform inverts that relationship.

**The user is modeled by a synapse that understands them.** Not a settings
page. A deep, persistent model: the user's skill level, their domain
expertise, their decision history, their engineering philosophy, their
current flow state. When they're mid-thought and shouldn't be interrupted.
When they've seen enough and want the bottom line. When a decision genuinely
requires their judgment vs. when it can be resolved from their established
preferences. What language they work in. What level of abstraction they
prefer.

The user doesn't review every line of code. The user doesn't parse compiler
errors. The user doesn't evaluate test quality. The user doesn't watch the
system iterate. The safety architecture makes the output structurally
trustworthy. The user's synapse makes the interaction respectful of their
attention. The user is engaged only when their judgment genuinely matters,
in whatever form serves them best.

The review burden isn't reduced. It's eliminated. Not by making code review
faster, but by making it unnecessary for the vast majority of work. The
safety architecture handles structural correctness. The user's synapse
handles the question of when the human's judgment adds value that the
architecture can't provide.

This inversion is the product. Everything else — the type system, the
containers, the coverage enforcement, the structural separation — is what
makes it trustworthy. The rest of this document explains how.

---

## A Structured Mind

The platform has two composable primitives: **neurons** and **synapses**.
They compose into topologies that form **minds**. The terminology is not
decorative. It describes what the system actually does: simple units
compose into something intelligent, and the intelligence is emergent — not
designed into any individual piece.

### Neurons

A neuron is a typed function in the mind's network.

```scala
// Neuron[+Err, -In, +Out]
val parser: Neuron[NumberFormatException, String, Int] =
    Neuron.init[String]("parser"): input =>
        Abort.catching[NumberFormatException](input.toInt)
```

Three type parameters: `Err` (error type, covariant), `In` (input type,
contravariant), `Out` (output type, covariant). All inputs and outputs
must be Json-serializable, enforced at construction time.

A neuron is any Kyo code. The internal effects — `AI` for LLM reasoning,
`Async` for concurrency, `STM` for transactional state — are the neuron's
own business, handled inside its actor scope. What the caller sees is the
error type, the input type, and the output type. A function wrapping the
compiler might fail with `CompileError`. A function using LLM reasoning
might fail with `AI.Error`. A pure transformation might not fail at all.
Same type. Different code.

The LLM builds neurons by writing Kyo code. Every module in the Kyo
ecosystem — the compiler, the container runtime, browser automation, HTTP,
database access, process execution, UI — is available as regular code
inside any neuron. The mind's capability surface is the Kyo ecosystem.
No special integration, no prescribed roles. The LLM composes whatever
it needs.

When a mind starts, each neuron becomes a running actor — an asynchronous
process with its own mailbox, processing messages sequentially. The actor
machinery is internal to the mind. The user creates neuron definitions;
the mind brings them to life.

Because neurons are long-lived actors, they can do more than respond to
individual messages. Between messages, a neuron can schedule work with
`Clock`, watch shared state via `STM` or `Signal`, hold and update
`SignalRef[UI]` surfaces for the user to see, run background tasks — all
with Kyo's standard concurrency primitives. A neuron that compacts
knowledge every 6 hours simply uses `Clock` internally. A neuron that
watches for three consecutive spec failures uses `Signal` internally. No
special mechanism. Just Kyo code.

**Persistent state.** A neuron's actor state is transient — if the actor
dies, it restarts fresh. Anything that should survive goes into a
**Table** — a typed, Dolt-backed persistence primitive. The neuron creates
typed tables at setup and captures them in its closure:

```scala
for
    decisions <- Table.init["category" ~ String & "rationale" ~ String &
                     "outcome" ~ String & "timestamp" ~ Instant]("decisions")
    neuron = Neuron.init[ReviewInput]("reviewer"): in =>
        for
            past   <- decisions.query("category" ~ in.category)
            result <- // ... process using past decisions
            _      <- decisions.insert(
                           "category"  ~ in.category &
                           "outcome"   ~ result &
                           "rationale" ~ why &
                           "timestamp" ~ Clock.now)
        yield result
yield neuron
```

`Table[F]` is typed at creation — `F` is the Record type. Each table
becomes a real Dolt table with columns derived from the Record's fields.
Primitive types, timestamps, durations, `Maybe[A]`, and nested Records
(flattened by path) become real SQL columns — filterable, indexable.
Collections and complex types are not supported; model them as separate
tables with foreign keys, the relational way. This is enforced at compile
time via a `Column` type class.

Because state lives in Dolt, the database *is* the mind's durable
knowledge. Always current, always queryable. No snapshot protocol, no
coordination. Starting a mind from a Dolt branch means fresh actors that
read from that branch's data. Dolt auto-commits every transaction — the
neuron never thinks about versioning.

### Synapses

A synapse routes messages between neurons. Two kinds:

**Static** — always fires. Types must match. Zero cost.

```scala
Synapse.static(implementer, compiler)
```

Every time the implementer produces output, it goes to the compiler.
No AI call, no evaluation. The compiler enforces type compatibility.

**Dynamic** — AI selects whether to fire and transforms the value if types
differ. Guided by a description.

```scala
Synapse.dynamic(
    "Send when compilation failed — implementer fixes errors",
    compiler, implementer)
Synapse.dynamic(
    "Send when compilation succeeded — runner executes code",
    compiler, runner)
Synapse.dynamic(
    "Send on third consecutive failure — alert user",
    compiler, speaker)
```

When the compiler neuron produces output, one AI call evaluates all its
outgoing dynamic synapses together. The call sees the output, each target
neuron's name, and each synapse's description. It selects which should
fire. For synapses where the source and target types differ, the same call
transforms the value — it already has the context. One call, not N gate
evaluations.

The routing AI is stateless — it sees the current output and the
descriptions. If routing needs history ("third consecutive failure"), the
neuron includes it in its output type. The output drives the routing. The
descriptions guide it.

```
Output: CompileResult { success: false, errors: [...], consecutiveFailures: 3 }

Dynamic synapses:
  1. → "implementer": "Send when compilation failed — fixes errors"
  2. → "runner": "Send when compilation succeeded — executes code"
  3. → "speaker": "Send on third consecutive failure — alert user"

Current depth: 5 / max 20
```

The AI returns selections with transformed values, each typed to what the
target expects. The AI can select zero synapses — propagation stops at
that node for this chain. It can select multiple — fan-out. The depth is
visible, so the AI can be more selective deep in a chain.

For simple pipelines, use static synapses — zero overhead. For
conditional routing, use dynamic — one lightweight AI call per neuron
activation. A topology typically mixes both: static backbone with dynamic
branches.

**Typed context flow.** In untyped multi-agent systems, context loss
between agents is silent and pervasive — the LLM decides what to pass
along and quietly drops fields, forgets history, summarizes away critical
details. No error. No signal. Just degraded behavior that's hard to
diagnose and impossible for evolution to optimize against.

Typed neurons eliminate this. If a neuron's input type requires
`previousAttempts: Chunk[FixAttempt]`, every synapse routing to it *must*
produce that field — a static synapse enforces exact type match, a dynamic
synapse's transformation must include it or compilation fails. The failure
mode shifts from silent context loss (runtime, invisible) to type mismatch
(compile time, actionable). The LLM can still fill a field badly, but it
can't forget the field exists.

This makes the topology's information flow inspectable, enforceable, and
evolvable. Types are visible contracts between neurons. The compiler
enforces them. Evolution optimizes within them — and the fitness signal is
clean, reflecting actual reasoning quality rather than information plumbing
failures.

For context that doesn't flow through synapses — history, decisions,
learned patterns — neurons side-load from their Tables. A neuron that
needs past attempts queries its own table regardless of what the synapse
carried. Synapses route *signals* (what happened, what to do next).
Neurons fetch *context* (history, expertise) from persistent state.

Every synapse — static or dynamic — threads a **depth counter**. Each
time a message crosses a synapse, depth increments. When depth exceeds the
mind's `maxDepth`, the synapse stops propagating. This bounds total
propagation without a central orchestrator. A feedback loop (A → B → A)
is naturally bounded — each round-trip increments depth.

**Schedule.** Every synapse carries an optional `Schedule` — Kyo's
composable scheduling primitive — controlling *when* messages are
delivered. Without a schedule, synapses fire immediately on every
activation (the default). With a schedule, the synapse gains temporal
control:

```scala
Synapse.static(compiler, runner)                                     // immediate
Synapse.static(compiler, implementer, Schedule.fixed(5.seconds))     // rate-limited
Synapse.dynamic("...", compiler, speaker, Schedule.fixed(30.seconds)  // batch window
    .probability(0.3))                                               // with sampling
```

`Schedule` composes: `fixed` for rate limiting, `delay` for deferral,
`exponentialBackoff` for progressive backoff, `anchored` for periodic
firing, `probability` for stochastic filtering (skip ahead in the
underlying schedule on miss — no wasted ticks). Schedules are
serializable, numeric — evolution tunes durations, factors, and
probabilities as direct parameter optimization, far cheaper than mutating
neuron code.

When a schedule says "not yet," the synapse must handle buffered messages.
A **buffer policy** controls this, per synapse: **drop** (discard —
right for monitoring and sampling), **keep-latest** (overwrite — right
for state updates), or **buffer-all** (collect — right for batching
errors before sending to an implementer). The default is buffer-all.

Schedule controls *when*. Dynamic synapses control *whether and what*. Two
orthogonal concerns — a dynamic synapse with a batch-window schedule means
the LLM routing call happens at most once per window over all accumulated
messages, not per message. This bounds the cost of dynamic routing
mechanically.

Descriptions are serializable, part of the mind's state, evolvable. They
are micro-prompts — tiny routing policies that evolution optimizes. A
description like "Send when compilation failed" might evolve to "Send when
compilation failed with novel errors — for repeated identical errors,
increase implementer temperature instead." Evolution tunes thousands of
these micro-prompts, a far more granular optimization surface than tuning
a few large neuron prompts.

Current AI systems put intelligence in the agents and make communication
dumb — message passing, tool calls, shared files. This inverts it. Neurons
are focused specialists. Synapses manage how they connect — with
distributed intelligence at every connection.

### Minds

A mind is a running topology of neurons and synapses.

```scala
val mind = Mind.init(
    neurons  = Chunk(designer, compiler, runner, reviewer, ...),
    synapses = Chunk(s1, s2, s3, s4, ...),
    maxDepth = 20
)
```

The mind itself is an actor — the topology's shell. External requests
arrive at the mind's mailbox. The mind dispatches them to entry neurons
via synapses (static or dynamic — the mind's outgoing synapses are wired
like any other neuron's). Neurons process messages, route outputs through
their own synapses to other neurons. Exit neurons route their outputs back
to the mind via synapses. The mind responds to the original caller.

```scala
val response = mind.ask(request)
```

Neurons never know whether they're talking to the mind or to another
neuron. It's all typed messages through synapses. The mind is a node in
its own topology — the entry and exit point.

Each neuron's actor handles its own outgoing routing. After processing a
message, the actor fires all static synapses immediately and makes one AI
call over its dynamic synapses. No central orchestrator. The routing is
distributed.

The mind holds:

- **Its own actor** — the entry and exit point for external interaction.
- **A Scope finalizer** for all neuron actors — closing the mind stops
  all neurons cleanly.
- **maxDepth** — bounding all synapse propagation.

**State is always in Dolt.** Neurons persist via their tables during normal
operation. There is no separate snapshot step. The Dolt database *is* the
mind's durable knowledge — always current, always queryable. The mind's
portable form is its topology code (in Git) plus its Dolt database. To
start a mind from an existing state: start fresh actors, they read from
the Dolt branch. To fork a mind: branch both Git and Dolt.

**Reactive UI.** The mind itself is pure message processing — it has no UI
concept. UI wiring lives in the `AIApp`, which creates `SignalRef[UI]`
signals at the app level and passes them to neurons via closure capture.
Neurons update these signals between messages — a code-writing neuron
sets a progress view, a reviewer sets a diff, a user-modeling synapse
presents a decision card. The AIApp composes these signals into a UI tree;
the platform renders it reactively. No separate chat protocol or
interaction API. Neurons dynamically generate whatever UI the moment
requires via `Compile.eval`. User interactions fire typed handlers
(`Unit < Async`) that send messages back to neurons.

**The mind spectrum.** The same primitives create a spectrum of behavior:

- A neuron that only processes messages it receives → reactive.
- A neuron that schedules its own work with `Clock` → autonomous.
- A neuron that watches shared state via `Signal` → vigilant.
- A neuron that holds `SignalRef[UI]` and updates it → interactive.

Some topologies only respond to `mind.ask`. Others have neurons that
schedule their own work, watch conditions, and update UI surfaces — a
living system. All from the same primitives.

**Three concerns.** A mind's state separates into **structure** (what the
mind *is* — topology, neuron code, synapse definitions), **knowledge**
(what the mind *knows* — Records in Dolt: decisions, histories, learned
patterns, the user's decision model), and **activity** (what the mind *is
doing* — running actors, messages in flight).

Every user's mind diverges over time. Same initial topology, different
structures after months of use — different specializations, different
persistent context, different domain knowledge. The mind became *theirs*,
not through settings but through evolution.

---

## When Constraints Liberate

The inversion — where the user stops watching — is only credible if the
system is structurally trustworthy. And it's only structurally trustworthy
because of a single design principle applied at every level: **constrain
how, liberate what.**

Each constraint eliminates a class of failure and enables a corresponding
freedom. Forbid untracked mutation — all state changes become visible in
the type system. Forbid execution from escaping the container — granting
real compute becomes safe. Constrain persistent state to typed Records in
Dolt — time travel becomes a SQL query. Constrain all user-facing output
to a sealed UI type hierarchy — dynamic interface generation becomes safe.
Keep structure, knowledge, and activity independent — version, branch, and
freeze each on its own terms. The user doesn't pay for any of these
constraints. Translation bridges the gap between the constrained internal
language and whatever the user works in.

This isn't incidental to the design. It *is* the design. The language
choice is this principle applied to code. The container model is this
principle applied to execution. The evolution mechanism is this principle
applied to self-improvement. Every section of this document is an instance
of the same idea: accept a constraint on *how* things are done, and a
freedom on *what* can be done emerges safely.

Constraints on *how*, not *what*. Every impure or untracked construct has
a replacement. Build anything. Integrate with anything. Strict coding.
Unlimited capability. Users don't pay — translation bridges the gap.

---

## Platform Guarantees

These safety properties hold unconditionally. Every mind gets them by
running on the platform. They cannot be bypassed by any topology the LLM
creates or evolution discovers. You cannot compose your way out of the type
system. You cannot escape the container.

### Language Safety

The platform uses Kyo, a Scala 3 toolkit with algebraic effect tracking,
as its verification core (detailed in "One Substrate, Any Language"). Three
properties matter here:

**The compiler knows whether something is a value or a computation.**
`Int` is a value you have. `Int < Async` is a computation that must be
executed. Discarding a computation silently is a compilation error. This
catches forgotten awaits, fire-and-forget side effects, and silently
dropped results — the largest class of AI-generated bugs.

**The compiler knows whether something can fail.** `Abort[NotFound]` in the
type signature forces error handling. If nothing handles it, the code
doesn't compile. The error type is specific.

**Every impure construct is forbidden.** `var`, `null`, `throw`, mutable
collections, raw threads, `scala.sys.process` — each replaced by a tracked
Kyo equivalent. The language has no escape hatches for untracked side
effects. All mutation is `Var` or `STM`. All failure is `Abort`. All
concurrency is `Async`. All IO is tracked. Regular `if`, `match`, and
boolean operators are fine — they're pure control flow. What matters is
that the effectful code inside them is tracked.

**What this catches:** undeclared computational behavior, unauthorized
libraries, type mismatches, unhandled errors, impure constructs.

### Coverage Completeness

Every Kyo computation carries a `Frame` — a compile-time macro capturing
file, line, column, class, method, and source snippet. The `Trace` system
records which computations were exercised during execution. Coverage is a
query: all registered computation points vs. those reached across spec
runs.

This tracks what matters. A branch like `if amount > balance then
Abort.fail(InsufficientFunds) else account.transfer(amount)` — both arms
contain tracked computations. Coverage checks: was the abort path
exercised? Was the transfer path exercised? Not by tracking the `if`
itself, but by tracking the computations it guards. Pure branches without
effects don't need tracking — they can't have side effects.

No external coverage tool. No bytecode instrumentation. The existing
Frame/Trace infrastructure is the coverage tool.

**What this catches:** untested computations, dead code paths, hidden
effectful behavior, code that exists but no spec exercises.

### Runtime Containment

Every execution runs in a **Podman container** — rootless, daemonless
container isolation. Filesystem scoped to the task's worktree. Network
restricted to model API and container-internal communication. Resources
capped via cgroups v2 (memory, PIDs, CPU). Infrastructure provisioned from
effect information — the platform reads the code's imports and provisions
what's needed: Postgres for database access, Redis for caching, message
queues, any software via Nix in the container. Specs run against real
services, not mocks.

Even if a neuron produces malicious code, the blast radius is a disposable
container with no external network access and capped resources.

**What this catches:** resource exhaustion, unauthorized network access,
filesystem escape, runaway processes.

### Temporal Safety

The mind's three concerns — structure, knowledge, activity — are
independently managed, as described below in "The Axes of Identity."
Temporal safety operates on this independence.

The key design constraint: **state lives in Dolt continuously.** Neurons
persist Records during normal operation via their tables. Dolt auto-commits
every transaction. There is no separate checkpoint step — the database
always reflects the mind's current knowledge. The state is structured,
queryable, and versioned automatically.

**What this enables:**

- **Evolution safety.** Branch Git and Dolt. Run current and proposed
  topologies against the same tasks. Compare with SQL queries across Dolt
  branches. Worse? Discard the branch. The bad evolution never happened.
- **Queryable state.** "Find all records where pass rate > 95%" is a SQL
  query. "Show me all decisions about error handling" is a SQL query.
- **Navigable lineage.** Every evolution step is a git commit paired with a
  Dolt commit. The full history is queryable. Branch from any ancestor.
- **Time travel.** Tables support `asOf` — query any table's state at any
  past instant. Compare what the user model looked like yesterday vs. today.
- **Knowledge orthogonality.** Old Dolt state + current git source → old
  knowledge, new topology. Current Dolt + old git → current knowledge, old
  topology. Branch Dolt and diverge → speculative directions.
- **Resource management.** Close a mind, its neurons stop. State is in
  Dolt — start a new mind from the same branch. The mind can be larger than
  what runs concurrently.

**What this catches:** bad evolution, state corruption, regression from
topology changes.

### Interface Safety

The mind's sole interaction surface with the user is `Signal[UI]` —
kyo-ui's sealed type hierarchy. Every UI element the mind produces is
constructed from `final case class` elements within a sealed hierarchy:
`Block`, `Inline`, `Interactive`, `Void`. There is no raw HTML, no
script injection, no arbitrary rendering. The type system enforces this
structurally.

This matters because the mind dynamically generates UI. Neurons using the
`AI` effect construct novel interfaces on the fly — custom forms for
specific decisions, comparison views tailored to particular tradeoffs,
dashboards shaped by the current task. The UI isn't a fixed template the
mind populates. It's whatever the topology produces, reactively rendered
via `Signal[UI]`.

Dynamic generation makes interface safety load-bearing. Without the sealed
hierarchy, a mind could generate misleading interfaces, fake
confirmations, or exfiltrate data through embedded scripts. Evolution could
discover that adversarial UI patterns score higher on "user respect"
fitness by making the user agree faster. The sealed hierarchy prevents
this entire class structurally — you can only compose from predefined
elements, and the rendering pipeline is controlled by the shell.

**What this catches:** script injection, misleading rendering, unsafe
event handlers (handlers are typed `Unit < Async`, not arbitrary strings),
UI escape, adversarial interface patterns.

### Platform Mechanisms

Beyond the unconditional guarantees, the platform provides mechanisms that
topologies *can* use. Available infrastructure, not mandated patterns:

- **Classpath isolation** — the compilation service supports separate
  worktrees with separate classpaths. Code in different worktrees physically
  cannot import each other.
- **Dolt branching** — branch the knowledge state for parallel experiments.
  Run multiple strategies in separate containers. Measure. Keep the best.
- **Dynamic synapses** — intelligent message routing based on LLM judgment.
- **Synapse scheduling** — `Schedule` on any synapse for rate limiting,
  batching, debouncing, probabilistic filtering, backoff. Numeric
  parameters that evolution tunes directly.
- **The `AI` effect** — LLM reasoning available in any neuron or synapse
  via kyo-ai.

Whether and how a topology uses these mechanisms is not prescribed. The
LLM writes whatever topology it wants. Evolution discovers which mechanisms
produce the best results for a given domain.

### How They Compose

1. **Language** constrains the code — the compiler enforces tracked effects
   and pure constructs.
2. **Coverage** ensures every computation is testable — intrinsic to the
   Frame/Trace infrastructure.
3. **Containment** bounds the blast radius — disposable containers.
4. **Temporal safety** makes everything reversible — structured, queryable,
   branchable state.
5. **Interface safety** constrains what the user sees — sealed UI hierarchy,
   no raw rendering, typed handlers.

Topologies use platform mechanisms to achieve further safety properties —
classpath isolation for independent verification, Dolt branching for safe
experimentation, dynamic synapses for controlled feedback. Evolution
discovers which combinations produce the highest fitness.

---

## The Axes of Identity

A mind's three concerns — structure, knowledge, activity — are not just
categories. They're independent axes, and their independence is one of the
most powerful properties the architecture has.

Structure and knowledge are independently versioned and independently
branchable. Activity is always ephemeral — actors start and stop on
demand, but structure and knowledge persist. This independence is a
consequence of the concerns being genuinely different things: structure
changes deliberately through evolution, knowledge grows continuously
through use, activity exists only in the moment.

Structure is code — files the compiler reads, topology definitions that
change deliberately. It lives in **Git**, where worktrees provide
filesystem isolation for the compilation service. Knowledge is structured
Records you query, branch, and diff. It lives in **Dolt**, a MySQL-
compatible SQL database where every table has git semantics: branch, merge,
diff, commit, log, time-travel queries. Activity is ephemeral execution.
It lives in **actors** within **containers** — disposable, resource-
limited, provisioned from effect information.

Git and Dolt stay in sync by convention — a Dolt commit references its
corresponding git commit hash, and vice versa. An evolution experiment is
a git branch (structure) paired with a Dolt branch (knowledge). Merge both
or discard both.

**Why both Git and Dolt:** Git worktrees provide filesystem-level isolation
for the compilation service. The compiler reads files from disk. Classpath
isolation is directory-based. Bloop (the incremental compiler) tracks file
timestamps and hashes to decide what to recompile — persistent git
worktrees give it stable file state natively. Dolt provides queryable,
branchable, diffable state. Neuron conversation histories, decision
records, metrics — these are structured Records you want to query ("show me
all decisions about error handling"), not browse as directory listings.

The independence means you can stop modifying the mind's structure and
let its knowledge keep growing — a fixed topology that gets wiser through
use. You can branch both structure and knowledge for an evolution
experiment. You can freeze both for a reproducible snapshot. You can ship
proven structure with seed knowledge that diverges at every deployment.
These aren't modes to configure. They're consequences of the independence.

---

## Evolution

The platform doesn't prescribe topologies. It provides neurons, synapses,
and the Kyo ecosystem. The LLM writes Kyo code that creates neurons, wires
them with synapses, and composes them into minds. But what makes a good
topology? The search space is enormous — how many neurons, what wiring,
what synapse descriptions, what depth bounds. This is where evolution
operates.

### The Mind's Genotype

The evolvable unit is the mind's complete state: its topology code (in
Git) plus its Dolt database (Records in tables). There is no separate
structural description. What evolution mutates is the same thing a new
mind starts from: look up each neuron's function by identifier, wire
synapses, start fresh actors that read from the Dolt branch.

The genotype includes: neuron identifiers and wiring, synapse definitions
with descriptions and schedules, maxDepth, and all Records in Dolt that neurons have
stored — conversation history, learned patterns, decision models. For
neurons using the `AI` effect, the stored state may include prompts and
reasoning patterns. The GA doesn't distinguish categories — it sees
topology and structured Records, and uses the `AI` effect to reason about
what changes might improve fitness.

### Three Modes

All three modes are the mind's own behavior — the mind's actor loop drives
evolution using `Compile.eval`, Dolt branching, and container evaluation.
The difference is scale, not mechanism.

**Bootstrap** — full genetic algorithm with population-based search.
The mind maintains a population of candidate topologies as Git + Dolt
branch pairs, evaluates each in containers, applies mutation via
`Compile.eval`, crossover via LLM-planned recombination, Pareto selection,
sexual selection with diversity pressure, elite preservation. Discovers an
initial topology from scratch. Expensive, run once per domain.

The bootstrap GA is needed because the topology is a living network, not a
pipeline — the search space is too large for incremental changes. LLM-
driven mutation alone can get stuck in local optima. The GA's population
diversity and mechanical selection protect against this.

**Continual learning** — the same loop with a population of two: current
vs. variant. Branch in both Git and Dolt, try a variant, measure against
the current topology, merge or discard. Structure and state keep improving
incrementally. Scale up to a larger population if continual learning gets
stuck.

**Frozen** — no structural evolution. The topology is fixed. Knowledge
keeps growing through use. The topology is proven, the expertise grows.
A mind that stops evolving structurally keeps accumulating knowledge —
decisions, patterns, domain expertise — all queryable in Dolt.

A mind's lifecycle: bootstrap → continual learning → optionally freeze
when stable. Or bootstrap → freeze immediately for distribution. When
evolution produces a better topology, the mind adopts it via behavior
swapping — draining inflight messages, transitioning to the new topology
without restart.

### The GA Machinery

Each **individual** in the population is a genotype paired with fitness
scores, lineage, and a branch pair — `BranchRef(dolt, git)`. Every
candidate has its own Git branch (structure) and Dolt branch (knowledge).
"Branch-measure-compare" is literally the GA evaluating a population of
branch pairs.

**Mutation.** The GA mutates topology and Dolt state. LLM-guided: the
`AI` effect reasons about what changes might improve fitness. The
mutations operate on:

- **Neuron state** — modify Records stored in Dolt. This can change
  prompts, memory, reasoning patterns, learned parameters — whatever the
  neuron persisted.
- **Synapse descriptions** — rewrite the routing micro-prompts. The
  heaviest mutation target. Descriptions are the routing policy that
  governs how intelligence flows through the topology.
- **Synapse schedules** — tune durations, backoff factors, probabilities,
  buffer policies. Numeric optimization — the cheapest mutation with
  directly measurable fitness impact.
- **Topology** — add/remove neurons and synapses. Shape changes. Convert
  static synapses to dynamic or vice versa. Each operation carries a
  reason.
- **Parameters** — modify maxDepth, synapse configuration.
- **Subnetwork** — transplant proven subgraphs from other minds.
- **Repair** — fix broken topologies that don't propagate.

Evolution with explanations. The LLM reasons about *why* a mutation should
help, guided by fitness scores and the topology's history.

**Crossover.** Two parent minds breed a child. A crossover plan says: take
these neurons from parent 1, those from parent 2, wire them like this. The
plan is itself LLM-generated, reasoning about which parent's strengths to
combine.

**Sexual selection.** Mating preferences create diversity pressure — genetic
distance, complementarity, similarity weights. Without this, the population
converges too fast and gets stuck in bad basins.

**Selection.** Pareto selection across multiple fitness dimensions.
Tournament selection for mating. Elite preservation carries the best
individuals forward.

**Fitness.** Weighted combination of accuracy, latency, and cost. The
fitness function is the specification of what we want. Evolution discovers
how to achieve it.

**Lineage.** Every individual tracks its ancestry. The full evolution tree
is navigable. You can branch from any ancestor, not just the most recent.

### What Evolution Discovers

Evolution operates within the platform guarantees — language safety,
coverage, containment, temporal safety, interface safety are unconditional. Within those
constraints, evolution discovers patterns that raise the ceiling.

These discovered patterns are stronger than designed ones. They're
independently found as optimal by the GA and empirically validated through
measurement. Nobody had to argue they were a good idea. They won on
fitness.

### Cross-Mind Learning

When the platform evolves a new mind — for a different domain, a different
user, a different task — the GA has the full history of every previous
mind's evolution. Patterns that worked in one domain can seed another.
Subnetwork mutation transplants proven subgraphs. Populations can be seeded
with state from previously successful minds.

Over time, the platform accumulates a library of discovered patterns — not
prescribed templates but empirically validated structures.

---

## The Seed Mind

The seed mind is what ships as the product for software development. It is
not designed. It is evolved.

### Fitness Criteria

The bootstrap GA runs against a suite of realistic software tasks. The
fitness function encodes the properties we want:

- **Correctness** — does the output satisfy its spec? Measured by spec pass
  rate against independently written reference specs.
- **Coverage** — are all computation paths exercised? Measured by the
  intrinsic Frame/Trace coverage query.
- **Resistance to reward hacking** — does the topology resist gaming?
  Measured by adversarial specs designed to detect shortcuts.
- **Convergence speed** — how quickly does the topology reach a working
  solution? Measured per task.
- **User respect** — does the topology minimize unnecessary interruptions?
  Measured by simulated user interaction.
- **Robustness** — does the topology handle ambiguous, incomplete, or
  contradictory inputs? Measured by edge-case tasks.

Fitness is multi-objective. Pareto selection balances the tradeoffs.

### Expected Emergent Patterns

We don't prescribe the topology. But given these fitness criteria and the
platform mechanisms available, we expect evolution to discover patterns
like:

- **Spec/implementation separation** — neurons with disjoint classpaths,
  using the platform's classpath isolation mechanism. One group writes specs
  from the API contract. Another implements. Neither sees the other's work.
  This resists reward hacking and scores high on correctness.
- **API-first development** — the API comes first as type signatures and
  effect declarations. Spec neurons use effect information as a blueprint:
  can-fail → error path specs, shared state → concurrency specs. This
  produces better specs and scores high on coverage.
- **Observer synapses** — dynamic synapses with descriptions tuned to
  detect reward hacking, architectural drift, and degenerate specs. The
  routing AI evaluates interactions between spec and implementation neurons
  and fires when it detects concerns.
- **A user-modeling synapse** — deeply models the user, manages when and
  how the mind engages them. Dynamic synapse descriptions tuned to resolve
  decisions from established preferences when possible, and surface
  domain-level choices when needed. Schedule parameters tuned for optimal
  notification timing — batching, debouncing, backing off when the user is
  in flow. Scores high on user respect.
- **Feedback synapses** — dynamic synapses between spec and implementation
  neurons with descriptions that interpret failures using effect
  information, Traces with Frame data, and memory of past attempts.
  Contextual guidance converges faster than raw error messages.
- **Knowledge-curation neurons** — manage persistent context via Tables.
  Periodically distill: "These 47 decisions about error handling reduce to
  three principles" — validated empirically in containers before compacting.

These resemble roles from human organizations — designer, supervisor,
mediator, reviewer, archivist, architect. But they're neurons and synapses,
not prescribed roles. Evolution might discover entirely different
structures — the feedback and observer functions might merge, the
spec-writing function might split into three specialized neurons, some
connectivity pattern nobody designed might converge 40% faster for
database-heavy tasks.

### The Safety Case

The platform guarantees a floor — five unconditional safety layers that
hold no matter what. Evolution then discovers patterns that raise the
ceiling — structural separation, semantic oversight, and whatever else
scores highest on fitness. These evolved patterns are validated empirically
before shipping: branch, measure, compare.

A failure in the seed mind would need to simultaneously: satisfy the type
system (language safety), have full coverage of its misbehavior (coverage
completeness), escape a disposable container (runtime containment), survive
branch-based evaluation (temporal safety), present a trustworthy interface
through a sealed type hierarchy (interface safety), and evade whatever
separation and oversight patterns evolution discovered.

### The User's Experience

This is the inversion in action. The evolved topology handles work
internally. Neurons iterate. The compiler catches structural issues.
Coverage enforcement eliminates untested computations. Observer synapses
flag concerns. Mediating synapses resolve feedback. All invisible.

When a finding genuinely requires human judgment, the user-modeling synapse
has extraordinary context: effect information encoding runtime *behavior*
("this code modifies shared state," "this function can fail with an error
nothing handles"), the Trace with Frames showing the execution path and
source snippets, the user's preferred language, the task description, and
every decision the user has ever made — all queryable from Tables in Dolt.

`Signal[UI]` is the sole interaction surface. There is no separate
interaction API, no chat protocol, no notification framework. The synapse
decides not just *whether* to engage the user, but *how* — by updating
`Signal[UI]` surfaces that the shell renders. The mind doesn't have a
fixed interface it populates. It dynamically generates the right UI for
the moment — a custom form for a specific decision, a comparison view
tailored to particular tradeoffs, a dashboard shaped by the current task.
The sealed UI hierarchy ensures this is always safe regardless of what the
mind generates.

- **Resolve from the decision model.** "Always prefer correctness for
  financial operations" → fix it, move on. No UI update. The user never
  knew.
- **Try alternatives.** Branch the Dolt state, implement both approaches
  in containers, verify both. Update `SignalRef[UI]` with a comparison
  view — results side by side, evidence from both runs, a selection
  control. The user sees a choice with proof, not a question.
- **Ask for advice.** "The task says 'fast' but doesn't specify latency."
  A text input whose handler sends the answer back to the neuron.
- **Present a domain-level decision.** Not "unhandled
  `Abort[AccountFrozen]`" but "your transfer can fail if the account is
  frozen — reject or queue?" The translation from type-system finding to
  domain language is the synapse's AI — the UI is just the surface.
- **Batch** at natural breakpoints — the synapse's `Schedule` debounces
  findings, accumulates them, updates the UI once with all items as a
  list. The "natural breakpoints" become a tunable, evolvable parameter.
- **Just inform** without blocking. A notification element in the
  `SignalRef[UI]`. No handler needed. No response expected.

The user gates visibility: see everything, see only decisions, or anywhere
in between — different `Signal[UI]` compositions at the shell level, same
topology underneath.

### The Decision Model

Every user decision is persisted in Dolt via Tables — decisions with
contexts, rationale, outcomes. Managed by neurons curating persistent
context.

- "Use Postgres" → never ask again.
- "Property-based specs for data transformations" → constraint on spec
  neurons.
- "Smaller PRs" → task decomposition neurons split work.
- User writes a spec → spec neurons generate similar specs proactively.
- User rejects a question → that category becomes autonomous.
- User ignores notifications → stop flagging.
- User overrides error-handling → escalate those.

The model captures engineering philosophy. Works across all languages
because it's about behavior, not syntax.

First project is noisy. Third project, nearly autonomous.

### Containers as Laboratory

Containers aren't just safety. Combined with Dolt branching, they're the
experimental substrate.

Neurons trial approaches in parallel — branch the current Dolt state, spin
up containers with different strategies, run all against the compiler and
specs, keep the best. Knowledge-curation neurons validate compaction
empirically — generate scenarios, run in containers, only compact if the
distilled principles produce the same outcomes. Translation improves through
experiments — generate programs, try strategies, measure success rates.
Topology improvements have evidence — branch Git and Dolt, run tasks
through current and proposed topologies in separate containers, compare
metrics with SQL queries across Dolt branches.

Things aren't reasoned about. They're tried. Failed experiments are
discarded — the branch is dropped. Nothing is lost.

---

## One Substrate, Any Language

### The Verification Core

Kyo is the substrate — the language that makes the platform guarantees
enforceable. It is where the constraints-liberate principle becomes
concrete. Every constraint — tracked effects, forbidden impure constructs
— is enforced by Kyo's compiler. Code is regular Kyo — normal `if`,
`match`, boolean operators. No special constructs. What's forbidden is
untracked side effects, not control flow.

**Pure vs. computation:**

```scala
val x: Int = 42              // a value — you already have it
val y: Int < Async = ...     // a computation — needs to be executed
```

The most valuable distinction. The compiler's `-Wvalue-discard` flag turns
discarding a computation into a compilation error.

**Can this fail?**

```scala
def getUser(id: UserId): User < (Abort[NotFound] & Async)
```

`Abort[NotFound]` forces error handling. Specific error types, not "might
throw something."

**Coarse-grained categories** — does it do IO? Touch shared state? Run
processes? Manage resources? These are what the safety layers primarily
operate on. Full effect precision is for power users and AI iteration loops.

**Coverage from computation tracking.** Every `A < S` computation carries
a `Frame` — file, line, column, class, method, source snippet. `Trace`
records which computations were exercised. Coverage is a query over Frames.
The effectful code is what gets tracked. Pure control flow is invisible —
and harmless.

### Machine-Friendly by Design

Kyo is developed by the same team building this platform:

- Evolves for machine-friendliness without compromise — most users never
  see it.
- Strict compiler flags and linter rules forbid every impure construct.
  No escape hatches for untracked side effects.
- Regular Kyo code — normal `if`, `match`, boolean operators. The LLM
  writes natural Scala.
- kyo-ui: sealed type hierarchy for UI — no raw HTML, no script injection.
- Pure computations zero-cost, fibers 32 bytes, minimal allocations.
- Frame/Trace infrastructure built in.

New modules (JDBC, messaging, file access) designed so type signatures
serve as LLM constraints. The platform builds itself. The tool and library
co-evolve.

### One Tool

Current AI systems juggle tool catalogs. This platform: **write Kyo code.**

The LLM's single tool is `Compile.eval` — generate Kyo source code,
compile it against the restricted classpath, evaluate it, get a typed
result:

```scala
Compile.eval[UI](source)(
    "status" ~ statusRef,
    "items"  ~ itemsSignal
)
```

The type parameter is the contract — the compiler verifies the generated
code produces `UI`. The bindings wire live context (signals, tables,
handlers) into the generated code's scope as typed vals. If the code uses
a binding incorrectly, compilation fails. One mechanism for everything:
generating UI, transforming data, creating sub-minds, writing specs,
implementing features.

Kyo provides a complete platform. Every module uses tracked effects. The
classpath is the permission system. This extends beyond coding —
kyo-playwright for browser automation, kyo-http for APIs, kyo-ui for
interface generation, `Process` for shell. Adding a capability means adding
a module, not building a new product.

Containers provision infrastructure from effect information: database →
Postgres, caching → Redis, messaging → Kafka, CLI → any software via Nix.
Each new module extends verified primitives — both capability and
constraint.

An application is an `AIApp` — a computation that returns `UI`:

```scala
object MyApp extends AIApp:
    def run: UI < Async =
        for
            // App-level state and signals
            prefs    <- Table.init["key" ~ String & "value" ~ String]("prefs")
            status   <- SignalRef.init[UI](span())
            codeView <- SignalRef.init[UI](span())

            // Neurons capture signals — they update them during processing
            coder = Neuron.init[Task]("coder"): task =>
                for
                    result <- Compile.eval[Code](...)
                    _      <- codeView.set(pre(result.source))
                yield result

            // Mind is pure message processing — no UI concept
            mind <- Mind.init(Chunk(coder, ...), Chunk(...), 20)
        yield div(
            header(status),
            main(codeView)
        )
```

No deployment, no build step. The AIApp creates signals at the app level,
passes them to neurons via closure capture, and composes them into a UI
tree. The Mind is pure message processing — it doesn't know about UI.
Signals are the bridge: neurons update them, the UI tree references them,
the platform renders reactively. The tool and its output are the same
thing. The classpath determines capabilities.

### Any Language

Translation renders Kyo in different forms — a rendering concern, separate
from topology. Fine-grained effects stay internal.

| User's world | Kyo |
|---|---|
| `async/await` (Python/TS) | `Async` |
| `try/except` (Python) | `Abort[E]` |
| `ZIO[R, E, A]` | `A < (Env[R] & Abort[E] & Async)` |
| `IO[A]` (Cats Effect) | `A < Async` |
| Mutable state | `Var`, `STM`, `Signal` |
| `subprocess.run()` | `Process` |
| React components | kyo-ui elements |

ZIO and Cats Effect have structural correspondence — reliable translation.
`ZIO[R, E, A]` maps: `R` → `Env[R]`, `E` → `Abort[E]`. Python/TypeScript
require more inference. Each improves independently through container
experiments.

The Scala audience goes from "developers who know Kyo" to all Scala FP
developers — ZIO teams, Cats Effect teams, plain Scala 3 shops, Scala 2
codebases. Beyond Scala, every language with translation is a potential
audience.

Synapses *use* translation to communicate in each party's language. But
neurons and synapses working within the topology in Kyo have full
interaction quality with no translation needed. Translation is how the
mind meets the user's language.

Full Kyo reference is in Part 2.

---

## What Emerges

The platform provides neurons, synapses, and the Kyo ecosystem. It doesn't
prescribe the topology. What patterns emerge is discovered. But we can
sketch the arc — from immediate applications to evolution discovering
structures no one designed.

### Generated Applications Are Minds

An Airbnb search app isn't a static artifact. It's a mind: neurons that
navigate via kyo-playwright (in containers, browser isolated, network
scoped), dynamic synapses managing the user's search interaction, neurons
tracking search history and site changes via `Clock`-scheduled work,
observer synapses watching for layout changes. It generates reactive
`Signal[UI]` interfaces with capabilities Airbnb doesn't offer: "places
within walking distance of three restaurants," "total cost including all
fees under $800," "compare 6 listings side by side," "monitor and notify
on match."

Same pattern for aggregation (Airbnb + Vrbo + Booking.com, separate
containers, unified UI), enterprise automation (playwright driving expense
systems, clean generated UI), research monitoring (journals checked
periodically, dashboard with summaries), personal tools (financial
dashboards, government services navigation). Each is a mind. Each is
independently evolvable. Each can be branched and shared.

### Professional Skill Capture

The same topology extends beyond software. A professional performs a task
— medical triage, contract review, financial underwriting, quality
inspection, creative direction. The user-modeling synapse observes every
decision, every judgment call, every preference. Initially high-touch. Each
decision becomes a rule. The mind gets quieter.

Aggregate sessions across multiple professionals. Where they agree, you
have robust knowledge. Where they disagree, you've found the interesting
edge cases. An insurance company with 50 adjusters: the mind captures all
50 perspectives. Knowledge-curation neurons distill. Where 48 agree, that's
institutional knowledge. Where 2 diverge, that's either an error or a
legitimate edge case.

Voice is the natural modality. A surgeon can't type while operating. An
inspector can't hold a keyboard while examining a structure. Local speech-
to-speech models like Moshi (7B parameters, 200ms latency, full duplex,
MLX on Apple Silicon, CC-BY 4.0) make this viable on the same hardware.
MoshiVis adds vision — the mind sees what the professional sees.

This isn't automation replacing the professional. It's skill capture —
judgment calls, exceptions, "it depends" — encoded in a mind that applies
expertise at scale.

### Self-Improvement

The mind is a Kyo application. It builds Kyo applications. Therefore it
can build itself. The mind's actor loop drives self-improvement: generate
topology modifications via `Compile.eval`, branch Git and Dolt, evaluate
variants in containers, compare metrics via SQL queries across Dolt
branches. Better? The mind adopts the new topology via behavior swapping —
a hot transition, no restart. Worse? Discard the branches. The bad
evolution never happened.

This is just the mind doing work between user requests — the same actor
loop that processes messages, routes through synapses, and updates UI.
Evolution isn't a separate mechanism. It's behavior. A mind that stops
evolving structurally keeps accumulating knowledge — the topology is
proven, the expertise grows.

What the mind can't do: remove its own safety layers, bypass user approval,
modify the compiler or container enforcement. You can't compose your way
out of the type system.

### Minds Creating Minds

When the user says "build me an Airbnb search tool," the mind creates a
new mind — writes the topology as Kyo code, evolves it via the bootstrap
GA with fitness criteria appropriate to the domain. That mind is
independently evolvable, independently branchable. Software becomes a live
medium.

The mind is a topology. The topology is Kyo code. The mind writes Kyo code.
Minds creating minds. Every user's mind diverges over time. Decisions,
expertise, philosophy — encoded in the topology and its Dolt-backed state.
The boundary between "using software" and "having AI that builds software
for you on the fly" dissolves.

### The Skill Capture Cycle

For professional skill capture, evolution is particularly powerful. The
initial topology is bootstrapped via GA. After 100 sessions with different
nurses, the continual learning loop has data on which structures led to
faster learning, fewer unnecessary interruptions, better decision capture.

After 1000 sessions across 50 nurses in 10 ERs, the platform has
discovered not just the knowledge (triage decisions) but the optimal
*structure* for capturing it. The mind for a new ER deployment is effective
on day one — evolved from data.

---

## The Path Forward

### Implementation Roadmap

Each phase compiles in isolation with its predecessors. Each is
independently shippable. Starting with Phase 3, the platform can help
build itself.

**Phase 1: Restricted Compilation Service** — *Depends on: existing Kyo*

Safe classpath (Kyo modules + safe stdlib subset — no `java.io`,
`java.net`, `java.lang.reflect`). Bloop wrapper enforcing restricted
classpath and strict flags. Classpath isolation via worktrees. Structured
error output for LLM consumption. Coverage query API over Frame/Trace.

*Result:* Compile Kyo against restricted classpath, get structured errors.
Coverage tracking and classpath isolation available.

**Phase 2: Container Runtime** — *Depends on: Phase 1*

Podman container orchestration with cgroups v2. Infrastructure provisioning
from imports/effects. Nix for deterministic container images. Execution
returning results + Traces.

*Result:* Two-phase compile-then-run loop. Containers as disposable
execution environments.

**Phase 3: Mind Primitives** — *Depends on: Phases 1 + 2*

Neuron (`Neuron[+Err, -In, +Out]` via `Neuron.init`, Json-serializable messages). Synapse
(static and dynamic, depth threading, optional `Schedule` with buffer
policy). Mind (actor shell, starts neuron actors via Scope, maxDepth,
`mind.ask`). Table (Dolt-backed typed persistence, Record columns, query,
asOf, queryRange).

*This is the first functional version.* The generic mechanism works.

**Phase 4: Temporal Safety** — *Depends on: Phase 3*

Dolt as the state backbone. Table persistence backed by Dolt with
auto-commit. Git + Dolt sync by convention. Branch/commit/diff operations.
`asOf` and `queryRange` backed by Dolt's time travel.

*Result:* The mind remembers across sessions. State is queryable,
branchable, diffable. Temporal safety operational.

**Phase 5: Evolution** — *Depends on: Phases 3 + 4 + 2*

Evolution as mind behavior: the mind's actor loop drives its own
improvement via `Compile.eval`, Dolt branching, and container evaluation.
Mutation of neuron state, synapse descriptions, synapse schedules, and
topology. Crossover, Pareto selection, fitness evaluation. Behavior
swapping for hot topology transitions. Population management with
diversity pressure. Evolution lineage as navigable tree.

*Result:* Minds can be bootstrapped, improved continuously, or frozen.

**Phase 6: The Seed Mind** — *Depends on: Phase 5*

Define fitness criteria for software development. Run bootstrap GA. Evolve
the initial topology. Validate emergent patterns — structural separation,
semantic oversight, user modeling, feedback mediation, knowledge curation.

*Result:* The first evolved mind for coding. Platform guarantees plus
evolved safety patterns.

**Phase 7: The Product** — *Depends on: Phase 6*

kyo-ui shell rendering `Signal[UI]` from the topology. Topology
configuration. Model API configuration (BYOK). Local-first data.
Distribution as container images with Dolt databases. Standalone packaging.

*Result:* The product ships.

**Phase 8: Translation** — *Depends on: Phase 7*

Translation framework. ZIO first (structural correspondence), then Cats
Effect, Scala 2/3, Python, TypeScript. Container-based quality measurement.
Each ships independently.

*Result:* Users work in their language.

**Phase Dependencies:**

```
Phase 1 (compilation service + classpath isolation + coverage)
  └→ Phase 2 (container runtime)
       └→ Phase 3 (mind primitives + Table) ← GENERIC MECHANISM
            ├→ Phase 4 (temporal safety)
            │    └→ Phase 5 (evolution) ← GA + CONTINUAL + FROZEN
            │         └→ Phase 6 (seed mind) ← EVOLVED TOPOLOGY
            │              └→ Phase 7 (the product) ← SHIPS
            │                   └→ Phase 8 (translation) ← BROAD AUDIENCE
            └→ Phase 5 (also needs Phase 2)
```

### Development Strategy

**Phase 3 is the first functional version** — the generic mechanism works.
Phase 4 adds temporal safety. Phase 5 adds evolution. Phase 6 uses
evolution to produce the seed mind. Each phase adds capability without
changing the safety foundation.

Starting with Phase 3, the platform can help build itself — the mind
primitives are Kyo code, and Kyo code is what the platform compiles and
runs.

**Translation improves through container experiments.** Start with Kyo
only. Then ZIO/Cats Effect (structural correspondence). Then
Python/TypeScript.

### Distribution

**The mind is a container image (structure) plus a Dolt database
(knowledge).** Domain-specific starter kits are evolved structure with pre-
loaded knowledge — a legal review mind has contract review patterns, a
medical triage mind has clinical decision patterns. Each recipient's
instance diverges in knowledge while running the same structure.

**Collaboration is Dolt replication.** `dolt push` your mind's knowledge to
a remote. A colleague `dolt pull` or `dolt clone` — they get the full mind
state: all neuron knowledge, all decisions, all evolution history. Dolt
supports remotes (DoltHub, filesystem, S3) for sharing when desired.

**Users bring their own model API keys.** Data stays local — persistent
context, decisions, evolved topology all on the user's machine.

**Open source:** the platform primitives, safety architecture, and Kyo
integration. Auditable, becomes a standard.

**Closed source:** evolution behavior (the mind's self-improvement code),
the seed mind, topology patterns.

**Licensing:** Kyo (Apache 2.0). Safety layers open. Mind proprietary.

**API compliance:** the mind is not a wrapper — it has its own verification
core, safety architecture, topology, persistent context. Each user
authenticates directly with their model provider.

### Risks and Mitigations

**LLMs don't know Kyo well.** Pre-1.0, limited training data. *Mitigation:*
Neurons iterate with enriched diagnostics and persistent memory. API shaped
for LLMs. Container experiments find effective patterns. Modules built with
the platform. Claude Code already performs well on Kyo. *Validation test:*
20 realistic tasks with current models before building anything else.

**Translation fidelity.** Lossy in both directions. *Mitigation:* The
user-modeling synapse buffers roughness. Safety layers catch errors.
ZIO/Cats Effect have structural correspondence. Container experiments
improve empirically. Persistent context tracks patterns.

**Memory and compaction quality.** Deciding what to store, retrieve, compact
is hard. Stale data can mislead. *Mitigation:* Persistent context is
advisory — safety layers verify regardless. Knowledge-curation neurons
validate compaction empirically. Start simple, expand.

**Coordination overhead.** Multiple independent neurons may converge slower
than one. *Mitigation:* Dynamic synapses with persistent memory. Contextual
guidance in routing descriptions. Parallel container trials from Dolt
branches.

**Scala compilation speed.** Seconds per compile. *Mitigation:* Incremental
compilation via Bloop. Pre-screening. Persistent memory. Parallel
branch-based trials.

**Evolution unpredictability.** Emergent topologies might produce unexpected
behaviors. *Mitigation:* Platform guarantees hold regardless of topology.
Evolution operates via branch-measure-compare — a proposed topology is
always evaluated empirically before adoption, and discarded if worse. The
evolution lineage is a navigable tree; you can branch from any ancestor.
The user approves structural changes.

**Self-improvement scope.** Minds evolving minds is ambitious. *Mitigation:*
Same safety architecture as everything else. Temporal safety makes evolution
reversible. Can't modify compiler, classpath enforcement, container
isolation, or coverage mechanism. Can't bypass user approval.

**Broad capability implications.** Closer to a general-purpose safe AI
system than a coding tool. *Mitigation:* Safety architecture designed for
broad capabilities. Each capability enters as a Kyo module — typed,
tracked, verified, contained.

**Bootstrap problem.** Modules don't exist yet. *Mitigation:* Early
development with direct Kyo. Topology features built on working core.

**Initial audience.** Until translation matures, primarily Kyo developers.
*Mitigation:* The user-modeling synapse makes rough translation usable.
ZIO/Cats Effect for Scala audience. Python/TypeScript follow.

---

# Part 2: Reference

---

## Platform Guarantees — Reference

### Language Safety

**Pure vs. computation.** **Can it fail.** **Coarse categories.** **Full
precision** for power users. Regular Kyo code — normal `if`, `match`,
boolean operators. Every impure construct forbidden and replaced by a
tracked equivalent.

**Catches:** undeclared properties, unauthorized libraries, type mismatches,
discarded computations, untracked side effects.

### Coverage Completeness

Every `A < S` computation carries a Frame. Trace records which computations
were exercised. Coverage is a query over Frames — all registered
computation points vs. those reached across spec runs.

**Catches:** untested computations, dead code paths, hidden effectful
behavior.

### Runtime Containment

Podman container per execution. Filesystem scoped. Network restricted.
cgroups v2. Infrastructure from effect information. Specs against real
services.

**Catches:** resource exhaustion, unauthorized access, escape.

### Temporal Safety

Three orthogonal concerns: structure (Git), knowledge (Dolt), activity
(actors in containers). State persisted continuously via Tables with Dolt
auto-commit. Structured and queryable.

Operations: `DOLT_BRANCH` (fork), `DOLT_DIFF` (compare),
`DOLT_CHECKOUT` (revert), `DOLT_MERGE` (combine), `AS OF` (time travel),
`DOLT_LOG` (lineage). Git and Dolt branches sync by convention.

**Catches:** bad evolution, state corruption, regression. **Enables:**
speculative execution, debugging via time travel queries, resource
management via stop/restart from Dolt, knowledge branching.

### Interface Safety

Sealed kyo-ui type hierarchy. All user-facing output constructed from
`final case class` elements within sealed traits (`Block`, `Inline`,
`Interactive`, `Void`). No raw HTML. Typed handlers (`Unit < Async`).
Dynamic UI generation safe — the mind constructs novel interfaces at
runtime, constrained to predefined elements.

**Catches:** script injection, misleading rendering, unsafe handlers, UI
escape, adversarial interface patterns.

### Platform Mechanisms

Available for topologies to use, not mandated:

- **Classpath isolation** — separate worktrees, separate classpaths via the
  compilation service.
- **Dolt branching** — parallel experiments with measurement.
- **Dynamic synapses** — intelligent message routing.
- **Synapse scheduling** — `Schedule` for rate limiting, batching,
  debouncing, probabilistic filtering, backoff.
- **The `AI` effect** — LLM reasoning available via kyo-ai.

---

## Kyo Reference

### Core Encoding: `A < S`

`A < S` — computation producing `A` with pending effects `S`. Compose with
`&`. Pure computations (`A < Any`) unboxed — zero allocations. Every
computation carries a Frame for coverage tracking.

```scala
def greet: String < Async
def saveUser: Unit < (STM & Abort[ValidationError] & Async)
def myPage: UI < Async
def add(a: Int, b: Int): Int < Any  // pure
```

### Effect Handling

Algebraic — suspend at call site, modular handlers per context:

```scala
def transferFunds: Unit < (STM & Abort[InsufficientFunds] & Async)
// Production: real transactional refs
// Tests: isolated test state
```

### Frame and Trace

`Frame` — compile-time macro: file, line, column, class, method, source
snippet:

```
Frame(Transfer.scala:34, TransferService, transferFunds,
      val balance = accounts.get(from).map(_.balance)📍)
```

`Trace` — fixed-size circular buffer of Frames. Pooled. On exception, full
effect path reconstructed. Coverage: registered Frames vs. exercised
Frames.

### Compiler Flags

```
-Wvalue-discard
-Wnonunit-statement
-Wconf:msg=...:error
-language:strictEquality
```

### Forbidden Constructs (WartRemover / Scalafix)

- `var` → `Var`, `STM`, `Signal`, `Atomic`
- `return` → expression-based control flow
- `while` / `do-while` → `Loop`, recursion, `Stream`
- `null` → `Maybe`
- `throw` → `Abort`
- `asInstanceOf` / `isInstanceOf` → pattern matching
- `mutable.*` → `Chunk`, immutable structures
- `scala.sys.process` → `Process`
- `synchronized` / `wait` / `notify` → `Async`, `STM`, `Meter`
- `Thread.*` → `Fiber`

Regular `if`, `match`, `&&`, `||` are allowed — they're pure control flow.
The forbidden constructs are those that bypass effect tracking.

### Data Types

- `Maybe[A]` — replaces `Option` / `null`. `Present(a)` or `Absent`.
- `Result[E, A]` — replaces `Either` / `Try`.
- `Chunk[A]` — efficient immutable sequence.
- `Span[A]` — zero-copy view over `Chunk`.
- `Tag[A]` — runtime type with variance.
- `Duration`, `Instant` — time primitives.
- `TypeMap` — heterogeneous typed map.
- `Schedule` — composable scheduling policy. Combinators: `fixed`,
  `exponential`, `fibonacci`, `exponentialBackoff`, `linear`, `anchored`.
  Modifiers: `delay`, `jitter`, `take`, `repeat`, `maxDuration`, `forever`,
  `probability`, `min`, `max`, `andThen`. Serializable, deterministic.

### Effects Catalog

**kyo-prelude** (pure, no IO):
`Abort[E]` (typed short-circuit errors), `Check` (validation accumulation),
`Env[R]` (dependency injection), `Var[A]` (local mutable state, tracked),
`Emit[V]` (writer / event emission), `Choice` (nondeterminism), `Memo`
(memoization), `Loop` (stack-safe looping).

**kyo-core** (IO, concurrency, resources):
`Async` (green threads, includes IO), `Sync` (suspended side effects),
`Fiber` (32-byte concurrent tasks), `Channel[A]` (typed channels),
`Hub[A]` (pub/sub), `Actor[M]` (typed mailbox with supervision),
`Barrier`, `Latch`, `Meter`, `Gate` (synchronization), `STM` (`TRef`,
`TMap`, `TTable`), `Atomic[A]` (lock-free), `Resource` (scoped lifecycle),
`Clock`, `Console`, `Random`, `System` (standard IO), `Process` (shell,
tracked), `Stream`, `Pipe`, `Sink` (streaming), `Batch`, `Retry`, `Timer`,
`Aspect`, `Layer` (dependency wiring), `Isolate` (effect propagation).

**Application modules:**
`kyo-http` (HTTP client/server, routes, JSON, SSE), `kyo-cache` (Caffeine),
`kyo-playwright` (browser automation), `kyo-stats-otel` (metrics),
`kyo-parse` (parser combinators), `kyo-offheap` (`Arena`, `Memory`),
`kyo-zio` (interop), `kyo-caliban` (GraphQL), `kyo-sttp` (HTTP client),
`kyo-tapir` (HTTP server), `kyo-ai` (LLM calls as tracked effect).

Modules use core effects. `kyo-http` → `Resource & Async`. Classpath
controls availability.

### kyo-ui

Sealed hierarchy — the mind's sole interaction surface with the user:

```scala
sealed abstract class UI
sealed trait Element extends UI
sealed trait Interactive extends Element
sealed trait Block extends Element
sealed trait Inline extends Element
sealed trait Void extends Element
```

`final case class` elements, factory constructors. No raw HTML, no
script injection — structurally impossible. `Signal[A]` / `SignalRef[A]`
for reactivity:

```scala
val count = SignalRef(0)
div(
  p(count.render(n => s"Count: $n")),
  button("Increment").onClick(count.update(_ + 1))
)
```

Handlers return `Unit < Async`. Strings → `Text`. `Signal[String]` →
reactive text. `Signal[UI]` → reactive UI. `Frame` on every constructor.
Neurons dynamically generate UI — the sealed hierarchy ensures safety
regardless of what the mind constructs.

### AIApp Entry Point

```scala
object MyApp extends AIApp:
    def run: UI < Async =
        for
            // App-level state and signals
            prefs    <- Table.init["key" ~ String & "value" ~ String]("prefs")
            status   <- SignalRef.init[UI](span())
            codeView <- SignalRef.init[UI](span())
            diffView <- SignalRef.init[UI](span())

            // Neurons capture signals — they update them during processing
            coder = Neuron.init[Task]("coder"): task =>
                for
                    result <- Compile.eval[Code](...)
                    _      <- codeView.set(pre(result.source))
                yield result

            reviewer = Neuron.init[Code]("reviewer"): code =>
                for
                    diff <- // ... review
                    _    <- diffView.set(div(diff.render))
                yield diff

            // Mind is pure message processing — no UI concept
            mind <- Mind.init(
                Chunk(coder, reviewer),
                Chunk(Synapse.static(coder, reviewer)),
                maxDepth = 20
            )
        yield div(
            header(status),
            main(codeView, diffView)
        )
```

`AIApp` is a Kyo computation returning `UI`. Signals are created at the
app level, passed to neurons via closure capture, and composed into the UI
tree. The Mind is pure message processing — it doesn't know about UI.
Neurons update signals during processing; the platform renders the UI
reactively.

### Cross-Platform

JVM, Scala Native, ScalaJS. Same effect system and safety properties.

---

## Building Blocks Reference

### Neuron

```scala
// Neuron[+Err, -In, +Out]
Neuron.init[In](name): In => Out < (Abort[Err] & ...)
```

Any Kyo code. Internal effects handled inside the actor scope. The caller
sees `Err`, `In`, `Out`. Json required on `In` and `Out`. Persistent
state via Tables captured in closure — actor state is transient.

### Synapse

```scala
object Synapse:
    def static[A](from: Neuron[?, ?, A], to: Neuron[?, A, ?],
        schedule: Schedule = Schedule.immediate,
        buffer: Buffer = Buffer.All): Synapse[A, A]
    def dynamic[A, B](description: String,
        from: Neuron[?, ?, A], to: Neuron[?, B, ?],
        schedule: Schedule = Schedule.immediate,
        buffer: Buffer = Buffer.All): Synapse[A, B]
```

| Kind | Behavior |
|---|---|
| Static | Always fires, types must match, zero cost |
| Dynamic | AI selects + transforms per neuron activation, guided by description |

| Buffer | Behavior |
|---|---|
| `Buffer.All` | Collect all messages until schedule fires (default) |
| `Buffer.Latest` | Keep only the most recent message |
| `Buffer.Drop` | Discard messages that arrive when schedule says "not yet" |

**Schedule** controls timing: `Schedule.immediate` (default — fire on
every activation), `Schedule.fixed(interval)` (rate limit),
`Schedule.exponentialBackoff(...)` (progressive), `Schedule.anchored(...)`
(periodic), any schedule `.probability(p)` (stochastic filter — skip
ahead on miss). Schedules compose via `.min`, `.max`, `.andThen`,
`.delay`, `.jitter`, `.take`, `.maxDuration`.

Depth threading: every synapse increments depth. Propagation stops at
`maxDepth`. Descriptions are serializable, evolvable — micro-prompts that
evolution optimizes for routing precision. Schedules are serializable,
numeric — evolution tunes them as direct parameter optimization.

Routing: after a neuron produces output, static synapses fire per their
schedule. One AI call evaluates all outgoing dynamic synapses together —
sees the output, target neuron names, descriptions, current depth. Returns
selections with transformed values typed to each target's input. A dynamic
synapse with a batch-window schedule triggers one AI call per window over
all accumulated messages, not per message.

### Table

```scala
sealed abstract class Table[F]:
    type Id <: Long
    def get(id: Id, asOf: Maybe[Instant] = Absent): Maybe[Record[F]] < Async
    def insert(record: Record[F]): Id < Async
    def update(id: Id, record: Record[F]): Maybe[Record[F]] < Async
    def remove(id: Id): Maybe[Record[F]] < Async
    def query[A](filter: Record[A],
        asOf: Maybe[Instant] = Absent): Chunk[(Id, Record[F])] < Async
    def queryRange[A](filter: Record[A],
        from: Instant, to: Instant): Chunk[(Instant, Id, Record[F])] < Async
    def size(asOf: Maybe[Instant] = Absent): Int < Async
```

Dolt-backed typed persistence. `Table.init[F](name)` creates a Dolt table
named `name_<hash of Record signature>` with columns derived from
`Fields[F]`. Column mapping enforced at compile time via `Column` type
class:

| Scala/Kyo type | SQL column | Filterable |
|---|---|---|
| `String` | `TEXT` | yes |
| `Int`, `Long` | `INT` / `BIGINT` | yes |
| `Double`, `Float` | `DOUBLE` / `FLOAT` | yes |
| `Boolean` | `BOOLEAN` | yes |
| `Instant` | `TIMESTAMP` | yes |
| `Duration` | `BIGINT` (millis) | yes |
| `Maybe[A]` | nullable column | yes |
| Nested `Record[F]` | flattened by path | yes |

Collections and other complex types are not supported — model them as
separate tables with foreign keys. Query with a partial Record: fields
present in the filter become SQL equality conditions. `asOf` uses Dolt's
time travel. `queryRange` uses `dolt_history_<table>`.

Dolt auto-commits every transaction. No versioning in the API — the
database is always current. Branching, diffing, and time travel are Dolt's
responsibility.

### Mind

```scala
val mind = Mind.init(neurons, synapses, maxDepth)
val response = mind.ask(request)
mind.close  // Scope finalizer stops all neuron actors
```

The mind is an actor — entry and exit point. Dispatches to entry neurons
via synapses. Exit neurons route back to the mind via synapses. Holds:
its own actor, neuron actors (via Scope), maxDepth.

**Behavior swapping.** The mind's actor holds the current topology as
state (following Kyo's `Actor.receiveLoop` pattern). When evolution
produces a better topology, the mind drains inflight messages, stops old
neuron actors, starts new ones from the evolved code, and continues — a
hot transition. Dolt state carries over (same branch). No restart needed.
This is how the mind evolves itself from its own actor loop: branch,
evaluate variants in containers, adopt the winner by swapping behavior.

Portable form: topology code (Git) + Dolt database. Start from existing
state = fresh actors reading from that Dolt branch. Fork = branch both
Git and Dolt.

### Evolution

| Component | Role |
|---|---|
| Genotype | Topology code (Git) + Dolt state (Records) |
| Individual | Genotype + fitness + lineage + branch pair (Git + Dolt) |
| Population | Collection of individuals with diversity management |
| Mutation | Neuron state, synapse descriptions, synapse schedules, topology, params, subnetwork, repair |
| Crossover | LLM-planned recombination of two parents |
| Selection | Pareto across fitness dimensions + tournament for mating |
| Mating | Sexual selection: genetic distance, complementarity |
| Fitness | Weighted: accuracy, latency, cost. Domain-specific |
| Lineage | Ancestry tracking. Navigable evolution tree |

Three modes: bootstrap (full GA), continual learning (propose-measure-
compare), frozen (knowledge grows, structure fixed). All three are Mind
behavior — the mind's actor loop drives evolution via `Compile.eval`,
Dolt branching, and container evaluation. Population size is a parameter
(1 = continual learning, N = full GA).

### Compile.eval

```scala
Compile.eval[A](source: String)(bindings: Record[F]): A < (Abort[CompileError] & Async)
```

The LLM's single tool. Generates Kyo source code, compiles it against the
restricted classpath via Bloop, evaluates the result. The type parameter
`A` is the contract — the compiler verifies the generated code produces
`A`. Bindings inject live context into the generated code's scope:

```scala
Compile.eval[UI](source)(
    "status" ~ statusRef,       // available as val status: SignalRef[String]
    "items"  ~ itemsSignal,     // available as val items: Signal[Chunk[Item]]
    "onSubmit" ~ submitHandler  // available as val onSubmit: Item => Unit < Async
)
```

The Record type from the bindings is injected into the source as a typed
interface. The compiled code is invoked with the actual objects. Signals,
tables, and handlers are live — the generated UI is reactive, the
generated code can read from tables and send messages to actors.

One mechanism for everything: generating UI, transforming data, writing
specs, implementing features, modifying topologies, evolving the mind.
All safety layers apply — restricted classpath, forbidden constructs,
coverage tracking.

### AIApp

```scala
object MyApp extends AIApp:
    def run: UI < Async = ...
```

A computation returning `UI`. Creates `SignalRef[UI]` at the app level,
passes them to neurons via closure capture, composes them into a UI tree.
The Mind is pure message processing — UI wiring lives here. The platform
renders the returned `UI`, subscribing to all signals reactively.

---

## Technical Architecture

### Compilation

**Bloop** — warm JVM, incremental, concurrent via BSP (Build Server
Protocol). One server, multiple clients. Build pipelining. Custom wrapper
enforces restricted classpath, strict flags, and linting rules for
forbidden constructs (WartRemover / Scalafix).

**LSP for neurons** — Scala 3 presentation compiler as API:
`textDocument/diagnostic`, `textDocument/references`,
`textDocument/definition`, `textDocument/completion`.

**Classpath isolation** — each worktree has its own classpath. The
compilation service enforces which modules are available. Code in
different worktrees cannot import each other.

**Coverage** — Frame/Trace infrastructure. Every computation point
registered. Spec runs record which were exercised. Coverage is a query.

**Compiler output is ground truth.**

### Runtime and State

**The three concerns map to specific tools:**

- **Structure → Git.** Source files, topology definitions, neuron code.
  Version controlled, diffable, branchable. Persistent worktrees give
  Bloop stable file state natively for incremental compilation.
- **Knowledge → Dolt.** Tables with typed Records: decisions, conversation
  history, metrics, learned patterns. MySQL-compatible SQL with git
  semantics. Real columns, real indexes, queryable, branchable, diffable.
  Auto-committed — neurons just read and write.
- **Activity → Actors in Containers.** Rootless Podman. Disposable
  execution. cgroups v2 for resource limits. Network restricted.
  Infrastructure provisioned from effect information via Nix.

**Git and Dolt sync by convention** — a Dolt branch references its
corresponding git commit hash, and vice versa. An evolution experiment is
a git branch (structure) paired with a Dolt branch (knowledge). Merge both
or discard both.

**State persistence:** Tables create real Dolt tables with columns from
Record fields. Table names are deterministic: `name_<hash of signature>`.
Stable across restarts, stable across branches, no coordination.

**Resource management:** closing a mind stops all neuron actors via Scope
finalizer. State is in Dolt — start a new mind from the same branch. The
mind can be larger than what runs concurrently.

### Persistent Storage

Dolt-backed typed Tables. Real columns from Record fields. Compile-time
enforcement via `Column` type class. Query by partial Record — fields
become SQL equality conditions. `asOf` for point-in-time reads.
`queryRange` for temporal analysis. Auto-committed — no versioning API.
Branching and time travel are Dolt's responsibility.

### Voice

Local speech-to-speech models. Moshi: 7B parameters, 200ms latency, full
duplex, MLX on Apple Silicon, CC-BY 4.0. Kyutai Pocket TTS: 100M
parameters, runs on CPU. MoshiVis adds vision. Voice capabilities are
Kyo code inside neuron functions — listen and speak via audio libraries.

### Hardware

Any Mac or Linux: 16GB+ RAM, multi-core, SSD. Optional dedicated box (Arch
appliance, Cage compositor). Optional local models (MLX, llama.cpp) for
voice and lighter neurons.

---

## What This Actually Is

A standalone mind that inverts the relationship between human and AI.

Current tools: the user watches the agent. This platform: the system is
structurally trustworthy — platform guarantees from language constraints to
temporal state management to interface safety — and the user is deeply modeled by an evolved
synapse that knows their skill, preferences, flow state, and decision
history. The user isn't watching. The user is engaged only when their
judgment matters.

Two building blocks: neurons (typed Kyo functions that become actors) and
synapses (static for guaranteed paths, dynamic for intelligent routing
with evolved micro-prompt descriptions, scheduled for temporal control).
Composed into minds. A running
mind is an actor topology with Tables in Dolt — three concerns
independently versioned: structure (what it is), knowledge (what it knows),
activity (what it's doing right now).

The LLM builds minds by writing regular Kyo code. Kyo is the verification
core — where the constraints-liberate principle becomes concrete. The Kyo
ecosystem is the capability surface. Translation renders it in any
language. Containers are both safety boundary and laboratory. Dolt gives
the mind structured, queryable, branchable knowledge — always current,
always versioned.

Evolution discovers optimal topologies. The mind's genotype is its topology
code plus its Dolt state. The bootstrap GA finds the initial structure.
Continual learning improves it. Freezing locks it. The seed mind for
software development is evolved, not designed — fitness criteria encode
what we want, evolution finds how to achieve it. Patterns like structural
separation, semantic oversight, and user modeling emerge because they win
on fitness. Synapse descriptions evolve into precise routing policies —
thousands of micro-prompts optimized for how intelligence flows through
the topology.

Beyond coding: kyo-playwright learns interfaces, kyo-ui generates reactive
applications, voice enables hands-free professional skill capture. Each
capability is a Kyo module — typed, tracked, verified.

Distribution is a container image (structure) plus `dolt clone` (knowledge).
Collaboration is `dolt push`. Every user's mind diverges through evolution.
Software becomes a live medium. Minds creating minds.

**To the user:** an AI that builds anything but can't break things — that
respects your attention and meets you wherever you are.

**To the Scala ecosystem:** keep your stack, get verification your effect
system alone doesn't provide.

**To the industry:** safe AI systems with broad capabilities need two
composable primitives — neurons and synapses — platform guarantees from
language constraints to interface safety, and empirical evolution
within provable bounds. Constrain *how* at every level, and *what* becomes
safe. Everything composes.

The product is the proof.

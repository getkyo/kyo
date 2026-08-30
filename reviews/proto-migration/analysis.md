# Proto migration backlog: analysis of B1 through B5

Read-only analysis against the tree as it stands. Reference kernel:
`kyo-kernel/shared/src/main/scala/kyo/kernel/` plus `kyo/{Arrow,Kyo,Mask}.scala`. Proto:
`kyo-kernel/shared/src/main/scala/kyo/proto/kernel/`. All paths below are relative to
`kyo-kernel/shared/src/main/scala/` unless they say `test` or a platform directory.

## Grounding corrections to the backlog itself

1. **`Eval.crossing` does not exist by that name.** The mechanism B2 cites is the
   foreign-suspension rebuild inlined in the proto eval's Suspend arm
   (`kyo/proto/kernel/internal/Eval.scala:136-212`), plus the two other region-as-value rebuild
   sites: `reenter` (`Eval.scala:140-147`) and `answerLoop`'s continue lane
   (`Eval.scala:388-393`, `Kyo.handle(out._2.chain(next), handler, out._1)`). The claim the
   backlog builds on survives, with one correction: a park wraps a **value** in plain `Handle`
   nodes, which is simpler than the crossing (the crossing wraps a **suspension** in a rotated
   re-handling transform). The closest shipped precedent for a park is `answerLoop`'s rebuild,
   not the crossing.

2. **The proto's `Safepoint` is a full port, not a stub.** Both platform files carry the whole
   protocol including the armed-drain on stop observation
   (`jvm-native/src/main/scala/kyo/proto/kernel/internal/Safepoint.scala:104-106`), the
   armed-and-stopped refusal in `enterPark` (`:160-178`), and the slot-displacement trick that
   sends the next `get()` through `resolve` once a stop lands (`:83-92`). B2 needs no substrate
   work at all.

3. **The debug gate is off in the tree** (`inline val enabled = false`,
   `kyo/proto/kernel/internal/Debugger.scala:65`). Q1's measured numbers describe an
   instrumented build, not the checked-in state.

---

## B1. `Isolate`

### 1. What the reference does

The reference splits isolation into a public protocol and one kernel-implemented instance.

**The protocol** (`kyo/kernel/Isolate.scala:89-262`): `Isolate[Remove, Keep, Restore]` with
abstract `State` and `Transform[_]`, three phases `capture` (`:110`), `isolate` (`:124`),
`restore` (`:136`); the derived operations `nest` (`:151`), `run` (`:169`, `:189`), `apply`
(`:212`), `use` (`:233`); and composition `andThen` (`:248`) which pairs states and nests
transforms. The derivation macro (`:518-521`, `deriveImpl` `:590-659`) flattens the `Remove`
intersection, drops what is in `Keep`, drops `ContextEffect`s (they cross automatically),
summons a component isolate per remaining effect, and folds them with `andThen` onto
`Contextual`. The macro's error message (`:613-653`) is user-facing surface. Isolates for
stateful arrow effects (`Var`, `Emit`) are defined above the kernel; the kernel deliverable is
the abstraction, the derivation, and `Contextual`.

**`Contextual`** (`:541-588`) is the isolate of context bindings, the neutral element every
derived instance folds from. Its three phases lean on two eval capabilities:

- `capture` is a `Bindings` node (`kyo/kernel/internal/KyoInternal.scala:196-212`): the eval
  answers it with every named binding in scope and what each holds (`Stack.bindings()`,
  `kyo/kernel/internal/Stack.scala:359`). The `cross` walk (`Isolate.scala:408-454`) then asks
  each binding's `fork(held)` strategy (`KyoInternal.scala:137`), skips tags already crossed
  (`shadowed`, `:462-474`, the innermost occurrence wins), and freezes each answer into a
  `Frozen` binding (`:484-506`) that holds the crossed value outright (`bound =
  Maybe((_: Maybe[Any]) => value)`) and names its `origin` so a name crossing many rounds stays
  one borrow deep.
- `isolate` attaches: the fork body is wrapped in a `Park` node carrying the frozen entries
  (`:560-574`), so evaluating the fork installs them; the body is made to end in another
  `Bindings` read so the way home has something to carry (`carried`, `:276-310`, pairs what
  came back with what crossed, by origin).
- `restore` is a third `Bindings` whose `updates` write the joined values back into the scopes
  that own them (`join` walk `:339-384`; the write lands by origin identity via `Stack.rebind`,
  `Stack.scala:335`, replacing the owning entry rather than shadowing it).

The per-binding strategies themselves are `Binding.fork` / `Binding.join`
(`KyoInternal.scala:137`, `:145`), surfaced to users through `ContextEffect.handle`'s `fork` /
`join` parameters (`kyo/kernel/ContextEffect.scala:160-180`).

### 2. What the proto has, and the brief's question

`Handler.HandlerContext` (`kyo/proto/kernel/internal/Handler.scala:53-60`) carries `resolve`,
`fork(current): State < S`, and `join(current, forked, result: Result[Nothing, State])`, and
`ContextEffect.handle` exposes them as `onFork` / `onJoin`
(`kyo/proto/kernel/ContextEffect.scala:164-181`). **Answer to the brief: this is the
counterpart of `Binding.fork`/`Binding.join` plus bound-resolution, that is, the per-binding
strategy surface only.** No kernel code calls `fork` or `join` today (verified by grep). It is
not the isolation surface: the three-phase protocol, `State`/`Transform`, `andThen`, `run`,
`nest`, `use`, the derivation macro with its error message, and everything `Contextual` does
(the enumeration walk, freezing, origin identity, the write-back) are all absent. One genuine
delta in the proto's favor: its `join` already receives the fork's ending disposition
(`Result[Nothing, State]`), which the reference `join(held, forked)` does not.

### 3. What has to be built

Three tiers, in ascending difficulty:

- **The protocol and the macro**: portable nearly shape-for-shape. The macro's quotes code has
  no kernel-representation dependency.
- **The attach half of `Contextual`**: has a natural proto shape with zero eval change. Wrap the
  forked computation in `Kyo.Handle` values whose handlers are frozen `HandlerContext`s: a new
  internal handler class whose `resolve` returns the crossed value regardless of `outer`, whose
  `fork`/`join` delegate to the origin handler, and which carries `origin` for join pairing.
  A new type, but justified the way the reference's `Frozen` is: join pairing needs identity
  because two bindings of one tag are the normal case, and the one-borrow-deep rule needs a
  place to live.
- **Capture and write-back**: the genuinely missing capability, and a fork for the user rather
  than something to invent (the kernel skill's rule: a piece with no counterpart is a fork).
  - *Capture*: nothing in the proto can ask "what is bound here". The eval's `ctx` TypeMap
    (`kyo/proto/kernel/internal/Context.scala`) holds the innermost live value per tag but not
    the handler that owns the strategies; the handlers sit in `Stack` entries
    (`kyo/proto/kernel/internal/Stack.scala:35-47`), which only expose the innermost region
    (`:80-84`). Candidate shapes, none to be picked silently: (i) a new suspension kind the
    eval answers from `ctx` plus the stack, which adds a dispatch arm to `Eval.loop`, exactly
    the pressure the user cares about; (ii) changing `Context` to carry (value, owning handler)
    per tag so the whole read can be answered from `ctx` alone, which still needs a read
    primitive but keeps the stack out of it; (iii) a kernel-reserved context tag under which
    the eval binds the current context itself, making capture an ordinary `SuspendContext`
    read through an existing arm, at the cost of a hidden special case.
  - *Write-back*: the reference writes into the owning entry by origin and the write survives
    until that entry pops. The proto cannot express a durable write today, because of the
    pre-existing issue below.

**Pre-existing issue B1 trips over.** The proto's context-update semantics diverge from its own
documentation. `ContextEffect`'s scaladoc says a read's update rebinds "for the rest of that
region's extent", the binding region's (`kyo/proto/kernel/ContextEffect.scala:21-23`). The
implementation scopes an update to the innermost *open* region instead: every region-exit path
resumes with the context captured at install time (`stack.push(kyo.handler, st0, ctx, ...)` at
`Eval.scala:269`; the exits read it back at `:209-211`, `:242-244`, `:280-282`, `:345-346`),
so an update made inside an inner region to an outer binding is reverted when that inner region
ends. Latent today because every public suspend carries the identity update
(`ContextEffect.scala:47`, `:69`, `:95`, `:121`), and the one non-identity test
(`test/scala/kyo/proto/kernel/internal/EvalTest.scala:608`, "a context update outlives an
operation answered after it") never lets an update collide with a region exit. Any durable
`join` write lands exactly on this. The intended scoping needs a ruling before `Contextual`
can be derived; designing the write-back around the current behavior would bake the
doc-vs-implementation divergence in.

### 4. Files touched, and the dispatch question

New proto `Isolate` file (public surface plus macro), a frozen-handler class in the internals,
probably `Context.scala`, possibly a new suspension class in `KyoInternal.scala`, and
`Eval.scala` **if** the capture primitive is a node. B1 is the only backlog item that
plausibly adds an arm to `Eval.loop`'s dispatch, and whether it does is the fork itself.
Tests: port `test/scala/kyo/kernel/IsolateTest.scala` (550 lines) and the fork/join halves of
the context-effect suites.

### 5. What could go subtly wrong

- Join pairing by tag instead of origin: wrong whenever one tag is bound twice. The reference
  pins this in IsolateTest's "nested bindings of one tag" block (`IsolateTest.scala:184`
  onward); the port must keep every case.
- Freezing that is not one borrow deep: a name that crosses on each of many rounds accumulates
  a delegation level per round and eventually overflows (the reference's `Frozen` doc,
  `Isolate.scala:476-483`, names this exactly). Test: fork through many rounds, assert flat.
- A frozen value silently re-derived: the rebuilt region re-resolves at installation
  (`Eval.scala:263`), so a frozen handler whose `resolve` consults `outer` undoes the freeze.
- Shadowed bindings crossing twice, or a binding the fork never held coming back at join
  (`carried`'s job in the reference).
- Write-back durability: bind, fork, join a new value, then read the tag again after an
  unrelated inner region has exited; the joined value must survive. Under today's ctx-restore
  discipline it would not.

### 6. Dependencies and order

Last. It carries the only unresolved design fork (the capture/write-back primitive), depends
on a ruling about update scoping, and its attach half is the same wrap-in-Handles idiom B2's
park builder introduces and pins under tests. Note the tension: B1 is also the item everything
above the kernel that forks is waiting on, so the user may choose to pay the fork earlier;
the ordering here is by technical dependency only.

---

## B2. Partial evaluation and preemption, without a `Park` node

### 1. What the reference does

`Eval.partial` (`kyo/kernel/internal/Eval.scala:112-123`): row `Any`, same as a full
evaluation; a stop already pending ends the slice before it starts (`consumeStopped`
short-circuit); otherwise `apply(v, armed = true)` runs with the stop consumed once, in the
`finally`, whatever way the slice ends. Arming (`:722-727`): `save` installs a fresh budget,
`arm` sets the bit, and a delivered stop drains the budget, so every strict application defers
and the loop reaches its poll within one operation. The poll sits in the `Defer` arm
(`:524`), reads without consuming (`Safepoint.stopped`), and is guarded by `parkable`
(`:491-500`), which refuses to end a slice in front of a binding install because a resource
would exist that no drain can find. `park` (`:472-483`) snapshots the stack's four arrays into
spans, clears the stack, and wraps the current value in the `Park` node
(`KyoInternal.scala:61-69`); an empty stack with nothing owed returns the value bare. Resume
is the `Park` arm (`:618-622`): `stack.restore` puts the spans back above whatever the
resuming eval holds and re-resolves bindings (`Stack.scala:437-463`, `resolveFrom`).
`finalizeResources` (`Eval.scala:807` onward) is the holder's give-up path over the park's
finalizer span.

### 2. What the proto has

- The complete, unused `Safepoint` substrate (see grounding correction 2). The settled-spin
  and cross-thread cases need no eval cooperation to become observable: a stop displaces the
  thread from its slot, the next `get()` misses the fast path, `resolve` drains if armed, maps
  start deferring.
- The region-as-value rebuild, shipped and tested: the crossing (`Eval.scala:136-212`),
  `reenter` (`:140-147`), `answerLoop`'s continue lane (`:388-393`). `Kyo.Handle` carries
  handler, state, and cont; `Handle.release` composes the interior's releases with its own
  (`KyoInternal.scala:189-194`); `Eval.release` (`Eval.scala:26-29`) is already the holder's
  give-up path over any pending value. **The proto needs no park-specific `finalizeResources`:
  a parked value's releases are structural**, which is a real simplification the
  representation buys over the reference.
- Two commented test suites already naming the target
  (`test/scala/kyo/proto/kernel/ArrowEffectTest.scala:457-465` and `:605-617`).

### 3. What has to be built

- `Eval.partial` with the reference's consume protocol, an `armed` flag threaded into the eval
  (a parameter on a private entry sharing the one loop), and `Safepoint.arm` after `save`.
- One branch in the existing `Defer` arm (`Eval.scala:58`): if armed and stopped, park.
- A cold, out-of-line park builder: fold the registers into the deferral
  (`Effect.defer(kyo.value, kyo.contA, kyo.contB.chain(contA.chain(contB)))`), then wrap the
  stack innermost (index `size - 1`) outward (index 0):
  `v = Kyo.Handle(v, handlers(i), states(i), conts(i))`, then clear the stack. The casts are
  erasure-forced at the storage boundary, the sanctioned `Stack` category. The per-entry `ctx`
  is deliberately dropped: re-installation re-derives, which is also what the reference's
  restore does (`resolveFrom`).
- No `parkable` guard: the proto has no binding-install step and no finalizer registry (R3).
  When the bracketing layer above lands, it will need a recognizable marker the way
  `Arrow.BindingStep` is one in the reference; that obligation belongs to that layer's design,
  recorded here so it is not rediscovered.

No new node kind, confirming the backlog. The additions to `Eval.loop` are one boolean branch
in one existing arm plus calls to cold methods.

### 4. The brief's question: does the claim hold for every region kind the Stack can hold?

The stack holds three handler kinds. Checked one by one:

- **`HandlerCont`**: state is `Unit`; the rebuild is trivially faithful.
- **`HandlerLoop`**: the live state is `stack.state` (the answer arm writes the successor at
  `Eval.scala:230`), and the `Handle` install arm reads `kyo.state` for non-context handlers
  (`:259`, `:269`), so state survives a park exactly. A clause that has suspended at park time
  is already **outside** the region as a value: the done lane popped the region and delivered
  `answerLoop`'s dispatch record (`:232-245`), which rebuilds the region on continue. The
  reference's trickiest park cases ("a park taken mid answer loop resumes in a fresh full
  eval", `test/scala/kyo/kernel/ArrowEffectTest.scala:1721` and `:1822`; "a stateful clause
  that suspends threads its state through the park",
  `test/scala/kyo/kernel/internal/EvalTest.scala:580`) are therefore *structural* in the
  proto: the in-flight window travels inside the parked value with no special handling. They
  still get ported as pins.
- **`HandlerContext`**: re-installation re-resolves and ignores the carried state
  (`Eval.scala:263`). That is parity with the reference, whose restore also re-resolves
  bindings, and it is the documented spec ("a re-installed region resolves again from wherever
  it stands"). What differs: a **non-identity update** made before the park is lost, because
  the live value sits in `ctx` while the rebuilt `Handle` carries the stale install-time
  state. The identical loss already exists on the shipped crossing path (`:135` reads
  `stack.state`). Latent while the public surface is identity-update only; it is the same
  pre-existing issue flagged under B1 and should be ruled on once, not patched per item.

**Cross-thread resume**: the rebuilt chain is immutable complete values; the resuming eval
borrows its own stack and slot; the departing eval's slot and budget are restored in the
existing `finally` (`:366-369`). Nothing thread-affine survives in the value.

So the claim holds, with the two recorded caveats (context updates; future bracket marker),
neither of which blocks the item.

### 5. What could go subtly wrong, and the tests

- **Consuming the stop at the poll instead of reading it**: the drain-triggered defers and the
  park check must see the same pending stop; consumption happens once at the slice boundary.
  The cross-thread tests (`jvm-native/src/test/scala/kyo/kernel/internal/EvalThreadingTest.scala:88`
  and `:111`) fail against a consuming poll; port them.
- **A stale sentinel surviving the slice**: the `finally` consume; pinned by "a stop already
  pending ends the slice before it starts" (`EvalTest.scala:934`).
- **A nested plain eval eating an enclosing slice's stop**: the proto's `resolve` already
  observes without consuming; pin it.
- **Register folding order at the park point**: `kyo.contB.chain(contA.chain(contB))` in the
  wrong order silently reorders the remainder; park between two maps and assert the value.
- **Multi-shot resume**: a park is a complete value, resumable more than once; the reference's
  spans are; a Handle chain is; pin it, because it is the property most likely to be silently
  broken by a later "optimization" that mutates during rebuild.
- **Re-derivation on resume**: a derive-from-outer binding parked under one enclosure and
  resumed under another must see the new one (that is the spec); pin it explicitly so the
  behavior is a decision, not an accident.
- Port targets: `EvalTest` partial section (`:923-970`), the jvm-native `EvalThreadingTest`,
  kernel `ArrowEffectTest` `:580`, `:1596`, `:1721`, `:1822`, `:1966`, `:2017`, and the two
  commented proto suites. Note the commented proto case at `:605-617` runs `Eval.partial` on a
  computation whose row is not yet `Any`; the reference's own version
  (`kernel/ArrowEffectTest.scala:613-627`) shapes it correctly and is the one to transcribe.

The backlog's cost note stands: N `Handle` allocations per park against four span copies, a
measurement, taken with the debug gate off (Q1) and through the bench harness's package check.

### 6. Order

After B4/B5, before B3.

---

## B3. `EffectTrace`

### 1. What the reference does

`kyo/kernel/internal/EffectTrace.scala`. The carrier is a suppressed exception on the failure
itself (`:22-34`): its presence marks an exception as enriched, it accumulates
reconstructions, and `getMessage` renders them. Nothing is recorded while the computation
runs; frames are **reconstructed at the boundary** from what the evaluator already holds:
`attach(ex, node, cont, stack)` (`:57`) and the arrow-position variant (`:67`). `splice`
(`:97-115`) writes synthesized frames first, then the physical trace with kernel plumbing
filtered (`isPlumbing`, `:125-137`); `NoStackTrace` keeps its carrier but skips the splice.
`carrierOf` (`:150`) installs race-safely under the exception's own monitor. The `Builder`
(`:181` onward) is a budgeted, stack-safe walk with the load-bearing rule that a node is
walked differently by **position**: value position takes it apart, arrow position contributes
only its frame (or a region label from a handler tag), which is what makes self-referential
continuation slots terminate structurally.

The attach sites are the eval's throw edges, where the operands are still in hand: the
out-of-line dispatches (`Eval.scala:179`, `:335`, `:565` among others), the delivery arms of
region completion (`:688-700`), `unhandled` (`:148` area), the recovery observer
(`Recover.panic` splices before a recovery reads the exception, `:69`), and the final rethrow
(`:765`).

### 2. What the proto has

The raw material, as the backlog says: a `Frame` on every node (`kyo/proto/Arrow.scala:22`,
overridden at every surface constructor) and on standalone arrows (`Step.frame`), and handler
tags for region labels. The proto throws raw: the only failure boundaries are `guarded`'s
single catch (`Eval.scala:334`), `recovered`'s rethrow (`:307`), and the bug paths (`:360`).
The proto's `Handler` is not an `Arrow` and regions are stack tuples, so the reference walk's
entries pass becomes a walk over (handler tag, cont) pairs; `Stack` exposes only the innermost
entry today (`Stack.scala:80-84`), so indexed read accessors are a needed, trivial addition.

### 3. What has to be built

- The carrier, splice, plumbing filter (proto package names), and a builder walk over the
  proto's shapes: `Suspend*` (frame, then cont), `Handle` (cont, region label, value), `Defer`
  (contB, contA, value), `Nested` payloads, `Chain`, `Transform` frames. No `Park` arm will
  ever be needed: after B2, a parked value is made of shapes the walk already covers.
- **The attach sites are the real design work.** The proto's unwind is centralized, and at
  `guarded`'s catch the failing node and the registers are gone (they were `loop` locals), so
  attaching only there loses the innermost frames, which are the point of the feature. The
  throw edges that still hold operands are the in-loop user-code calls: the arrow application
  (`Eval.scala:293`), the clause calls (`:220`, `:225`), `done` (`:278`), the context update
  applications (`:61`, `:66`), plus `answerLoop`'s dispatch. Wrapping an edge in try/catch is
  legal inside the tailrec loop (the recursive call stays the tail call) but taxes the hot
  loop; the reference's stated shape is the guide: bulky and cold dispatches live out of line,
  each compiled with its own budget (`Eval.scala:125-128`), and the attach lives there. So B3
  is eval surgery in **cost structure**, not in semantics: no new node kind, no new arm, but
  the loop's edges move and the inlining profile must be re-measured.
- Splice sites: before `handler.recover` in `recovered` (the recover is the proto's second
  observer of a failure, the obligation `Recover.panic` documents), and before the final
  throw when no region answers. Attach on the `unhandled suspension` bug path with the
  reference's render-failure fallback.

### 4. Files touched

New proto `internal/EffectTrace.scala`; `Eval.scala` (throw edges, possibly hoisting arms out
of line); `Stack.scala` (indexed accessors); ports of `EffectTraceTest` and the jvm-native
physical and threading variants. No dispatch arm added.

### 5. What could go subtly wrong

- Attaching after the unwind has popped what it describes: `recovered` pops as it declines
  (`:315`, `:323`), so attachment must happen before the walk moves, or the trace describes
  what happens next rather than what went wrong (the reference's spliced-not-attached
  distinction, `Eval.scala:63-70`).
- Describing a failure must never replace it: every walk failure swallowed
  (`EffectTrace.scala:75-89`), render fallbacks on the bug path.
- Non-termination on self-referential continuation slots: the proto's fused nodes make
  `cont eq this` the common case, so the position rule is load-bearing from day one; pin it.
- Double enrichment when an exception crosses several boundaries: the carrier-mark discipline
  and the synchronized `carrierOf`.
- Tests must assert concrete frame sequences, as the reference suite does, not just
  non-emptiness.

### 6. Order

After B2, so the attach-site work happens once against the eval's final shape, and so the
partial paths B2 adds (park, resume, slice boundaries) are covered by the same pass.

---

## B4. `Mask`

### 1. What it is (the brief's question)

`kyo/Mask.scala:12`: `sealed abstract class Mask[S] extends ArrowEffect[[A] =>> A < S, Id]`,
an effect whose operation input is an **unevaluated computation** and whose answer is that
computation's value. `Mask[E](v)` (`:21-30`) is `handleCont` over `E`, translating each `E`
operation into a `Mask[E]` operation whose payload is the re-raised
`ArrowEffect.suspend(tag, input)`; the computation keeps evaluating in place and every other
effect stays visible to local handlers. `Mask.run` (`:33-36`) is `handleCont` over `Mask[S]`,
evaluating each payload at its boundary (`input.map(cont(_))`), which re-exposes `S` to the
handlers outside it. Purpose: **scoped handler bypass**. Handlers for `E` between the mask and
its `run` see none of the masked computation's `E` operations; they tunnel out and the answers
flow back in. `test/scala/kyo/MaskTest.scala` pins the contract: tunneling past an inner
handler, per-operation tunneling, selectivity (other effects stay local), a stateful local
handler threading across tunneled operations, a multi-shot outer handler replaying the masked
region with its local effects, deep stack safety, double-masking behaving as one mask, and
masks of different effects stacking independently.

### 2. What the proto has

Nothing named, and nothing missing: both methods are equations in `handleCont`, `suspend`, and
`map`, all of which the proto has. The tunneling exercises exactly the foreign-crossing
rebuild, which is shipped and tested.

### 3. What has to be built

One file, a direct transcription onto the proto's `ArrowEffect`. The masked payload enters
`suspend` as an already-typed `I[C]`, so no lift fires and no nesting hazard arises: the
payload is opaque input, delivered raw to the clause that maps it. `Tag` derivation is shared
`kyo-data` machinery and identical.

### 4. Files touched

New proto `Mask` file (beside the proto's public veneer under `kyo/proto/`) plus the
`MaskTest` port. No eval touch, no node kind. Port caveat: MaskTest's interaction cases use
`Effect.bracket` and `Effect.catching` (`MaskTest.scala:61-98`), which the proto kernel does
not have (R1, R3); those cases stay behind until the layers above exist. The core cases port
now.

### 5. What could go subtly wrong

- Subtype-tag operations: the `E2 >: E` bound and tag subsumption in `handleCont`; pin with
  the proto suite's existing `Ask`/`AskSub` pattern.
- A masked operation whose answer is a computation held as data must round-trip the union
  discipline through two suspensions; pin the box case.
- The multi-shot replay case (`MaskTest.scala:111`) is capture-and-replay of a region inside a
  region, the proto's hostile axis; it is the one case most worth keeping byte-faithful.

### 6. Order

First or second: smallest item, zero eval risk, and its tests widen the crossing coverage B2
then leans on.

---

## B5. `handleFirst` / `dispatchFirst`

### 1. What the reference does

`FirstSuspended` (`kyo/kernel/ArrowEffect.scala:331-334`): an abstract `private[kyo]` token
with `input` and `cont`, implemented anonymously per expansion so a primitive input stays
unboxed. `handleFirst` (`:348-373`) is `handleCont` instantiated at region value type `Any`:
the clause returns the token **settled**, which completes the region at the first operation;
the done lane discriminates, running the user's clause with the erased input and the raw
remainder at its true row (`E` still present, `Arrow[O[Any], A, E & S]`), or `done` for a body
that finished without performing the effect. Both lanes run outside the region.
`dispatchFirst` (`:556-572`) is a separate, fueled, read-only walk to the first operation of a
tag, seeing through `Handle`, `Park`, and `Defer`, running at most one deferral body; it
exists for inspecting computations that must not run (an interrupted fiber's unprocessed
join). The token's second reference role is confirmed exactly as the backlog states: the
completion path skips the orphan drain for it (`Eval.scala:688`, `:696`), and R3 makes that
moot in the proto.

### 2. What the proto has

`handleCont` with the same completion discipline. The settled arm and the done lane deliver
unnested payloads, and the token is a plain object that is never `Nested`, so `isInstanceOf`
discrimination is sound. Nothing else is needed.

### 3. What has to be built

The token under the proto internals (a settled value, **not** a node kind; `Pending`'s sealed
hierarchy is untouched); `handleFirst` as an inline method on the proto `ArrowEffect`
delegating to `handleCont` at `Any` exactly as the reference; `dispatchFirst` as a fueled walk
over `Suspend` (tag test, input), `Handle.value`, `Defer.value`, and `Nested`. No `Park` arm
exists or will be needed: after B2 a parked value is made of shapes already walked, one more
place the proto representation collapses reference machinery.

### 4. Files touched

`kyo/proto/kernel/ArrowEffect.scala`, one internal file for the token; port of the reference
`handleFirst` block (`test/scala/kyo/kernel/ArrowEffectTest.scala:1014` onward) and the
`dispatchFirst` cases. No eval touch, no dispatch arm.

### 5. What could go subtly wrong

- The token escaping its expansion: the done lane unwraps before anything else observes the
  value, and nested `handleFirst` regions each unwrap their own; pin a nested case.
- The remainder's row: the clause receives the continuation with `E` still in it and every
  consumer re-handles round by round; a port that erases `E` from the remainder compiles and
  silently drops the re-handling obligation. Pin the round-by-round shape.
- One behavioral difference to record, not fix: the proto runs `done` strictly with the region
  still installed (`Eval.scala:277-278`) where the reference pops first; observable only
  through a `recover` on the same region, which `handleCont`-built handlers do not define.
- A settled body value that is a computation held as data reaches the done lane raw; the
  `Any`-typed region must preserve the box discipline; pin the box round-trip.

### 6. Order

Early; surface-only.

---

## The ruled-out items, pressure-tested

**R1 (`Catching`): holds**, with one caveat to carry to the consumers. The reference's
`handleCatching` guards the body's *construction*: `value` is forced only after the handler is
installed (`kyo/kernel/ArrowEffect.scala:584-588`), which is what `Abort.run { throw ... }`
rests on. The proto's `Kyo.handle` matches its value strictly at the call site
(`KyoInternal.scala:169-184`), but `Kyo.Handle.value` is abstract, so a construction-guarded
region is expressible by hand when the migrating layer needs it. No kernel work; a note for
whoever ports `Abort`.

**R2 (`Binding`/`Bindings`): correct for one of the pair's two roles, and the ruling's text
conflates them.** The `Binding` role (per-tag bind, read, derive) is genuinely covered by
`ContextEffect` plus `Context` plus `HandlerContext`. The `Bindings` role (the whole-context
read at a fork and the origin-matched write-back at a join, `KyoInternal.scala:196-212`,
`Stack.rebind` at `Stack.scala:335`) is covered by nothing in the proto, and it is precisely
the missing capability inside B1. Either the ruling is amended to scope itself to the binding
role, or B1 silently absorbs a ruled-out item. This is the one place the backlog's own
accounting is wrong as written.

**R3 (`Finalizer`): the core holds, two lanes escape it and need recorded answers.** The
ruling is coherent for every holder that holds a `Pending`: a park, a captured region value,
and any composed computation all release structurally (`Handle.release`
`KyoInternal.scala:189-194`, `Eval.release` `Eval.scala:26-29`). Two abandonment lanes do not
pass through any holder:

1. The `Loop.done` lane drops `next` inside the eval (`Eval.scala:232-245`) without consulting
   `release`; regions folded into that continuation by earlier crossings are abandoned
   invisibly, and **no layer above ever holds the dropped value**, so "Sync brackets above"
   cannot reach this lane. The reference drains orphans exactly here (`:688-700`). The proto
   has the machinery to do the equivalent cheaply (sequence `next`'s release ahead of the
   delivery) if the ruling is that the kernel owes it.
2. A `handleCont` clause that drops its continuation holds an `Arrow`, and bare arrows
   deliberately do not release (`Eval.scala:23-25` typing note); the rotated carriers keep
   their releases on the suspension node, which the clause never holds.

Neither invalidates R3, and B2 does not depend on resolving them (parks release correctly).
They are consequences to carry forward explicitly, before release-owing regions exist in
practice.

**R4 (platform splits): sound**, with one precision: `Safepoint` is already platform-split in
the proto, so R4's residue is only the debugger cell (a plain module var,
`Debugger.scala:68`) and the stack pool's shared-code `java.lang.ThreadLocal`
(`Stack.scala:141-147`). Optimization, revisit with numbers.

---

## Q1, as it bears on this backlog

The gate is off in the tree (`inline val enabled = false`). The binding consequence for these
items: every benchmark row supporting B2 or B3 must be produced with the gate confirmed off,
through the bench harness whose package check verifies the measured class references the
package under review (the `ProtoKernelBench` escape in
`kyo-kernel/.claude/skills/kernel/rulings.md` is exactly the failure this guards). Q1 itself
is a build-configuration question; none of B1 through B5 depends on resolving it.

---

## Implementation order

**B4, B5, B2, B3, B1**, by technical dependency:

1. **B4 (Mask)**: smallest, surface-only, and its multi-shot and crossing tests widen the
   coverage of the rebuild machinery everything later leans on.
2. **B5 (handleFirst/dispatchFirst)**: surface-only; its one eval interaction in the reference
   is moot by R3.
3. **B2 (partial/park)**: the substrate is complete; the change is one branch in an existing
   arm plus a cold builder; it pins the wrap-in-Handles idiom under hostile tests.
4. **B3 (EffectTrace)**: instruments the eval's throw edges, so it wants the eval's final
   shape; running it after B2 covers the partial paths in the same pass and avoids
   re-measuring the loop's inlining profile twice.
5. **B1 (Isolate)**: carries the only surviving design fork (the capture and write-back
   primitive) and depends on a ruling about context-update scoping; its attach half reuses
   the frozen-Handle idiom with B2's precedent in the tree.

Two rulings gate B1 and should be raised when it opens, not before: the capture primitive's
shape (node kind versus `Context` representation versus reserved tag), and the intended
scoping of context updates (the doc-versus-implementation divergence at region exit). One
smaller ruling belongs to R3's follow-through: whether the `Loop.done` lane owes a release
walk over the continuation it drops.

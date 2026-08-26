# Staging the eval through JIT-inlined fragments: exploration, corrected

Prompt, verbatim: "how about we simplify things by 'staging' Eval itself in via JIT in inlined
classes? the answers impls should be a concern of Eval and might even be able to reuse code
better." Clarified after a first misreading: "The thing isn't inlining Eval. It's generating new
callsites in already inlined apis like handle* that point to parts of the eval that can be
inlined by the JIT."

This supersedes the first version of this document, which wrongly reduced the proposal to
Scala-level source unification. The corrected model follows, grounded in the compiled classes
of this tree (bytecode sizes measured with javap) and in HotSpot's actual specialization
machinery.

## 1. The corrected mechanism model

Per-site specialization needs two things, and they are separable:

1. **A per-site compilation root with exact type knowledge.** Each handle site needs its own
   bytecode method (so it has its own invocation and backedge counters, its own profile, and a
   `this` whose class is effectively final). This is the part only scalac's `inline` can mint,
   and the already-inline `handle*` APIs mint it today: each expansion creates an anonymous
   handler class whose `answers` method is that site's root.
2. **The specialized logic inside that root.** This does NOT have to be spliced source text.
   The JIT copies shared bytecode into a root by inlining, and specializes the copy through
   type propagation: when C2 compiles `AnonHandler.answers`, `this` has a known (effectively
   final, CHA-clean) class; pass it into an inlined shared fragment and the fragment's virtual
   calls on it devirtualize to this site's methods, which then inline in turn, which is what
   lets escape analysis scalar-replace the outcome boxes across the whole window.

My first reading collapsed these into one ("the staging annotation available to us is Scala
inline"), which is wrong: scalac must mint the call sites, but the logic can be shared bytecode
that the JIT stages into each root. The codebase already proves the second mechanism works,
because it already uses it:

- The expanded `answers` at a Mask handleCont site measures ~335-352 bytecode bytes (javap,
  `kyo.Mask$$anon$2`/`$$anon$10`).
- `nextAnswer`, ~245 bytes, is a shared ordinary method called from inside that hot loop, and
  it JIT-inlines into each site's root today (under FreqInlineSize 325 at a hot site).
- `resuspend` (12 bytes) and `Effect.defer` are likewise shared and JIT-inlined.

So the current design is already a hybrid: scalac splices the hot window (loop control, clause,
destructure, Out writes), and the JIT stages the shared classify and rebuild fragments into
each root. The proposal generalizes the second mechanism; the question is how far it can go.

## 2. What HotSpot gives and what it gates

The relevant machinery, stated precisely because the feasibility argument turns on it:

- **Roots are chosen by counters.** Invocation counters and loop backedge counters decide what
  gets compiled. Whoever owns the `while` header owns the backedges and becomes the natural
  root.
- **Inlining budgets.** MaxInlineSize (35 bytes) for ordinary sites, FreqInlineSize (325) for
  hot ones, and InlineSmallCode (2500 bytes of MACHINE code): a callee that already has its own
  compiled nmethod bigger than that is refused with "already compiled into a big method". That
  refusal string is live in this repository's captured compilation logs (the harness's reason
  vocabulary records 6 occurrences), so this gate is not hypothetical here.
- **Devirtualization.** Exact receiver types propagate through inlining (the strongest form:
  no dependency needed); CHA devirtualizes single-implementor calls (the Debugger seam
  documents this codebase already relying on it: "the zero-cost mechanism is class hierarchy
  analysis"); profiles devirtualize up to two receiver types per site and go megamorphic past
  that (`h.answers` at the eval's dispatch is megamorphic by construction and stays a virtual
  call once per window, which is fine, amortized).
- **Profile pollution.** MethodData is per method, per bytecode index, aggregated over every
  caller. A shared fragment inlined into many roots carries the union profile into each copy:
  type checks and branches taken by ANY caller stay alive in every copy. Argument-type
  propagation cuts through this for devirtualization, but branch pruning per site does not
  happen. This is the structural reason a shared fragment is never quite as clean as spliced
  text.
- **Scalar replacement needs one compiled unit.** The outcome box disappears only while the
  allocation and every use inline into the same root. Any budget failure anywhere in the chain
  reintroduces one allocation per answer (the morphism probe measured exactly this cost when
  handler polymorphism broke the chain).

## 3. The design spectrum

**A. Today.** The whole hot window (~350 bytes) is spliced per site by scalac; shared fragments
(nextAnswer, resuspend, Effect.defer) are staged in by the JIT. Specialization is unconditional
(the clause is text, not a call), warmup compiles the right root immediately (the site method
owns the backedges), and no budget can break it. Cost: every site carries the window bytecode,
and the protocol text is maintained in triplicate at the source level.

**B. The maximal reading: the window as one shared Eval method.** The per-site `answers`
becomes a thin stub, `Eval.answersWindow(this, input, k, out)`; the loop lives once in Eval as
ordinary code. Specialization then depends on the JIT staging the window into the stub root and
the clause into the window. Three gates stand in the way, now quantifiable:

  1. The window method owns the backedges, so it becomes a hot root FIRST, with a megamorphic
     clause call inside (every handler flows through the one copy). Its nmethod will exceed
     InlineSmallCode with high probability, after which the stub's later request to inline it
     is refused with exactly the "already compiled into a big method" string this repo's logs
     already contain.
  2. Even by bytecode size the window is ~350 bytes, already over FreqInlineSize (325).
  3. The stub is invoked once per window (1/128th of the answer rate), so it crosses the C2
     threshold late; everything before that runs the generic megamorphic window, and clause
     inlining inside it is budget-gated where today it is unconditional.

  B is therefore fragile-by-arithmetic, not impossible: a falsification probe is cheap (one
  family rewritten as a stub, the effectful rows, PrintInlining grepped for the two refusal
  strings, gc.alloc.rate.norm watched for the box reappearing).

**C. The robust reading: shrink the splice to what must own the root.** Keep two things spliced
per site, because they are exactly what the JIT cannot be trusted to stage: the loop header
(root ownership from the first warmup iteration; backedge counters at the site) and the clause
application (unconditional specialization, no budget). Move everything else into Eval-owned
shared fragments shaped like nextAnswer already is: straight-line, under ~245 bytes, no loops
of their own (so no competing nmethod root), receiving what they need as arguments. Concretely
movable out of the current templates: the bail-arm bodies (the Out writes plus the
Effect.defer rebuilds), the ClauseThrew choreography, the stop-poll bail, and the
family-specific destructure protocol. The spliced text drops from ~350 bytes toward the loop
header plus the clause; the protocol becomes ordinary, testable, single-source Eval code; and
each fragment is individually verifiable with the bytecode reader and PrintInlining.

C is also where "the answers impls become a concern of Eval" lands literally: Handler keeps
the class hierarchy and the Out cell; the protocol methods live with the eval whose protocol
the file comment already says it is.

## 4. Where the eval itself enters

The same fragments serve both compilation contexts, and that is the reuse the proposal buys:

- Called from a per-site root: exact `this` propagates, the fragment's virtual calls
  devirtualize, the copy specializes to the site.
- Called from the eval's shared drive (the general path): the same bytecode runs megamorphic,
  which is what the general path already is.

One logic, two compilation contexts, with the specialization decided by where the JIT stages
the copy rather than by which of three hand-maintained templates scalac spliced. The general
dispatch pair and the fast dispatch trio then converge on the same fragment calls, which is the
advisor's dispatch-dedup lane derived from the compilation model instead of from source
aesthetics.

## 5. Probes, in order

- **PB (price the maximal version):** rewrite `answersCont` alone as a thin stub over an Eval
  window method. Run the effectful and suspendWith rows, PrintInlining on. Decisive positive:
  the stub root inlines the window and the clause, allocation unchanged. Decisive negative:
  either refusal string appears, or gc.alloc.rate.norm gains a box per answer. Either outcome
  settles B for this codebase's shapes.
- **PC (validate the robust version):** extract one bail-arm body into an Eval fragment, full
  bench class, expect byte-identical allocation and flat times; then proceed arm by arm, one
  variable per measurement.
- Both run after P1 and P0, which are built and queued: P1's canonical shapes shrink the
  classify step the fragments implement, and P0 prices what any of this machinery is worth
  against the general path.

## 6. Bottom line

The proposal is about who copies the logic: scalac (splice everything, today) or the JIT
(stage shared Eval fragments into per-site roots). The codebase already runs the JIT variant
for its classify and rebuild steps, so the direction is proven in-repo; the open question is
the boundary. The arithmetic says the loop header and the clause must stay spliced (root
ownership and unconditional specialization), and everything else is a candidate for Eval-owned
shared fragments, taken one measured step at a time. The maximal version (the whole window as
one shared method) is gated by InlineSmallCode and FreqInlineSize with the refusal strings
already observed in this repo's logs, and gets one cheap falsification probe before being
believed or buried.

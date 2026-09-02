# The fifteen TODO notes in the proto kernel: analysis and proposals

Scope: every `TODO` in `kyo-kernel/shared/src/main/scala/kyo/proto/`,
`kyo-kernel/jvm-native/src/main/scala/kyo/proto` and `kyo-kernel/js-wasm/src/main/scala/kyo/proto`.
Fifteen found, all in `shared`, all in `kernel/internal/`. They group into six roots, so this
document has six entries.

The standard applied is `kyo-kernel/.claude/skills/kernel/SKILL.md`: no new terminology, avoid new
types, correct by construction, method size on the hot path is a design property, one variable per
measurement, no regression accepted without a number. Every proposal that touches the evaluator
names the commit that put the current shape there and the benchmark rows it reaches.

Nothing here has been edited, built, or measured. This is analysis only.

## The notes

| # | file:line | note (verbatim) | entry |
|---|---|---|---|
| 1 | `internal/Stack.scala:9` | `please review if all the APIs are still necessary and if we can simplify them. Also check if we can improve naming for clarity` | 1 |
| 2 | `internal/EffectTrace.scala:29` | `please review if all the APIs are still necessary and if we can simplify them. Also check if we can improve naming for clarity` | 1 |
| 3 | `internal/Implicits.scala:17` | `let's check if we can remove these liftings. I also wanted to see if we can detect if an arrow is a pure function without < S in the return to enable optimizations. If I;m not mistaken these liftings prevent implementing that` | 2 |
| 4 | `internal/Handler.scala:35` | `why isn't this in Eval?` (on `ContHandler.answering`) | 3 |
| 5 | `internal/Handler.scala:47` | `why isn't this in Eval?` (on `ContOpHandler.answering`) | 3 |
| 6 | `internal/Handler.scala:59` | `why aren't these methods in Eval?` (on `LoopHandler.running`, `clauseDispatch`, `answers`) | 3 |
| 7 | `internal/Handler.scala:139` | `can we move this to Eval?` (on `answersLoopState`) | 3 |
| 8 | `internal/Eval.scala:72` | `how about we move the atTop branching to the called methods?` | 4 |
| 9 | `internal/Eval.scala:95` | `are you sure the repeated code for the special atTop case is worth it? size of the loop mehtod is critical for performance` | 4 |
| 10 | `internal/Eval.scala:436` | `how about we use kyo.Dict?` | 5 |
| 11 | `internal/Eval.scala:471` | `why do we need to collect then release? can't we release while iterating?` | 5 |
| 12 | `internal/Eval.scala:475` | `if we still need these auxiliary methods for release, let's move them to nested methods in the release method` | 5 |
| 13 | `internal/KyoInternal.scala:16` | `we can not have methods thrown at files like this. A source file is a type + its companion` | 6 |
| 14 | `internal/KyoInternal.scala:31` | `can't this be sealed abstract class and Debugger.onAlloc is in it? I imaigne putting in Kyo, which is a trait, would generate overhead? Or could we put it there so we can ensure all allocations are captured?` | 6 |
| 15 | `internal/KyoInternal.scala:39` | `this should be Pending no?` (on `object Kyo`) | 6 |

## Recommendations at a glance

| entry | subject | recommendation |
|---|---|---|
| 1a | `Stack` API and naming | DO for `snapshot` to `takeAll`; NEEDS RULING for `scratch`; nothing removable |
| 1b | `EffectTrace` API and naming | DO for `Builder.entries` to `Builder.regions`; nothing removable |
| 2 | the six function liftings | NEEDS RULING, with two mechanical experiments named |
| 3 | four "why not in Eval" notes | DO: leave all four where they are, record the three reasons, delete the markers |
| 4 | the two `atTop` notes | DO WITH MEASUREMENT, three changes in one bracket |
| 5 | the three `release` notes | DO: leave as is; the session's three answers verified, one claim unverifiable |
| 6 | the three `KyoInternal` notes | DO for the file split and the object rename; DO WITH MEASUREMENT for the hook |

---

## Entry 1. The two API-review notes: `Stack` and `EffectTrace`

Two notes, identical wording, one per file. They ask the same three questions of two different
surfaces, and the answers differ, so this entry is split.

> `// TODO please review if all the APIs are still necessary and if we can simplify them. Also check if we can improve naming for clarity`
>
> `kernel/internal/Stack.scala:9` (above `final private[kernel] class Stack`)
> `kernel/internal/EffectTrace.scala:29` (above `private[kernel] object EffectTrace`)

### 1a. `Stack`

**What the class is.** The eval's open-region stack, four parallel arrays plus an owed-dumps lane
per entry, pooled per thread. Regions moved onto a stack in `49e2171a2c` ("open regions live on a
stack, so nesting costs heap not stack"); the pool and `clear()` arrived with `1a6663fc57`; the
owed lanes and the `owe`/`oweBelow`/`takeEvalOwed` vocabulary with `fc01bc667a` and `54db7b2c66`;
`settle` by snapshot identity across lanes with `ccafba44c9` (soundness finding S4); `findExact`
with `ccafba44c9` (S2); the `epoch` counter with `ccafba44c9` (S6).

**Is anything dead?** No. Every public member has at least one main-source caller.

| member | main-source call sites |
|---|---|
| `isEmpty` | `Eval.scala:193, 223, 309` |
| `push` | `Eval.scala:173, 177, 267, 274` |
| `pop` | `Eval.scala:108, 140, 283, 292, 320, 332, 345, 363` |
| `takePopped` | `Eval.scala:109, 141, 285, 294, 321, 333, 346, 364` |
| `takeOwed` | `Eval.scala:117, 157` (and internally from `snapshot`, `dump`, `takePopped`) |
| `owe` | `Eval.scala:268, 275` |
| `oweBelow` | `Eval.scala:109, 141, 182, 254` |
| `owesAny` | `Eval.scala:109, 117, 141, 157, 284, 293` |
| `settle` | `Eval.scala:253` |
| `takeEvalOwed` | `Eval.scala:222, 310, 377` |
| `clear` | `Stack.scala:281` (the pool's release) |
| `snapshot` | `Eval.scala:233` |
| `contextual` | `Isolate.scala:79` |
| `depth` | eleven sites in `Eval.scala`, one in `Isolate.scala:129` |
| `handler` / `state` / `setState` / `continuation` | `Eval`, `Isolate`, `EffectTrace` |
| `truncate` | `Eval.scala:118, 158` |
| `find` | `Eval.scala:63` |
| `findExact` | `Eval.scala:286, 417` |
| `dump` | `Eval.scala:395` |
| `epoch` | `EffectTrace.scala:62` |
| `scratch` | `Eval.scala:123` (write only) |
| `Snapshot.Builder` | `Isolate.scala:113` |
| `borrow` / `release` | `Eval.scala:40, 381` |

**Can it be simplified?** Two candidates were examined and both are rejected.

`owe(i, snapshots)` writes lane `i`; `oweBelow(i, snapshots)` writes lane `i - 1`, falling through
to `evalOwed` at `i == 0`. They could be one method with `i == -1` as the eval lane. That trades two
methods whose names state which entry owes for one method plus a sentinel index, which is the wrong
direction under "correct by construction": the sentinel is a rule the caller has to remember, the
two names are a rule the compiler enforces. Leave as is.

`takePopped()` is `takeOwed(size)` (`Stack.scala:47`). It is not a forwarding helper in the banned
sense: it names the index invariant "the lane of the entry just popped", which after `pop()` is
exactly `size`. All eight call sites follow a `pop()`. Leave as is.

**Naming: one real finding and one that needs a ruling.**

Three methods return `Stack.Snapshot` and they do three different things to the stack:

| method | effect on the stack |
|---|---|
| `snapshot()` (`:114`) | takes all four lanes and leaves the stack empty |
| `dump(from)` (`:187`) | takes entries from `from` up, truncates to `from`, and registers the result as owed by `from - 1` |
| `contextual()` (`:131`) | reads the context regions, mutates nothing |

The class already has a prefix for "read and clear": `takeOwed`, `takePopped`, `takeEvalOwed`.
`snapshot()` is the park capture and clears everything, so it belongs to that family and reads today
as if it were the non-destructive one, which is `contextual()`. Proposal: rename `snapshot()` to
`takeAll()`. That is not new terminology, it is the class's own established prefix, and it makes the
one destructive-but-unnamed method say so. `dump` keeps its name: it is the established word for what
a crossing does, used throughout the backlog and in `Eval.dumped`.

`scratch` (`:21`) is a public `var` that nothing reads. Its only write is `Eval.scala:123` and its
only other touch is `clear()` nulling it. It exists because the store forces a C2 escape:
`a8cff0a3f8` measured `handleLoopAnswersInPlace` 363 to 155 us, `handleLoopFusesContinuation` 359 to
170, `statefulAnswersPaySuccessor` 163 to 153, validated by `-XX:-EliminateAllocations` and by
disassembly. With comments banned in these sources the name is the only thing carrying that intent,
and "scratch" says the opposite of what is true: it is not scratch space, it is a store whose whole
purpose is that it happens. A name that says so would be better, but every candidate is new
vocabulary, so this is the user's to pick.

**Proposal.**

1. `Stack.snapshot()` becomes `Stack.takeAll()`. One main-source call site (`Eval.scala:233`), two
   in `StackTest` (`:375, :391`).
2. `Stack.scratch` keeps its shape and is renamed to whatever the user chooses, or stays.
3. Nothing is removed, nothing merged.

**Risks.** Both are pure renames with no behavior and no bytecode change. `scratch` must stay a
written-and-never-read field on the pooled `Stack`; the JIT behavior depends on the store, not the
name, so the rename cannot regress it, but the `handleLoop*` rows are the ones that would show it if
the field were accidentally elided.

**Tests before and after.** `StackTest`, `StackThreadingTest`, then the full kernel suites on JVM,
JS and Native. **Benchmark rows:** none reached by a rename; run `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation` and `statefulAnswersPaySuccessor` anyway as the guard on `scratch`.

**Recommendation: DO** for `snapshot` to `takeAll`. **NEEDS RULING** for `scratch`: the question to
put is "the write-only field on Stack that forces the C2 escape is called `scratch`, which describes
storage rather than the mechanism; do you want it renamed, and to what?"

### 1b. `EffectTrace`

**What the object is.** The cold-path machinery that builds an effect trace and splices it into a
thrown exception's stack trace. The carrier is an `EffectTrace` exception suppressed on the original
(`:13`). `32ee518804` gave every site that applies user code an attach carrying what it was applying;
`ccafba44c9` (S6) added the per-eval epoch so a rethrow through a later eval on the same pooled stack
is not skipped; `b32f3b1318` ruled that the settled dispatch attaches nothing, because a handler over
the megamorphic apply in the loop body cost +27% on `deferBindUnderTrailingMap`.

**Is anything dead?** No. Each of the five entry points has exactly two call sites.

| entry point | call sites |
|---|---|
| `attach(ex, cont, frame)` | `Handler.scala:121` (`answers`), `Handler.scala:211` (`answersLoopState`) |
| `attach(ex, stack)` | `Eval.scala:335, 360` |
| `attach(ex, node, stack)` | `Handler.scala:71` (`running`), `Eval.scala:389` (`unhandled`) |
| `attach(ex, node, cont, stack)` | `Handler.scala:40, 52` (both `answering`s) |
| `splice(ex)` | `Eval.scala:312, 336, 361` |

Inside, `Builder.frame`, `region`, `arrow`, `node`, `entries` and `installInto` are each reached from
at least two of those, and the private `push`, `pushValue`, `drain` and `full` from the builder
itself.

**Can it be simplified?** The four `attach` overloads look like duplication and are not. They are
four fill scripts over one implementation: `reconstruct` (`:58`) is an `inline def` taking an
`inline fill: Builder => Unit`, so each overload expands its own script at its own call site and the
builder stays monomorphic on a path that must not cost anything when nothing throws. Three of the
four could be folded into one `attach(ex, node: Maybe[...], cont: Maybe[...], stack: Stack)`, because
their scripts are the same order with pieces omitted, but the fourth (`cont, frame`, with no stack)
is a different script, so the fold would leave two methods instead of four while making six call
sites spell `Absent`. That is not a simplification, it is a redistribution. Leave as is.

**Naming: one finding.** `Builder.entries(stack)` (`:182`) walks the live stack's regions and calls
`Builder.region` for each. The word `entries` is used elsewhere in the kernel for a `Stack.Snapshot`
(`Eval.scala:73, 131, 241`, `Isolate.scala:111`), so the builder method reads as if it took one.
`Builder.regions(stack)` matches the method it calls (`region`) and `Snapshot.regions`. Proposal:
rename `Builder.entries` to `Builder.regions`. Private to the object, three call sites, all in
`EffectTrace.scala`.

Two names were considered and left alone. `reconstruct` does not reconstruct, it guards the dedupe
and runs the fill, but "reconstruct the effect trace" is a fair reading and no better name is in the
kernel's vocabulary. `seen` plus `seenEpoch` are one fact split across two fields; the split is what
S6 needed, and pairing a reference with an epoch is the standard shape for exactly this.

**Already tracked elsewhere.** `isPlumbing` (`:94`) filters frames whose class name starts with
`kyo.proto.`. Under `kernel-swap-plan.md` phase 1 that package becomes `kyo.`, which would filter
every kyo frame rather than the kernel's own. The plan already lists this string as one to update; it
is noted here only so the two documents agree.

**Proposal.** Rename `Builder.entries` to `Builder.regions`. Nothing else changes.

**Risks.** None beyond the rename; the method is private to the object.

**Tests before and after.** `EffectTraceTest`, `EffectTracePhysicalTest`, `EffectTraceThreadingTest`,
then the full suites on three platforms. **Benchmark rows:** none. The trace machinery runs only on
the throwing path and no `ProtoBench` row throws.

**Recommendation: DO.**

---

## Entry 2. The six function liftings

> `// TODO let's check if we can remove these liftings. I also wanted to see if we can detect if an arrow is a pure function without < S in the return to enable optimizations. If I;m not mistaken these liftings prevent implementing that`
>
> `kernel/internal/Implicits.scala:17`, above `liftPureFunction1` through `liftPureFunction6`
> (`:18` to `:46`)

The note asks two questions that look like one. They are independent, and the second one's answer is
"no". Taking them apart is most of the work here.

### What the code does

`Implicits` (`:6`) is mixed into `object <` (`Pending.scala:15`), so everything in it is in implicit
scope wherever the pending type is. It holds three things: the value lift `lift` (`:8`), the guided
error `abortCastUnit` (`:15`), and the six function liftings (`:18` to `:46`). Each lifting is

```scala
implicit inline def liftPureFunctionN[A1, ..., B](inline f: (A1, ..., AN) => B)(
    using inline flat: CanLift[B]
): (A1, ..., AN) => B < Any =
    (a1, ..., aN) => lift(f(a1, ..., aN))
```

so each one is a wrapper that applies the value lift to the result. They add no lifting behavior of
their own.

**Why they exist.** `a507c475ea` (2024-07-09, "prelude: lift pure functions"), whose message is the
whole rationale: "I'm noticing more scenarios where pure functions fail compile when passed to
methods that take effectful functions while porting `kyo-core`." They were an ergonomics fix found
by porting a downstream module, not a kernel requirement. The proto's copy is a straight port of the
old kernel's (`kyo/kernel/internal/Implicits.scala:21` to `:49`).

**When they fire.** Only when an existing function *value* of type `A => B` reaches a position
expecting `A => B < S`. A lambda written at that position does not use them: the expected type
propagates into the body, and the value lift fires on the body instead. An eta-expanded method
reference behaves like the lambda. So the surface is narrow, and narrow in a way that makes it hard
to enumerate by reading: the only reliable enumeration is deleting them and compiling.

**What pins them today.** `ImplicitsTest` "lifted functions" (five cases: one, two, three and four
params, plus the negative case that they do not lift into a nested computation). No proto main source
depends on them.

### Answering the first question: can they be removed?

Mechanically, yes; the question is what breaks. Two facts bound it.

They are not kernel machinery. A read of the proto's main sources finds no site that passes a bare
function value into an effectful position, and the kernel's own surface does not take one. That is a
reading rather than a compile, which is why experiment 1 below exists: the known cost of removal
inside the kernel is the five `ImplicitsTest` cases, and the compiler is what turns "known" into
"complete".

They are downstream ergonomics, and downstream is where the cost lives. `kernel-swap-plan.md` records
71 files in 12 modules importing `kyo.kernel`; whatever `Implicits` the proto ships becomes theirs at
phase 1. The 2024 commit says the liftings were added because downstream code failed to compile
without them, so the honest prior is that some of those sites still rely on them.

There is also an argument for removal on the skill's own terms. "The single lift and the suspension
equilibrium" says there is one lift, the macro-backed implicit, and that the simplicity of the lift
surface is a design requirement. Six additional implicit conversions in the same implicit scope are
lift variants at function positions. They delegate to the one lift rather than adding a second one, so
they do not break the rule as written, but they do enlarge the surface it is protecting.

**The two experiments that settle it**, both mechanical and neither touching the proto:

1. **The enumeration.** Delete `liftPureFunction1` to `6` from the *old* kernel's
   `kyo/kernel/internal/Implicits.scala` in a throwaway worktree and compile the aggregate. The
   compiler names every dependent site. That is the list the decision needs, and it costs one build.
2. **The compile-time measurement.** Six `implicit inline def` candidates sit in the implicit scope of
   every function-typed position under the pending type. Time `sbt --batch 'kyo-kernelJVM/clean'
   'kyo-kernelJVM/compile'` with and without them. The skill treats compile time as a first-class cost
   (the `*With` overloads copy their bodies precisely because "inline nesting measurably inflates
   compile time"), so a number here is part of the case either way.

### Answering the second question: do they prevent pure-arrow detection?

No, and this is the part of the note to correct.

**What detection would need.** An `Arrow[A, B, S]` built from `Arrow.apply` (`Arrow.scala:52`) wraps
an `f: A => B < S`. A "pure" arrow would be one whose `f` returns `B`, so it cannot suspend and
cannot re-enter the eval. Knowing that at construction would let a chain of pure arrows be folded
into one application without the per-step `Pending` test in `Step.apply` and without the intermediate
`Defer`.

**Why the type cannot carry it.** `<` is `opaque type <[+A, -S] = A | Pending[A, S]`
(`Pending.scala:13`). Outside `object <` the alias is not transparent, so a bare `B` does not conform
to `B < S`; the value lift `lift` is what makes it conform. Once the lift has fired, the body's static
type is `B < S` whether the user wrote a value or a computation. That erasure is done by `lift`, the
one lift, at every lambda position. It is not done by `liftPureFunction1..6`, which only cover
function-value positions.

**Why overloading cannot recover it.** Adding `inline def apply[A, B](inline f: A => B)` beside
`inline def apply[A, B, S](inline f: A => B < S)` does not work, and not because of the liftings: a
lambda argument has no type before an overload is picked, and both alternatives are applicable to it,
so the call is ambiguous. Removing the liftings does not change that.

**What would work.** Detection has to read the tree, not the type, and the tree is available because
`map`, `flatMap`, `andThen` and `Arrow.apply` are all `inline` with `inline f`. `lift` expands to one
of two recognizable shapes, `v.asInstanceOf[A < S]` for the primitive branch and
`Nested.nest(v).asInstanceOf[A < S]` otherwise (`Implicits.scala:8` to `:13`). A macro in the existing
`CanLiftMacro` / `LiftMacro` family (`CanLift.scala:42`, `:62`) could ask whether the expanded body is
a lift application and emit a pure transform when it is. The liftings do not obstruct this; where they
fire they *help*, because the converted function's body is a `lift` application and therefore
recognizable by the same test.

**What does obstruct it** is the compiler equilibrium the skill describes: the `CanLift` evidence is a
splice macro, a file that summons a same-module macro is suspended to a retry run, and new summons
inside a core inlined-from file deepen the cascade until dotty crashes with `StaleSymbolException`,
visible only on the clean batch build. `Pending.scala` is exactly such a file. So pure-arrow detection
is a design item with a named, previously-paid-for risk, and it is not this note's item.

### Proposal

Split the note into two items and answer them separately.

- **The liftings.** No code change until the two experiments above have run. If the enumeration comes
  back empty or small, delete `liftPureFunction1` to `6` from
  `kernel/internal/Implicits.scala` and delete the five `ImplicitsTest` "lifted functions" cases,
  replacing the negative case with nothing (it only asserts that the liftings do not compose with
  nesting). If the enumeration is large, the liftings stay and the item closes with that number
  recorded.
- **Pure-arrow detection.** Open as its own backlog item, not blocked by the liftings, and record in
  it that the type-level route is closed by the opaque union plus the value lift, that the tree-level
  route is a macro in the `LiftMacro` family, and that the route runs into the suspension equilibrium.

### Risks

Removing the liftings is a source-compatibility change to the public implicit scope, so its blast
radius is downstream code, not the kernel. It cannot regress a benchmark: the conversions are
`inline` and expand to the same `lift` the lambda path already uses. It cannot change the
representation: they call the one lift.

The pure-arrow item carries the clean-batch-build risk described above and must be verified with
`sbt --batch 'kyo-kernelJVM/clean' 'kyo-kernelJVM/compile'` and diagnosed with `-Xprint-suspension`.

### Benchmark rows and tests

**Rows for the liftings:** none. To guard against an accidental change in what the lift emits, run
`PendingBytecodeTest`, which pins the lift's expansion at the call site ("lift of a primitive is a
bare cast" at 8 bytes, "lift of a String" at 2, "lift of a concrete class" at 8, "lift of a generic
value" at 8).

**Rows for pure-arrow detection, when it is taken up:** `trailingMapsStayLinear`,
`deferBindUnderTrailingMap`, `fusionAllocatesNothing`, `dynamicChainOfMapsStaysLinear`,
`dynamicChainOfBindsStaysLinear`, `pureIterationViaArrow`, `pureIterationViaMethod`, plus
`fusionAfterSuspension` and `fusionAfterSuspensionRunOnly`, which backlog Q7 still records as open
at 131 us against main's 88 and 0.53 against 0.28.

**Tests:** `ImplicitsTest`, `CanLiftTest`, `NestedTest`, `PendingTest`, `ArrowTest`, then the full
suites on JVM, JS and Native, then the clean batch build.

**Recommendation: NEEDS RULING.** The question to put is: "the six function liftings came from
`a507c475ea` because pure functions failed to compile while porting kyo-core; they are user-facing
ergonomics with no kernel use. Deleting them from the old kernel and compiling the aggregate will
name every dependent site. Do you want that enumeration run, and are you willing to break those sites?
Separately: they do not block pure-arrow detection, which needs a macro over the inline body; should
that become its own backlog item?"

---

## Entry 3. The four "why isn't this in Eval" notes

> `// TODO why isn't this in Eval?` on `ContHandler.answering` (`kernel/internal/Handler.scala:35`)
> `// TODO why isn't this in Eval?` on `ContOpHandler.answering` (`kernel/internal/Handler.scala:47`)
> `// TODO why aren't these methods in Eval?` on `LoopHandler.running`, `clauseDispatch` and `answers` (`kernel/internal/Handler.scala:59`)
> `// TODO can we move this to Eval?` on `Handler.answersLoopState` (`kernel/internal/Handler.scala:139`)

Four notes, one question, four different answers. Three of the five methods cannot move for a reason
recorded in a commit; two could move and should not, for a reason with a number behind it.

### What each method is

`Handler` is the region interface (`70ae4f5347`, "Handler becomes the region interface"). The five
methods under the notes are the region's side of the evaluator protocol.

- **`ContHandler.answering`** (`:36`) and **`ContOpHandler.answering`** (`:48`) are `try run(...)
  catch { attach the trace; rethrow }`. Called from `Eval.scala:78` and `:92`.
- **`LoopHandler.running`** (`:60`) is the same wrapper around `run`, plus
  `discard(Eval.dumped(stack, idx, kyo))` before the attach, so a foreign clause that throws leaves
  its region dumped. Called from `Eval.scala:122`.
- **`LoopHandler.clauseDispatch`** (`:74`) builds the `Arrow.Step` that dispatches a pending clause
  outcome: a nested pending defers, a `Continue2` re-installs the region through `Kyo.handle`, a done
  payload unnests. Called from `Eval.scala:111` and `:145`.
- **`LoopHandler.answers`** (`:95`) is the top-of-stack answer entry point, overridden at every
  `handleLoop*` site in `ArrowEffect.scala` (`:272, :323, :445`). Called from `Eval.scala:98`.
- **`Handler.answersLoopState`** (`:140`) is the `inline` body those three overrides share.

### The three reasons

**1. The trace attach must not be an exception handler inside `loop`.** The two `answering`s and
`running` exist because of `32ee518804`: "Every site that applies user code now attaches what it was
applying", which is information `Eval.guarded`'s outer catch cannot reconstruct because `loop`'s
locals are gone by then. The obvious placement is a `try` at the call site in `loop`, and that has a
number against it. Backlog Q5 records it: "a handler over the megamorphic apply inside the loop body
cost +27% on `deferBindUnderTrailingMap` (31.1 to 39.5 us at 3 forks), the same with a handler
touching only the stack, so the cost is the handler's presence". That is why the settled dispatch
attaches nothing at all (`b32f3b1318`).

The subtler point is why a *private method on `Eval`* is not equivalent. Today the call from `loop`
crosses a megamorphic virtual dispatch (`handler.answering`), which the JIT cannot inline, so the
exception handler is structurally outside `loop`. A private `Eval.answering(handler, ...)` would be a
monomorphic static call and therefore an inlining candidate, and if it inlined it would put the
handler range back inside `loop`, which is the shape the +27% measured. `loop` is also already the
method whose inlining budget produced the other recorded regression: `162c646984` records
`continuationBodiesFuse` regressing 79% with byte-identical allocation because "the owed additions
pushed loop past C2's DesiredMethodLimit, and inlining of the late-offset delivery accessors
stopped". Moving these methods trades a structural guarantee for a JIT decision on the one method
where that decision has already gone wrong once, and buys nothing but placement.

The alternative that avoids both, recording the in-flight node on the stack so no handler is needed,
was tried and ruled out permanently. Q5: "recording the arrows per iteration on the pooled Stack
retained them past the eval and is rejected for good ('DO NOT GO BACK TO THESE LEAKING FIELDS')".

**2. `answers` is the per-site extension point, and moving it would delete the largest measured win
in the loop handler.** `345a4f5bf3` made `answers` abstract-with-a-default so each `handleLoop*` site
implements it inline with the clause statically bound, "so consecutive same-tag settled answers loop
inside one compiled method per site: the clause, the outcome, the delivery, and the next suspension
decompose all monomorphize there and the per-answer `Continue2` dies in the same method". The numbers:
`handleLoopAnswersInPlace` 149 to 40.9, `handleLoopFusesContinuation` 174 to 40.7,
`statefulAnswersPaySuccessor` 153 to 41.3, the four-handler polymorphic row 764 to 41.9. A virtual
method on `LoopHandler` is the mechanism, not an accident of placement. `c9841297b6` then refuted the
"monomorphic entry alone" theory by experiment (+39 to +72 percent without scalac inlining), so the
per-site expansion is load-bearing on its own.

**3. `clauseDispatch` is where it is by an explicit ruling, and `answersLoopState` cannot move
without changing which file the compiler inlines from.** `c9841297b6`: "For the cold-interior pieces
the shared-body shape is right: the crossing step lives on `SuspendArrow`, the effectful-clause
pending exit on `LoopHandler` beside `answers`, both reached through virtual calls the JIT keeps out
of the loop." That names `clauseDispatch`'s placement as the decision, with the same virtual-call
mechanism as reason 1.

`answersLoopState` is different in kind. It is `inline`, expanded into `ArrowEffect.handleLoopState`
at three sites, so its home file is a file that `ArrowEffect.scala` inlines from. Moving it to `Eval`
would make `Eval.scala` an inlined-from file for `ArrowEffect.scala`, and `Eval.scala` already
summons the lift macro. The skill's rule is exact about this shape: "a file that summons a
same-module macro is suspended to a retry run ... new summons inside a core, inlined-from file deepen
the cascade until dotty crashes with a `StaleSymbolException`, and only on the clean batch build:
incremental compiles mask it. The map-based clause dispatch broke exactly this way." So the move
carries a previously-paid-for compiler risk for no runtime benefit. Locality also argues against it:
`answersLoopState` is the shared body of the `answers` overrides, and it belongs beside the abstract
method it implements.

### The one wart worth naming

`LoopHandler.running` calls `Eval.dumped(stack, idx, kyo)` (`Handler.scala:70`), a handler method
reaching back into the evaluator. It is there because `07cdd9d9bc` kept a foreign clause's throw
outside its region, so the unwind and the trace see the right geography. Moving `running` to `Eval`
would delete the back-reference and re-import the exception handler; keeping it keeps a
one-directional dependency that is already declared (`Eval.dumped` at `Eval.scala:394` is
`private[kernel]`, which is what lets `Handler` reach it). The back-reference is the smaller cost.

### Proposal

Leave all five methods where they are. Delete the four TODO markers and record the three reasons as
a backlog item, so the question does not get re-asked from a summary.

If the placement still reads wrong to the user, the only version of the move that does not run into
reason 1 is moving the *bodies* while keeping the virtual entry points, which is what the code already
is. There is no third shape.

### Risks of the alternative (recorded so the decision is revisitable)

Moving `answering` and `running` to `Eval`: the exception handler becomes an inlining candidate for
`loop`, whose budget is already the recorded cause of a 79% regression; the failure mode is invisible
to the suite and visible only in `PrintInlining` and the fusion rows.
Moving `answers`: deletes the per-site expansion, which is worth 3.6x to 18x on four rows.
Moving `clauseDispatch`: contradicts `c9841297b6`.
Moving `answersLoopState`: deepens the macro suspension cascade, visible only on the clean batch
build.

### Benchmark rows and tests

If the move is taken up despite the above, it reaches every suspension row, so the whole class runs
on both variants at `-f 1` and then `-f 3` on anything outside the drift band. Mandatory rows named
in advance: `deferBindUnderTrailingMap`, `deferBindUnderIdleHandler`, `continuationBodiesFuse`,
`idleHandlerAddsNothing`, `suspensionBaseline`, `suspensionFusesContinuation`,
`handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`,
`emittingClausesPayRegionRebuild`, `foreignCrossingsPayRotation`, `foreignCrossingsAnsweredInPlace`,
`sharedHandlerPaysDispatch`, plus `PrintInlining` on `loop` for both variants.

Tests: `HandlerTest` no longer exists (`3429829c14` removed it under the test-hygiene ruling and
verified each of its sixteen cases against a public twin), so the coverage is `ArrowEffectTest`,
`EffectTraceTest`, `EffectBracketTest`, `ContextEffectTest`, `LoopTest`, `EvalTest`, then
`EffectTracePhysicalTest` and `EffectTraceThreadingTest` on jvm-native, then the full suites on JVM,
JS and Native.

**Recommendation: DO** the leave-as-is. Record the three reasons in `backlog.md` and remove the four
markers. No code change.

---

## Entry 4. The two `atTop` notes

> `// TODO how about we move the atTop branching to the called methods?`
> `kernel/internal/Eval.scala:72`, inside the `ContHandler` arm of the suspension dispatch
>
> `// TODO are you sure the repeated code for the special atTop case is worth it? size of the loop mehtod is critical for performance`
> `kernel/internal/Eval.scala:95`, above `case handler: Handler.LoopHandler[...] if atTop =>`

Both notes are about the same flag and they get one design. `atTop` is computed once at
`Eval.scala:68` as `idx == stack.depth - 1`: the region that answers this suspension is the topmost
open one, so nothing has to run with inner regions absent.

### What the flag guards, arm by arm

`fc01bc667a` introduced the split ("the crossing arm splits into its degenerate top tier and the
eager non-top tier"); `162c646984` merged the cont and op tiers back into one arm each with
ternaries and left "the top guard on the loop arm"; `da539a68c4` gave the loop arm its four-way
outcome split. So the current shape is the settled result of three passes, not an accident.

**The cont and op arms (`:71` to `:94`) carry three ternaries each, six duplicated lines.** Taking
them one at a time:

`val entries = if atTop then Stack.Snapshot.empty else dumped(stack, idx, kyo)` is load-bearing for
more than code size. At `atTop`, `dumped` calls `stack.dump(idx + 1)` with `count = 0`, which still
allocates a zero-length array, wraps it, appends it to `owed(idx)`, and, decisively, sets
`owes = true` (`Stack.scala:204`). `owes` is sticky and gates six `owesAny` reads
(`Eval.scala:109, 117, 141, 157, 284, 293`) that `162c646984` introduced precisely as "one read and
a rarely taken branch per site". Removing the ternary would make all six branches taken on every
eval that suspends once, and would put a `KyoException("remainder discarded")` allocation
(`Eval.scala:407`) on every region exit. **Keep.**

`val continuation = if atTop then kyo.cont.chain(contA.chain(contB)) else kyo.crossing(entries, ...)`
is load-bearing. `crossing` (`KyoInternal.scala:67`) with an empty snapshot is observationally
equivalent: the `Kyo.Park` it builds is unwrapped by the eval's `case kyo: Kyo.Park if
kyo.entries.isEmpty` arm (`Eval.scala:181`). But it allocates an `Arrow.Step`, then a `Defer` and a
`Park` per operation, three allocations on the rows where there are none today. **Keep.**

`val ctx2 = if atTop then ctx else rebound(stack, entries, ctx)` is pure redundancy given the first
ternary. `rebound` (`Eval.scala:411`) loops `while i < entries.regions`, and `entries` is
`Stack.Snapshot.empty` on the `atTop` path, so `rebound(stack, Stack.Snapshot.empty, ctx)` returns
`ctx` after one comparison. **Removable**, in both arms.

**The two loop arms (`:96` to `:160`) are two algorithms, not one with a special case.** This is the
answer to the second note, and it has numbers on both sides.

The `atTop` arm dispatches through `handler.answers`, the per-site inline burst. `345a4f5bf3`
measured what that is worth: `handleLoopAnswersInPlace` 149 to 40.9, `handleLoopFusesContinuation`
174 to 40.7, `statefulAnswersPaySuccessor` 153 to 41.3, the four-handler polymorphic row 764 to 41.9.
The burst can only run at top, because it commits the region's state to the eval's own slot between
answers; with regions open above, the answer's continuation has to run with them absent.

The non-top arm dispatches through `handler.running` and splits the outcome four ways by whether
anything has to run outside the inner regions. `da539a68c4` measured that split:
`foreignCrossingsAnsweredInPlace` at 603 us and 1.52 MB/op against 1,092 us and 2.24 MB/op for the
eager version.

So the "repeated code" is not a duplicated algorithm. What *is* repeated across the two arms is the
pending-clause exit (`:104` to `:111` against `:135` to `:145`) and the done exit (`:112` to `:119`
against `:146` to `:159`), about sixteen lines. Extracting those from `loop` has been tried:
`9bf049ec4f` ("clausePending out of the loop") shipped known-red with
`deferBindUnderIdleHandler` +29%, and the resolution in `c9841297b6` was to extract only the arrow
body onto `LoopHandler` as `clauseDispatch` and leave the pop, owe and continuation bookkeeping
inline. Re-extracting them is therefore a measured-negative direction that can be reopened only with
a bracket showing the idle row flat.

### Proposal

Three changes, one bracket. Each is a separate variable, so measure them as a chain of three commits
if any row moves.

**P4.1. The operation reification moves off `loop`, onto the node.** `Eval.scala:87` to `:91` builds
an anonymous `Kyo.SuspendArrow` inside the loop body to reify the operation for a `ContOpHandler`.
Add a sibling to `crossing` on `Kyo.SuspendArrow`:

```scala
private[kyo] def operation: O[A] < E =
    val t = tag
    val i = input
    new Kyo.SuspendArrow[I, O, E, A, O[A], E]:
        def tag   = t
        def input = i
        def cont  = Arrow.id
```

and the arm becomes `handler.answering(kyo.operation, continuation, kyo, stack)`. No casts: the
node's own type parameters are exactly the ones the construction needs. This is the same move
`c9841297b6` made for the crossing step ("the crossing step lives on `SuspendArrow`"). It removes
five lines and one allocation site from `loop`. `ContOpHandler` has one user, `handleContOperation`
(`ArrowEffect.scala:135`), and no bench row exercises it, which is the point: the arm is cold and its
mass is spending the hot paths' inlining budget.

**P4.2. Drop the `ctx2` ternary in both arms**, so both read `val ctx2 = rebound(stack, entries, ctx)`.
Removes two branches and two basic blocks from `loop`, and adds one static call on the `atTop` path.

**P4.3. Merge the two `LoopHandler` case arms into one**, with the two bodies as the two branches of
one `if atTop`. The bodies do not change. Today the non-top path runs `instanceof ContHandler`,
`instanceof ContOpHandler`, `instanceof LoopHandler` with a failing guard, then `instanceof
LoopHandler` again. Whether the pattern matcher emits one type test or two for consecutive same-type
cases with a guard is a bytecode question, answered by `javap -c` on `Eval$` or by the javassist
harness `PendingBytecodeTest` and `ArrowEffectBytecodeTest` already use. If it emits two, the merge
removes one type test from `foreignCrossingsPayRotation` and `foreignCrossingsAnsweredInPlace`. If it
emits one, P4.3 is a no-op and should not be taken.

**Not proposed, recorded for the decision.** The six duplicated lines in the cont and op arms could be
written once if `ContHandler` and `ContOpHandler` had a common supertype carrying `answering`, which
would also collapse two case arms into one and drop a type test from the loop rows. That is a new
type, which the skill puts under "avoid new types: a new type must earn itself against the ones
already here". It plausibly does earn itself, but it is a surface decision inside the region
interface, so it belongs to the user, not to this pass.

### Risks

Every change here is inside `loop`, the method whose bytecode budget produced `162c646984`'s 79%
regression on `continuationBodiesFuse`. The mechanism there was C2's `DesiredMethodLimit`: past it,
the late-offset delivery accessors stopped inlining, with the `PrintInlining` lines naming it. All
three changes push in the safe direction (less mass in `loop`), but "less mass" is a hypothesis until
the inlining log confirms which callees changed verdict, so `PrintInlining` on `loop` for both
variants is part of the evidence, not an extra.

P4.2 puts a call on the hottest suspension path, and "inlining the delivery entry" is in the skill's
list of moves that looked right and measured worse. It is the one of the three most likely to come
back flat or negative.

P4.1 adds a megamorphic virtual call (`kyo.operation`) on the ContOp path. That path is cold and has
no bench row, so the change cannot be defended by its own row; it is defended by `loop` shrinking,
which the hot rows measure.

### Benchmark rows and tests

The suspension dispatch is shared machinery, so the claim covers the whole class, not a subset:
`Jmh/run -f 1 kyo.proto.bench.ProtoBench` on both variants back to back, same session, then `-f 3` on
any row outside the drift band. Rows that exercise changed code and are therefore mandatory to
report: `suspensionBaseline`, `suspensionFusesContinuation`, `handleLoopAnswersInPlace`,
`handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, `continuationBodiesFuse`,
`deferBindUnderIdleHandler`, `deferBindUnderTrailingMap`, `idleHandlerAddsNothing`,
`trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`, `foreignCrossingsPayRotation`,
`foreignCrossingsAnsweredInPlace`, `fusionAfterSuspension`, `fusionAfterSuspensionRunOnly`,
`partialSuspensionBaseline`, `sharedHandlerPaysDispatch`, `effectfulIterationViaLoop`. Add
`-prof gc` and read `gc.alloc.rate.norm`, because P4.1 moves an allocation site and P4.2 and P4.3
must not move any.

Tests: `ArrowEffectTest`, `ArrowEffectMaskTest`, `ContextEffectTest`, `EffectTest`,
`EffectBracketTest`, `IsolateTest`, `LoopTest`, `EvalTest`, `EvalCaptureTowerTest`, `StackTest`,
`EffectTraceTest`, then `EvalThreadingTest`, `EvalConcurrencyTest`,
`ArrowEffectThreadingTest`, `ContextEffectThreadingTest` and `EffectTracePhysicalTest`, then the full
suites on JVM, JS and Native, then `PendingBytecodeTest` and `ArrowEffectBytecodeTest`, then the
clean batch build.

**Recommendation: DO WITH MEASUREMENT** for P4.1 and P4.2, as a chain of three shas so each step is
attributable. **P4.3 is conditional**: run the bytecode check first and take it only if the type test
is genuinely duplicated. The common-supertype option is **NEEDS RULING**, with the question: "the
cont and op arms duplicate six lines and cost the loop rows an extra type test; a shared supertype on
`ContHandler` and `ContOpHandler` carrying `answering` would remove both, at the price of one new
type in the region interface. Do you want it?"

---

## Entry 5. The three `release` notes

> `// TODO how about we use kyo.Dict?` (`kernel/internal/Eval.scala:436`, above `val collected = ArrayBuffer.empty[AnyRef]`)
> `// TODO why do we need to collect then release? can't we release while iterating?` (`kernel/internal/Eval.scala:471`, above `releaseCollected(collected, ex)`)
> `// TODO if we still need these auxiliary methods for release, let's move them to nested methods in the release method` (`kernel/internal/Eval.scala:475`, above `expandOwed`)

Answers to these three exist from the session that wrote them. This entry verifies each against the
code and the history and records what held, what needed correcting, and what could not be checked.

### What the code does

`Eval.release(v, ex)` (`:435`) is the abandonment path: the scheduler holds a parked or partially
evaluated computation, decides not to resume it, and the regions inside it must fire their `release`
hooks. `80f08d7281` records the design before this one, where release composed as a computation and
ran innermost first through the carriers' own `release` implementations; the current shape walks the
value spine imperatively.

`collect` (`:438`) is `@tailrec` over the value: a `Defer` descends into its `value`, a `Handle`
appends `(handler, state)` for a `ContextHandler` and then descends, a `Park` expands its owed
snapshots and its own entries and then descends, a `Suspend` and a `Snapshot` contribute nothing.
`releaseCollected` (`:497`) then walks the buffer backwards in strides of two. `expandOwed` (`:476`)
flattens a chunk of snapshots into the same buffer, recursing into each entry's own owed.

### Note 10: `kyo.Dict`

**The answer, verified: no.** The instinct about *layout* is right and the objection is about the
*contract*.

`Dict[K, V]` is `Span[K | V] | HashMap[K, V]` (`kyo-data/shared/src/main/scala/kyo/Dict.scala:66`),
so a small `Dict` is exactly what `collected` is: one flat array of alternating slots.
`OrderedDict[K, V]` is `Span[K | V] | TreeSeqMap[K, V]` (`OrderedDict.scala:70`). What both add on
top of that layout is keyed lookup, and keyed lookup means one value per key.

`collected` is not keyed. It is an ordered multiset consumed in reverse, and the same handler can
legitimately appear more than once: a region that was dumped into one snapshot, resumed, and dumped
again appears in two snapshots, and a `Park`'s `entries` can hold a handler that an owed snapshot
also holds. Each occurrence carries its own state, so collapsing them by handler identity would drop
a release with a state the surviving entry does not have.

The second half of the answer is the one worth keeping: keying would also hide a regression. Backlog
Q7 records the bug where "a raw release hook fired twice for a region resumed and then unwound", and
its fix was `installed` settling the lane's debt in O(1), with the pin flipped from two releases to
one. Under a keyed carrier that pin passes whether or not `settle` works, because the carrier
deduplicates what the settle machinery is supposed to prevent. `ccafba44c9` (S4) then made `settle`
work by snapshot identity across lanes; a `Dict` would make S4's reproduction unfailable.

**Recommendation: DO** the leave-as-is. Record the reason so `kyo.Dict` is not proposed again from the
layout resemblance.

### Note 11: collect then release

**The answer, verified: yes, two reasons, and the second is the harder one.**

*Order.* Releases must run innermost first. `collect` visits outermost first: for a `Handle` it
appends the handler at `:447` and only then descends into `kyo.value` at `:451`, and `kyo.value` is
the region's interior. For a `Park` it walks `entries` ascending, and `installed` (`:256`) pushes
entries ascending, so index 0 is the outermost. `releaseCollected` iterating from
`collected.length - 2` downwards is what turns that into innermost-first. Releasing during the walk
would fire outermost first, which is the wrong order for nested brackets.

*Stack safety.* Producing innermost-first order directly would mean descending before appending,
which is not a tail call. `collect` is `@tailrec` (`:438`) and the spine it walks is user-shaped: the
`deepRecursion*` rows build `Defer` chains deep enough that a non-tail walk is a stack overflow, and
`maxStackDepth` (`internal/package.scala:3`) is 512, far below what those chains reach. The
`ArrayBuffer` is the explicit stack that keeps the recursion flat, which is the same reason the
kernel keeps regions on a `Stack` rather than on the JVM stack (`49e2171a2c`, "open regions live on a
stack, so nesting costs heap not stack").

One correction to the session's phrasing. The claim that the spine "can be a million nested `Defer`
nodes" is a shape argument, not a measured one; no bench row or test asserts a specific depth. The
argument does not need the number: any depth past the JVM's frame budget is enough, and the code is
`@tailrec` today precisely because that budget is not something the kernel controls.

**Recommendation: DO** the leave-as-is.

### Note 12: nesting `expandOwed` and `releaseCollected` inside `release`

**The answer, verified: no, because `drainOwed` needs both.**

`drainOwed` (`:505`) is `expandOwed` followed by `releaseCollected`, and it has six call sites:
`Eval.scala:311, 322, 334, 347, 365` and `:408` inside `drainDiscarded`. Those are the eval's own
exit drains: the eval end for root dumps, the recovery walk's three pops, `guarded`'s post-recovery
pop, and the discarded-remainder signal. Nesting the two helpers inside `release` would leave
`drainOwed` with no implementation and force it to be written again.

The three methods are one small module with one shape: `expandOwed` is snapshots to buffer,
`releaseCollected` is buffer to hooks, `drainOwed` is both, and `release` is spine to buffer plus
`releaseCollected`. Their placement as private siblings of `Eval` matches `dumped`, `rebound`,
`released` and `drainDiscarded`.

### The one claim that could not be verified

The session recorded that "a `ChunkBuilder` of typed pairs was tried and reverted for allocation (a
tuple per entry and a copy on read)". There is no trace of it: `git log -S"ChunkBuilder" --
kyo-kernel/shared/src/main/scala/kyo/proto/kernel/internal/Eval.scala` returns nothing, so if it was
tried it was in the working tree and never committed. The reasoning stands on its own without the
experiment. A `ChunkBuilder[(ContextHandler[?,?,?,?], Any)]` allocates a `Tuple2` per collected entry
where the flat `ArrayBuffer[AnyRef]` allocates none, and reading a `Chunk` back in reverse goes
through `toIndexed`, which `expandOwed` (`:478`) and `settleIn` (`Stack.scala:81`) already pay for
their own chunks. Recording it as reasoning rather than as a measurement is the accurate form.

### Proposal

No code change. Delete the three markers and record the four answers above in `backlog.md`, including
the two corrections (the depth claim is structural, not measured; the `ChunkBuilder` attempt has no
commit).

### Risks

None: nothing changes. The risk being avoided is the one the answers name, a keyed carrier masking a
double-collection regression that `ccafba44c9` fixed and whose pin is the only thing standing between
that bug and a silent return.

### Benchmark rows and tests

**Rows:** none. `Eval.release` runs only on abandonment and no `ProtoBench` row abandons a
computation.

**Tests** that hold the current behavior, and that any future change here must run:
`EffectBracketTest` (abandonment of a park with nothing owed, the discard drain, the throwing release
on the discard drain, multi-shot over a bracket, two parks, the contextual isolate forking inert),
`EvalTest` ("an abandoned region value derives its state once and releases that state", the reading
audit pins), `ContextEffectTest` (the two S4 pins: a region crossed to a foreign loop answered with a
pending outcome, and one that resumes inside a nested region), `IsolateTest`, then the full suites on
JVM, JS and Native.

**Recommendation: DO** the leave-as-is on all three, with the answers recorded.

---

## Entry 6. The three `KyoInternal.scala` notes

> `// TODO we can not have methods thrown at files like this. A source file is a type + its companion` (`:16`, above `short` and `site`)
> `// TODO can't this be sealed abstract class and Debugger.onAlloc is in it? I imaigne putting in Kyo, which is a trait, would generate overhead? Or could we put it there so we can ensure all allocations are captured?` (`:31`, above `trait Kyo`)
> `// TODO this should be Pending no?` (`:39`, above `object Kyo`)

Three notes about one file. The file today holds five things: two loose methods, the trait `Kyo`, the
sealed trait `Pending`, and the object `Kyo` carrying the node ADT. The first and third notes are
right and the fix is one split; the second is right in its goal and wrong in its mechanism, and the
reason is worth recording because it is what justifies `trait Kyo` existing at all.

### Note 13: the two loose methods

`short(v: Any)` (`:17`) and `site(frame: Frame)` (`:25`) are `private[proto]` top-level defs that
render a value and a frame for `toString`. Users: `Arrow.scala:97` and `:139` (both), `Loop.scala`
(imports `site`), and `KyoInternal.scala`'s own `Defer.toString` (`:50`).

The package already has the right home for this. `kernel/internal/package.scala` exists and holds
`maxStackDepth` and `maxTraceFrames` as package-level members. Moving `short` and `site` beside them
satisfies the rule as written: after the move no type's file carries a loose method, and a file named
`package.scala` is by construction the home for package-level definitions rather than a type's file
with methods attached to it.

While moving them, narrow the visibility. `private[proto]` is wider than the callers need: every
user is in `kyo.proto.kernel` or `kyo.proto.kernel.internal`, so `private[kernel]` is the correct
qualifier and matches the two constants already there. The import lines in `Arrow.scala` and
`Loop.scala` are unchanged.

The alternative considered and rejected: making them members of the node object, so `Pending.short`.
`Arrow.scala` would then import the node ADT's companion in order to render an arrow, which is a
worse dependency than the one it has.

### Note 15: `object Kyo` should be `object Pending`

**Yes.** Everything in it is a `Pending` subtype: `Defer`, `Suspend`, `SuspendArrow`,
`SuspendContext`, `Handle`, `Snapshot`, `Park`, and the five pre-mixed fusion bases `DeferWith`,
`SuspendArrowWith`, `SuspendContextWith`, `SnapshotWith`, `HandleWith`. The one non-class member,
`handle` (`:89`), builds a `Handle`. Renaming it makes it the companion of the `sealed trait Pending`
already declared eight lines above it, which is what the first note is asking the file to become.

It also removes a live name collision. `kyo.proto.Kyo` (`Kyo.scala:13`) is the user-facing combinator
companion, ported by `57cd147c9b` under backlog Q6, whose message already records the half-measure:
"the internal node base `trait Kyo` moved into KyoInternal.scala beside the internal object, so
`kyo.proto.Kyo` is only the user-facing companion". Under `kernel-swap-plan.md` phase 1 the two
become `kyo.Kyo` and `kyo.kernel.internal.Kyo`, so the collision survives the swap unless it is fixed
first.

Blast radius: 61 references in ten main files (`Eval.scala` 19, `ArrowEffect.scala` 13,
`ContextEffect.scala` 5, `EffectTrace.scala` 5, `Handler.scala` 5, `Pending.scala` 4,
`ConsoleDebugger.scala` 4, `Isolate.scala` 3, `Effect.scala` 2, `KyoInternal.scala` 1) and 11 in
three test files (`EffectBracketTest` 9, `EvalTest` 1, `EffectTraceTest` 1), plus six
`import kyo.proto.kernel.internal.Kyo` lines. Mechanical, and most files already import
`internal.Pending` for their pattern matches, so the import becomes one name covering both the trait
and its companion.

`@publicInBinary` on `Defer`'s constructor (`:42`) and the `private[kyo]` qualifiers are unaffected;
only the emitted binary names change, from `Kyo$Defer` to `Pending$Defer`, and the proto is
unpublished. `ConsoleDebugger.scala:83` to `:86` matches on the node classes to render their names as
strings; the strings it emits are literals, so `DebuggerTest` is unaffected, but the file is on the
rename list.

### Note 14: `sealed abstract class`, and the allocation hook

**It cannot be a class, and it cannot be sealed.**

`Kyo` is the shared supertype of *both* sides of the representation: `Arrow` extends it
(`Arrow.scala:14`) and `Pending` extends it (`KyoInternal.scala:35`). The five pre-mixed fusion
bases inherit from both at once, for example `abstract class DeferWith[A, B, -S] extends
Defer[A, B, B, S] with Arrow.Transform[A, B, S]` (`:133`). `Defer` is a class on the `Pending` side
and `Arrow.Transform` is a trait on the `Arrow` side; if `Kyo` were a class, `Arrow` would have to be
one too, and a class cannot have two class parents. Those five bases exist because mixing at each
fusion site emitted mixin forwarders that denied the JIT a small receiver profile (`1a6663fc57`), so
they are not negotiable.

It cannot be `sealed` either, for a reason the first note creates: `sealed` requires all direct
subtypes in the same file, and `Arrow` and `Pending` are in different files by the one-type-per-file
rule.

The old kernel could use `sealed abstract private[kyo] class Kyo`
(`kyo/kernel/internal/KyoInternal.scala:18`) because its `Arrow` does not extend `Kyo` at all
(`kyo/Arrow.scala:28`). That is the whole difference, and it is why the two kernels answer this
question differently.

**On the hook: the goal is reachable, and `Kyo` is the only place it works.**

Today `Debugger.onAlloc(this)` appears at seven sites: `Arrow.Step` (`Arrow.scala:96`),
`Arrow.Chain` (`:115`), and `Defer`, `Suspend`, `Handle`, `Snapshot`, `Park`
(`KyoInternal.scala:43, 55, 104, 117, 127`). Those seven cover every allocation on both sides:
`Id` and `Ensure` extend `Step`, `SuspendArrow` and `SuspendContext` extend `Suspend`, and each
`*With` base extends one of the node classes.

Moving the statement into `trait Kyo` is coverage-neutral, replaces seven lines with one, and makes
it structurally impossible for a new node or arrow class to miss the hook, which is the note's stated
goal. It also fires exactly once for the values that are both an arrow and a node: a trait initializer
runs once per instance under linearization, so `DeferWith` reports one allocation, as it does today.

This is the argument that keeps `trait Kyo` alive. It carries no members anyone uses: a repository-wide
search finds `Kyo[` only in its own declaration and in the two `extends` clauses, never in a signature.
Deleting it and giving `Arrow` and `Pending` their own `frame` declarations would be tempting, and
would remove one of the two duplicate filenames the split creates, but it would also make the single
hook impossible: with the statement in both `Arrow` and `Pending`, every `*With` value would report
twice.

**What it does not achieve.** "All allocations captured" is still not true, because `Loop.Continue1`
through `Continue4` carry their own `Debugger.onAlloc(this)` (`Loop.scala:18, 24, 31, 39`) and extend
`Serializable`, not `Kyo`. That is deliberate: `87f98b6637` and the old kernel's design note make
`Continue` extend nothing but `Serializable` so the lift's boxing arm is provably unreachable. Those
four keep their own hook.

**The overhead the note suspects is real and small.** `Kyo` has no initialization statement today, so
adding one makes it an initializing trait and every arrow and node constructor gains a
`Kyo.$init$(this)` call. With the flag off, `Debugger.enabled` is
`CompileTimeFlag.boolean("kyo.proto.kernel.internal.Debugger.enabled", false)` and
`inline if enabled then ...` compiles to nothing, so today the seven sites cost zero bytes and after
the move every allocation pays one call to an empty static method. Arrow and node construction is the
hottest allocation in the kernel, so this is a measurement, not a judgment call.

### Proposal

**P6.1 (note 13).** Move `short` and `site` from `KyoInternal.scala` to
`kernel/internal/package.scala`, narrowing `private[proto]` to `private[kernel]`.

**P6.2 (notes 13 and 15).** Split `KyoInternal.scala` into two files and delete it:

- `kernel/internal/Kyo.scala`: `trait Kyo[+A, -S]` and nothing else.
- `kernel/internal/Pending.scala`: `sealed trait Pending[+A, -S] extends Kyo[A, S]` and
  `object Pending`, renamed from `object Kyo`, holding the node classes and `handle`.

Rewrite the 72 references and the six imports. In the same edit, check whether `trait Kyo` compiles as
`private[kyo]`: nothing outside `kyo` names it, and narrowing it would make the internal marker a
compiler rule rather than a package-name convention. If a public `sealed trait Arrow` cannot extend a
`private[kyo]` parent, it stays as it is.

**P6.3 (note 14).** Move `Debugger.onAlloc(this)` from the seven class bodies into `trait Kyo`.
`Loop.Continue1` through `Continue4` keep theirs.

**Sequencing against the swap plan.** `kernel-swap-plan.md` phase 1 moves
`kyo/proto/kernel/**` to `kyo/kernel/**` with `git mv` "so history follows". A file split does not
survive a `git mv` cleanly, so P6.1 and P6.2 land before phase 1, and phase 1 then renames files that
are already in their final shape. The plan's inventory line for `kyo/proto/kernel/internal/*` goes
from ten files to eleven.

**One cost of the split to name.** After the swap the module will have `kyo/Kyo.scala` beside
`kyo/kernel/internal/Kyo.scala`, and `kyo/kernel/Pending.scala` beside
`kyo/kernel/internal/Pending.scala`. The second pair is unavoidable in a different way:
`kernel/Pending.scala` is named for `Pending` although it declares `opaque type <` and `object <`,
because `<` cannot name a file. That exception is inherited from the old kernel
(`kyo/kernel/Pending.scala`) and stands.

### Risks

P6.1 and P6.2 are source-only, with no behavior and no bytecode change. The one thing to verify is
the clean batch build, because `Effect.scala` imports `internal.Kyo.*` (`:11`) and moving the object
across a file boundary changes which file the inline sites in `ArrowEffect.scala` and `Effect.scala`
reference; the lift equilibrium is sensitive to exactly that shape and incremental green is not clean
green.

P6.3 is the measured one. It adds a static call to every arrow and node constructor, on the paths
`fusionAllocatesNothing`, `deferBindPerStep` and `trailingMapsStayLinear` exist to hold flat, and
constructors are inlined into their allocating method, so the added bytecode lands inside `map`'s
expansion and inside `loop`. The `162c646984` mechanism applies: mass added anywhere inside `loop`'s
inlining reach can flip a callee's verdict.

### Benchmark rows and tests

**P6.1 and P6.2:** no rows. Run the clean batch build
(`sbt --batch 'kyo-kernelJVM/clean' 'kyo-kernelJVM/compile'`), then the full suites on JVM, JS and
Native, then `PendingBytecodeTest` and `ArrowEffectBytecodeTest`, whose pinned sizes must not move
(`map` at `test -> 18` and `run -> 95`, `suspend` and `suspendWith` at 14, `handleCont` at 48).

**P6.3:** the whole `ProtoBench` class on both variants, `-f 1` to screen and `-f 3` on anything
outside the band, with `-prof gc` to confirm `gc.alloc.rate.norm` is byte-identical (it must be: the
hook compiles away, so any allocation delta is a bug in the move). Rows that must be reported because
they exercise arrow and node construction directly: `fusionAllocatesNothing`,
`fusionPastBudgetPaysRescuesOnly`, `deferBindPerStep`, `deferBindUnderIdleHandler`,
`deferBindUnderTrailingMap`, `trailingMapsStayLinear`, `dynamicChainOfMapsStaysLinear`,
`dynamicChainOfBindsStaysLinear`, `suspensionBaseline`, `suspensionFusesContinuation`,
`continuationBodiesFuse`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`,
`statefulAnswersPaySuccessor`, `emittingClausesPayRegionRebuild`, `nestedPayloadsUnwrapInMaps`,
`inlineLimitKeepsZeroAllocation`, `pureIterationViaArrow`, `pureIterationViaMethod`,
`evalFixedOverhead`. Plus `PrintInlining` on `map`'s `run` and on `loop`, since a constructor growing
is exactly the kind of change only that log shows.

Tests for all three: `DebuggerTest` (the hook defaults, install and uninstall, and that a session
observes an eval exactly when `Debugger.enabled` compiles the hooks in), `ArrowTest`, `PendingTest`,
`KyoTest`, then the full suites on three platforms. `DebuggerTest` is the one that would catch a
coverage change in P6.3, so it is worth extending with a case asserting that a value which is both an
arrow and a node reports one allocation, not two.

**Recommendation: DO** for P6.1 and P6.2. **DO WITH MEASUREMENT** for P6.3, as its own commit after
the split so the two variables do not travel together.

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

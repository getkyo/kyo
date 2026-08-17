# Optimization candidates: JIT inlining and method size

Analysis only. No source was edited, no build or benchmark was run. Every number below is read out of
`qa-artifacts/qa-logc.xml` (3.2 MB HotSpot LogCompilation, JDK 25.0.3+9-LTS, bsd-aarch64) or quoted from source.

---

## 0. Provenance of the log, stated before anything rests on it

`qa-artifacts/qa-jmh.json` contains exactly one entry:

```
kyo.kernel.bench.ProtoKernelBench.nestedPayloadsUnwrapInMaps   avgt   forks=1   5.839 us/op   32080 B/op
```

`qa-logc.xml` is that single fork. Grepping the log for benchmark class names returns only
`ProtoKernelBench_nestedPayloadsUnwrapInMaps_jmhTest` and `ProtoKernelBench_jmhType`. So this log covers
**1 of the 15 rows, at `-f 1`**. Everything derived from it is a fact about `nestedPayloadsUnwrapInMaps`
and a hypothesis about the other 14.

The hot compilations survive to the end of the run: `<make_not_entrant>` never fires for compile ids
922, 923, 954, 956 or 958. The only invalidations are C1 warmup nmethods and one OSR stub.

---

## 1. The finding that reframes the brief: every "callee is too large" in this log is C1, not C2

HotSpot's LogCompilation prints `level='3'` (or `'1'`, `'2'`) on C1 tasks and omits `level` on C2 tasks.
Splitting all 4,945 call sites by that attribute:

| reason | C1 (L1/L2/L3) | C2 (L4) |
|---|---:|---:|
| callee is too large | **1166** | **0** |
| no static binding | 205 | 0 |
| callee uses too much stack | **173** | **0** |
| not inlineable | 121 | 0 |
| callee's klass not linked yet | 60 | 0 |
| low call site frequency | 0 | 19 |
| already compiled into a big method | 0 | 6 |
| hot method too big | 0 | 5 |
| too big | 0 | 3 |
| recursive inlining is too deep | 0 | 2 |
| virtual call | 0 | 2 |

The budget model in the brief (MaxInlineSize 35, FreqInlineSize 325, "too large is relative to the caller's
remaining budget") is the **C2** model, and the log confirms it for C2: of 476 C2 inline successes, 77 are
larger than 35 bytes, the largest is 125 bytes, and **none** exceeds 325.

C1 obeys a different, flatter rule, also verifiable from this log without reading JVM source: of 2,129 plain
C1 `inline` successes, 2,124 are 35 bytes or smaller and the maximum is 36. C1 has no hot-site exception,
so any callee over 35 bytes is refused with `callee is too large` regardless of how hot the site is.
C1 tier-3 code is profiled warmup code; it is discarded once C2 compiles the method.

**Consequence.** The refusal table in the brief is a warmup artifact. Re-reading those eight rows split by tier:

| method | bytes | C1 verdicts | C2 verdicts |
|---|---:|---|---|
| `Stack::push` (3 overloads) | 37 / 50 / 60 | 7 refused, `callee is too large` (all in cid 945, one C1-L3 compile of `Eval$::loop`) | **1 site, `inline (hot)`, count 4098** |
| `Stack::truncate` | 52 | 7 refused (cid 943 x1, cid 945 x6) | **1 site, `inline (hot)`, count 4098** |
| `Stack::pop` | 52 | 1 refused (cid 945) | **1 site, `inline (hot)`, count 4098** |
| `Safepoint::enter` | 50 | 1 refused (cid 896) | **5 sites, all `inline (hot)`, counts 7214 to 79448** |
| `Safepoint::exit` | 19 | 1 refused (cid 896), `callee uses too much stack` | **5 sites, all `inline (hot)`, counts 7008 to 79368** |
| `Nested::nest` | 26 | 2 refused (cid 902, 907), `callee uses too much stack` | **4 sites, all `inline (hot)`, count 8351** |
| `Eval$::dump$1` | 92 | 2 refused (cid 945) | **1 site, `inline (hot)`, count 4098** |
| `Arrow$Transform::chain` | 19 | 2 inlined (cid 896) | **5 sites, all refused, `low call site frequency`, counts 8 / 8 / 17 / 17 / 80** |

Seven of the eight rows have **zero** C2 refusals. The eighth (`Transform::chain`) is refused in C2 for the
opposite reason from the one in the brief, and that refusal is the desired outcome (see section 6).

This is the same failure mode the brief itself warns about ("two runs of one identical comparison named
disjoint mechanisms"), one level deeper: aggregating verdicts across tiers manufactures mechanisms that
do not exist in the code that runs the measurement iterations.

---

## 2. Direct answer: is `Stack::push` on a hot path in these benchmarks?

**In `nestedPayloadsUnwrapInMaps`, no. It is close to a red herring twice over: it is not refused in C2,
and it executes roughly once per benchmark operation against roughly 1000 map deliveries. The log cannot
settle the question for the other 14 rows, and there is good reason to expect a different answer on
several of them.**

Evidence, in order of strength.

**(a) It is not refused where it matters.** The single C2 compilation of `Eval$::loop` is
`<task compile_id='954' ... bytes='1582' count='4376' backedge_count='13128'>`. In it,
`Stack::push` at bci 118 reports `inline (hot)` with `count='4098'`. All seven refusals live in compile id
945, the C1 tier-3 compile of the same method, which was made not entrant at stamp 0.274 when 954 replaced it.

**(b) The drive loop runs three iterations per operation.** The C2 parse of `Eval$::loop` records the
outer-loop branch directly:

```
kyo.kernel.proto.Eval$::loop  size=1582  bc=23 -> 1541  taken=4376  not_taken=13128  prob=0.25
```

13,128 backedges against 4,376 invocations is exactly 3.0 iterations per call. The nmethod confirms it at a
later moment: `count='5092' backedge_count='15274'`. `ProtoKernelBench.scala:190` sets `NarrowDepth = 1000`
and `ProtoKernelBench.scala:112-117` builds a 1000-deep `.map` chain, so ~1000 deliveries per op are handled
somewhere other than the drive loop.

**(c) The 1000 deliveries happen inside the `map` expansion, not inside `Eval.loop`.** In this row
`boxed(ask)` (`ProtoKernelBench.scala:115`, `:204`) produces a `Nested` payload, not an `Arrow`, so
`Pending.scala:48-62` takes the `case v =>` arm: unnest, poll the safepoint, call `f(res)` directly. `f` is
the user's `_ => loop(i + 1)`, which recurses. The recursion is compiled standalone as
`ProtoKernelBench::loop$9` (cid 923) and `run$39` (cid 922), and those compilations contain no `Stack` call
at all. Their per-site counts are 7,000 to 80,000 while every depth-1 site in `Eval$::loop` reads 4,098 or 8,196.

**(d) Within `Eval$::loop`, push is exactly as hot as everything else that survived, and that is once per op.**
All depth-1 sites in cid 954 carry `count='4098'` except one at 8,196: `Stack$::current`, `Stack::size`,
`Bind::cont`, `Stack::push`, `Bind::value`, `Stack::marked`, `Stack::state`, `Stack::pop`, `Step::step`,
`Transform::tail`, `Eval$::dump$1`, `Identity$::chain`, `Transform::head`, `anon$51::apply`, `Stack::truncate`.
4,098 against 4,376 invocations is one execution per call. The other seven push/pushAll bcis that C1 saw
(83, 851, 899, 923, 971, 1172, 1182) do not appear in the C2 parse at all: C2 pruned them as never taken.

**Where the evidence runs out.** Rows that suspend through a handler drive `Chain`, `Handle`, and `Suspend`
through the loop on every step, which is where the `push(f, t)` (50 B) and `push(f, t, s)` (60 B) overloads and
`pushAll` (134 B) live (`Eval.scala:206-215`). Those are `suspensionBaseline`, `suspensionFusesContinuation`,
`handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor`, `idleHandlerAddsNothing`,
`trailingMapsStayLinear`, `emittingClausesPayRegionRebuild`, `continuationBodiesFuse`, and
`deepRecursionPaysRescuesOnly`. **No log exists for any of them.** Saying `Stack::push` is cold is a statement
about one row; the honest scope is "cold here, unmeasured elsewhere, and the cheapest way to settle it is one
LogCompilation run on `suspensionBaseline`."

---

## 3. The `callee uses too much stack` peculiarity, explained

All 173 occurrences are C1 (171 at L3, 2 at L2). C2 emits it zero times.

The callee sizes at those 173 sites range from 8 to 35 bytes, with a maximum of exactly 35. That is the tell:
C1's size gate rejects anything over 35 first, so only callees that already passed the size gate ever reach
this later check. The message therefore cannot be about the callee's code size, and it is not a statement
about the callee at all: it is a property of the (caller, callee) pair. C1's graph builder grafts an inlined
callee's locals and expression stack onto the caller's frame and bails when the accumulated frame would
exceed its budget. The same 10-byte `ConcurrentHashMap::tabAt` and 14-byte `SoftReference::get` appear in
that list, which no size-based reading can explain.

The pair-not-callee reading is directly falsifiable in this log, and it holds:

- `Nested::nest` (26 B) is refused with this reason at C1-L2 under `ProtoKernelBench$::boxed` (cid 902, 907),
  and inlines `inline (hot)` at C2 under **the same call chain**, four times, count 8,351 (cid 922 depth 2 and 4,
  cid 923 depth 3 and 5).
- `Safepoint::exit` (19 B) is refused once at C1-L3 under `run$39` (cid 896), and inlines `inline (hot)` at
  five C2 sites including depth 3 inside `Eval$::loop` at count 79,368.

So there is nothing to fix. A 19-byte and a 26-byte method are not too big; a warmup-tier compiler declined
to graft them into a particular frame, then threw that code away.

---

## 4. What the C2 evidence actually shows: the hot compilation unit

The row's real hot unit is `loop$9` / `run$39`, compiled at cid 923 and 922. Both resolve to the same inline
tree: **1,708 bytes of bytecode** (66 or 112 root plus 1,642 or 1,596 inlined, 91 inlined callees).
Where those bytes go:

| component | bytes | share of 1,708 |
|---|---:|---:|
| safepoint poll total (`Safepoint` 248, `Safepoint$` accessors 20, `VarHandle*` 410, `AtomicReferenceArray::get` 24, `Thread::threadId` 10) | **712** | **41.7%** |
| of which: the `VarHandle` guard chain alone | **434** | **25.4%** |
| `Arrow$Identity$::apply`, two copies at 92 | 184 | 10.8% |
| `Nested` (`nest` 26, `unnest` 21, `apply` 9, ctors) x2 | 124 | 7.3% |
| constructor chains (`Bind`/`Suspend` -> `Defer` -> `Step` -> `Arrow` -> `AbstractFunction1` -> `Object`) | 130 | 7.6% |
| `Integer::valueOf` x2 | 64 | 3.7% |

The consequence is visible in the log as a C2 verdict, not an inference. `loop$9` compiles to a **3,464-byte
nmethod** and `run$39` to **3,896 bytes**, both above `InlineSmallCode`. So `loop$9` is refused at the four
hottest kyo-adjacent C2 sites in the entire log:

```
cid=956  loop$9  b=66  cnt=5121    already compiled into a big method   caller=nestedPayloadsUnwrapInMaps
cid=954  loop$9  b=66  cnt=79368   already compiled into a big method   caller=run$39
cid=961  loop$9  b=66  cnt=45166   already compiled into a big method   caller=nestedPayloadsUnwrapInMaps
cid=979  loop$9  b=66  cnt=101487  already compiled into a big method   caller=nestedPayloadsUnwrapInMaps
cid=980  loop$9  b=66  cnt=109640  already compiled into a big method   caller=nestedPayloadsUnwrapInMaps
```

and `run$39` is refused with `recursive inlining is too deep` at cid 922, count 8,351. Kernel bytecode volume
is what pushed those nmethods over the line, so it is a kernel lever even though the refused method is the
benchmark's.

C2 branch profiles give the second half of the picture: three of the largest kernel methods on this path
execute only their entry compare.

| method | bytes | branch record | meaning |
|---|---:|---|---|
| `Arrow$Identity$::apply` (`Arrow.scala:63-74`) | 92 | `bc=2 (if_acmpne) -> 7  taken=0  not_taken=6007  prob=never` | `next eq Identity` holds 6007/6007; returns at bci 6; ~86 of 92 bytes never execute |
| `Stack::truncate` (`Stack.scala:56-61`) | 52 | `bc=5 (if_icmpge) -> 51  taken=4382  not_taken=0  prob=always` | the while loop body runs zero times, 4382/4382 |
| `Eval$::dump$1` (`Eval.scala:35-49`) | 92 | `bc=11 -> 31 taken=4386 always`, `bc=34 (if_icmpne) -> 44 taken=0 not_taken=4386 never` | scan loop never iterates, `i == top` always true, returns `Arrow[Any]` 4386/4386 |
| `Safepoint::get` (`Safepoint.scala:71-76`) | 28 | `bc=17 (if_acmpne) -> 22  taken=0  not_taken=79960  prob=never` | `slots.get(h) eq thread` holds 79960/79960; `resolve` never called |
| `Stack::push` (`Stack.scala:26-30`) | 37 | `bc=9 (if_icmpne) -> 16  taken=4390  not_taken=0  prob=always` | `grow()` never called; the rest of the body does real work |
| `Safepoint::enter` (`Safepoint.scala:111-112`) | 50 | `bc=26 (if_icmpne) -> 41  taken=9  not_taken=7671` | park tail taken 9/7680; the fast arm is the fall-through |

That is the candidate generator. Three methods carry 236 bytes into hot inline trees to perform one compare.

---

## 5. Candidates

Ranked cheapest-and-most-decisive first. None adds `inline`.

---

### C1. Split `Arrow.Identity.apply` into a fast-path entry and an out-of-line delivery

**Hypothesis.** `Identity.apply` is 92 bytes of which 6 execute on 100% of profiled calls, and it is inlined
twice into the 1,708-byte hot unit; moving the other 86 bytes behind a call removes ~156 bytes (9.1%) from
that unit without changing any semantics.

**Mechanism.** `Arrow.scala:63-74`:

```scala
def apply[C, S2](v: Any < S2, next: Arrow[Any, C, S2]): C < S2 =
    if next eq Identity then v.asInstanceOf[C < S2]
    else
        v match
            case v: Arrow[Any, Any, S2] @unchecked => Chain(v, next)
            case v =>
                next match
                    case _: Defer[?, ?, ?] =>
                        Bind(v, next)
                    case _ =>
                        val s = next.step
                        s.head(v, s.tail)
```

The compiled shape is `aload_2; aload_0; if_acmpne 7; aload_1; areturn; ...`. The C2 parse of that method
inside cid 954 records `<bc code='166' bci='2'/>` followed by
`<branch target_bci='7' taken='0' not_taken='6007' cnt='6007.000000' prob='never'/>`: the else block at
bci 7..91 is dead in this row. It is dead by construction, not by luck: `Pending.scala:57-58` calls
`step.head(f(res), step.tail)`, and for a `Transform` `step.tail` is `Arrow[B]` (`Arrow.scala:39`) which is
`Identity` (`Arrow.scala:26`). The log shows exactly that resolution at cid 922 bci 96
(`Transform::tail` -> `Arrow$::apply`, 7 B) feeding bci 99.

The move is the one `SuspendWith` already documents at `Arrow.scala:132-138` ("Kept out of apply so the shape
that needs no folding stays the smaller body"): keep `if next eq Identity then v.asInstanceOf[C < S2] else
deliver(v, next)` in `apply` and put the rest in a `private def deliver`. Expected `apply` size 12 to 15 bytes,
which also puts it under `MaxInlineSize` so it inlines at non-hot sites and in C1.

**Predicted signal.**
- `javap -c -p` on `kyo/kernel/proto/Arrow$Identity$.class`: `apply(Object, Arrow)` drops from 92 to 15 or fewer.
- Per-site verdicts, at the five C2 sites that exist today (cid 922 bci 99 depth 1 and 3; cid 923 bci 99
  depth 2 and 4; cid 954 bci 99 depth 3): `Arrow$Identity$::apply` stays `inline (hot)` at all five with a
  new `bytes` value. A new callee `Arrow$Identity$::deliver` appears at those same five sites and is refused
  with **`low call site frequency`**, the same reason `Safepoint::enterPark` and `Transform::chain` already
  get on the cold arms beside it.
- Total inlined bytecode for the cid-922/923 unit falls from 1,708 to about 1,552.
- nmethod size for `loop$9` falls from 3,464.
- JMH: `nestedPayloadsUnwrapInMaps` at `-f 3`, plus B/op unchanged (this changes no allocation).

**Falsifier.** `apply` drops below 35 bytes, `deliver` shows up refused for low frequency, the unit shrinks
by the predicted ~156 bytes, and the JMH score moves less than the 3-4% drift band on every row. That kills it.
It is also killed if `deliver` inlines anyway (meaning the else arm is hot on some row), or if `loop$9`'s
nmethod stays above `InlineSmallCode` and the `already compiled into a big method` refusals do not change.

**Target rows.** Moves: `fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`,
`deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`, `idleHandlerAddsNothing`, `continuationBodiesFuse`
(all deliver settled values through `Identity`). At risk of regressing, because they take the else arm and now
pay one call: `trailingMapsStayLinear`, `suspensionBaseline`, `suspensionFusesContinuation`,
`emittingClausesPayRegionRebuild`. Should not move: `evalFixedOverhead` (one map, dominated by `Eval` entry),
`handleLoopAnswersInPlace`, `handleLoopFusesContinuation`, `statefulAnswersPaySuccessor` (dominated by the
handler arms). Full class on both variants regardless.

**Cost and risk.** One method split, roughly ten lines, no signature change. The named risk is the SKILL's own
counter-precedent: "Inlining the delivery entry ... measured slower than a larger version that the JIT refused,
on the same row." That entry describes flipping an entry's own verdict from refused to inlined. This change
does not flip `apply`'s verdict (it already inlines at all five C2 sites); it reduces the byte volume the
caller pays. Different mechanism, but the counter-precedent is close enough that a full-class run is mandatory
before any claim.

**Gated constructs.** No `inline`. No cast added or removed (the existing `asInstanceOf` moves unchanged).
`Identity` is `sealed abstract class` / `object` inside `object Arrow`, `deliver` is `private`, so no public
API change.

---

### C2. Split the two degenerate-tier methods in the drive loop: `Eval.dump` and `Stack.truncate`

**Hypothesis.** `dump$1` (92 B) and `Stack::truncate` (52 B) both execute their entry compare and return in
100% of profiled calls in this row, and both are inlined into `Eval$::loop`, the one compilation unit in the
kernel with no budget to spare; splitting the fast path out of each removes ~112 bytes from that unit and
drops `truncate` under `MaxInlineSize`.

**Mechanism.**

`Stack.scala:56-61`:

```scala
def truncate(n: Int): Unit =
    while top > n do
        top -= 1
        entries(top) = null
        tags(top) = null
        states(top) = null
```

C2 parse: `bc=5 (if_icmpge) -> 51  taken=4382  not_taken=0  prob=always`. Target 51 in a 52-byte method is the
return. The loop body never runs. This is called from `Eval.scala:287` (`finally stack.truncate(base)`), and the
loop exits at `Eval.scala:227` precisely when `stack.size == base`, so `top == n` at that site by construction.
Rewriting as `if top > n then truncateTo(n)` with the loop in a `private def truncateTo` gives about 12 bytes.

`Eval.scala:35-49` (`dump`):

```scala
def dump(): Arrow[Any, Any, Any] =
    val top = stack.size
    var i   = top
    while i > base && !stack.marked(i - 1) do i -= 1
    if i == top then Arrow[Any]
    else
        ...
```

Two branch records: `bc=11 -> 31 taken=4386 not_taken=0 always` (the scan loop is skipped) and
`bc=34 (if_icmpne) -> 44 taken=0 not_taken=4386 never` (`i == top` always holds), so all 4,386 calls return
`Arrow[Any]`. The fast path is `if stack.size == base then Arrow[Any] else dumpScan(...)`, which is equivalent
because `top == base` makes the while condition false immediately and forces `i == top`.

**Predicted signal.**
- `javap -c -p kyo/kernel/proto/Stack.class`: `truncate(int)` drops 52 to about 12, under 35.
- `javap -c -p kyo/kernel/proto/Eval$.class`: `dump$1` drops 92 to about 20.
- Per-site verdicts in the C2 compile of `Eval$::loop` (today cid 954): `Stack::truncate` at bci ~1576 and
  `Eval$::dump$1` at bci ~1489 both stay `inline (hot)` with new sizes; two new callees (`Stack::truncateTo`,
  `Eval$::dumpScan$1`) appear at those bcis refused with **`low call site frequency`**.
- Warmup side effect that is real but not the point: the seven C1-L3 `callee is too large` refusals of
  `truncate` (cid 943 bci 86; cid 945 bci 341, 403, 574, 743, 1566, 1576) and the two of `dump$1` (cid 945
  bci 1016, 1489) flip to `inline`. Report this as warmup only, never as the mechanism.
- Total inlined bytecode for the cid-954 unit falls from 1,095 to about 983.

**Falsifier.** Both methods shrink, both new slow-path methods get refused for low frequency, and no row moves
outside drift. Also killed if the `truncate` fast path turns out to be false on the handler rows (a row where
the drive loop exits with `top > base` would make the split a pure extra branch), which the log cannot check
because it covers one row.

**Target rows.** `truncate` is called from `Eval.scala:44`, `:72`, `:79`, `:117`, `:144`, `:150`, `:162`,
`:174`, `:287`, so it reaches all 15. `dump` is called from `Eval.scala:219` and `:275`, also all 15.
Largest expected effect where the drive loop iterates most: `deepRecursionPaysRescuesOnly`,
`suspensionBaseline`, `trailingMapsStayLinear`, `handleLoopAnswersInPlace`, `statefulAnswersPaySuccessor`,
`emittingClausesPayRegionRebuild`. Smallest: `nestedPayloadsUnwrapInMaps` and the fusion rows, where the drive
loop runs three iterations per op.

**Cost and risk.** Two small splits, no signature change. `truncate` is `private[proto] class Stack`, `dump` is
a local def. Risk is that both are already inlined hot in C2, so the only gain is byte volume in
`Eval$::loop`'s unit, and `Eval$::loop` is refused into `Eval$::apply` anyway (`hot method too big`,
1,582 bytes, counts up to 109,638), so the freed budget benefits only the callees below it.

**Gated constructs.** No `inline`, no casts, no public API change.

---

### C3. Take the `VarHandle` guard chain off the per-delivery safepoint poll

**Hypothesis.** One `AtomicReferenceArray.get` inside `Safepoint.get()` force-inlines 434 bytes of
`java.lang.invoke` guard bytecode into every delivery site, 25.4% of the hot unit, and it is the largest
single contributor to `loop$9`'s nmethod exceeding `InlineSmallCode`.

**Mechanism.** `Safepoint.scala:28`:

```scala
@static private val slots = new AtomicReferenceArray[Thread | Stop](Slots + 1)
```

and `Safepoint.scala:71-76`:

```scala
@static def get(): Slot =
    val thread = Thread.currentThread()
    val h      = home(thread)
    if slots.get(h) eq thread then h
    else resolve(thread, h)
```

`Safepoint::get` is 28 bytes, but its inline expansion is not. From the cid 922 tree, per copy:

```
Safepoint::get                                   b=28   inline (hot)
  Safepoint::home                                b=17   inline (hot)
    Thread::threadId                             b=5    accessor
  AtomicReferenceArray::get                      b=12   inline (hot)
    VarHandleGuards::guard_LI_L                  b=89   force inline by annotation
      VarHandle::checkAccessModeThenIsDirect     b=29   force inline by annotation
      VarForm::getMemberName                     b=38   force inline by annotation
      VarHandleReferences$Array::getVolatile     b=49   force inline by annotation
```

That is 217 bytes per `Safepoint.get()` call site. `run$39` gets inlined twice into the unit, so the unit
carries 434 bytes of it. These are `force inline by annotation`, so no budget decision can refuse them: the
only way they leave a caller is if the call is not there.

The branch profile says the read is a formality on this path: `Safepoint::get  bc=17 (if_acmpne) -> 22
taken=0  not_taken=79960  prob=never`. The `eq thread` test holds 79,960 times out of 79,960 and `resolve`
(119 B, already out of line) is never reached.

The direction: the per-delivery fast path needs only "is this cell still mine", which is a read of the
thread's own prior write. Replace the `AtomicReferenceArray` acquire read on that path with a plain
`Array[AnyRef]` load (`getstatic; iload; aaload`, roughly 4 bytes), keeping the CAS claim
(`Safepoint.scala:91`), the `Stop` publication (`Safepoint.scala:151`), and `consumeStopped`
(`Safepoint.scala:161-167`) on their existing atomic machinery. **The correctness question is open and is the
owner's to answer**: whether a thread may observe a stale `Thread` where another thread has CAS'd in a `Stop`,
and whether the `depths` armed-state check (`Safepoint.scala:101-103`) already covers that window. This
candidate is a proposal to answer that question, not an assertion that it is safe.

**Predicted signal.**
- The four `java.lang.invoke` entries (`VarHandleGuards::guard_LI_L` 89, `VarHandle::checkAccessModeThenIsDirect`
  29, `VarForm::getMemberName` 38, `VarHandleReferences$Array::getVolatile` 49) disappear entirely from the
  inline tree of the `loop$9` / `run$39` compiles and from cid 954. Today they appear at 6 C2 sites each.
- Total inlined bytecode for the cid-922/923 unit falls from 1,708 to about 1,274 (-25.4%); for cid 954 from
  2,677 to about 2,460.
- `loop$9`'s nmethod falls from 3,464 bytes. If it crosses `InlineSmallCode`, the verdict at
  `nestedPayloadsUnwrapInMaps` bci -> `loop$9` (count 109,640, the hottest kyo-adjacent refusal in the log)
  flips from **`already compiled into a big method`** to `inline (hot)`.
- JMH on all 15 rows; B/op should be unchanged (no allocation moves).

**Falsifier.** The `VarHandle` subtree vanishes, the unit shrinks by the predicted 434 bytes, and neither the
`loop$9` refusal flips nor any row moves outside drift. It is also killed if the guard chain is already folded
to nothing by C2 (the bytecode count would drop but the machine code would not), which the nmethod sizes will
show: if `loop$9` stays at ~3,464 bytes of nmethod after 434 bytes of bytecode leave the tree, the guards were
already free and the candidate is dead.

**Target rows.** `Safepoint.get()` is called once per settled delivery from `Pending.scala:53`, `:80`, `:106`,
`:132`, `:285`. That is every `map`, `flatMap`, `andThen`, and `unit` in the module, so **all 15 rows**. This
is the candidate with the widest blast radius and the one where "measure the whole class" is not optional.

**Cost and risk.** Highest of the five. It changes a concurrency primitive's memory ordering, in the file that
implements preemption. It needs the `Safepoint` invariants restated before the edit and a targeted test for the
stop/arm race, not just a benchmark. It is listed third rather than first for that reason, not because the
payoff is smaller: the payoff is the largest of the five.

**Gated constructs.** No `inline` added. No public API change (`Safepoint.slots` is `@static private`; `Safepoint`
carries a `// TODO this should be private[kernel]` at `Safepoint.scala:8`). The gate is a **memory-model
decision that requires owner approval**, and a `// Unsafe:` style justification comment at the plain-read site
per the repo's safe-by-default rule.

---

### C4. Keep the park branch's constructor chain out of the hot delivery unit

**Hypothesis.** The `Arrow.Bind` construction on the park arm of `map` costs 41 bytes of inlined constructor
chain per copy on a branch taken 9 times in 7,695, and a single out-of-line factory would get the same
`low call site frequency` refusal its two neighbours already get.

**Mechanism.** `Pending.scala:54-55`:

```scala
if !Safepoint.enter(slot) then
    Arrow.Bind(v, arrow.chain(next))
```

At cid 922 the three calls on that arm split two ways. Two are already refused, correctly:

```
bci=65  ProtoKernelBench::arrow$40       b=10  cnt=8   FAIL low call site frequency
bci=69  Arrow$Transform::chain           b=19  cnt=8   FAIL low call site frequency
```

The third is not, because it is a constructor and C2 expands the whole chain:

```
bci=72  Arrow$Bind::<init>               b=15  cnt=8   OK inline (hot)
          Arrow$Defer::<init>            b=5
            Arrow$Step::<init>           b=5
              Arrow::<init>              b=5
                AbstractFunction1::<init> b=9
                  Object::<init>         b=1
                  Function1::$init$      b=1
```

41 bytes, twice in the unit, for an arm the branch profile records as `run$39  bc=54 -> 76  taken=7686
not_taken=9`. Routing the construction through one private factory (a `def` that does the `chain` and the
`new`) makes the whole arm a single call that C2 will refuse at the same frequency threshold that already
rejects `arrow$40` and `Transform::chain` three bytecodes earlier.

**Predicted signal.**
- `Arrow$Bind::<init>` and its six-deep constructor chain disappear from the inline tree at the five C2 sites
  where they appear today (cid 922 bci 72 depth 1 and 3; cid 923 bci 72 depth 2 and 4; cid 954 bci 72 depth 3).
- A new factory callee appears at bci ~72 refused with **`low call site frequency`**, count in the single digits.
- Total inlined bytecode for the cid-922/923 unit falls from 1,708 to about 1,626 (-82).
- B/op unchanged. The `Bind` still allocates, on the same arm, at the same rate.

**Falsifier.** The chain leaves the tree, the factory is refused for low frequency, and nothing moves outside
drift. Also killed if C2 inlines the factory anyway (the frequency threshold is relative, and a row where the
park arm is common would inline it, which is correct behaviour, not a defect).

**Target rows.** The park arm fires when `Safepoint.enter` returns false, that is, on the preemption budget
boundary. Frequency scales with `Safepoint.period` (512, `Safepoint.scala:31`) against the row's delivery
count, so the deep rows fire it most: `deepRecursionPaysRescuesOnly` and `fusionPastBudgetPaysRescuesOnly`
are named for it. Byte-volume benefit is largest where the unit is near `InlineSmallCode`:
`nestedPayloadsUnwrapInMaps`, the two fusion rows, `continuationBodiesFuse`. Should not move:
`evalFixedOverhead`.

**Cost and risk.** Smallest of the five. It is a mechanical extraction in four near-identical places
(`Pending.scala:55`, `:82`, `:108`, `:134`) plus `:287`. Risk: it overlaps the allocation family's territory,
so it must not be measured in the same bracket as any allocation change or attribution is lost
(the SKILL's "one variable per measurement" rule).

**Gated constructs.** No `inline` added. No casts. `Arrow.Bind` is already `final class` inside `object Arrow`;
the factory is `private[proto]`, so no public API change.

---

### C5. Make `Eval.dispatchInline` a plain private method

**Hypothesis.** `dispatchInline` is a Scala `inline def` used twice (`Eval.scala:202` and, via `dispatch`, at
`:204`), so the dispatch body exists twice in the compilation unit: once expanded into `Eval$::loop` (1,582 bytes)
and once as `Eval$::dispatch$1` (607 bytes); de-inlining removes the duplicate and cuts `loop` to roughly 985 bytes.

**Mechanism.** `Eval.scala:124-185`:

```scala
inline def dispatchInline(s: Suspend[...], whole: Arrow[Any, Any, Any]): Any = ...

def dispatch(s: Suspend[...], whole: Arrow[Any, Any, Any]): Any =
    dispatchInline(s, whole)
```

The log's sizes are the evidence for the duplication: `Eval$::loop bytes='1582'` and
`Eval$::dispatch$1 bytes='607'`. `dispatch` calls nothing else, so its 607 bytes are the same body
`dispatchInline` also pastes into `loop`. Making `dispatchInline` a `private def` collapses them.

SKILL.md already flags this exact construct: "Two uses in the proto (`Eval.dispatchInline`,
`Effect.deferInline`) were introduced without asking and stand as open questions rather than precedent."
Removing `inline` is not the gated direction (only adding is), but it is an owner-visible reversal of a
choice the skill records as unresolved, so it is proposed, not taken.

**Predicted signal.**
- `javap -c -p kyo/kernel/proto/Eval$.class`: `loop` drops from 1,582 to roughly 985; `dispatch$1` at 607
  becomes the single `dispatch`.
- The C2 compile of `Eval$::loop` gains a `Eval$::dispatch` callee at the Suspend and SuspendWith bcis.
  At 607 bytes it exceeds `FreqInlineSize` (325), so the predicted verdict at those sites is
  **`callee is too large`**, meaning one real call per suspension. That is the trade being measured.
- `Eval$::loop` stays refused into `Eval$::apply` with `hot method too big` (985 is still far above 325), so
  that verdict does **not** flip. Any candidate claiming it does is wrong.
- `Eval$::loop`'s nmethod falls from 4,008 bytes.

**Falsifier.** `loop` shrinks as predicted and every suspension row regresses, because the row now pays a call
where it previously paid inlined code. That is the likely outcome and the reason this ranks last. It is also
killed if `loop` does not shrink (meaning `dispatchInline` was not being duplicated the way the byte counts suggest).

**Target rows.** Reaches only rows that suspend through a handler: `suspensionBaseline`,
`suspensionFusesContinuation`, `handleLoopAnswersInPlace`, `handleLoopFusesContinuation`,
`statefulAnswersPaySuccessor`, `idleHandlerAddsNothing`, `trailingMapsStayLinear`,
`emittingClausesPayRegionRebuild`, `continuationBodiesFuse`. Does **not** move `evalFixedOverhead`,
`fusionAllocatesNothing`, `fusionPastBudgetPaysRescuesOnly`, `uncachedValuesPayBoxingOnly`,
`deepRecursionPaysRescuesOnly`, `nestedPayloadsUnwrapInMaps`.

**This candidate cannot be evaluated on the available log at all.** In cid 954, C2 pruned every Suspend-path
bci (83, 851, 899, 923, 971, 1172, 1182 and the whole 155-1095 range) as never taken. Evaluating it requires a
LogCompilation run on `suspensionBaseline` first.

**Cost and risk.** One keyword and one delegation removed, then a full-class measurement. Risk is the highest
probability of a straight regression among the five.

**Gated constructs.** Removes `inline` rather than adding it. No casts, no public API change
(`dispatchInline` is a local def inside `Eval.loop`). Owner-visible because SKILL.md records the construct as
an open question.

---

## 6. Non-candidates: things in the brief that are working as designed

- **`Arrow$Transform::chain`, 19 bytes, refused at 5 of 5 C2 sites with `low call site frequency`
  (counts 8, 8, 17, 17, 80).** This is `arrow.chain(next)` on the park arm at `Pending.scala:55`. Being
  refused is the desired outcome, and it is the log's own proof that the "move cold work out of line"
  technique works here: three cold calls in a row (`arrow$40` 10 B, `Transform::chain` 19 B,
  `Safepoint::enterPark` 22 B) are all held out of the hot unit by frequency. The two `inline` verdicts the
  brief counts are C1-L3 (cid 896), where no frequency check exists. Do not chase this.
- **`Safepoint::enterPark`, 22 bytes, refused at 6 of 6 C2 sites, `low call site frequency`, count -1.**
  Same story. `Safepoint.scala:114-117` is exactly the shape the SKILL prescribes and C2 is honouring it.
- **`Eval$::loop`, 1,582 bytes, `hot method too big` into `Eval$::apply` at 5 C2 sites.** SKILL.md already
  calls this expected. It is.
- **`Arrow$Step::head`, `Arrow$Step::tail`, `Arrow$Transform::apply` reporting `no static binding` at 0 bytes.**
  All 25 such proto verdicts are C1-L3. SKILL.md calls this megamorphism, not a defect; the log agrees, and
  C2 reports it zero times.
- **`Stack$::current` pulling 119 bytes of `ThreadLocal` machinery into `Eval$::loop` (cid 954: `ThreadLocal::get`
  8 + 35, `getMap` 17, `Thread::threadLocals` 5, `ThreadLocalMap::getEntry` 42, `Reference::refersTo` 6 + 6).**
  Real, but it runs once per `Eval` invocation (count 4,098 for 4,376 invocations), not per delivery. Noted
  and deliberately not proposed; it would be a candidate only if a row shows `Eval` entered per step.

---

## 7. Ranking

| rank | candidate | expected value | cost | decisive on this log? |
|---|---|---|---|---|
| 1 | C1 Identity fast-path split | 156 B (9.1%) off the hot unit; 100% dead-code evidence | one method split | yes |
| 2 | C2 `dump` + `truncate` splits | 112 B off the `Eval$::loop` unit; 100% dead-code evidence on both | two small splits | yes |
| 3 | C3 VarHandle chain off `Safepoint.get` | 434 B (25.4%) off the hot unit; may flip the log's hottest refusal | memory-model change, owner-gated | yes for the verdict flip, no for the correctness question |
| 4 | C4 park-branch factory | 82 B off the hot unit | mechanical, 5 sites | yes |
| 5 | C5 de-`inline` `dispatchInline` | ~597 B off `Eval$::loop` bytecode, at the price of a call per suspension | one keyword | **no**, needs a suspension-row log |

C1, C2 and C4 are independent and each is a single-variable edit, so they can be bracketed in sequence in one
session. C3 must be its own bracket. C5 must not be started before a LogCompilation run exists for
`suspensionBaseline`.

---

## 8. What this log cannot settle, and the cheapest way to fix that

1. **Fourteen of fifteen rows have no JIT evidence at all.** Every claim above about `suspensionBaseline`,
   the handler rows, the fusion rows and `trailingMapsStayLinear` is extrapolation from source shape.
2. **The `Stack` push/pop overloads that handle `Chain`, `Handle` and `Eval` nodes
   (`Eval.scala:196`, `:206`, `:209`, `:211`, `:215`) never execute in the one row that was logged.**
   `push(f, t)` (50 B), `push(f, t, s)` (60 B) and `pushAll` (134 B) all report `iicount='1'` at their C1
   declarations, meaning they had been invoked once when C1 saw them. Whether they are hot anywhere is
   an open question, not a settled negative.
3. **The recommended next measurement, before any edit:** one `-f 1 -wi 5 -i 1` LogCompilation run on
   `suspensionBaseline` and one on `trailingMapsStayLinear`, parsed the same way (split verdicts by
   compile tier, read the C2 tasks only, read `<branch>` records alongside `<inline_fail>`).
   That is two runs and it converts most of section 5 from hypothesis to evidence.
4. **A parsing rule for whoever reads the next log.** Aggregating inline verdicts across compile ids without
   splitting on the task's `level` attribute produces the table in the brief, in which seven methods appear
   to be refused on the hot path and none of them is. Read C2 tasks (`<task>` with no `level`) and read the
   `count` on each `<call>`; a site with no `count` is a site C2 never saw.

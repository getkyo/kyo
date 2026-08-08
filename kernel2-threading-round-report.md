# kernel2 report: the context-threading round

State of the round you approved with "ok, let's fix the context propagation", plus
the two findings it surfaced and the combinator fix you directed. One item needs
your ruling (section 4); everything else is done and committed.

# 1. What landed: the context as an execution parameter
but you keep the preempt function in handlePartial right?
The old kernel's mechanism, ported faithfully. The context rides every execution
application and is never stored in a node:

```scala
// Arrow.Transform: every transform receives the ambient and passes it onward
def run[C, S2](v: Any, context: Context, cont: Arrow[B, C, S2]): C < (S & S2)
```

A context read is a plain Defer consuming the parameter in one map lookup, the old
kernel's KyoDefer shape; `ContextRead`, `ContextSnapshot`, and both chain-walk
resolvers (`resolveContext`, `snapshotContext`) are deleted:

```scala
// ContextEffect.suspend(tag): V < E
Kyo.Defer[Unit, V, E](
    (),
    new Arrow.Transform[Unit, V, E]:
        def frame = _frame
        def run[C, S2](v: Any, context: Context, cont: Arrow[V, C, S2]): C < (E & S2) =
            cont(context.get[V, E](effectTag), context)
)
```

A binding is a typed re-arming interceptor at the region's entry. On each entry it
derives this scope's value from the incoming context and passes the updated context
inward; when the region parks, it re-prepends itself so every resumption re-derives
from the resume-time context (the old kernel's per-resumption KyoContinue re-wrap,
via the same re-arm pattern Catching already used):

```scala
final private[kyo] class ContextBinding[V, E <: ContextEffect[V]](
    effectTag: Tag[E],
    ifUndefined: () => V,
    ifDefined: V => V,
    _frame: Frame
) extends Arrow.Interceptor:
    def frame = _frame
    def run[C, S2](v: Any, context: Context, cont: Arrow[Any, C, S2]): C < (Any & S2) =
        val value =
            if context.contains(effectTag) then ifDefined(context.get[V, E](effectTag))
            else ifUndefined()
        cont(Kyo.lift(v), context.set(effectTag, value)) match
            case kyo: Kyo[?, ?] => kyo.prepend(this).asInstanceOf[C < (Any & S2)]
            case w              => w
```

Who supplies the context: `eval` and `evalPartial` enter with `Context.empty` (the
only two fabrication sites in execution code); a drive's context is a constant of
the drive, re-derived inward by the re-armed interceptors on every bounce, the old
kernel's root-re-application made explicit; `handlePartial` takes the context from
its caller, the old kernel's exact signature and the fiber integration point:

```scala
private[kyo] def handlePartial[…](effectTag: Tag[E], v: A < (E & S), context: Context)(clause: …)
```

The public arrow application defers to acquire its ambient: application constructs
a typed Defer (zero casts, variance carries the proof), and the arrow runs when a
drive pops it, under whatever bindings enclose the site where the caller embedded
the result:

```scala
def apply[S2](v: A < S2): B < (S & S2) =
    if self.asInstanceOf[AnyRef] eq empty then v.asInstanceOf[B < (S & S2)]
    else if v.isInstanceOf[Kyo[?, ?]] then v.asInstanceOf[Kyo[A, S2]].map(self)
    else Kyo.Defer[A, B, S & S2](v, self)
```

Also landed: `Defer.value` typed as `A < S` (deleting the drive's pop cast),
`Suspension` collapsed into `Suspend` (reads are Defers now, so one suspension kind
remains), the bracket-context fix (`Bracket.prepend` wraps `release`, so finalizers
run under the region's interceptors), and `Handler.install` keeping the eager
execution form on settled values (a handler installed on a value runs its
completion step immediately, matching the old behavior the Nested tests pin).

Commits: `c1c1a6f09a` (main sources), `94b020c89b` (test adaptation), `0496b6b1c7`
(install fix).

# 2. Benchmarks: guards held, and the one anomaly diagnosed to the byte

| row | pre-threading (same day, same box) | threading | verdict |
|-----|------------------------------------|-----------|---------|
| eagerMap5 | 4.62 ns, 0 B | 4.62 ns, 0 B | the parameter is free on the pure path |
| deepBind10k | 66.6-68.8 us / 160,336 B | 68.8 us / 160,336 B | noise, alloc identical |
| state10 | 828 ns / 4,560 B | 838 ns / 4,560 B | ~1% |
| loopPure10k / loopSuspend1k | 18.9 us / 71.4 us | 19.0 us / 70.4 us | noise |
| suspension | 447.7 ns / 2,120 B | 489.2 ns / 2,120 B | +9.3% time, alloc identical |
| narrowIter | 2,490 ns / 12,024 B | 2,627 ns / 12,024 B | +5.5% time, alloc identical |
| stateMap10k | 2.05 ms / 7,832,021 B | 2.39 ms / 8,792,071 B | +16.7% time, +96 B/iter, JMH only |
| resumeFused | 18.4 ns / 0 B | 28.4 ns / 16 B | the predicted public-defer price: one Defer, one pop |
| contextRead100 (new) | n/a | 56 ns, 208 B per read | the new baseline for reads under a binding |

The stateMap10k +96 B/iteration looked like an allocation regression, so I chased
it with a deterministic probe: constructor counters on every node class plus
`ThreadMXBean.getThreadAllocatedBytes` around a single 10k-iteration run, at both
commits. The result is unambiguous:

```
baseline:  offsets=140002 andThens=50000 continues=70001 defers=0 suspends=20001  BYTES=7671984
threading: offsets=140002 andThens=50000 continues=70001 defers=0 suspends=20001  BYTES=7671984
```

Identical node counts and identical single-run allocation to the byte. The change
allocates nothing extra; the JMH-only deltas on the dispatch rows are JIT inlining
and escape-analysis shifts from the wider three-argument signatures, materializing
allocations the baseline profile scalar-replaces. That is the measured price of the
mechanism itself, the same parameter the old kernel passes on every continuation
application.

# 3. The combinator fix you directed: immutable Chunks everywhere
check if you can keep code closer to the old kernel
Every collection combinator in the `Kyo` companion shared a mutable iterator and
often a mutable builder across effect steps:

```scala
// before: one iterator and one builder shared by every replay
def takeWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
    val it = source.iterator
    def loop(acc: Map[K1, V1]): Map[K1, V1] < S =
        if !it.hasNext then acc
        else
            val kv = it.next()
            f(kv).map(keep => if keep then loop(acc + kv) else acc)
    loop(Map.empty)
```

A continuation captured mid-traversal and applied twice resumes against the
already-advanced iterator and the already-filled builder: skipped elements,
duplicated results, or a NoSuchElementException. The fix is the current kernel's
canonical shape, applied to all ~30 combinators (both the Iterable family and the
Map overloads): snapshot into an indexed Chunk, thread the index and immutable
accumulators through the loop, rebuild once at completion:

```scala
// after: every replay traverses independently from its captured index
def takeWhile[K1, V1, S](source: Map[K1, V1])(f: ((K1, V1)) => Boolean < S)(using Frame): Map[K1, V1] < S =
    val entries = Chunk.from(source).toIndexed
    val len     = entries.length
    def loop(i: Int, acc: Map[K1, V1]): Map[K1, V1] < S =
        if i == len then acc
        else
            val kv = entries(i)
            f(kv).map(keep => if keep then loop(i + 1, acc + kv) else acc)
    loop(0, Map.empty)
```

Committed as `c68ac9ce1c`, with reproduction tests written first and confirmed
failing for the right reason on the old code.

# 4. NEEDS YOUR RULING: the flat chain loses binding scope

I'm thinking we need a different kind of arrow step: rotate. Take a look at https://gist.github.com/fwbrasil/8c8b2b0236793391546c624cbbacd421

The one red test in the suite is structural. The scenario: a binding installed
OUTSIDE an operation handler whose CLAUSE reads the context:

```scala
ContextEffect.handle(Tag[Env], 3) {
    ArrowEffect.handleResume(Tag[CtxOp], program)([C] => _ => env.map(_ * 2))
}
// old kernel: the clause's env read resolves to 3 (the binding wraps the handler)
// kernel2 today: the read dies as a missing-value defect
```

The root cause is an encoding limit, not a dispatch detail. With pure installation,
the two scoping cases produce byte-identical chains:

```scala
handle(Env, 3)(handleResume(...)(program))   // binding outside: clause must see 3
handleResume(...)(handle(Env, 3)(program))   // binding inside: clause must not
// both encode as: Continue(op, [binding, steps..., delimiter])
```

The old kernel distinguishes them by wrapper NESTING, which the flat chain erased.
Value-flow semantics are correct in both cases; only clause scope needs the
distinction, so no dispatch-side rule can be right for both. The candidates:

1. **A nesting-preserving node** (my recommendation): `Bound(inner, binding, cont)`,
   the old kernel's wrapper made explicit in the node algebra. The drive enters
   `inner` under the derived context, parks re-wrap the node, dispatch passes
   through it so an operation inside reaches delimiters in `cont` and its clause
   runs under the context OUTSIDE the node: exact old-kernel scoping. Touches the
   drive and dispatch.
2. **Entry and exit markers** in the chain with a scope-depth walk at dispatch:
   keeps the flat chain, but the pairing must survive every piece of chain surgery
   dispatch performs; fragile.
3. **Prefix-fold at dispatch**: fold ContextBindings found in the captured prefix
   into the clause context. Smallest change, fixes the failing case, but leaks
   inside-installed bindings into clause scope, a documented divergence from the
   old kernel in exactly the case the encodings cannot distinguish.

# 5. FINDING: interleaved multi-shot diverges, in BOTH kernels

The combinator reproduction tests initially used this handler shape and hung:

```scala
ArrowEffect.handle(tag, program)(
    [C] => (input, cont) => cont(input).map(x => cont(input + 100).map(y => x ++ y))
)
// program: op(1).map(a => op(2).map(b => List(a, b)))
```

I reduced it to a minimal case with no combinators, and then ran the identical
program against the OLD kernel (it sits on kernel2's test classpath): the old
kernel diverges the same way, dying with OutOfMemoryError in the same ever-growing
list concatenation. So this is not a kernel2 bug: kyo's deep-handler continuations
are SPLICED, in both kernels. The continuation a clause receives is the operation's
full downstream continuation, including fragments a previous dispatch's clause
already appended:

```
op2's captured continuation = [rest-of-region, op1-clause's .map(x => cont(...)),  delimiter]
                                               ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                               re-invokes cont on every replay
```

Under the formal delimited-handler semantics op2's continuation would stop at the
delimiter and the program would terminate with the 8-element cross product. Under
kyo's spliced semantics, re-invoking the inner continuation re-runs the outer
clause's second branch, and the nested-resume shape diverges. Terminating
multi-shot is the cursor-advancing kind (Choice's traversal shape, and the existing
"multi shot" tests, which resume a single operation), where every splice moves a
finite cursor forward.

Consequences taken: the reproduction tests were rewritten to the terminating shape
that still catches iterator corruption, a continuation captured mid-traversal via
`handlePartial` and applied twice, each application required to produce an
independent correct result:

```scala
"foreach" in {
    val k = park(Kyo.foreach(List(1, 2))(i => TestEffect1(i)))
    assert(finish(k(10)) == List(10, 3))
    assert(finish(k(20)) == List(20, 3))   // corrupted by the old shared iterator
}
```

The old iterator bug had been masking all of this: replays no-oped against
exhausted iterators, so nothing ever truly replayed a traversal.

# 6. Current state

1. The corrected full suite is running now; expected result is everything green
   except the one clause-scope red from section 4.
2. Temporary diagnostic files (the multi-shot probes) are deleted once the suite
   confirms.
3. Waiting on you: the section 4 ruling. The dispatch work for it and any future
   delimitation decisions touch the same code, so I will design against your
   choice before implementing.

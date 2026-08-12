# Bracket as a node kind: `Kyo.Bracket` for kernel2

Scope: the design of the bracket primitive for kyo-kernel2 as a **node type** in the sealed
`Kyo` hierarchy, structurally parallel to `Kyo.Handled`. Analysis and design only. No source
was changed, nothing was built or run.

Read at worktree HEAD **`fe06ac4df95f5e3f1247660ed7286e173952c5e9`**
(`[kernel2] isolate design: fork as handler-stack transplant with per-cell policy`). The
branch takes concurrent commits, so every line number below is stated against that hash.

Codebases referenced:

- **worktree** = this tree at `fe06ac4df9`, module `kyo-kernel2`, package `kyo.kernel`. All
  bare `Eval.scala`, `KyoInternal.scala`, `Handlers.scala`, `Handler.scala`,
  `ArrowEffect.scala`, `Effect.scala`, `Pending.scala`, `Arrow.scala`, `Safepoint.scala`
  citations are `kyo-kernel2/shared/src/main/scala/kyo/...` in this tree.
- **origin/main** = the shipped kernel and its consumers, modules `kyo-kernel`, `kyo-prelude`,
  `kyo-core`. Cited only for consumer call shapes and for the baseline finalizer contract.
- **prototype** = the deleted drive-era bracket, read at `cc446cf27b` for failure modes only
  (section 12). Its mechanisms are not imported.

The contract is fixed by the maintainer and is treated as non-negotiable throughout:

1. The bracket holds acquire, use, and release computations.
2. Use is fully interruptible; masking the region is wrong.
3. No interruption may land in the gap between acquire finishing and use starting.
   Equivalently: at every instant where the resource exists, the release obligation is
   reachable by the kernel's release paths.
4. Release always executes: on normal completion of use, on exceptions unwinding, and on
   interruption or discard of a parked remainder that is never resumed. Release failures are
   suppressed onto the primary failure. Release runs to completion once started.
5. The deleted prototype is not a reference design.

---

## 1. The design in one page

**Core mechanism: the node exists exactly when the resource exists, and the resource enters
the node in the same strict step that produced it.**

The bracket is one new node kind and one new spine cell kind:

- `Kyo.Bracket[R, A, B, S, S2]` holds `value: A < S` (the use computation), `acquired` (the
  resource together with its release and a one-shot claim), and `exit: Arrow[A, B, S & S2]`.
  It has the same three-field shape as `Kyo.Handled` (`KyoInternal.scala:106-127`), the same
  fused `map` (identity-collapse arm plus the `AndThen`-with-node arm), and the same
  evaluator entry (one cell, no call, no frame).
- `Handlers.Bracket` is the cell: `acquired`, the erased `exit`, and `prev`. It carries no
  handler and no tag, so `Handlers.find` (`Handlers.scala:46-56`) can never select it.

The acquire phase is **not** in the node. `acquire` is an ordinary computation chained into a
construction arrow, and that arrow is the only place the resource is read:

```
bracket(acquire)(release)(use)   ==   acquire  ->  open  ->  Kyo.Bracket(use(r), acquired, exit)
                                      ^^^^^^^      ^^^^      ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                      chain        one strict transform, no budget check
```

`open` is an `Arrow.Transform` whose settled branch reads the resource, allocates the
`acquired` obligation, and allocates the node, with no `Safepoint.enter` between them. It
therefore contains no site at which a `Kyo.Defer` can be minted, and a `Defer` is the only
place `Eval.partial` parks (`Eval.scala:103-105`, plus the partial-mode unhandled-suspension
arm at `Eval.scala:49-50`). Contract point 3 is a corollary of the representation: there is no
value in the currency that means "acquired, no obligation". Before `open` runs, nothing is
acquired; after it returns, the obligation is a field of a `Kyo` node, and every kernel path
that preserves the currency (chain fusion, `rebuild`, park, discard) preserves it. No mask is
required, and none is proposed (section 5).

Release runs at four sites, all of which are the same operation on a different segment of the
spine: **the cells between the arm's incoming spine and the spine it continues at**. One
helper, `release(top, stop, cause, next)`, covers the settle pop, the `done` truncation, the
`Cont`/`First` capture, and the unwind, and the discard entry runs the same fold over the node
onion instead of the cell list. Once-per-acquire is object identity on `acquired` plus one CAS
(`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:174-179`
does the same thing for the same reason).

The weakest point is stated up front: a bracket cell crossed by a `Handler.Cont` clause's
captured continuation leaves the spine without being popped, and deciding when it releases is
a value fork (section 6.4). Every other path is structural.

---

## 2. What the current evaluator gives the design

Five facts from the tree are load-bearing. They are stated here once and cited once.

**F1. Entering a region is one cell, and exiting is `prev`.** `Eval.scala:109-116` for
`Kyo.Handled`, `:117-122` for `Kyo.HandledState`, `:123-128` for `Kyo.HandledFirst`; the pops
are the settled-value arm at `:129-138`. Nothing recurses (`CONTRIBUTING.md:83-102`).

**F2. Parks happen at exactly two places.** The `Kyo.Defer` arm when the drive is partial and
a stop is pending, `Eval.scala:103-105`:

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    if partial && Safepoint.consumeStopped(slot) then
        rebuild(hs, Empty, v)
```

and the unhandled-suspension arm in partial mode, `Eval.scala:44-50`. Both produce
`rebuild(hs, Empty, v)`. In a non-partial drive the `Defer` arm resets the budget and steps
(`Eval.scala:106-108`), so budget rescues never escape. `ArrowEffect.handlePartial` has its
own stop check on its own `Defer` arm (`ArrowEffect.scala:276-280`) and returns the standing
value at anything it does not recognise (`ArrowEffect.scala:281-282`).

**F3. `rebuild` turns open cells into nodes, and re-entry is by identity.**
`Eval.scala:270-282` walks `prev` wrapping one node per cell; `RebuiltNode` and friends
(`Eval.scala:245-268`) carry the original cell, and the entry arms re-enter it when it lands
where it was built from (`Eval.scala:111-114`). A residual rebuilt down to `Empty` re-enters
its whole spine with zero cell allocations.

**F4. A `done` skips cells; a `Cont` clause captures them.** `done` feeds its own cell's exit
and continues at `node.prev` (`Eval.scala:68-69` and `:85-86`; `CONTRIBUTING.md:98`), and the
cells above it are simply dropped, pinned by `EvalTest.scala:395-411` ("a done from an outer
region discards multiple inner scopes"). A `Cont` clause instead runs at `node` with a
continuation that rebuilds the crossed cells per call (`Eval.scala:88-93`,
`CONTRIBUTING.md:110`), so the same cells travel inside a value. That difference is the split
this design's release rule is built on (section 6.3 and 6.4).

**F5. A transform may skip the budget check when it cannot loop.** `ArrowEffect.suspendWith`
does exactly this and says why (`ArrowEffect.scala:49-51`): "no budget check: f either
suspends, returning the node flat, or settles into the chained arrows, whose strict segments
carry their own checks in map". `open` is the same shape and takes the same licence.

---

## 3. The node (design question 1)

### 3.1 Type shape

```scala
// worktree KyoInternal.scala, beside Kyo.Handled (KyoInternal.scala:106-144)

// a resource under an outstanding release, as a value: the use computation with the
// release's effects still in the row, the acquisition, and the continuation outside the
// region, where map chains
trait Bracket[R, A, +B, S, -S2] extends Kyo[B, S & S2]:
    def value: A < S
    def acquired: Bracket.Acquired[R, S]
    def exit: Arrow[A, B, S & S2]

    final def map[C, S3](f: Arrow[B, C, S3]): C < (S & S2 & S3) =
        val v = value
        val a = acquired
        val e = exit
        if e eq Arrow[A] then
            // the identity exit collapses: e meaning forces B = A
            new Bracket.Impl[R, A, C, S, S2 & S3](v, a, f.asInstanceOf[Arrow[A, C, S & S2 & S3]])
        else
            new Arrow.AndThen[A, B, C, S & S2 & S3](e, f) with Bracket[R, A, C, S, S2 & S3]:
                val value    = v
                val acquired = a
                def exit     = this
        end if
    end map

    final override def toString = s"Kyo(Bracket(${acquired.resource}, $value))"
end Bracket

object Bracket:
    // not inline: only the construction arrow and the evaluator build through this apply
    def apply[R, A, B, S, S2](value: A < S, acquired: Acquired[R, S], exit: Arrow[A, B, S & S2]): Bracket[R, A, B, S, S2] =
        new Impl(value, acquired, exit)

    final class Impl[R, A, +B, S, -S2](
        val value: A < S,
        val acquired: Acquired[R, S],
        val exit: Arrow[A, B, S & S2]
    ) extends Bracket[R, A, B, S, S2]

    // one object per acquisition, shared by every node and cell copy of it: the release
    // obligation's identity is the acquisition's identity
    abstract class Acquired[R, S]:
        def resource: R
        def release(r: R, cause: Maybe[Result.Error[Any]]): Any < S
        private val released                = new AtomicBoolean
        private[kernel] def claim(): Boolean = released.compareAndSet(false, true)
    end Acquired
end Bracket
```

The shape is `Kyo.Handled`'s, field for field: a `value` in the region, the thing that
governs the region, and an `exit` arrow that `map` fuses into
(`KyoInternal.scala:111-124`). Variance follows `Handled` for the same reasons: `B` is
covariant and `S2` contravariant because `Kyo[+A, -S]` (`KyoInternal.scala:26`); `A` is
invariant because it appears in `value: A < S` (covariant) and in `exit: Arrow[A, B, ...]`
(contravariant); `R` is invariant because it appears in `resource: R` (covariant) and in
`release`'s parameter (contravariant).

`map`'s two arms are the ones every other node uses: the identity-collapse arm reuses the
incoming arrow as the new exit with one allocation, and the general arm makes the node its own
chain node by mixing in `Arrow.AndThen` (`KyoInternal.scala:115-123` for `Handled`,
`:56-77` for `Suspend`, `:84-91` for `Defer`). `acquired` is copied **by reference** in both
arms, which is what makes the one-shot claim survive every copy the kernel makes of a node:
`Effect.guarded`'s rewrap (section 7), `open`'s exit fusion (section 5.2), and any user
composition after the bracket.

`Maybe[Result.Error[Any]]` is the cause type, matching origin/main's finalizer signature
verbatim (`origin/main:kyo-kernel/shared/src/main/scala/kyo/kernel/internal/Safepoint.scala:169-171`,
`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:108-111`). kyo-data is already a
kernel dependency (`ArrowEffect.scala:5` imports `kyo.Maybe`, `Pending.scala:5` the same), so
this introduces no new dependency and no new vocabulary. The kernel itself only ever
constructs `Result.Panic`; a library error reaches release only through the discard entry,
whose caller supplies it (section 6.6).

`Acquired` is an abstract class rather than a tuple of function values so the inline
constructor can compile the user's release body into one anonymous instance, the way the
handling variants compile a clause into one anonymous handler
(`ArrowEffect.scala:66-72`, `CONTRIBUTING.md:81`). No function value is allocated for
`release`.

### 3.2 Why acquire is not a field

The brief's sketch lists `acquire` as a node field. It is deliberately absent, and this is the
single most important decision in the design.

A node holding an un-run `acquire` alongside a `release` is exactly the shape in which the gap
of contract point 3 is representable: such a node has two states (acquire pending, acquire
done) and the evaluator has to record which one it is in. Recording it takes either a second
node kind or a sentinel field, and `CONTRIBUTING.md:81` rules on precisely this pattern for
`HandledState`: "absence of state is node shape, not a sentinel". Two shapes then have to be
distinguished by every walk that inspects nodes, including the discard walk, which is the one
walk whose correctness is hardest to argue (section 6.6). Under the design as written, the
walk cannot mistake an unentered bracket for an owed one **because unentered brackets are not
nodes**.

Keeping acquire in the chain costs nothing that the node would have provided. `map` after the
bracket still lands on the node's `exit`: at construction the value is `acquire.map(open)`, so
`.map(f)` fuses `f` after `open` in the chain (`KyoInternal.scala:56-77`), and at run time
`open` hands that `f` straight to the node as its exit (section 5.2). The fusion happens at
evaluation instead of at construction and lands in the same field.

---

## 4. Eval integration (design question 2)

### 4.1 Where the obligation lives while use runs: a cell

```scala
// worktree Handlers.scala, beside Node (Handlers.scala:15-22)
final class Bracket[R, A, S](
    val acquired: Kyo.Bracket.Acquired[R, S],
    val exit: Arrow[Any, Any, Any],
    val prev: Handlers
) extends Handlers:
    def withPrev(prev: Handlers): Bracket[R, A, S] =
        new Bracket(acquired, exit, prev)
```

Three fields, like `Node` (`Handlers.scala:15-19`). How it differs from `Node`/`StateNode`/
`FirstNode`:

| | `Node` / `StateNode` / `FirstNode` | `Handlers.Bracket` |
|---|---|---|
| what governs the region | a `Handler` with a `tag` (`Handler.scala:9-10`) | an `Acquired`, no tag |
| reachable from `find` | yes, by `tag <:< handler.tag` (`Handlers.scala:48-54`) | never: `find` gains a skip arm |
| what a settled value does at it | `resume(exit, v)` (`Eval.scala:132-133`) | claim, run release, then `resume(exit, v)` |
| what a `done` crossing it does | discards it (`Eval.scala:68-69`) | releases it, then discards it |
| `withState` | `StateNode` only (`Handlers.scala:30-31`) | none; the acquisition is immutable |

`Handlers` is a sealed trait (`Handlers.scala:9`), so a fourth case is a compile-time
obligation at four matches, all of which are cold or already walking: `find`
(`Handlers.scala:48-54`), `rebuild` (`Eval.scala:273-281`), and `replace`'s three loops
(`Eval.scala:291-294, 301-304, 311-314`). The `find` arm is `case l: Bracket[?, ?, ?] =>
loop(l.prev)`: a bracket cell is a cell an operation walks past, never one it resolves to.
That is the structural statement of "a lifetime is not an answer source"
(`isolate-kernel2-design.md:745-753` reaches the same conclusion from the fork side).

### 4.2 The evaluator arms

Two arms are added and one is extended.

**Entry**, beside the `Kyo.Handled` arm (`Eval.scala:109-116`), with the identical
rebuilt-cell identity check:

```scala
case kyo: Kyo.Bracket[Any, Any, Any, Any, Any] @unchecked =>
    kyo match
        case kyo: RebuiltBracketNode if kyo.cell.prev eq hs =>
            // the onion layer lands where it was built from, so the original
            // cell re-enters the stack as is
            loop(kyo.value, kyo.cell)
        case _ =>
            loop(kyo.value, new Handlers.Bracket(kyo.acquired, kyo.exit, hs))
```

**Pop**, a new arm in the settled-value match (`Eval.scala:129-138`):

```scala
case n: Handlers.Bracket[?, ?, ?] =>
    loop(release(hs, n.prev, Absent, resume(n.exit, v)), n.prev)
```

where `release(top, stop, cause, next)` is the one helper described in section 6.1. On the
normal path `top` is `hs` and `stop` is `n.prev`, so the segment is the single cell `n`, and
the helper returns `next` unchanged when the claim was already taken.

**Rebuild**, one arm (`Eval.scala:270-282`) and one wrapper beside `RebuiltNode`
(`Eval.scala:245-251`):

```scala
final private class RebuiltBracketNode(
    val value: Any < Nothing,
    val cell: Handlers.Bracket[Any, Any, Any]
) extends Kyo.Bracket[Any, Any, Any, Any, Any]:
    def acquired = cell.acquired
    def exit     = cell.exit
```

The re-entry identity check is therefore unchanged in kind: `kyo.cell.prev eq hs`, the same
predicate as `kyo.node.prev eq hs` at `Eval.scala:111`. The induction the IOTask design relies
on carries over verbatim (`iotask-kernel2-integration-r2.md:748-760`): a residual is rebuilt
down to `Empty` and re-entered from `Empty`, so every layer, bracket layers included,
re-enters its original cell with zero cell allocations. The resource crosses a park by
riding the cell, exactly as a stateful region's state does
(`EvalTest.scala:275-284` pins the state analogue).

### 4.3 What does not change

`Handlers.find`'s walk order, `replace`'s path-copy, `Eval.partial`'s residual shape, the
`Cont` continuation's multi-shot rebuild, and every existing arm's behaviour. A bracket cell
is inert to all of them except by being one more cell to walk past. `replace` may copy a
bracket cell when a stateful handler below it updates (`Eval.scala:284-316`); the copy shares
`acquired` by reference through `withPrev`, so the claim is not forked.

---

## 5. The acquire gap (design question 3)

### 5.1 The claim

**Between the instant `acquire` settles and the instant the obligation is inside a `Kyo`
node, the evaluator executes a straight-line sequence with no budget check, no loop iteration,
and no call into user code other than `use(r)`. Since every park in the kernel requires a loop
iteration over a `Kyo.Defer` (F2), no park can land in that window.**

### 5.2 The construction arrow

```scala
// worktree Effect.scala, the settled branch is the whole mechanism
new Arrow.Transform[R, A, S]:
    self =>
    def frame = _frame
    def apply[C, S2](v: R < S2, next: Arrow[A, C, S2]): C < (S & S2) =
        v match
            case kyo: Kyo[R, S2] @unchecked =>
                // acquire is still pending: re-chain, nothing is acquired yet
                kyo.map(self.chain(next))
            case v =>
                // no budget check: reading the settled resource, allocating the
                // acquisition, and allocating the node are one strict step, and a
                // check here is the one place a park could separate them
                val r        = Kyo.unnest(v)
                val acquired = new Kyo.Bracket.Acquired[R, S]:
                    val resource                                            = r
                    def release(r: R, cause: Maybe[Result.Error[Any]])       = releaseBody(r, cause)
                Kyo.Bracket(useBody(r), acquired, next.asInstanceOf[Arrow[A, C, S & S2]])
```

Line by line against the tree:

- The pending branch is `suspendWith`'s pending branch verbatim
  (`ArrowEffect.scala:44-47`): a pending input re-suspends through this same transform with
  the remaining steps chained after it. Nothing is acquired, so nothing is owed, and a park
  during `acquire` produces a remainder that owes nothing.
- `Kyo.unnest` at the crossing to a plain function parameter is the currency discipline's
  rule, with the precedents at `ArrowEffect.scala:52`, `ArrowEffect.scala:102`, and the
  `FirstNode` settle arm `Eval.scala:138` ("taking it out of the currency because it crosses
  to a function").
- The absence of `Safepoint.enter` is the licence F5 documents at `ArrowEffect.scala:49-51`.
  It is not an optimisation here, it is the mechanism: `Pending.map`'s settled branch
  (`Pending.scala:38-48`) is the site that mints `Kyo.Defer(v, arrow.chain(next))` when the
  budget is exhausted, and if the bracket were written as `acquire.map(r => node)` **that
  `Defer` would hold the acquired resource as its value with the obligation buried in an
  arrow closure**. That is the gap, and it is why the constructor must not use `<.map`.
- `next` becomes the node's exit directly rather than through `node.map(next)`, saving one
  allocation and one identity test. The cast sits at the erased-construction boundary
  `CONTRIBUTING.md:173-174` sanctions, whose existing instance is the successor built in
  `statePending` (`Eval.scala:153-158`).

### 5.3 The trace, acquire settling to use's first step

Take `bracket(Sync.defer(openFile))(release)(use)` inside a drive.

1. The constructor matched `acquire` at construction: it is a `Kyo`, so the value is
   `acquireNode.map(open)` (`KyoInternal.scala:56-77` fuses `open` onto the node's
   continuation, one allocation, no evaluation).
2. The loop reaches the acquire node. If it is a `Kyo.Defer`, the `Defer` arm
   (`Eval.scala:103-108`) applies. **This is a park site**: in partial mode with a stop
   pending, `rebuild(hs, Empty, v)` returns a remainder whose chain still ends in `open`.
   Nothing is acquired. Owed: nothing. Correct.
3. Otherwise the arm calls `walk(kyo.cont, kyo.value)` (`Eval.scala:108`), which reaches
   `resume` and `evalChain` (`Eval.scala:192-240`). The user's acquire body runs here and
   produces the resource.
4. The chain walk arrives at `open`. **There is no arm between step 3 and step 4**: `evalChain`
   applies transforms in a loop under the Safepoint budget (`Eval.scala:209-228`), and when
   the budget denies an entry it does not park, it flattens and continues
   (`Eval.scala:221-228`). The only value that leaves `evalChain` early is a `Suspended`,
   which is minted from a `Kyo` result (`Eval.scala:227`), and a settled resource is not a
   `Kyo`.
5. `open.apply(r, next)` runs the settled branch of section 5.2: unnest, allocate `Acquired`,
   call `useBody(r)`, allocate the node. **No `Safepoint.enter`, no `Kyo.Defer`, no
   suspension arm, no loop iteration.** The resource and the obligation are allocated in the
   same expression.
6. The node returns through the remaining chain. Every arrow application on a `Kyo` chains
   rather than steps (`Pending.scala:36-37`, `ArrowEffect.scala:44-47`, `Effect.scala:46-47`,
   `Eval.scala:193-194`), and `Kyo.Bracket.map` fuses into `exit` (section 3.1), so the value
   that reaches the loop is still a `Kyo.Bracket`. No arm can consume it as a settled value.
7. The loop's entry arm (section 4.2) pushes the cell and tail-calls with
   `hs = new Handlers.Bracket(...)`.
8. `kyo.value` is `useBody(r)`, whose own `map` sites carry their own budget checks
   (`Pending.scala:40-42`). The first of them that parks does so with the cell already on the
   spine, so `rebuild` includes a `RebuiltBracketNode` and the discard walk finds it.

Every `Defer` arm on the path is enumerated: one in step 2 (before acquisition) and any number
in step 8 (after the obligation is a cell). Between them there is none.

### 5.4 No mask

Contract point 2 forbids masking the region, and this design needs no mask anywhere:

- The gap needs none, by section 5.1.
- `use` runs with the spine holding the bracket cell and with every existing park site live.
  A stop request during `use` parks exactly as it does inside a handled region today, and the
  resulting remainder carries the obligation. Interruptibility is therefore total across the
  whole use computation, including its first step.
- Release is the one place where a mask could be argued for (contract point 4's "runs to
  completion once started"). Section 8.3 states why the design still does not add one, what
  the residual exposure is, and what a mask would cost.

The prototype masked (`Eval.Masked` and `Safepoint.maskPreempt`, prototype
`cc446cf27b:kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Eval.scala:16-17, 32-35,
55-60`), which is exactly what contract point 2 rules out.

---

## 6. The release paths (design question 4)

### 6.1 One helper, four sites

Every release site is the same question: the loop is about to continue at a spine shallower
than the one it was handed, so the cells in between are leaving. The helper takes the segment
and returns the value to continue with.

```scala
// worktree Eval.scala, beside rebuild
// the releases owed by the cells in [top, stop), innermost first, sequenced before next.
// Returns next unchanged when the segment owes nothing, which is the common case.
private def release(top: Handlers, stop: Handlers, cause: Maybe[Result.Error[Any]], next: Any < Nothing): Any < Nothing =
    @tailrec def loop(l: Handlers, acc: Any < Nothing): Any < Nothing =
        if l eq stop then acc
        else
            l match
                case n: Handlers.Bracket[Any, Any, Any] @unchecked =>
                    if !n.acquired.claim() then loop(n.prev, acc)
                    else loop(n.prev, guardedRelease(n.acquired, cause, acc))
                case n: Node[?, ?, ?, ?, ?]            => loop(n.prev, acc)
                case n: StateNode[?, ?, ?, ?, ?, ?]    => loop(n.prev, acc)
                case n: FirstNode[?, ?, ?, ?, ?, ?, ?] => loop(n.prev, acc)
                case Empty                             => acc
    loop(top, next)
```

Properties, each by shape rather than by discipline:

- **Innermost first.** The walk starts at `top`, which is the innermost cell, and each release
  is sequenced *before* the accumulator, so the last one visited (the outermost) runs last.
  This is the same walk direction `rebuild` uses (`Eval.scala:270-282`).
- **Once per acquisition.** `claim()` is a CAS on the one `Acquired` object of that
  acquisition (section 3.1). Copies of the node and of the cell share it.
- **Free when there is nothing to release.** With no bracket cell in the segment the helper
  returns `next` by identity, and the segment is one cell on the settle path.
- **Stack safe.** A flat `@tailrec` loop over an immutable list, the carrier `rebuild` and
  `replace` already name (`CONTRIBUTING.md:115-121`). The value it builds is a chain the
  evaluator drives through the arrow-chain carrier (`Arrow.scala:105-139`).

`guardedRelease(acquired, cause, then)` is `acquired.release(resource, cause)` sequenced
before `then`, with a fast path when the release settles:

```scala
acquired.release(acquired.resource, cause) match
    case kyo: Kyo[Any, Any] @unchecked => kyo.map(constant(then))   // one map node
    case _                             => then                       // no allocation
```

The proof obligation this leaves is mechanically checkable: **no arm may pass a spine
shallower than its own without routing through `release`**. That is a grep over `Eval.scala`
for `node.prev`, `n.prev`, and `Empty` in tail position. It is a real obligation and it is
named as such in section 13.

### 6.2 Path one: settle

Covered in section 4.2. Ordering is structural: the exit arrow is a field of the node, so
whatever the user composed after the bracket is inside `n.exit`, and `release` sequences the
release before it. This is the property six prototype encodings failed to hold
(`kernel2-finalizer-design.md:44-49`, defect class 1: "with the region extent defined
implicitly as the whole cont chain, extension lands inside the region"). Here the extent is a
field, so extension cannot land inside it.

### 6.3 Path two: a `done` that truncates the spine

The four sites where a `done` discards cells:

| site | worktree line | segment |
|---|---|---|
| stateful clause `done` | `Eval.scala:68-69` | `[hs, node)` |
| loop clause `done` | `Eval.scala:85-86` | `[hs, node)` |
| stateful pending-outcome `done` | `Eval.scala:159-160` | `[hsAll, node)` |
| loop pending-outcome `done` | `Eval.scala:183-184` | `[hsAll, node)` |

Each becomes `release(hs, node, cause, resume(node.exit, Nested.lift(done)))`, and the loop
continues at `node.prev` as it does today. The releases run before the answering region's own
exit, which is the correct nesting: the brackets were opened inside that region.

The two pending-outcome sites run inside a closure (`Eval.scala:151-161, 180-185`) rather than
in the loop, which is why the helper returns a **value** instead of performing the releases.
The same call works in both positions; that is the reason for the helper's shape.

This path is what makes `Abort.fail` inside a bracket release in band. `Abort` is handled with
a `Loop` clause that issues `Loop.done` (`iotask-kernel2-integration-r2.md:897-905` walks the
same reasoning from IOTask's side and calls this requirement R-B3), so the failure hits
`Eval.scala:85-86` and the segment between the failing suspension and the `Abort` cell holds
the bracket. It is released, innermost first, before the `Abort` region's exit and therefore
before anything mapped after `Abort.run`. The pinned contract's "release runs BEFORE anything
composed after the region, on every path" (`kernel2-finalizer-design.md:26-29`) holds here by
construction rather than by seam arithmetic.

`cause` on this path is `Absent`: the kernel does not know the value is an error. Section 11.2
records the fidelity question and the option that closes it.

### 6.4 Path two and a half: cells captured by a `Cont` clause

`Eval.scala:88-93` and `Eval.scala:94-102` do not discard the cells above `node`, they hand
them to a closure that rebuilds them per call (F4). Releasing at those arms would release a
resource that a live continuation can still resume into, so the helper must **not** be applied
there unconditionally. Two behaviours are possible and this is a value fork:

- **(a) Travel with the value.** Do nothing at the arm. If the continuation is resumed, the
  region re-enters and releases at its pop. If it is never resumed and nobody discards the
  value, the release never runs. This is a leak relative to origin/main, where the fiber-level
  list catches it at fiber end
  (`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:153-155`).
- **(b) Release at the clause's settle if unclaimed.** Chain a step onto the clause's result
  so that when it settles at `node`, any still-unclaimed bracket in `[hsAll, node)` is
  released. A resumption that completed the region already claimed, so there is no double
  release; a resumption that is still pending has not settled the clause, so the check cannot
  run early; a stored continuation resumed later finds a released resource.

Recommendation: **(b)**. It has no leak, it keeps "release before downstream" on the
short-circuit path, and its exposure (a stored continuation resumed after the handler already
returned) is the case where origin/main's `Ensure` CAS produces a spent finalizer too
(`origin/main:kyo-kernel/.../Safepoint.scala:174-179`). The cost is one segment scan per
`Cont` dispatch that crosses at least one cell, which argues for the per-cell counter of
section 10.4 if measurement shows it.

### 6.5 Path three: unwind

`evalLoop` has no `try` today. The addition is a per-arm `try` around each arm's non-tail
computation, excluding the tail call so `@tailrec` is preserved. This is the same structural
constraint and the same solution the enrichment design specifies for the same arms
(`exception-enrichment-design.md:274-277`: "Implementation-wise they collapse to per-arm `try`
regions that exclude the tail call, so `@tailrec` is preserved").

```scala
case kyo: Kyo.Defer[Any, Any, Any] @unchecked =>
    if partial && Safepoint.consumeStopped(slot) then rebuild(hs, Empty, v)
    else
        Safepoint.reset(slot)
        val next =
            try walk(kyo.cont, kyo.value)
            catch case t: Throwable => unwind(hs, t)
        loop(next, hs)
```

`unwind` is not a walk that releases everything. It releases **one** bracket and hands control
back to the loop:

```scala
// the innermost unspent acquisition releases under its own cell's spine, and the
// exception is carried in the chain: the loop re-enters this path until nothing is owed
private def unwind(hs: Handlers, t: Throwable): Any < Nothing =
    innermostBracket(hs) match
        case null => throw t
        case n    => release(n, n.prev, Present(Result.Panic(t)), rethrow(t))
```

with `loop(next, n.prev)` at the site. Why this shape rather than one flat pass:

- **Handler fidelity.** Each release runs at its own cell's `prev`, so the operations it
  raises resolve against the handlers that were in scope where the bracket was opened. A
  single flat chain evaluated at `Empty` would lose every ambient handler, which the pinned
  contract forbids (`kernel2-finalizer-design.md:36-38`: "finalizers are effectful
  computations running in the ambient context").
- **Termination.** `rethrow(t)` throws when the loop reaches it, the arm catches it again, and
  `unwind` finds no unspent bracket in the shallower spine and rethrows for real. The fixed
  point is "nothing owed", and it is reached in at most one iteration per bracket.
- **Parks during unwind.** A release that suspends parks normally; the remainder carries the
  rest of that release followed by the rethrow, with the outer bracket cells still in the
  onion. A discard of that remainder still finds them (section 6.6).
- **Stack safety.** One tail call per bracket. 1M nested brackets unwind in bounded stack, the
  same way 1M nested regions enter in bounded stack (`EvalTest.scala:456-464`).

Suppression: `guardedRelease` on the unwind path wraps the release in `Effect.catching`
(`Effect.scala:15-21`) with `t2 => { t.addSuppressed(t2); () }`, which is contract point 4's
"release failures are suppressed onto the primary failure". The known hole is the one
`catching` documents about itself: a throw from inside a *region* nested in the release body is
not covered (`ArrowEffect.scala:253-255`, pinned by `EffectTest.scala:80-119`). Section 7.3
returns to this.

Fatal errors: the arm catches `Throwable`, not `NonFatal`, because origin/main runs finalizers
on the fatal path
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/IOTask.scala:131-144`). Only the
suppression guard filters with `NonFatal`.

Happy-path cost of the added `try` regions: none in instructions. A `try` with no throw is an
entry in the exception table, not code. The honest residual is that a handler region can
constrain JIT code motion, which is exactly what the mandated JMH A/B with `-prof gc`
(`CONTRIBUTING.md:168`) exists to answer, and the allocation rows must come back flat because
no arm allocates anything new.

### 6.6 Path four: discard of a parked remainder

The scheduler-side entry:

```scala
// worktree Eval.scala
private[kyo] def discard[A, S](v: A < S, cause: Maybe[Result.Error[Any]]): Unit
```

**What it walks.** The layers of the onion, through `value`, and nothing else:

```scala
@tailrec def peel(v: Any < Nothing, outer: Handlers, owed: Chunk[Owed]): Chunk[Owed] =
    v match
        case kyo: Kyo.Bracket[Any, Any, Any, Any, Any] @unchecked =>
            peel(kyo.value, outer, owed.append(Owed(kyo.acquired, outer)))
        case kyo: Kyo.Handled[...]      => peel(kyo.value, cellOf(kyo, outer), owed)
        case kyo: Kyo.HandledState[...] => peel(kyo.value, cellOf(kyo, outer), owed)
        case kyo: Kyo.HandledFirst[...] => peel(kyo.value, cellOf(kyo, outer), owed)
        case _                          => owed
```

Three claims about this walk, each of which is why it is nothing like the prototype's:

1. **It never enters an arrow.** The onion `rebuild` produces is nodes wrapping nodes
   (`Eval.scala:270-282`), so `value` is the only edge to follow. The prototype's
   `finalizeArrow` had to walk `Arrow.AndThen`, `Arrow.Offset`, and even a handler's internal
   rotation step (prototype `Finalize.scala:69-89`), which is unimplementable against the
   current `Arrow` representation, whose chains are flattened on demand through a thread-local
   buffer (`Arrow.scala:112-138`).
2. **Every `Kyo.Bracket` it finds is owed.** There is no unentered bracket node (section 3.2),
   so there is no shape to misclassify and no flag to read.
3. **It is complete for values produced by `Eval.partial`.** `Eval.partial` returns either a
   settled value or `rebuild(hs, Empty, v)` (`Eval.scala:49-50, 104-105`), and `rebuild` emits
   one layer per open cell down to `Empty`. Open bracket cells are cells, therefore layers,
   therefore reachable. This is a precondition, stated plainly: `discard` is the discard entry
   for remainders, not a general "find every bracket in an arbitrary value".

**What it runs.** Innermost first, which is the reverse of the peel order, so the fold builds
the chain by prepending: for layers visited outermost to innermost, `acc = rel_i.andThen(acc)`
leaves the innermost first. Each release runs under the handler layers that were outside it,
reconstructed with **identity exits** so no parent region's continuation runs during a
discard; that reconstruction is the same operation the fork design specifies for its
transplant (`isolate-kernel2-design.md:301-314`), reused rather than reinvented. The whole
chain is then driven by one `Eval.apply` call, which is legitimate here in a way a nested
drive inside `evalLoop` would not be: the discard entry is a top-level entry point, like
`Eval.apply` itself, called by a scheduler that holds no drive.

Each release is individually guarded so one failure does not skip the rest, and failures are
suppressed onto `cause.exception` when present and logged by the caller otherwise, matching
`origin/main:kyo-core/shared/src/main/scala/kyo/Scope.scala:183-186`.

IOTask calls this on its three discard paths (queued-interrupt, mid-slice interrupt, fatal),
which is requirement R-B1 of the integration design
(`iotask-kernel2-integration-r2.md:858-872`), and carries **no finalizer field**: the property
"a fiber's outstanding releases are known" is held by the shape of the residual
(`iotask-kernel2-integration-r2.md:845-853`). `Finalizers`
(`origin/main:kyo-core/shared/src/main/scala/kyo/scheduler/Finalizers.scala`) loses its only
consumer, and requirement R-B2 (a throw escaping `Eval.partial` has already run what it
unwound) is section 6.5.

---

## 7. Ordering (design question 5)

### 7.1 Relative to region exits

A bracket's release runs when its cell leaves the spine, and cells leave in `prev` order, so
ordering with regions is entry order reversed, the same rule `EvalTest.scala:52-58` pins for
regions ("scope exits run innermost first"). Concretely: a bracket opened inside a region
releases before that region's exit; a bracket opened outside it releases after. Nested
brackets release innermost first on all four paths, because all four use the same walk
(section 6.1).

### 7.2 Relative to `Effect.catching` guards

`Effect.catching` installs guards in the chain, not on the spine: `guarded` rewraps a
suspension's `cont`, a `Defer`'s `cont`, and a region node's `exit`
(`Effect.scala:53-77`), and `isolate-kernel2-design.md:143-149` records the consequence. The
bracket needs one arm there:

```scala
case kyo: Kyo.Bracket[Any, Any, B, S, Any] @unchecked =>
    Kyo.Bracket(kyo.value, kyo.acquired, guard(kyo.exit))
```

sharing `acquired` by reference, which is why the claim lives on a separate object
(section 3.1). The guard therefore covers what the user composed after the bracket and the
release chain that precedes it, and does not reach into `use`. That is the boundary `catching`
already draws and documents: "A region nested inside the computation evaluates on its own, so
its internals are not covered" (`ArrowEffect.scala:253-255`; `Effect.scala:23-25` says the
same in the guard's own comment).

### 7.3 Worked example

```scala
val program: String < Any =
    Effect.catching {
        ArrowEffect.handleLoop(Tag[Ask],
            Effect.bracket(acquire)(release)(r => ask.map(_ => throw new RuntimeException("boom")))
        )([X] => _ => Loop.continue(1))
         .map(s => "after: " + s)
    }(_ => "caught")
```

At construction: `catching` rewraps the `Handled` node's exit (`Effect.scala:64-65`), so the
guard sits on `.map(s => "after: " + s)`. The bracket value is `acquire.map(open)` inside the
region.

1. The loop enters the `Handled` node: one cell (`Eval.scala:109-116`).
2. `acquire` evaluates; `open` mints `Acquired` and the `Kyo.Bracket` node (section 5.2).
3. The loop enters the bracket node: a second cell, above the `Ask` cell.
4. `ask` suspends; `find` walks past the bracket cell (no tag) and resolves the `Ask` cell
   (`Handlers.scala:48-54`); the clause continues with `1` (`Eval.scala:79-84`).
5. The continuation throws. The throw surfaces in the arm that called `resume`, whose `hs` is
   `[bracket, ask]`.
6. `unwind` releases the bracket under `bracket.prev` (the `Ask` cell is still on the spine, so
   a release raising `Ask` is answered), suppressing any release failure onto the
   `RuntimeException`, then rethrows.
7. The rethrow re-enters the arm with the bracket claimed; nothing is owed; it propagates out
   of `evalLoop`.
8. It is **not** caught by the `catching` in this program, because the guard is on the chain
   after the region and that chain was never entered. `"after: "` never runs, and neither does
   the `Ask` region's exit.

The observable order is: release, then the exception. If the user wants the recovery to see
it, the `catching` goes inside `use`, where its guards are on the chain the throw travels:
`Effect.bracket(acquire)(release)(r => Effect.catching(...)(...))` catches first, the bracket
completes normally, and the release then runs on the settle path. Both orders are consistent
with the pins at `EffectTest.scala:80-119` ("failure in a map after a region" catches because
the throw is in the chain, not in a region's internals).

One forward note: the guard placement is already flagged in the tree as provisional
(`Effect.scala:27`, "this seems an expensive workaround for something that should be handled
in Eval or Arrow?"). If guards move into `Eval`, the bracket's unwind arm and the guard become
the same per-arm `try`, and the two should be designed in one pass rather than stacked.

---

## 8. The release effect row (design question 6)

### 8.1 Can release suspend

Yes. `release: (R, Maybe[Result.Error[Any]]) => Any < S` and the constructor's row is the
union of the acquire, use, and release rows (section 9.1). The kernel imposes no restriction
on the normal, truncation, and unwind paths.

### 8.2 What drives it on each path

| path | driver | ordering guarantee |
|---|---|---|
| settle | the ambient drive; the release is sequenced into the currency at `n.prev` | the exit arrow is chained after it, so nothing after the bracket can run first |
| `done` truncation | the ambient drive, at `node.prev` | sequenced before the answering region's exit |
| unwind | the ambient drive, at the bracket cell's `prev`, with the rethrow chained after | the exception cannot escape before the release completes |
| discard | one `Eval.apply` from the discard entry, with the onion's outer layers reconstructed with identity exits | the entry returns only when every owed release has run |

No nested drive is created inside `evalLoop` on any in-band path. That matters: the sub-drive
(`settle`) is one of the four mechanisms the current evaluator deleted
(`CONTRIBUTING.md:35-49`), and reintroducing one for releases would reintroduce its two
defect classes (a value delivered to the wrong scope, and unbounded Java-stack depth).

### 8.3 The honest contract on "runs to completion once started"

The claim is taken **before** the release runs. So:

- Nothing else in the drive can interleave: the release chain is the current value and the
  post-bracket chain is behind it.
- A stop request arriving while the release is in flight parks it like any other computation.
  If that remainder is later resumed, the release finishes. If it is discarded, the discard
  walk finds the bracket already claimed and does not restart it, so a half-run release stays
  half-run.

That window is real and it is new relative to origin/main, which forbids it structurally by
evaluating every finalizer synchronously
(`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:111`:
`Safepoint.ensure(ex => Sync.Unsafe.evalOrThrow(f(ex)))(v)`), and which
`kernel2-finalizer-design.md:233-234` restates as a restriction for discard paths. Three ways
to close it, with costs:

1. **Forbid suspending releases** (baseline parity). The row becomes `Any < Any` and kyo-core
   keeps `evalOrThrow`. Cheapest, and it gives up the capability the kernel would otherwise
   have.
2. **Mask preemption while a release is in flight.** `evalLoop` gains a boolean parameter and
   the `Defer` arm's park condition becomes `partial && !masked && consumeStopped(slot)`.
   One register on the hot loop, but clearing the flag needs a marker the loop can see when
   the release finishes, which is a second mechanism, and a release that awaits a promise
   would then be unparkable, which is a deadlock, not a guarantee.
3. **Accept and pin.** Claim before running, document that a discard does not restart an
   in-flight release, and pin the behaviour.

Recommendation: **3**, with the pin. It is the only option that keeps effectful releases and
adds no mechanism, and the exposure requires a discard that lands inside a release's own
suspension.

---

## 9. Surface (design question 9)

### 9.1 The constructor

Home: `Effect.scala`, beside `defer` and `catching`, which are the kernel's other two
value-constructing primitives (`Effect.scala:15-21, 81-93`).

```scala
/** Acquires a resource, uses it, and releases it.
  *
  * `release` runs on every path: when `use` completes, when a handler discards the region,
  * when an exception unwinds it, and when a remainder holding it is discarded. It receives
  * the resource and the cause when there is one. `use` is interruptible throughout.
  */
@nowarn("msg=anonymous")
inline def bracket[R, A, S1, S2, S3](inline acquire: R < S1)(
    inline release: (R, Maybe[Result.Error[Any]]) => Any < S3
)(inline use: R => A < S2)(using inline _frame: Frame): A < (S1 & S2 & S3)
```

Inline, for the reason the handling variants are inline (`CONTRIBUTING.md:81`): the user's
`release` body compiles into the `Acquired` instance and the `use` body into `open`, so
neither becomes a function value. The body is the construction match of section 5.2, with a
settled-acquire arm that builds the node directly, mirroring `handle`'s construction match
(`ArrowEffect.scala:64-78`).

One caveat to document at the surface: an `acquire` argument that has already performed its
effect before the call (a by-value expression rather than a `Sync.defer`) is outside any
kernel guarantee, because the resource existed before the kernel saw anything.

### 9.2 `Sync.ensure`

```scala
// origin/main: inline def ensure[A, S](f: Maybe[Error[Any]] => Any < (Sync & Abort[Throwable]))(v: => A < S): A < (Sync & S)
Effect.bracket(())((_, cause) => f(cause))(_ => v)
```

A unit resource. The cause type is unchanged from origin/main's signature, so the adapter is
an identity on the interesting parameter. `Sync.acquireReleaseWith`
(`origin/main:kyo-core/shared/src/main/scala/kyo/Sync.scala:77-82`) becomes a direct
`Effect.bracket` call and stops being a composition of `defer` and `ensure`.

### 9.3 `Scope`

`Scope.run` needs **one** bracket per scope, not one per resource, because origin/main already
accumulates library-side:
`ContextEffect.handle(Tag[Scope], finalizer, _ => finalizer)(v).handle(Sync.ensure(finalizer.close), Abort.run[Any])`
(`origin/main:kyo-core/shared/src/main/scala/kyo/Scope.scala:129-142`), where `Finalizer` is a
queue of registered closures closed in reverse
(`origin/main:kyo-core/shared/src/main/scala/kyo/Scope.scala:145-196`, the close at `:174-192`).
The mapping is:

```scala
Effect.bracket(Finalizer.Awaitable.Unsafe.init(closeParallelism))(
    (finalizer, cause) => finalizer.close(cause).andThen(finalizer.await)
)(finalizer => ContextEffect.handle(Tag[Scope], finalizer, _ => finalizer)(v))
```

So multi-resource accumulation is expressible **both ways**, and the cost decides:

- **Shared finalizer (recommended, and what origin/main does).** One bracket per `Scope.run`:
  one cell, roughly five allocations (section 10.1), plus one queue entry per resource. Cleanup
  parallelism, `await`, and error logging stay where they already are.
- **One bracket per resource.** Also correct: each is a cell, `prev` order gives reverse
  release, and nesting is stack-safe. It costs one cell and roughly five allocations per
  resource, adds one cell to every `find` walk inside the scope per live resource, and loses
  the parallel close, since the kernel releases sequentially by construction. For a scope
  holding tens of resources that is the wrong trade; for two or three it is fine and it is
  what `Sync.acquireReleaseWith` already gives.

### 9.4 Visibility

`Kyo.Bracket` and `Handlers.Bracket` are internal (`kyo.kernel.internal`), `Effect.bracket` is
public, and `Eval.discard` is `private[kyo]`, matching `Eval`'s existing visibility and the
scheduler's need to call it.

---

## 10. Costs (design question 8)

### 10.1 Allocations per bracket, happy path

| # | object | when |
|---|---|---|
| 1 | the `open` transform | per bracket evaluation |
| 2 | the fused `acquire.map(open)` node | only when `acquire` is pending |
| 3 | `Acquired` (holds resource, release body, claim) | at the acquire-to-node transition |
| 4 | the `Kyo.Bracket` node | same expression |
| 5 | the `Handlers.Bracket` cell | at entry |
| 6-7 | one map node for a suspending release at the pop | only when the release is pending |

Five on the common path, seven with a suspending release, three of which (`open`, the fused
map node, the cell) have exact analogues in the region path. A `handleLoop` region by
comparison is two (the fused handler-and-node object plus the cell,
`ArrowEffect.scala:115-123`). The bracket is more expensive than a region and that is inherent:
it carries a resource and an obligation that a region does not.

`Acquired` cannot be folded into the node, because `map` and `guarded` copy nodes and the
claim must not fork. The one allocation it costs buys once-per-acquisition across every copy.

### 10.2 Per park crossing

One `RebuiltBracketNode` per open bracket cell, the same per-layer cost every open cell already
pays (`Eval.scala:270-282`), and zero on resume, because re-entry is by cell identity
(`Eval.scala:111-114` and its bracket twin). Nothing else changes about the residual.

### 10.3 Added dispatch cost for non-bracket code

- **The node match.** One more `case` in `evalLoop`'s value match. The match is a chain of type
  tests in frequency order (`Eval.scala:41-138`), so the cost lands on the arms below the
  insertion point and on the settled-value default. Placing the bracket arm after
  `Kyo.HandledFirst` leaves `Suspend` and `Defer`, the two hot arms, untouched, and costs one
  additional type test per settled value that reaches the pop. That is the one row of the JMH
  board most likely to move, and it is the row the gate (`CONTRIBUTING.md:168`) reads first.
- **`find`.** One more arm in `Handlers.find` (`Handlers.scala:48-54`), and, for programs that
  actually hold a bracket, one more cell to walk past per operation raised inside it. With
  `Sync.ensure` or `Scope` wrapping a large region, that is one extra cell on the resolution
  path of everything inside, and each visit is a failing type test, not a `Tag` comparison.
- **`replace` and `rebuild`.** One arm each in three loops and one loop respectively. Both are
  cold (`CONTRIBUTING.md:113`).
- **`Effect.guarded`.** One arm (`Effect.scala:53-77`), taken only under `catching`.

### 10.4 Added walk cost on release-adjacent paths

`release(top, stop, ...)` scans the segment. On the settle path the segment is one cell. On the
`done` paths it is the cells between the suspension's spine and the answering cell, typically
zero to three, and this is a new cost on the short-circuit path that does not exist today. On
the `Cont`/`First` arms under option (b) of section 6.4 it is per dispatch.

If measurement shows the `Cont` scan matters, the O(1) form is a `brackets: Int` field on every
cell, computed at construction as `prev.brackets + (if bracket then 1 else 0)`, making
"does this segment owe anything" a single comparison. That is a summary of the list, not a
second source of truth, but it is still a field to maintain on four cell kinds, so it should be
added only against a measured row.

---

## 11. Interaction with the fork design (design question 7)

### 11.1 A child never inherits an obligation

`isolate-kernel2-design.md:281-314` builds the fork as `transplant`, a walk over the spine that
copies handler cells with neutral exits and consults a per-cell policy. The bracket's answer is
not a policy value:

```scala
@tailrec private def transplant(top: Handlers, acc: Any < Nothing): Any < Nothing =
    top match
        case Empty                             => acc
        case n: Node[...]                      => ... // Inherit or Skip
        case n: StateNode[...]                 => ... // Inherit or Skip
        case n: Handlers.Bracket[?, ?, ?]      => transplant(n.prev, acc)   // no arm copies it
```

The cell kind has no arm that copies it, so **a child holding a second release for the parent's
acquisition is unrepresentable**. That is the structural version of the rule
`isolate-kernel2-design.md:745-753` states, and it is what the guide asks for: the property is
a corollary of the shape, not a check someone maintains (`CONTRIBUTING.md:33`). Compare the
alternative in which a bracket is a handler cell: `transplant` would copy it unless a policy
said otherwise, and the policy is a code path that can be wrong.

The argument for the policy itself: two releases of one acquisition violates exactly-once
(`kernel2-finalizer-design.md:22-24`), and a child's lifetime is not the parent's region. A
child that legitimately needs the parent's resource gets it the way origin/main gives it: the
`Scope` finalizer is a shared handle carried by an inherited context effect, so a child
registers into the same finalizer rather than into a copy
(`origin/main:kyo-core/shared/src/main/scala/kyo/Scope.scala:37, 132-134`;
`isolate-kernel2-design.md:771-782`).

### 11.2 The pair `rebuild` / `transplant`

The two walks are the clean statement of the difference: **`rebuild` preserves exits and
brackets, `transplant` neutralises exits and drops brackets.** `rebuild` must preserve them
because a remainder resumes *inside* the region; `transplant` must drop them because a child
resumes *outside* it.

### 11.3 A fork inside acquire

Nothing to decide: during `acquire` there is no bracket cell at all (section 3.2), so the fork
walk sees the ambient handlers and no obligation. A fork inside `use` sees the bracket cell and
walks past it. A fork inside `release` likewise. All three deserve a pin
(`isolate-kernel2-design.md:784-787` asks for two of them).

---

## 12. Prototype failure modes this design must not reintroduce

Read at `cc446cf27b` for enumeration only. The mechanisms are not imported and each is listed
with the property in this design that makes it unnecessary.

1. **Java-stack recursion per bracket, capped.** `recur(bracket.acquire, depth + 1)` with
   `if depth >= Finalize.BracketDepth then bracket` (prototype
   `kyo-kernel2/shared/src/main/scala/kyo/kernel2/internal/Eval.scala:26-46`,
   `Finalize.scala:59`). This is the shape `CONTRIBUTING.md:115-121` rules against twice: a
   recursion whose carrier is the Java stack, and a cap sized instead of a representation
   chosen. Here entry is one cell and every release path is a tail call.
2. **Three drive modes, one of which masks.** `Preemptible`, `Masked`, `Cascade`
   (prototype `Eval.scala:12-17`), with `Safepoint.maskPreempt()` around the release
   (`:55-60`). Contract point 2 rules the mask out; this design has one drive and no modes.
3. **Reacquire on a suspending acquire.** `Finalize.reacquire` rebuilds a bracket node whose
   `acquire` is the already-settled resource (prototype `Finalize.scala:36-50`), which exists
   only because the node held acquire and had to re-enter its own arm. With acquire in the
   chain there is nothing to re-enter.
4. **`Finalize` as a chain transform.** The release lived in an arrow
   (prototype `Finalize.scala:9-14`), which is what forced every discard to walk arrow
   structure. Here the obligation is a node field and a cell field.
5. **The arrow walk.** `finalizeArrow` matched `Arrow.AndThen`, `Arrow.Offset`, and a handler's
   internal rotation step (prototype `Finalize.scala:69-89`). Section 6.6's walk follows
   `value` edges between nodes only, and would not compile against the current `Arrow`, whose
   chains materialise on demand (`Arrow.scala:112-138`).
6. **Nested full evaluation inside the drive.** `cleanup` ran `bracket.release(resource).eval`
   (prototype `Finalize.scala:52-58`) and `finalizeArrow` did the same. In-band paths here
   sequence releases into the currency instead; only the discard entry, which is outside any
   drive, calls `Eval.apply`.

The lineage's own conclusion is worth carrying forward: six encodings failed because "structure
alone cannot express 'after the region' on the discard path"
(`kernel2-finalizer-design.md:111-116`). Every one of them defined the region as "the rest of
the chain". This design defines it as a field, which is the assumption that failure rests on.

---

## 13. Where the handler-kind architecture likely wins (design question 10)

Stated as strongly as the case allows, because the pair is only useful if both sides are real.

1. **No new cell kind, therefore no new arms in four walks.** A bracket expressed as a handler
   kind reuses `Node` or `StateNode`, so `find`, `replace`, `rebuild`, and `transplant` are
   untouched, and `Effect.guarded` needs no arm. This design adds one arm to each. Those arms
   are cold, but they are five more places a future edit can get wrong, which is exactly the
   cost `CONTRIBUTING.md:33` prices.
2. **No new arm in the hot match.** `evalLoop`'s value match would keep its current arity, so
   the settled-value default keeps its current type-test chain. This design's most likely JMH
   regression row disappears.
3. **The acquire gap may close on machinery that already exists.** If acquire is an operation
   answered by the region's own handler, the resource can be installed in the cell by the
   ordinary state update, `node.withState(c._1)` (`Eval.scala:56-61`), which is atomic with
   respect to parks for the same reason this design's `open` is: it happens inside one loop
   iteration. The resource then crosses parks, multi-shot re-entries, and interior updates on
   pinned machinery (`EvalTest.scala:275-284, 286-297, 422-430`) with no new argument needed.
   The residual question for that route is whether the acquire result can reach the cell's
   state in one clause, since a clause decides its next state before its pending answer runs
   (`Eval.scala:52-67`); if it takes two operations, the gap reopens between them and has to be
   closed the same way this design closes it.
4. **It can ride a feature the kernel needs anyway.** A handler kind with a state-aware region
   exit is already required for `Var.runTuple`, `Emit.run`, and `Check.runChunk`, and is
   recorded as an unlisted parity gap (`isolate-kernel2-design.md:541-548`). A bracket's pop is
   exactly "run something with the region's state at exit". One new handler kind serving both
   is a better trade than one new node kind plus one new cell kind serving one.
5. **The `done` truncation and the discard walk are no harder there.** Both designs put the
   obligation on the spine, so section 6.3's four sites and section 6.6's onion walk are
   identical work. Anything the node design has to build for them, the handler design also
   gets.
6. **The once-claim is not a differentiator.** Multi-shot capture forks the lineage in both
   designs, so both need it.

Where the node design answers back, briefly: a handler cell has a tag, so `find` can select it
and the effect must be kept out of user reach; `transplant` would copy it unless a policy says
skip, where the node design has no arm at all (section 11.1); and the acquire gap is closed by
node existence rather than by clause atomicity, which is a shorter argument that does not
depend on how a future clause protocol evolves.

---

## 14. Open rulings

1. **Naming.** `Bracket`, `Acquired`, `claim`, `open`, and the `release` helper are new nouns
   in a module whose rule is that names are maintainer-approved (`CONTRIBUTING.md:172`).
   `acquire`, `use`, `release`, and `exit` come from the contract itself.
2. **Cause fidelity on the `done` path.** The kernel passes `Absent` when a handler's `done`
   discards a bracket, because it cannot see that the value is an error. Options: leave it
   (release learns only that the region did not complete normally), or pass the settled value
   and let the library classify, which the finalizer design records as a heuristic with a known
   misreport (`kernel2-finalizer-design.md:234-236`).
3. **Captured continuations** (section 6.4): travel with the value, or release at the clause's
   settle when unclaimed. Recommendation: the latter.
4. **In-flight release across a discard** (section 8.3): accept and pin, forbid suspending
   releases, or mask. Recommendation: accept and pin.
5. **Whether `release` also takes the settled result of `use`**, which would let kyo-core
   implement error-aware finalizers without the kernel knowing about `Result`.

## 15. Test pins this design owes

Red-first, all through the public surface, none naming a mechanism
(`CONTRIBUTING.md:155-160`):

- release runs after `use` and before anything mapped after the bracket, pure and under a
  handler;
- a handler `done` from outside releases the brackets it discards, innermost first, before the
  region's exit (the bracket twin of `EvalTest.scala:395-411`);
- a throw in `use` releases before propagating, with a release failure suppressed onto it;
- a throw in `release` on the normal path becomes the primary and unwinds outer brackets;
- a park during `acquire` owes nothing on discard, and re-acquires nothing on resume;
- a park during `use` resumes inside the region with the same resource, and a discard of that
  remainder releases exactly once;
- nested brackets release innermost first on all four paths;
- 1M nested brackets enter and unwind in bounded stack (the twin of `EvalTest.scala:456-473`);
- a multi-shot continuation crossing a bracket releases once;
- a suspending release completes before the downstream on the settle and `done` paths;
- driving the same bracket value twice acquires and releases twice
  (`kernel2-finalizer-design.md:41-42`);
- a fork inside `acquire`, inside `use`, and inside `release` inherits no obligation.

Plus the gate the guide requires for any `Eval.scala`, `KyoInternal.scala`, or `Arrow.scala`
change: a JMH A/B against a frozen baseline with `-prof gc`, answering rows allocation-flat
(`CONTRIBUTING.md:168, 190`).

---

## 16. Comparison-ready summary

| axis | this design (node kind) |
|---|---|
| **C1: holds acquire, use, release** | `use` and `release` are node fields (`value`, `acquired`); `acquire` is a chain step ahead of the node, deliberately (section 3.2) |
| **C2: use fully interruptible** | no mask anywhere; every existing park site is live throughout `use`, including its first step (section 5.4) |
| **C3: no interruption in the acquire gap** | structural: the resource enters the node in the same strict expression that reads it, inside a transform with no budget check, so no `Kyo.Defer` exists between them and `Defer` is the only park site (sections 5.1-5.3) |
| **C4: release always runs** | four paths, one helper (section 6.1); once per acquisition by CAS on the single `Acquired` object |
| **Release path: settle** | new arm in the settled-value match; release sequenced before the node's `exit`, which is where all post-bracket composition lands |
| **Release path: unwind** | per-arm `try` in `evalLoop` excluding the tail call; `unwind` releases one bracket under its own `prev` and re-enters the loop carrying the exception; terminates at "nothing owed"; zero happy-path instructions, exception-table only |
| **Release path: discard** | `private[kyo] Eval.discard(v, cause)`; walks the onion through `value` edges only, never into arrows; complete for `Eval.partial` residuals; every `Kyo.Bracket` found is owed because unentered brackets are not nodes |
| **Fourth path (not in the brief)** | a handler `done` truncating the spine releases the cells it discards; same helper, same order (section 6.3) |
| **Acquire-gap mechanism** | none required; representation. The alternative that needs one is a node holding an un-run acquire |
| **Allocations** | 5 per bracket happy path (open transform, fused acquire map, `Acquired`, node, cell), 7 with a suspending release; region baseline is 2 |
| **Eval deltas** | +1 node arm, +1 settled-arm case, +1 arm each in `find`/`rebuild`, +3 in `replace`, +1 in `Effect.guarded`, per-arm `try` regions, one release helper, one `RebuiltBracketNode` |
| **Fork story** | `transplant` has no arm that copies a bracket cell, so an inherited obligation is unrepresentable; `rebuild` preserves brackets, `transplant` drops them |
| **Scope mapping** | one bracket per `Scope.run`, keeping the accumulating `Finalizer`; per-resource nesting also works at one cell plus five allocations each and loses parallel close |
| **Weakest point** | a bracket crossed by a `Cont` clause's captured continuation leaves the spine without a pop; when it releases is a value fork (section 6.4), and origin/main covers the never-resumed case with the fiber-level list this design deletes |

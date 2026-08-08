# kernel2 `Context` and `TypeMap`: performance design (item #19)

Status: design only. No code was changed, no build was run, no benchmark was executed
for this document. Every cost claim below is **structural**, derived by reading the
implementation and counting allocations and scans on the source. Structural claims are
labelled as such and each one is turned into a JMH row in section 7. Nothing here is
measured, and nothing here should be adopted on the strength of the reasoning alone.

---

## Summary

The item as written ("migrate `Context` from the hand-rolled `Map[Tag[Any], AnyRef]` to
`TypeMap`") does not survive contact with either the typing or the performance evidence,
but the goal behind it does. The recommendation is three concrete changes:

1. **Do not make `Context` a `TypeMap`.** Two facts block it independently of
   performance. `Context` is keyed by *effect* type while `TypeMap` is keyed by *value*
   type, and the codebase already contains two distinct context effects carrying the same
   value type (`Local.internal.State` and `Local.internal.NoninheritableState`, both
   `ContextEffect[Map[Local[?], AnyRef]]`, `Local.scala:117-118`), so a value-keyed map
   cannot tell them apart. Separately, `ContextEffect` lives in kyo-kernel2 and kyo-data
   sits below it (`build.sbt:731-734`), so no kyo-data type can carry the
   `E <: ContextEffect[V]` relation that recovers a binding's value type from its key.
   Section 3.

2. **Change `TypeMap`'s backing from `TreeSeqMap` to kyo-data's `OrderedDict`.** This is
   where the performance actually is, and the reason is not obvious from the item's
   framing: `Context` already *contains* a `TypeMap`. `Env` is declared
   `ContextEffect[TypeMap[R]]` (`Env.scala:39`) and every `Env.get` in the codebase
   funnels through one physical `TypeMap.get` call (`Env.scala:131`). So an environment
   read today pays a cheap `Context` lookup followed by an expensive `TypeMap` lookup.
   Structurally, `TreeSeqMap.get` allocates two `Option`s per lookup
   (`TreeSeqMap.scala:139`, `get(key) = mapping.get(key).map(value)`) and stores every
   entry as a `(Int, V)` tuple (`TreeSeqMap.scala:363`), with no small-size
   specialization at all. `OrderedDict` (`OrderedDict.scala:70`) is a flat `Span` with
   linear scan up to 8 entries and an insertion-ordered `TreeSeqMap` beyond, returns
   `Maybe` rather than `Option`, and allocates nothing on a small-map read. Migrating
   `Context` into `TypeMap` would import `TreeSeqMap`'s cost into the kernel; fixing
   `TypeMap` removes that cost from the path it is already on. Sections 4 and 5.

3. **Keep `Context` as its own type, give it a public surface, and share the
   *backing* rather than the type.** The user-facing goal is met by `Context` being a
   documented public type with a real API instead of a `private[kyo] opaque type` alias
   for `Map`, backed by the same kyo-data structure `TypeMap` uses. Section 6.

The item is also entangled with item #18, whose design (`kernel2-context-threading-design.md`,
section 3.2) replaces `Context` with a frame list and records the conflict as its open
question O-2. Section 1 resolves that: the two designs are compatible once `Context`'s two
distinct roles are separated, and this document takes an independent position on O-2 and
O-3 with evidence.

---

## 1. Scope, and the collision with item #18

Item #18's design is already written and proposes replacing `Context`'s representation
with an immutable frame list (`Context = Context.Frame | Null`) carrying two frame kinds,
`Const` and `Derived` (`kernel2-context-threading-design.md` section 3.2). Its O-2 asks
for a ruling on whether #19 survives.

The resolution is that `Context` is doing two jobs today under one name, and they have
opposite requirements:

**Role A, the in-flight scope stack.** The set of bindings visible at a point during
execution. `ContextEffect.handle` takes a transform, not just a value
(`ContextEffect.scala:74-86`), and a transform-form binding's value depends on the
bindings outside it, which grow every time the computation is composed into a larger one.
Item #18's section 7.2 shows this cannot be a materialized keyed map: a value computed at
construction time goes stale, and memoizing per node is unsound under multi-shot
continuations that re-run a captured prefix under a different enclosing context. That
argument is correct and independently checkable against
`ContextEffect.handle(tag, ifUndefined, ifDefined)`: `Env.run` uses the transform form
(`Env.scala:98`, `_.union(env)`) and `Local.let` uses it too (`Local.scala:131`), so this
is the common case, not a corner. Role A also needs *order* as its core semantics:
"innermost binding wins" is the shadowing rule, and it is nesting order, not a key
property. Role A is a scope stack. It is not a map and should not be one.

**Role B, the resolved boundary snapshot.** What crosses a fork. Fully resolved (no
transforms left), noninheritable bindings filtered out, at most one entry per tag. This is
the value `Isolate.internal.runDetached` hands to a detached computation
(`Isolate.scala:107-109`), the value `Fiber` passes at four call sites
(`Fiber.scala:174, 750, 793, 905`), and the value `IOTask` carries
(`IOTask.scala:19, 188, 196`). This one genuinely is a keyed map, has no ordering
semantics beyond one entry per tag, and is the thing that is or will be user-facing
through the boundary API of item #13.

So: **#19 re-scopes to Role B**, exactly as #18's O-2 proposes as its first option, and
this document recommends taking that option rather than the two-types fallback. The
reason to prefer it is not that it is less work; it is that Role A cannot be a keyed map
at all, so preserving #19 as written would mean keeping a representation that is wrong
for the job Role A does.

One consequence worth stating: after #18 lands, Role B's read path (a fork's inherited
bindings) is on the hot path for any forked computation that reads context, so Role B is
not merely a fork-time construction cost. Its read profile matters and section 7 measures
it.

---

## 2. The operation profile

### 2.1 The key universe is five tags, and it is closed

Grepping every production `ContextEffect` subtype across the repository (excluding tests)
returns exactly four:

| effect | declaration | key tag | value type |
|---|---|---|---|
| `Env[+R]` | `Env.scala:39` | `Tag[Env[Any]]`, one tag for every `R` (`Env.scala:152`, `erasedTag[R] = Tag[Env[Any]].asInstanceOf[...]`) | `TypeMap[R]` |
| `Local.internal.State` | `Local.scala:117` | `Tag[State]` | `Map[Local[?], AnyRef]` |
| `Local.internal.NoninheritableState` | `Local.scala:118` | `Tag[NoninheritableState]` | `Map[Local[?], AnyRef]` |
| `Scope` | `Scope.scala:37` | `Tag[Scope]` | `Scope.Finalizer` |

Plus the internal `NoninheritableFlag` sentinel entry (`Context.scala:50, 58-59`). That is
a maximum of five entries, and the realistic distribution is 0 to 3: a program using `Env`
and `Scope` under a `Local.let` has three.

Two properties follow, and both are load-bearing for the recommendation.

**The keys are compile-time constants and are interned.** `Tag` is
`opaque type Tag[A] = String | Tag.internal.Dynamic` (`Tag.scala:39`), and the macro emits
a statically-derived tag as `Expr(encodedStr)` (`TagMacro.scala:65`), which compiles to a
`Literal` string constant. String literals go in the constant pool and are interned, so
`Tag[Scope]` obtained at two different call sites is the *same reference*. Every key
comparison in `Context` therefore hits `String.equals`'s identity fast path. Any candidate
structure that compares keys with `(k eq key) || k.equals(key)` gets reference equality in
practice. `Dict` and `OrderedDict` already write the comparison in exactly that form
(`Dict.scala:167`, `OrderedDict.scala:174`).

**`Context` needs exact-key lookup, not subtype search.** Every read site passes the same
tag the corresponding handler installed: `Env` uses one erased tag for all `R`, `Local`
uses one of two fixed trait tags, `Scope` uses `Tag[Scope]`. The current implementation is
already exact (`Context.scala:24, 41`, `self.contains(tag.erased)` and `self(tag.erased)`).
This independently answers item #18's open question O-3 in the affirmative: no downstream
effect binds a subtype and reads a supertype, because no downstream effect has more than
one tag per effect family. Subtype matching appears in `Context` only inside `inherit`
(`Context.scala:36`), where it is a predicate over entries, not a lookup.

This matters because `TypeMap.get`'s distinguishing feature is precisely the subtype
search fallback (`TypeMap.scala:33-41`), which `Context` does not need and would pay for.

### 2.2 Which operations are hot

The frequencies below are for a threaded-context kernel, which is what #18 produces and
what the old kernel already is.

| operation | frequency | evidence |
|---|---|---|
| `get` / `getOrElse` | once per context read | `ContextEffect.suspendWith`; after #18, `Kyo.readContext(ctx => ctx.get(tag))` (threading design 3.3) |
| `set` | **once per suspension crossing a handler**, not once per handler install | old kernel `ContextEffect.handle`'s `handleLoop` builds a `KyoContinue` whose `apply(v, context)` runs `context.contains(tag)` then `context.set(...)` on every resumption that crosses the region |
| `inherit` | once per fork | `Isolate.scala:109`, `context.inherit` |
| `isEmpty` | once per task construction | `IOTask.scala:196`, `if ctx.isEmpty then` picks the allocation-free `IOTask` over an anonymous subclass |
| `contains` | as a component of `getOrElse` and `set` | `Context.scala:25, 40` |

The `set` row is the correction that matters most. The brief describes `set` as "on
handler install"; in the old kernel it is per suspension-crossing, which is orders of
magnitude more frequent. Under #18 the equivalent is a cons per binding installation on
the composition path. Either way `set` is a hot write, so a representation whose write
allocates six to ten objects is not acceptable, and that is exactly what `TreeSeqMap.updated`
does (section 4.2).

The old kernel threads the context explicitly: `def apply(v: O[A], context: Context)`
(`KyoInternal.scala:59`), and every `map`/`flatMap`/`andThen`/`unit` continuation forwards
it unchanged (`kernel/Pending.scala:69-70, 105-106, 138-139, 171-172`). kernel2 today does
not, which is why `resolveContext` walks the chain (`Pending.scala:719`, carrying the TODO
at `Pending.scala:718`) and why `snapshotContext` exists as a second walking suspension
(`Pending.scala:587`).

One more current-code detail worth recording, because it is a free win independent of
every other decision: **three `Context` operations scan the map more times than they need
to.**

- `getOrElse` (`Context.scala:39-41`) does `contains` then `apply`: two scans, where the
  underlying map overrides `getOrElse` to do one (`Map.scala:276, 331, 433, 552`).
- `set` for a noninheritable effect (`Context.scala:48-52`) does two `updated` calls, so
  two allocations.
- The old kernel's `ContextEffect.handle` does `contains`, then `get` (which is
  `contains` plus `apply`), then `set`: four scans and one allocation where one scan and
  one allocation suffice.

---

## 3. Two structural facts that decide the `TypeMap` question

Both are independent of performance, and either one alone is sufficient.

### 3.1 `Context` is keyed by effect type; `TypeMap` is keyed by value type

`TypeMap`'s contract is that the key tag *is* the value's type:

```scala
opaque type TypeMap[+A] = TreeSeqMap[Tag[Any], Any]                       // TypeMap.scala:12
def get[B >: A](using t: Tag[B], ev: NotIntersection[B]): B               // TypeMap.scala:32
inline def add[B](b: B)(using inline t: Tag[B]): TypeMap[A & B]           // TypeMap.scala:57
```

`Context`'s contract relates key and value through the effect's type parameter:

```scala
def get[A, E <: ContextEffect[A]](tag: Tag[E]): A                         // Context.scala:43
def set[A, E <: ContextEffect[A]](tag: Tag[E], value: A): Context         // Context.scala:47
```

The `E <: ContextEffect[A]` bound is what recovers `A` from `E`. There is no way to
express that with `TypeMap`'s `Tag[B] -> B` relation.

The obvious repair, keying `Context` by the value type instead so `TypeMap` fits, is
closed off by existing code:

```scala
sealed private[kyo] trait State               extends ContextEffect[Map[Local[?], AnyRef]]
sealed private[kyo] trait NoninheritableState extends ContextEffect[Map[Local[?], AnyRef]] with ContextEffect.Noninheritable
```
(`Local.scala:117-118`)

Two distinct context effects, one value type. They must be distinguishable keys, and they
are distinguishable only by effect type. A value-keyed map collides them, and the
collision is not hypothetical: `Local.init` and `Local.initNoninheritable` produce locals
under these two tags respectively (`Local.scala:94, 110`), and a program using both would
have one silently shadow the other. Keying `Context` by value type is a correctness bug,
not a design preference.

The only remaining way to make `Context` literally a `TypeMap` is to store it as
`TypeMap[Any]` with a `private[kyo]` raw tier (`addRaw(tag: Tag[Any], value: Any)`,
`getRaw(tag: Tag[Any])`) and keep the typed signatures on `Context` as wrappers. That
type-checks, but it produces a public type whose public `get` is meaningless for this
content: `TypeMap[Any].get[B >: Any]` admits only `B = Any`, so the user-facing operation
the type exists for does nothing here. That is worse than a separate honest type, not
better.

### 3.2 kyo-data sits below kyo-kernel2 and cannot see `ContextEffect`

```
lazy val `kyo-data`    = ... .dependsOn(`kyo-stats-registry`)      // build.sbt:696-699
lazy val `kyo-kernel2` = ... .dependsOn(`kyo-data`)                // build.sbt:731-734
```

`ContextEffect` is declared in kyo-kernel2 (`kernel2/ContextEffect.scala:19`). So no type
defined in kyo-data can carry the `E <: ContextEffect[V]` bound. A kyo-data type can be
`Context`'s *backing* (keyed by `Tag[Any]`, values erased), and the typed relation lives in
kyo-kernel2 where `ContextEffect` is visible. That is the sharing that is available, and it
is the sharing worth having: the datastructure is written, tested, and benchmarked once in
kyo-data, and the kernel supplies the typing.

---

## 4. Datastructure analysis

### 4.1 The current backing is already the small-map fast path

This is the finding that reframes the item. `Context.empty` is `Map.empty`
(`Context.scala:19`), and `scala.collection.immutable.Map` specializes sizes 0 to 4 into
flat classes before it ever reaches `HashMap`:

- `EmptyMap` is a singleton with `contains(key) = false` and
  `getOrElse(key, default) = default` (`Map.scala:248-255`).
- `Map1` through `Map4` store keys and values in plain fields and implement `apply`,
  `contains`, and `getOrElse` as a chain of `==` comparisons with no hashing and no
  allocation (`Map.scala:272-276, 326-333, 427-433, 545-552`).
- `updated` allocates exactly one flat object per write and promotes in place:
  `Map1.updated` returns a `Map2`, `Map2.updated` a `Map3`, and so on
  (`Map.scala:281-283, 360-363`). Only the fifth distinct key spills to `HashMap`
  (`Map.scala:590`).

Combined with interned `Tag` keys (section 2.1), a `Context` read at the realistic size of
1 to 3 entries is one to three reference comparisons, zero allocations, zero hashing. A
write is one object.

**There is no performance headroom to recover inside `Context` itself.** Every candidate in
the brief is competing against a structure that is already close to the floor for this
profile. The risk in this item is entirely downside risk, and the acceptance bars in
section 7 are written to catch it.

Two caveats on the current code that are real and worth fixing regardless:

- `isEmpty` is `self eq empty` (`Context.scala:22`), which is identity, not emptiness. It
  happens to be correct today because `Map.empty` is a singleton and `filterNot` on a
  fully-filtered small map builds back to that same singleton, but it is a property of the
  builder, not a guarantee. `IOTask.apply` branches on it to avoid allocating an anonymous
  subclass (`IOTask.scala:196`), so a false negative costs an allocation per task and a
  false positive would be a correctness bug. A replacement should make this exact and keep
  it a single comparison.
- The `getOrElse` double scan and the noninheritable double `updated` from section 2.2.

### 4.2 `TreeSeqMap` measured against that

`TypeMap` is `TreeSeqMap[Tag[Any], Any]` created with `OrderBy.Modification`
(`TypeMap.scala:12, 113`). Reading the stdlib source (scala-library 3.8.4, matching
`build.sbt:11`):

```scala
final class TreeSeqMap[K, +V] private (
    private val ordering: TreeSeqMap.Ordering[K],
    private val mapping: TreeSeqMap.Mapping[K, V],
    private val ordinal: Int,
    val orderedBy: TreeSeqMap.OrderBy)          // TreeSeqMap.scala:48-52

private type Mapping[K, +V] = Map[K, (Int, V)]  // TreeSeqMap.scala:363
def get(key: K): Option[V] = mapping.get(key).map(value)   // TreeSeqMap.scala:139
```

Structural cost, per operation, at any size:

| operation | `TreeSeqMap` | current `Context` backing at n<=4 |
|---|---|---|
| `get` | `mapping.get` allocates a `Some`, `.map` allocates a second `Some`: **2 allocations**, plus a `HashMap` trie walk | 0 allocations, n reference compares |
| `contains` | delegates to `mapping.contains`, allocation-free (`TreeSeqMap.scala:165`) | 0 allocations |
| `updated` | `ordering.append` builds a patricia-trie path (`Bin`/`Tip`, `TreeSeqMap.scala:363+`), `mapping.updated(key, (o1, value))` allocates the `(Int, V)` tuple plus a `HashMap` path, then `new TreeSeqMap(...)`: **roughly 5 to 10 allocations** (`TreeSeqMap.scala:84-109`) | 1 allocation |
| empty | `EmptyByModification` singleton, so an `eq` check still works (`TreeSeqMap.scala:299-304`) | singleton |

`TypeMap.get` adds two costs on top:

```scala
def get[B >: A](using t: Tag[B], ev: NotIntersection[B]): B =
    def search: Any = ...                                      // TypeMap.scala:33-41
    if isEmpty && t =:= Tag[Any] then ().asInstanceOf[B]
    else self.getOrElse(t.erased, search).asInstanceOf[B]      // TypeMap.scala:42-43
```

`get` is not `inline`, and `search` is passed by name, which compiles to a `Function0`
allocation at the call site capturing `self` and `t`. It may be scalar-replaced by the
JIT; that is not something to design on. And `TreeSeqMap` does not override `getOrElse`,
so the call goes through `MapOps.getOrElse`, which is `get(key) match { ... }` and pays
the two `Option` allocations above.

**Candidate (a), `TypeMap` as-is, is rejected.** It replaces zero allocations per read
with two plus a closure, and one allocation per write with five to ten, on operations that
run per context read and per suspension crossing. There is no size at which it wins.

There is also a correctness wrinkle if `inherit` were implemented on `TypeMap` via
`filter`: `TreeSeqMap.newBuilder` defaults to `OrderBy.Insertion` (`TreeSeqMap.scala:313, 316-317`),
so `filter` silently downgrades a `Modification`-ordered map. `TypeMap.prune` already has
this bug (`TypeMap.scala:85`); it is unobservable today only because `prune` has no
production call site.

### 4.3 `Tag` as a key

`Tag`'s cost profile is favorable and does not constrain the choice.

- Equality as a map key is `String.equals` on interned literals, so the identity fast path
  fires (section 2.1). `Tag`'s own `=:=` is not used for map lookup, but where it is used
  it fast-paths on `(self eq that) || self.hashCode == that.hashCode` (`Tag.scala:80-86, 173-184`)
  with the note that `String.hashCode` is memoized.
- `<:<` (`Tag.scala:106-107`) falls through to `checkTypes`, which is a thread-slot array
  cache keyed by a mixed 64-bit pair hash (`Tag.scala:380-406`). A cache hit is a hash
  computation plus one array read. This is what `inherit`'s per-entry noninheritable
  predicate and `set`'s noninheritable test cost (`Context.scala:36, 49`).

The one thing to avoid is a structure that turns exact lookups into `<:<` calls. That is
what `TypeMap.get`'s search fallback would do for any `Context` key that missed, and
`Local.get` misses on every read when no `Local.let` is in scope, because it reads with a
default (`Local.scala:125`, `suspendWith(tag, Map.empty)`). The miss path is common, not
rare.

### 4.4 kyo-data already has the right structure

Candidate (c) in the brief, "a new small-map datastructure tuned for tiny sizes (linear
scan over parallel arrays up to N, spill to map beyond) that `TypeMap` could adopt as its
backing", already exists in kyo-data, twice:

```scala
opaque type Dict[K, V]        = Span[K | V] | HashMap[K, V]        // Dict.scala:66,  threshold 8 at :71
opaque type OrderedDict[K, V] = Span[K | V] | TreeSeqMap[K, V]     // OrderedDict.scala:70, threshold 8 at :74
```

`Span[+A] = Array[? <: A]` is a zero-cost opaque wrapper (`Span.scala:36, 184, 931`), so
the small representation is a single flat array holding keys in the first half and values
in the second. The read is a linear scan with the interned-key fast path:

```scala
if (k.asInstanceOf[AnyRef] eq kr) || k.equals(key) then Maybe(Span.apply(span)(n + i)...)
```
(`OrderedDict.scala:174-175`)

and returns `Maybe`, which is `opaque type Maybe[+A] >: (Absent | Present[A])` with
`Present[A] = A | PresentAbsent` (`Maybe.scala:12, 81`), so a present non-null value is
the value itself with no wrapper allocated. The small-path read allocates nothing, which is
the property `TreeSeqMap` lacks and `Map1`..`Map4` have.

The two differ in exactly the way that decides which to use where:

- `Dict` leaves iteration order unspecified above the threshold (`Dict.scala:16-18` and the
  `HashMap` branch).
- `OrderedDict` preserves insertion order **at every size** by using an insertion-ordered
  `TreeSeqMap` for the large representation (`OrderedDict.scala:8-18`), and documents the
  rule: a new key appends, updating an existing key keeps its position, removal preserves
  relative order.

`TypeMap` needs a defined order at all sizes, because its subtype-search fallback returns
the first matching entry and real `TypeMap`s exceed 8 (the existing `TypeMapBench` builds
roughly 50-entry environments). So `TypeMap`'s backing must be `OrderedDict`, not `Dict`.
`Context` needs no order and could use either; using `OrderedDict` for both keeps one
structure on the path.

The available `OrderedDict` surface covers everything both types need: `get` returning
`Maybe` (`:166`), `update` (`:198`), `remove` (`:233`), `contains` (`:192`), `size`
(`:112`), `isEmpty` (`:119`), `foreach` (`:276`), `foldLeft` (`:405`), and inline `filter`
/ `filterNot` (`:444, :472`).

Structural comparison for the write path, since that is where the current `Map1`..`Map4`
is strongest: `OrderedDict.update` on the small representation allocates one `Array` for
the replace case (`OrderedDict.scala:211-215`) and goes through `OrderedDictBuilder` for
the append case. That is one to two allocations versus exactly one for `Map1.updated`. The
sizes are comparable (a 2-entry span is an array header plus 4 references; a `Map2` is an
object header plus 4 references). This is the one place a migration could plausibly lose,
and it is a benchmark row, not an argument (section 7, `contextSet*`).

### 4.5 The `NoninheritableFlag` should become a bit, not an entry

The current trick makes `inherit` O(1) when nothing is noninheritable by testing for a
sentinel entry (`Context.scala:33`), and pays for it by writing a second map entry on
every noninheritable `set` (`Context.scala:49-51`). Item #18's design proposes deleting the
flag and filtering in one pass, on the grounds that a list scan is cheap at these sizes.

Neither is the best available option. The flag is a *property of the context*, and it
should be stored as one:

- Keep the O(1) `inherit` fast path, which the brief correctly identifies as a property any
  replacement must preserve.
- Stop the sentinel from occupying a key slot. Today it pushes a 3-binding context into a
  `Map4` and, more importantly, it appears in the snapshot a user or a fork boundary can
  observe, which is a fake binding in a value that item #13 intends to make user-facing.
- Charge nothing new: `set` already performs the `tag <:< Tag[ContextEffect.Noninheritable]`
  test on every write, inheritable or not (`Context.scala:49`). Recording the result in a
  boolean is strictly cheaper than writing a second map entry.

Concretely, for Role B this is one extra field alongside the backing; for Role A under
#18's frame list it is `val hasNoninheritable = (tag <:< Noninheritable) || next.hasNoninheritable`
computed once at cons time. `inherit` becomes `if !hasNoninheritable then self else filter`,
which is the same O(1) fast path with none of the key-space pollution. This is a concrete
improvement over both the current code and #18's proposal, and it should be recorded
against #18 as well since it changes that design's section 3.2.

---

## 5. The `TypeMap` that is already on the hot path is inside `Env`'s binding

This is the load-bearing observation for the whole item.

```scala
sealed trait Env[+R] extends ContextEffect[TypeMap[R]]                    // Env.scala:39
private def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]        // Env.scala:152

inline def use[R](using Frame)[A, S](inline f: R => A < S)(...) =
    ContextEffect.suspendWith(erasedTag[R]) { map =>
        f(map.asInstanceOf[TypeMap[R]].get(using tag, ni))                // Env.scala:130-131
    }
```

`Env.get` and `Env.use` both route through `use`, so `Env.scala:131` is the single physical
`TypeMap.get` call that every environment read in the codebase executes. A sweep of the
repository counts these `Env.get`/`Env.use` sites: 128 in kyo-prelude, 47 in kyo-browser,
22 in kyo-bench, 19 in kyo-combinators, and smaller counts across kyo-direct, kyo-test,
kyo-core, kyo-caliban, kyo-zio, kyo-actor, kyo-aeron, kyo-parse, kyo-stm, kyo-offheap.

So the cost of one environment read today is:

```
Context.getOrElse(Tag[Env[Any]], ...)   ->  1-3 reference compares, 0 allocations   (Map1..Map4)
TypeMap.get[R]                          ->  1 by-name closure + 2 Options + HashMap walk (TreeSeqMap)
```

The expensive half is already the `TypeMap`, and it is expensive for reasons that have
nothing to do with `Context`. Migrating `Context` into `TypeMap` would nest a `TypeMap`
inside a `TypeMap` and add the outer one's cost to the inner one's. Fixing `TypeMap`'s
backing removes the inner cost from a path that runs on every `Env.get` in the codebase,
and it does so without touching the kernel at all.

That is the direction with the leverage, and the item as written points away from it.

---

## 6. Recommended direction

### 6.1 Change 1: `TypeMap`'s backing becomes `OrderedDict`

```scala
opaque type TypeMap[+A] = OrderedDict[Tag[Any], Any]
val empty: TypeMap[Any] = OrderedDict.empty
```

Operation by operation:

- `get`: replace `self.getOrElse(t.erased, search)` with `self.get(t.erased)` returning
  `Maybe`, matching on `Present`/`Absent` and falling into `search` only on `Absent`. This
  removes the by-name closure and both `Option` allocations on the hit path, and it removes
  them at every size, not only the small one.
- `add`: `self.update(t.erased, b)`.
- `union`: keep the two empty fast paths (`TypeMap.scala:70-71`); the merge becomes a fold
  of `that` into `self` so `that` still wins on a duplicate key, preserving the current
  semantics (`TreeSeqMap.concat` at `TreeSeqMap.scala:265-286` lets the right-hand value
  win, and `Env.runAll` and `Layer`'s `And` both rely on it: `Env.scala:98`,
  `Layer.scala:297-301`).
- `prune`: `self.filter(...)`, using `OrderedDict`'s inline `filter` (`OrderedDict.scala:444`).
  This also fixes the existing `OrderBy` downgrade bug noted in section 4.2, since
  `OrderedDict` has one order mode.
- `search`: unchanged in shape, iterating in insertion order.
- `size`, `isEmpty`, `show`: direct delegation.

**The one user-visible semantic change** is the ordering rule for the subtype-search
tie-break. `TreeSeqMap` with `OrderBy.Modification` currently means "first inserted wins,
and re-adding an existing key moves it to the tail". `OrderedDict` is insertion-ordered:
re-adding keeps the key's position. `TypeMapTest`'s `".get" > "deterministic"` test
(`TypeMapTest.scala:121-201`) encodes the current rule directly, including the block
commented "Moving the entries to the end shifts them all forwards one place", and that test
will fail under the new backing and must be rewritten against the new rule.

The evidence that this is safe to change, from a full sweep of every `TypeMap` call site in
the repository:

- No production site can reach the multi-candidate case. `Env` stores under the static
  `Tag[R]` and reads with the same tag, so every ordinary read is an exact hit.
  `Layer.init`'s macro rejects ambiguity at compile time (`LayerMacros`,
  `GraphError.AmbiguousInputs`), so a layer-built environment cannot hold two entries
  matching one `get`. kyo-actor's `Subject[A]` is invariant, so nested actors' subjects are
  never in a subtype relation.
- `LayerTest`'s one genuine subtype scenario asserts `env.size == 1` (`LayerTest.scala:274`),
  so only one candidate is present.
- `show` sorts, so it is order-independent (`TypeMap.scala:106`, and `TypeMapTest.scala:307-321`
  expects an alphabetically sorted string, not an insertion-ordered one).
- `TypeMap`'s opaque type means nothing outside `TypeMap.scala` can iterate it or see the
  representation; `TreeSeqMap` appears in no other module.
- `prune` already downgrades to `OrderBy.Insertion` today, so the type is already
  inconsistent about which rule it follows.

The honest characterization is that the current rule is an incidental property of the
chosen backing rather than a designed contract, and the migration is the moment to make it
a designed contract. It should be stated in `TypeMap`'s scaladoc, which currently says
nothing about ordering at all. This is a public-API semantics change and is open question
O-B in section 9.

### 6.2 Change 2: `Context` keeps its own type, gains a public surface, shares the backing

`Context` stays a kyo-kernel2 type, because only kyo-kernel2 can see `ContextEffect`
(section 3.2), and it keeps the typed signatures that recover the value type from the
effect type (section 3.1). What changes:

- It stops being `private[kyo] opaque type Context = Map[Tag[Any], AnyRef]`
  (`Context.scala:14`) and becomes a public, documented type with scaladoc, which is the
  substance of "the context is a user-facing type". The boundary API of item #13 is what
  makes this visible to users.
- Role A (in-flight scope stack) takes item #18's frame list, with the noninheritable bit
  from section 4.5 replacing the sentinel entry.
- Role B (the resolved boundary snapshot) is backed by `OrderedDict[Tag[Any], AnyRef]`,
  the same kyo-data structure `TypeMap` now uses.
- The typed surface stays exactly what it is today (`get`, `getOrElse`, `contains`, `set`,
  `inherit`, `isEmpty`), with the double-scan fixes from section 6.3.

On the performance case for Role B specifically, honesty requires a caveat. If #18 lands,
`resolve` already produces a `Const`-only frame list, and at 0 to 3 entries a cons-list
walk and a `Span` scan are close enough that the difference will be inside the noise band
of any benchmark that is not measuring the structure in isolation. The measurable
differences are on the construction side: a snapshot of n bindings is one array rather than
n cons cells, and `inherit`'s filtering rebuild is one array rather than n cons cells. So
the argument for `OrderedDict` at Role B is primarily that it makes the boundary value a
real shared datastructure with a real API instead of an exposed chain of internal frame
classes, with a small allocation win at fork time. It is not a large performance win, and
it should not be sold as one. If the user weighs the extra concept as not worth it, keeping
Role B as the resolved frame list is a defensible outcome and the rest of this document
stands.

### 6.3 Change 3: the operations that scan more than once

Independent of every choice above, and worth doing first because they are small and
measurable:

- `getOrElse` (`Context.scala:39-41`): one scan instead of two, by delegating to the
  backing's own `getOrElse` (`Map.scala:276` and friends) or, with `OrderedDict`, by
  matching on the `Maybe` from a single `get`.
- `set` for noninheritable effects (`Context.scala:47-53`): one write instead of two, by
  recording the bit rather than writing a sentinel entry (section 4.5).
- A single `update`-style primitive for the handler's read-modify-write, so
  `ContextEffect.handle`'s `contains` / `get` / `set` sequence becomes one scan and one
  write instead of four scans and one write.
- `isEmpty`: make it exact rather than `eq`-based (`Context.scala:22`), while keeping it a
  single comparison, since `IOTask.apply` branches on it per task (`IOTask.scala:196`).

### 6.4 What item #19 becomes

Restated as a task list, in dependency order:

1. `TypeMap` backing swap to `OrderedDict`, with the `TypeMapTest` ordering test rewritten
   against the new documented rule and the `prune` order bug fixed as a consequence. This
   is independent of the kernel and can land before #18.
2. The `Context` operation fixes in section 6.3, which apply to the current representation
   and survive #18.
3. After #18 lands: `Context` made public with scaladoc, Role B backed by `OrderedDict`,
   the noninheritable sentinel replaced by a bit in both roles.

---

## 7. JMH rows and acceptance bars

All rows use the existing harness settings from
`kyo-kernel2/jvm/src/jmh/scala/kyo/kernel2/bench/KernelBench.scala` (`@Fork(1)`,
5 warmup and 5 measurement iterations, `Mode.AverageTime`, nanoseconds) and are run with
`-prof gc`, because the decisive quantity in this item is allocation per operation, not
wall time. Every row is run **before** any change, so the baseline is measured rather than
asserted, following the same rule item #18's section 6 adopts.

### 7.1 Datastructure rows

New file `kyo-bench/src/main/scala/kyo/bench/ContextBench.scala`, or an extension of
`TypeMapBench` if the two are kept together. These measure `Context` in isolation, with no
kernel around it, so a regression is attributable.

| row | workload | what it isolates |
|---|---|---|
| `contextGetHit1` / `3` / `5` | `get` on a context of 1, 3, 5 bindings, hitting | the read path across the `Map1`..`Map4` to `HashMap` boundary at 5 |
| `contextGetMiss3` | `getOrElse` with no matching binding, 3 bindings present | the miss path, which `Local.get` takes on every read with no `Local.let` in scope (`Local.scala:125`) |
| `contextSet1` / `3` / `5` | `set` of a new key at each size | write allocation, the row where `OrderedDict` could lose to `Map1`..`Map4` |
| `contextSetExisting3` | `set` of an already-present key | the replace path, distinct from append in `OrderedDict.update` |
| `contextSetNoninheritable` | `set` of a `Noninheritable` effect | the double-write today (`Context.scala:49-51`); must become a single write |
| `contextInheritClean3` | `inherit` with no noninheritable binding | the O(1) fast path the flag exists for; must stay O(1) and return `self` |
| `contextInheritFiltering3` | `inherit` with one noninheritable binding of three | the filtering rebuild |
| `contextIsEmpty` | `isEmpty` on empty and non-empty | `IOTask.apply` branches on this per task |

### 7.2 `TypeMap` rows

Extend the existing `kyo-bench/src/main/scala/kyo/bench/TypeMapBench.scala`, which already
carries the ZIO `ZEnvironment` comparison rows and a roughly 50-entry environment.

| row | workload | note |
|---|---|---|
| `getOneKyo` | existing, large (~50) environment | must not regress; this is the `TreeSeqMap` branch of `OrderedDict` |
| `getOneKyoSmall` | new, environment of 3 | the `Span` branch, where the win should be largest |
| `getKyo` | existing, 10 reads | composite read |
| `addKyo`, `addGetOneKyo` | existing | write path at both sizes |
| `getSubtypeSearchKyo` | new: `get[Super]` with several subtypes stored | the `search` fallback, which is where the ordering contract lives; the row that documents the tie-break |
| `unionKyo` | new: `union` of two small maps | `Env.runAll` and `Layer`'s `And` both go through it |

### 7.3 Composite rows

These prove the section 5 claim, which is the reason to do change 1 at all.

| row | workload |
|---|---|
| `envGetSmall` | `Env.run(x)(Env.get[X]).eval`, environment of 1 |
| `envGetLarge` | same with an environment of ~50 |
| `localGetBound` | `local.let(v)(local.get).eval` |
| `localGetUnbound` | `local.get.eval` with no binding, the default path |
| `scopeRead` | one `Scope` read under a `Scope` handler |

Each should be run against both kernels where an equivalent exists, following the
precedent item #17 set for cross-kernel comparison.

### 7.4 Acceptance bars

1. **`eagerMap5` does not regress and stays at 0 B/op.** Current baseline is 5.67 to 5.78
   ns/op depending on the run; the bar is the run's own confidence band. This is the guard
   that nothing from this item leaked into the eager path. Non-negotiable.
2. **`contextGetHit*` and `contextGetMiss3` allocate 0 B/op at every size.** This is the
   bar that mechanically rejects candidate (a): a `TreeSeqMap`-backed `Context` cannot pass
   it, because `TreeSeqMap.get` allocates two `Option`s unconditionally
   (`TreeSeqMap.scala:139`). It is also the bar an `OrderedDict`-backed `Context` must
   clear, and it should, since `OrderedDict.get` returns `Maybe` and a present non-null
   value allocates nothing.
3. **`contextSet1`/`3` allocate at most one object per set, and `contextSetNoninheritable`
   allocates no more than `contextSet3`.** Today the noninheritable case allocates two
   (`Context.scala:48-51`). If a candidate cannot meet the one-object bar at n<=4, it loses
   to `Map1`..`Map4` on the write path and the case for changing must be made on the read
   or fork rows instead, explicitly.
4. **`contextInheritClean3` allocates 0 B/op and is flat in the number of bindings.** This
   is the brief's stated requirement that the `NoninheritableFlag` O(1) property survive.
   The section 4.5 bit satisfies it; a per-entry `<:<` scan would not, and the row is what
   decides that.
5. **`contextIsEmpty` stays a single comparison, and `IOTask` keeps its no-subclass fast
   path.** Verify by allocation profile on a task-construction row, not by reading the
   code.
6. **`getOneKyoSmall` improves by at least the two `Option` allocations plus the closure
   (expect a drop from roughly 48 to 0 B/op); `getOneKyo` at ~50 entries does not regress.**
   The large case still uses `TreeSeqMap` inside `OrderedDict`, so parity is the bar there,
   not improvement.
7. **`addKyo` improves at both sizes.** `TreeSeqMap.updated` builds two tries and a tuple
   per write (`TreeSeqMap.scala:84-109`); the `Span` path builds one array.
8. **The kyo-versus-ZIO rows in `TypeMapBench` do not move in ZIO's favor on any row.**
   That comparison is already published in the benchmark and a regression there is a
   regression in a claim the project makes.
9. **`envGetSmall` improves by at least the `getOneKyoSmall` delta.** This is the row that
   confirms the section 5 composite claim rather than assuming it. If it does not, the
   model of where the cost sits is wrong and the direction should be re-derived before
   proceeding.
10. **`suspension`, `resumeFused`, `state10`, `stateMap10k`, `deepBind10k` within noise.**
    Nothing in this item should touch them; if one moves, find out why before continuing.

---

## 8. Alternatives rejected

**(a) `TypeMap` as-is as `Context`'s representation.** Rejected on both typing and
performance. Typing: sections 3.1 and 3.2, either of which is sufficient alone.
Performance: two `Option` allocations plus a by-name closure per read against zero today,
and five to ten allocations per write against one (section 4.2). Bar 2 rejects it
mechanically.

**(b) `TypeMap` with targeted `private[kyo]` additions (exact-key fast `get`, `filter`,
flag maintenance) and no backing change.** The additions are all achievable, but they do
not address the cost, which is in `TreeSeqMap` rather than in `TypeMap`'s API surface. An
exact-key fast `get` still lands on `TreeSeqMap.get`'s two `Option`s. And the typing
objections in section 3 are untouched by any amount of API addition. Rejected as
insufficient rather than wrong: the backing change in 6.1 is the part of this that is
worth doing, and it does not require `Context` to become a `TypeMap`.

**(c) A new small-map datastructure built for this item.** Rejected because kyo-data
already has it, twice (`Dict.scala:66`, `OrderedDict.scala:70`), with the same design the
brief describes (linear scan over a flat span up to 8, spill beyond) and with the
tuple-free, `Maybe`-returning API the hot path wants. Writing a third one would be a
duplicate to maintain and to benchmark. The correct move is to adopt `OrderedDict` as
`TypeMap`'s backing (section 6.1), which is candidate (c)'s substance delivered through an
existing type.

**(d) Keep the internal `Map` and expose conversions.** Rejected because it concedes the
goal without buying anything. The conversion would allocate at every boundary crossing,
and the value the user sees would still be a `Map` in a trench coat, including the
`NoninheritableFlag` sentinel entry appearing as a fake binding. Making `Context` a real
public type with the section 4.5 bit is both cheaper and more honest.

**(e) Key `Context` by value type so `TypeMap` fits.** Rejected as a correctness bug:
`Local.internal.State` and `Local.internal.NoninheritableState` are both
`ContextEffect[Map[Local[?], AnyRef]]` (`Local.scala:117-118`) and would collide
(section 3.1).

**(f) `Context = TypeMap[Any]` with a `private[kyo]` raw tier.** Type-checks and preserves
"the context is a `TypeMap`" literally, but the resulting public `get` is inapplicable to
the content (only `B = Any` satisfies `B >: Any`), so the type's defining operation does
nothing for this value. A public type whose main method is unusable on it is worse than a
separate honest type (section 3.1).

**(g) Materialize the in-flight context into a keyed map at composition time.** Rejected,
and item #18's section 7.2 has the argument: a transform-form binding's value depends on
the bindings outside it, which grow on every composition, so a value computed at
construction time goes stale, and memoizing per node is unsound under multi-shot
continuations. This is independently checkable, and the transform form is the common case
rather than a corner: `Env.run` (`Env.scala:98`) and `Local.let` (`Local.scala:131`) both
use it.

---

## 9. Open questions that need the user

**O-A. Does #19's approval survive the re-scope?** Item #19 is approved as a direction on
the premise that `Context` is a keyed map. Section 1 argues it is two things, only one of
which is a keyed map, and recommends re-scoping #19 to the boundary snapshot plus the
`TypeMap` backing swap. This is the same fork item #18 records as its O-2, and this
document recommends taking O-2's first option (re-scope) rather than its two-types
fallback, on the grounds that the in-flight role cannot be a keyed map at all. Confirm the
re-scope, or say the `TypeMap` migration should be protected as written and this analysis
should be redone against that constraint.

**O-B. The `TypeMap` ordering rule is a public semantics change.** Section 6.1 changes the
subtype-search tie-break from `OrderBy.Modification` (first inserted wins, re-adding moves
to the tail) to insertion order (first inserted wins, re-adding keeps position). No
production code and no other test depends on it, and `prune` already violates the current
rule, but `TypeMapTest.scala:121-201` encodes it deliberately enough that it should not be
rewritten without a ruling. Three options: adopt insertion order and document it in
`TypeMap`'s scaladoc (recommended, and the scaladoc currently says nothing about ordering
at all); preserve modification order by adding a `refresh`-on-update path to `OrderedDict`;
or declare the multi-candidate case unspecified and remove the test. Note that whichever is
picked, `TypeMap` should state its rule in scaladoc, which is a gap today regardless of
this item.

**O-C. Is the `OrderedDict` backing for the boundary snapshot worth the extra concept?**
Section 6.2 is candid that the read-path win at 0 to 3 bindings is small and the real
argument is API shape plus a modest fork-time allocation win. If the user's read is that a
resolved frame list is a good enough public shape, that is a defensible outcome and only
section 6.2 changes. The `TypeMap` backing swap (6.1) and the operation fixes (6.3) are
independent of this and should proceed either way.

**O-D. Should `Context` become genuinely public, or stay `private[kyo]` with the
user-facing surface being item #13's boundary type?** The brief's stated ideal is that the
context be a user-facing type. Section 6.2 assumes `Context` itself becomes public. The
alternative is that item #13 introduces the public boundary type and `Context` stays
internal behind it. This is a decision about the boundary API's shape, so it belongs with
#13; flagging it here so the two items agree on one answer.

**O-E. Sequencing.** The `TypeMap` backing swap has no kernel dependency and can land
first, independently of #18 and of the rest of this item. The `Context` work depends on
#18. Confirm the swap can go ahead on its own rather than waiting for the kernel track,
since it is the part with the measured leverage on existing code.

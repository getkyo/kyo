# Proposal: Kyo as the root of the node and arrow hierarchy

Status: draft for review. Applies to the prototype kernel under `kyo-kernel/shared/src/main/scala/kyo/proto/`.

## 1. Context: the prototype kernel and how it executes

The proto kernel is a minimal experimental kernel being migrated toward the reference implementation in `kyo.kernel` (same module). Its pending type is a union:

```scala
opaque type <[+A, -S] >: A = A | Arrow[Any, A, S]
```

A settled value inhabits the union directly through the `>: A` bound. A pending computation is an `Arrow[Any, A, S]`: today every computation node (`Kyo.Defer`, `Kyo.SuspendArrow`, `Kyo.SuspendContext`, `Kyo.SuspendContextDefault`, `Kyo.Handle`) extends `Arrow.Transform[Any, A, S]` through the shared base `Kyo[A, -S]`, whose default two-argument apply is the constant-arrow behavior: ignore the input, compose self with the continuation.

The execution machine (`Eval`) rolls and unrolls these values with a tight protocol:

- The loop carries two continuation registers (`contA`, `contB`) and pattern matches node classes to destructure values (the Defer peel, the suspend reify via `withCont`, the Handle region).
- Continuations are applied through the two-argument apply, `arrow(v, cont)`, with `head`/`tail` splitting normalized runs for per-site monomorphic delivery.
- Composition laws keep allocation at a measured floor: free-slot absorption on `Suspend.cont`, `Defer.contB`, and `Handle.cont`, plus the normalizing factories.

A sustained fusion campaign made the hot nodes single allocations fulfilling multiple roles: the map rescue record is its own transform (`contA = this`), `suspendWith` mints a suspension that is its own continuation, the `With` region variants mint a `Handle` that is the transform following it, and the eval fuses foreign re-entries and answerLoop rebuilds the same way. Every scenario in `protodemo.Main` (21 rows) pins results, allocation counts, and the execution log.

## 2. The problem

The merged nodes inherit their two-argument apply signature from `Kyo[A, -S] extends Arrow.Transform[Any, A, S]`: the arrow input is pinned to `Any`. An override cannot narrow a parameter, so every merged node receives `v2: Any < S4` and must recover its true input type at the seam (typed pattern plus `Nested.unnest`). This recovery has been ruled a hack: the node knows its input statically, and the type system should carry it.

Root cause: one class serves two roles with one apply. The value role (union membership) demands the input-blind shape; the transform role demands the precise input. With the input as a type parameter, one class gets exactly one instantiation of the parent, so the two demands cannot both be met.

## 3. Designs explored and rejected

- **Separate Kyo from Arrow entirely** (the reference's shape: their `Kyo` is not an arrow, merged nodes mix in `Step`). Rejected: the proto's machine leans on nodes being arrows (registers, cont slots, constant-arrow composition), and the separation forces the largest migration.
- **Input type parameter on `Kyo`** (`Kyo[-In, A, -S]`, union arm `Kyo[Nothing, A, S]`). Works, but was judged the wrong shape, and the wide arm variant (`Arrow[Nothing, A, S]`) is unsound: it admits typed continuations as values, which the eval would apply with `()`.
- **Three-parameter root** `Pending[A, B, S]` with `<[+A, -S] = A | Pending[Any, A, S]`. The arm is sound (contravariance admits exactly the input-blind nodes, the same set as today), but a merged node must then instantiate the root at `Any` to be a value, and Scala forbids inheriting a generic trait at two instantiations (`extends Kyo[C, S] with Arrow[A, C, S]` inherits `Pending[Any, C, S]` and `Pending[A, C, S]`: rejected by the compiler). The typed apply is unreachable: the design renames the problem.

The resolution is the one place Scala lets a single class answer the input question differently from what a parent fixed: a type member.

## 4. The proposal

Root `Kyo[A, S]`, with `Pending` and `Arrow` extending it:

```scala
sealed trait Kyo[+A, -S]:
    type In
    def frame: Frame
    def apply[C, S2](v: In < S2, cont: Arrow[A, C, S2]): C < (S & S2)
    def chain[C, S2](a: Arrow[A, C, S2]): Arrow[In, C, S & S2]
    type X
    def head: Arrow[In, X, S]
    def tail: Arrow[X, A, S]

/** A pending computation: the union's node arm. Membership is independent of In. */
sealed trait Pending[+A, -S] extends Kyo[A, S]

/** The continuation currency. The lower bound is the variance-legal spelling for a
  * contravariant parameter; concrete classes pin the member exactly. */
sealed trait Arrow[-A, +B, -S] extends Kyo[B, S]:
    type In >: A

opaque type <[+A, -S] >: A = A | Pending[A, S]
```

Node classes extend both sides at their true input, pinning the member (their type parameters are invariant, so the exact alias is legal):

```scala
abstract class Defer[A, B, C, -S] extends Pending[C, S], Arrow[A, C, S]:
    type In = A
    ...

abstract class SuspendArrow[I[_], O[_], E, State, A, S]
    extends Suspend[E, A, S], Arrow[O[State], A, S]:
    type In = O[State]

abstract class Handle[E, A, B, C, -S, State] extends Pending[C, S], Arrow[B, C, S]:
    type In = B
```

Both parents instantiate `Kyo[C, S]` identically, which is legal; `In` resolves once. A merged node's inherited apply is then genuinely typed: the map record receives `v2: A < S4`, `suspendWith` receives `O[C] < S2`, the `With` regions receive `B < S4`. The `Any` seams, the recovery matches, and the `Nested.unnest`-on-pending spellings all disappear. `def contA = this` is an exact type, not a contravariance conformance.

A compile spike validates the variance spellings end to end (root with abstract `In`, `Arrow` with the lower bound, node classes with the exact alias, typed merged apply, typed self-reference, union membership, register-style delivery with a raw payload): it compiles on Scala 3.8.

## 5. What the machine keeps, what moves

**Kept unchanged**: the eval's registers and cont slots stay typed `Arrow[A, B, S]` with today's variance; node classes keep their parameter lists, so the eval's patterns are stable; the peel, the reify, `withCont`, `head`/`tail` delivery, the region protocol, the answer seams, the `Safepoint` gating, and all the merged-node fusions carry over. Nodes remain arrows, so everything that holds a node in a continuation position keeps working.

**Moves, forced by the arm being `Pending` rather than `Arrow`**:

1. **Bare arrows stop being values.** `Id`, `Chain`, and `Transform` extend `Arrow` only, which is the point: "not all arrows can be `<`" becomes structural. Consequences:
   - The constant apply (`Kyo`'s default: self-compose with the continuation) must produce a value: `this.chain(cont)` yields a `Chain`, no longer a member, so the default body becomes `Effect.defer(this, cont)`. Count-neutral: a `Defer` allocates where a `Chain` did.
   - `<.chain`'s pending arm routes through `Effect.defer(self, cont)` instead of `self.chain(cont)`, and `Effect.defer` becomes the normalizing factory carrying the free-slot laws (suspend cont-slot absorption, the Handle slot fill, the Defer rebuild with the self-fulfilling guard). The per-node `chain` overrides are deleted, which also resolves a latent role ambiguity: `chain` is continuation composition and stays the Chain/identity law; value composition is the factory's job.
   - The `Arrow(...)` factory's products (`lazily` in the demo) are no longer values: the by-name carrier becomes the reference's `Effect.defer`/`deferInline` port (a `Defer` that is its own step; its `unitValue` needs no cast here because of the `>: A` bound).
   - The eval's value-position `Transform` and `Chain` arms become type-enforced dead code and are removed; the `bug` arms tighten.
2. **`fromKyo` becomes `(kyo: Pending[A, S]): A < S`**, the reference's own signature shape, and every pending test becomes a single `isInstanceOf[Pending[?, ?]]`, the reference's own test.
3. **`Function1` leaves the root** (it cannot be parameterized by a member); the one-argument apply moves to `Arrow`, where its parameter is the trait's `A`.

**Expected measurement**: allocation counts stay at the current floors; kinds shift where `Chain` values become `Defer` records and `lazily` transforms become fused defer records, one for one. The 21 demo rows are the acceptance gate: results identical, counts identical modulo the named kind shifts, zero unfused applies.

## 6. Risks and open questions

1. **Path-dependent `In` at delivery sites.** `k.head` is `Arrow[k.In, X, S]` with `k.In >: B` known from `k: Arrow[B, C, S]`; passing a `B`-typed payload conforms through the lower bound and the covariant slot of `<`. The spike covers the register-style case; the eval's real sites (`k.head(nv, k.tail)`, `contA(res, contB)`, `cont.head(f(...), cont.tail)`) should be validated early in the implementation, as the first checkpoint.
2. **The lower bound is looser than an alias on the `Arrow` view.** Through an `Arrow[A, B, S]`-typed reference, `In` is only known to be a supertype of `A`. All current call sites pass values at `A` or pending `A < S2`, which conform; sites that needed the exact equality would need the concrete class in hand. None are known.
3. **`Suspend`'s `In`.** `Op` is currently a type member; under the proposal each concrete suspend class pins `In` (and `Op` can alias it or be replaced by it). The eval's existential patterns (`res.Op`) are unaffected.
4. **Naming.** The root takes the name `Kyo`, which currently names the node namespace object; the object can keep the name (companion-style) or the classes can rehome. The union type `<` keeps its name; `Pending` names the node arm.
5. **Sealing.** `Kyo` and `Pending` sealed in one file with the node classes and `Arrow` requires the current single-file layout of `KyoInternal.scala` plus `Arrow.scala` to merge or the seals to relax to `private[kyo]` constructors; to be decided at implementation.

## 7. Migration inventory

- `Arrow.scala`: root trait `Kyo` (protocol, `In`, `frame`), `Pending`, `Arrow` (currency, one-arg apply, `Function1` decision), `Id`/`Chain`/`Transform` under `Arrow`.
- `KyoInternal.scala`: node classes re-parent (`Pending` plus `Arrow` at their true inputs, `type In = ...`), constant apply via `Effect.defer`, chain overrides deleted, `short` helper arity updates.
- `Effect.scala`: `defer` gains the normalizing free-slot laws; the by-name `defer`/`deferInline` port.
- `Pending.scala`: union arm, `fromKyo`, pending tests, merged map-family nodes lose their recovery seams (typed applies).
- `Eval.scala`: value-`Transform`/`Chain` arms removed, pending tests retyped, merged nodes typed, pattern arities checked.
- `ArrowEffect.scala`: `suspendWith` and the `With` variants typed.
- `Loop.scala`: pending tests retyped; the re-entry arrows unchanged.
- `Main.scala`: `lazily` via `Effect.defer`.

Verification at each checkpoint: JVM demo run, 21 rows, results and counts against the recorded baseline.

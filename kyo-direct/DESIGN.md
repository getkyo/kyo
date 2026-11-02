# kyo-direct: an own lowering for `direct`, `.now`, and `.later`

Status: design for the reimplementation of kyo-direct without dotty-cps-async. The user-facing surface stays, with the two deliberate changes listed under "Surface". The lowering core this design describes is also the core the `.kyo` language (`kyo-lang`) is built on; what the language needs from it, and what the language still has to settle on its own side, is recorded here so the two stay one implementation.

## Objective

Keep the `direct { ... }`, `.now`, `.later` paradigm as users know it, and replace the implementation underneath with a lowering owned by this repository:

- `direct` stays a `transparent inline` macro. The block's effect row is inferred from the markers inside it, and the block's type is the lowered `A < S`.
- `.now` sequences an effect and exposes its result; `.later` keeps an effect pending as a value. Both stay non-transparent `inline` methods whose expansion is an error, so a marker that survives lowering is a compile error at its site, and both are errors outside a block.
- The rejections and their diagnostics stay: `var`, `lazy val`, nested `class`/`trait`/`object`, `def` or lambda containing `.now`, `try`, `throw`, `synchronized`, mutable field reads through a selection, a bare `A < S` statement, `.now` in a by-name argument, `.now` inside a `Stream` lambda, nested `.now` inside `.later`.
- The shift-aware higher-order methods keep working: `.now` inside a lambda passed to a recognized method on a recognized receiver runs per element through Kyo's traversals, with the receiver's collection type preserved and multi-shot semantics intact.
- dotty-cps-async, `KyoCpsMonad`, the `AsyncShift` classes, and the `doctestFreshDriver` workaround in the build disappear.

The lowering core is written once, parameterized by the markers, the boundary kinds, and a policy. kyo-direct uses it with `.now`/`.later` and the permissive policy; kyo-lang uses it with its plugin-planted markers and the strict policy. Control-flow rules live only here.

## Surface

Unchanged: the names, types, and diagnostics of `direct`, `now`, and `later`; a pure block still has type `A < Any`; `TestSupport.runLiftTest` keeps working, and with it every suite that goes through it.

Two changes, both made on purpose and both reflected in the README in the milestone that ships them:

1. **Shift recognition is by resolved symbol, not by name.** A method is a shift when its resolved symbol is, or overrides, a symbol in the `Shifts` table. A custom method that happens to be called `map` on a supported receiver type is no longer rewritten; a class overriding `IterableOps.map` still is. The README currently documents name matching; the sentence changes with the implementation.
2. **A nested boundary used as a bare statement is rejected.** Today a nested `direct` in statement position is skipped by the validator and its effects are dropped silently. The new rule rejects any pending-typed expression in statement or value position that is under no marker, nested boundaries included, with the existing "must explicitly use either .now or .later" message.

## What the current implementation does, as the tests pin it

Items marked "pinned" have a test in `shared/src/test/scala/kyo` today and that test runs unchanged. Items marked "documented" are in the README or the whitelist with no test; the milestones add their tests before claiming them.

| Family | Behavior | Status |
|---|---|---|
| Blocks and bindings | `val x = e.now` binds once; later references reuse the result. Statements run in order. A discarded `e.now` still runs. Nested blocks, blocks inside tuples, `val (a, b) = (x.now, y.now)` (a refutable pattern, desugared to a `match`). | pinned: `BlockTest` |
| Conditionals | Effectful condition, effectful branches, `val` bindings inside a branch, an `if` with an implicit `()` branch. | pinned: `IfTest`, `PreludeTest` |
| Boolean operators | `&&` and `||` with effectful operands short-circuit: the right operand runs only when the left does not decide. | pinned: `BooleanTest` |
| Pattern matching | Effectful scrutinee, effectful case bodies, pure guards, `val` bindings inside case bodies, a pattern-bound pending identifier used through `.now`, the refutable `val Some(a) = e.now`. | pinned: `PatMatchTest` |
| Loops | `while` with effectful condition and body, compound `&&` conditions, `while true` ended by `Abort`, loops inside nested blocks with `Abort`. Reads of an enclosing local `var` through a plain identifier are allowed; only a selection of a mutable member is rejected. | pinned: `WhileTest`; stack safety: documented, test added in M1 |
| Nested markers | `f(x.now).now` binds inner then outer. `.later` inside `.later` is fine. `.now` inside `.later` is rejected. `.later` appears inside lambdas and inside local `def`s and is erased there. | pinned: `HygieneTest`, `StreamTest`, `CollShiftMethodSupportTest` (`fold`) |
| Handlers | A handler receives a nested `direct` block and the handler call takes `.now`. Strict parameters: `Env.run`, `Var.run`, `Emit.run` (with `Reducible` evidence), `Random.withSeed`, `Console.withOut`, `Choice.run`. By-name parameters: `Abort.run` (with `ConcreteTag` and `Reducible`), `Meter.run`, `Fiber.initUnscoped` (with `Isolate` and `Reducible`). Lambda position: `Poll.fold`. The inner block is its own boundary; handled effects do not reach the outer row. | pinned: `PreludeTest`, `CoreTest`, `WhileTest` |
| Shifts on collections | `.now` inside the lambda of `map`, `flatMap`, `collect`, `collectFirst`, `find`, `filter`, `filterNot`, `withFilter`, `dropWhile`, `takeWhile`, `partition`, `partitionMap`, `span`, `fold`, `foldLeft`, `foldRight`, `groupBy`, `groupMap`, `groupMapReduce`, `exists`, `forall`, `count`, `maxByOption`, `corresponds`, `foreach`, `tapEach`, `scanLeft`, `scanRight` on `Seq`, `List`, `Vector`, `Chunk`, `Iterable`, a one-shot `Iterable`, and `Set`. The collection type of the result is preserved. A one-shot iterable is consumed once. `Choice` inside `map`, `flatMap`, `foldLeft` yields every combination. A pure `def` inside a shifted lambda is allowed. | pinned: `CollShiftMethodSupportTest`, `ShiftTest`; `flatten`: documented |
| Shifts on containers | `Maybe`: `map`, `flatMap`, `filter`, `filterNot`, `exists`, `forall`, `foreach`, `collect`, `fold`, `orElse`, `getOrElse`. `Result`: `map`, `flatMap`, `filter`, `exists`, `forall`, `foreach`, `fold`, `orElse`, `getOrElse`, and `Panic` short-circuiting every operation with its identity kept. `Option`: `map`, `filter`, `orElse`, `getOrElse`. `Try`: `filter`, `orElse`, `getOrElse`. The by-name argument of `orElse`/`getOrElse` may contain `.now`. | pinned: `ShiftMethodSupportTest`; `Option`/`Try` `flatMap`, `exists`, `forall`, `foreach`, `collect`, `fold`, `filterNot`, `recover`, `recoverWith`, and `Either`: documented |
| Streams | `.now` inside a `Stream` lambda is rejected with the lazy-structure diagnostic. `.later` inside the lambda and a nested `direct` inside the lambda are allowed. `.now` on a whole `Stream` expression works. | pinned: `StreamTest`, `PreludeTest` |
| Opaque receivers | `maybe.now.fold(...)` and `maybe.now.contains(...)` where the receiver type is opaque. | pinned: `HygieneTest` (#993) |
| Rejections | The list in the objective, each with its message. `return` and a `for` over a non-monadic value fail with the compiler's own errors. | pinned: `HygieneTest`, `ShiftHygieneTest` |
| Type contracts | A block's row is exactly the union of its markers' rows. `.now` whose expected type widens the result (`val n: Long = Sync.defer(7).now`) fails today because the extension's `A` is bound by both the receiver and the expected type; the test records the limitation and this design does not change it. | pinned: `HygieneTest` |
| Production use | kyo-examples' ledger uses `direct` for `Env`, `Async`, `Abort`, and `Sync` compositions, outside package `kyo`. `TestSupport.runLiftTest` is a main-source `transparent inline` in package `kyo.internal` that expands `direct`. | compiles |

Observations that shape the design:

- **Why nested boundaries type.** `direct` is `transparent inline`, and the compiler expands transparent inline calls while typing the expression that contains them. A nested `direct` in a handler argument is therefore already the lowered `A < S'` when the handler's type arguments are inferred, for strict and by-name parameters alike. `.now` and `.later` are not transparent, so they survive to the enclosing macro, which is what lets it find them. Both properties are load-bearing and kyo-lang's boundary must keep them. This proves user-placed boundaries; it says nothing about placing a boundary automatically, which is kyo-lang's open question (see "What kyo-lang needs").
- **Why a lambda with `.now` is rejected outside a shift.** Not because the lambda could not be rebuilt; shifts do rebuild lambdas. The receiving call and everything after it were typed against the lambda's original result type, and the macro cannot retype them. A shift is the case where the receiving call is replaced by one whose result shape is known.
- **`.later` is the explicit computation value.** It stays, and it is erased wherever it appears inside a boundary.
- The opaque-receiver preparation in today's `impl` (`hasOpaqueNow`) worked around dotty-cps-async; the new lowering binds every `.now` result to a fresh local anyway. The #993 test decides whether anything more is needed.
- The validator and the transformer today agree on what a shift is only by construction (both match names). In the new design `Validate` asks `Shifts`.

## Architecture

```text
direct { body }                                 .kyo definition body (kyo-lang)
   |  user-written .now / .later                    |  plugin-planted markers and boundaries
   v                                                v
Validate(markers, shifts, policy)   restrictions; strict policy adds admission and construct vetoes
Erase                               pass-through markers removed everywhere in the boundary
Row                                 effect row from the sequencing markers of this boundary
Lower                               the reduction rules, producing a Term of type A < S
Shifts + ShiftRuntime               resolved-symbol substitutions and the traversals Kyo lacks
```

Files, all under kyo-direct:

| File | Responsibility |
|---|---|
| `Direct.scala` | The public surface: `direct`, `now`, `later`, their outside-block diagnostics. Calls the core with kyo-direct's markers, the `direct` boundary kind, and the permissive policy. |
| `internal/Markers.scala` | The marker description the core is parameterized by: an extractor for sequencing markers (yielding the computation term and its row), an extractor for pass-through markers, and the boundary kinds. Extractors rather than symbol sets, so a marker can be a method application (`now`), an overload (kyo-lang's pending `force`), or a conversion application if kyo-lang needs one. |
| `internal/Policy.scala` | The policy: construct allowances (local `def`, `match`, `while`, pattern `val`), the admission hook for resolved symbols, and whether a pure boundary lifts to `A < Any` or keeps its type. |
| `internal/Validate.scala` | The restriction checks and diagnostics, content unchanged, parameterized by markers, shifts, and policy. |
| `internal/Lower.scala` | Erasure, the row, the reduction rules, joins, owners, frames. |
| `internal/Shifts.scala` | The substitution table: resolved method symbol and receiver family to the replacement call, how its type arguments are computed, and its result shape. |
| `internal/ShiftRuntime.scala` | The traversals Kyo does not provide, written on `Kyo.foldLeft`, `Kyo.shiftedWhile`, and `Loop`, for collections and for `Option`, `Try`, `Either`, `Maybe`, and `Result`. |
| `internal/TestSupport.scala` | Stays: `runLiftTest`, used by four suites. |
| `internal/Trees.scala` | Stays: the traversal helpers. |

Deleted with the dependency: `AsyncShift.scala`, `ChunkAsyncShift.scala`, `ListAsyncShift.scala`, `KyoCpsMonad.scala`.

### The macro and the typer

`direct` is a `transparent inline` method whose right-hand side is a splice. The compiler expands it while typing the enclosing expression, so the lowered type `A < S` is what the enclosing code sees. Everything below runs inside that expansion on the already-typed body. Nothing re-types a tree: the rules rebuild only the parts that contain markers, through `Apply.copy`, `Select.copy`, `If.copy`, `Match.copy`, fresh `ValDef`s, and lambdas built with `Lambda(owner, MethodType, body)` whose types come from the typed subterms. Where a replacement call needs explicit type arguments, they are computed from those types and the call is built with `Select.overloaded`, which resolves the Kyo overload from the argument types.

## Erasure, then the row

**Erasure.** Before anything else, every pass-through marker inside the boundary is replaced by its argument: `later(e)` becomes `e`. This runs over the whole boundary tree, including lambda bodies, local `def` bodies, by-name arguments, and nested boundaries' surroundings, because `later` is an inline macro that errors if it survives, and today's corpus places it inside lambdas (`StreamTest`), inside local `def`s (`CollShiftMethodSupportTest` `fold`), and inside another `later` (`HygieneTest`). A sequencing marker inside a pass-through marker is rejected before erasure, with today's message. kyo-lang's pure `force` is a pass-through marker and is erased the same way.

**The row.** The row of a boundary is the intersection of the rows recorded by every sequencing marker inside it, excluding markers inside a nested boundary. Rows are deduplicated structurally (`=:=`), and an `AndType` contributed by a marker is split before deduplication. The order is source order; `A & B` and `B & A` are the same type. A boundary with no sequencing marker is pure: `direct` returns `Kyo.lift[A, Any](body)`, zero cost and the type users see today; kyo-lang's body boundary returns the body unchanged, so a pure `.kyo` method keeps its ordinary type. That choice is the policy's `liftPure` flag.

## Reduction rules

Each rule states the typed shape it matches and the term it produces. `K[·]` is the continuation, the rest of the boundary as a function of the rule's result term. `bind(e, x => K[x])` is the generated composition `e.map(x => K[x])`, with the lambda owned by the generated code and the `Frame` passed explicitly from the marker's position (see Frames). `now(e)` is a sequencing-marker application. `S` is the boundary's row.

**R1. Subtree without a sequencing marker.** Left as it is, after references to lowered locals are rebound. The lowering never descends into a tree it has no reason to change. Pass-through markers inside it were already erased.

**R2. Sequence a marker.** `now(e)` becomes `bind(lower(e), x => K[x])`. The marker's own argument is lowered first, so `f(x.now).now` binds `x` and then the outer call.

**R3. Blocks.** Statements lower in order. `val x = rhs` becomes `lower(rhs)` with `K` receiving a fresh local bound to the result (`ValDef.let` under the current owner); every later reference to `x` is rebound to that local. A statement whose value is unused still lowers, and its continuation ignores the bound value. A `val` with a refutable pattern is lowered as Scala desugared it, a `match` whose scrutinee is the right-hand side (R7). A local `def` whose body has no sequencing marker is a pure closure and stays; with one it is rejected with today's message, or refused outright under a strict policy that disallows local definitions.

**R4. Conditionals.** `if c then a else b` lowers `c` first. If the conditional is the last expression of the boundary, each branch lowers with the boundary's final continuation. Otherwise the branches join: each lowers with `K0 = x => Kyo.lift[A0, S](x)` where `A0` is the type the typer gave the `If` node, giving two terms of type `A0 < S` (a branch whose own row is smaller still conforms by the variance of `<`); the join is `If.copy(c', a', b')`, typed `A0 < S` by the reflect API from its branches, and the join is bound once into `K`. The continuation is never duplicated into branches.

**R5. Boolean operators.** `l && r` with a sequencing marker in `r` becomes `lower(l)` then the join `if l' then lower(r) else false`, bound into `K`; `||` is symmetric with `true`. With a marker only in `l`, R6 applies. These rules fire on the resolved `Boolean.&&` and `Boolean.||` symbols only, whose parameters are strict, which is why the by-name rule of R6 does not see them.

**R6. Applications.** For `f(a1, ..., an)` the receiver (or function value) lowers first, then each strict argument in order, each bound before the next is evaluated, and the call is rebuilt with the bound terms through `Apply.copy`. A pure strict argument that precedes a marker argument is bound to a local as well, so `combine(pure(), e.now)` evaluates `pure()` first. A by-name argument is never bound: with no sequencing marker it stays in place and stays delayed; with one, the call is first checked against `Shifts` (R9 covers the by-name arguments of `orElse` and `getOrElse`), and otherwise rejected with today's by-name message. Type applications, selections, ascriptions, and repeated arguments follow the same order.

**R7. Pattern matching.** `s match { cases }` lowers `s`, then each case: the pattern and its bound symbols are kept as they are, a pure guard stays a guard, the body lowers as a block. If the match is the last expression, each body ends with the final continuation; otherwise the bodies lower with `K0` as in R4 and `Match.copy` builds the join of type `A0 < S`, bound once into `K`. A guard containing a sequencing marker is rejected with a dedicated message, because evaluating it after the pattern and before fallback needs a rule that has not been written. Pattern-bound names keep their symbols, so a pending identifier bound by a pattern can still take `.now` in its body.

**R8. Loops.** `while c do b` becomes `Loop[Unit, Unit, S](())(_ => lower(c) then if c' then lower(b).map(_ => Loop.continue[Unit]) else Loop.done[Unit])`, with the `continue` and `done` overloads selected by symbol and the branch types both `Outcome[Unit, Unit] < S` through `Kyo.lift`. No value is threaded through the loop because `var` is excluded; every state the loop observes lives behind an effect. `Loop` is stack safe, and a test with a loop of a hundred thousand effectful iterations pins that. The loop is bound into `K` once.

**R9. Shifts.** A call whose resolved method symbol is, or overrides, an entry in `Shifts` for its receiver family, and whose function argument contains a sequencing marker, is rewritten: the lambda is rebuilt with `Lambda(owner, MethodType(params => resultType < S'), body')` where `body'` is the lowered body with the old parameter symbols substituted and moved subtrees re-owned, and the call becomes the entry's replacement with explicit type arguments; its result is bound into `K`. A pure lambda keeps the original call. A lambda with a sequencing marker passed to a method with no entry is rejected with today's message. The table:

| Receiver family (how `CC` is found) | Methods | Replacement | Type arguments | Result |
|---|---|---|---|---|
| `Iterable[A]` with `IterableOps[A, CC, CC[A]]` in its base types, `CC` read from that base type; also the concrete `List`, `Chunk`, `Set` overloads Kyo provides, chosen through `Select.overloaded` | `map`, `flatMap`, `foreach`, `filter`, `filterNot`, `collect`, `takeWhile`, `dropWhile`, `span`, `partition`, `partitionMap`, `scanLeft`, `groupBy`, `groupMap`, `foldLeft` | `Kyo.foreach`, `Kyo.foreachConcat`, `Kyo.foreachDiscard`, `Kyo.filter` (negated predicate for `filterNot`), `Kyo.collect` with the partial function lifted to `A => Maybe[B] < S`, `Kyo.takeWhile`, `Kyo.dropWhile`, `Kyo.span`, `Kyo.partition`, `Kyo.partitionMap`, `Kyo.scanLeft`, `Kyo.groupBy`, `Kyo.groupMap`, `Kyo.foldLeft` | `A` from the receiver's element type; `B` from the rebuilt lambda's result type (for `flatMap`, the element type of its `IterableOnce` base); `S` the lambda body's row | the receiver's collection type, through its `iterableFactory`, as Kyo's traversals already do; one-shot sources are materialized once by those traversals |
| same | `fold`, `foldRight`, `scanRight`, `count`, `exists`, `forall`, `find`, `collectFirst`, `maxByOption`, `corresponds`, `tapEach`, `groupMapReduce`, `flatten`, `withFilter` | `ShiftRuntime`: `fold` and `count` over `Kyo.foldLeft`; `foldRight` and `scanRight` over a reversed `Chunk`; `exists`, `forall`, `find`, `collectFirst` short-circuiting over `Kyo.shiftedWhile`; `find` and `collectFirst` converting `Maybe` to `Option`; `maxByOption`, `corresponds`, `groupMapReduce` over `Kyo.foldLeft`; `tapEach` as `Kyo.foreach` returning the element; `flatten` as `foreachConcat` through the conversion argument; `withFilter` as `Kyo.filter` followed by a pure `withFilter(_ => true)`, so the result is the `WithFilter[A, CC]` the typer gave the original call | as above | as the original method's result type |
| `Option`, `Try`, `Either` (and `LeftProjection`), `Maybe`, `Result`, by the receiver's dealiased type constructor | `map`, `flatMap`, `filter`, `filterNot`, `exists`, `forall`, `foreach`, `collect`, `fold`, `orElse`, `getOrElse`, `recover`, `recoverWith` where the container has the method | `ShiftRuntime` functions, one per container and method, with the semantics of today's `MaybeAsyncShift` and `ResultAsyncShift` (a `Result.Panic` short-circuits and keeps its identity) and of dotty-cps-async's `Option`/`Try`/`Either` runtime for the rest | `A` from the receiver, `B` and `S` from the rebuilt function | the container type |

A by-name argument of `orElse`/`getOrElse` with a sequencing marker lowers as a thunk `() => B < S` and the `ShiftRuntime` function takes it as such. A partial-function literal (`collect`, `collectFirst`, `Maybe.collect`) arrives as `Block(DefDef($anonfun, Match), Closure(PartialFunction))`; the rebuilt closure is a new method with the case bodies lowered to `B < S`, the `Bind` symbols re-owned to it, and a `Closure` whose type is `PartialFunction[A, B < S]`, which `ShiftRuntime` lifts to `A => Maybe[B] < S`.

**R10. Nested boundaries.** A nested `direct` inside the body is a value of type `A < S'` as far as the outer boundary is concerned; its markers belonged to it. Under a sequencing marker it is bound like any computation; under a pass-through marker it is a value. Not under a marker it is a bare pending expression and is rejected, in value and in statement position (the second surface change).

**R11. Lambdas.** A lambda whose body contains a sequencing marker and that is not the argument of a shift is rejected (today's message). A lambda whose body is a nested boundary, or contained only pass-through markers, is left alone by R1.

**R12. Streams.** A sequencing marker inside a lambda passed to a `Stream` method is rejected with the lazy-structure diagnostic, regardless of the method. Pass-through markers and nested boundaries inside such a lambda are fine. In practice such a `.now` never types: the lambda's expected result type `V2 < S2` binds the marker's `A`, so it reaches the validator as an unresolved selection named `now`, and the rule recognizes that shape too, which is what makes the diagnostic the one the user sees rather than the type error alone.

**R13. End of a boundary.** The last expression lowers with `K = x => Kyo.lift[A, S](x)`, and the boundary's type is `A < S`.

### Owners and hygiene

Every generated lambda is created with the generated method symbol as the owner of its body, every subtree moved into it has its owner changed to that symbol (`changeOwner`), and every parameter reference in a rebuilt lambda is substituted for the new parameter symbol. Fresh locals come from `ValDef.let` under the current owner. The build compiles with `-Xcheck-macros`, so an owner mistake fails this module's tests rather than a user's build. The hygiene corpus (shadowing, nested blocks inside marker arguments, pattern-bound names) runs on every platform.

### Frames

Every generated call that takes a `Frame` receives one explicitly: `<.map`, `Loop.apply`, every `Kyo.*` traversal, every `ShiftRuntime` function. None relies on implicit `Frame` search, because that search derives at the macro expansion position and refuses package `kyo` outside test files, and the one main-source expansion site in package `kyo` today is `TestSupport.runLiftTest`, through which four suites run. The frame is built from the sequencing marker's own position, so a trace through a block points at the `.now` that suspended. This needs one additive helper in kyo-data: a `private[kyo]` macro-side constructor that produces a `Frame` expression for a given `Position`, factored out of the existing derivation, with its own tests in kyo-data. The `runLiftTest` suites are the check that no implicit derivation remains.

### Diagnostics

The messages are those of today's `Validate`, since the tests pin them. One new message: an effectful guard (R7). The lambda rejection keeps today's "method definitions" wording until its tests change with it.

## What kyo-lang needs from the core, and what it still owns

The core is parameterized by:

- **Markers**: extractors for sequencing and pass-through markers. kyo-lang's `force` has a pure overload (pass-through, erased) and a pending overload (sequencing), selected by typing; both register through the extractors. Should kyo-lang adopt a conversion-based marker for value positions, it registers as a sequencing marker the same way; the core does not care what syntax produced the marker.
- **Boundary kinds**: a set, each with its own policy. kyo-lang has a method-body boundary and a field boundary; the field boundary rejects a non-empty row ("Effectful fields are not supported") where the method boundary lowers it. Both keep a pure body's type; `direct` lifts a pure body.
- **Policy**: the admission hook for resolved symbols (strict mode refuses what is outside the admitted surface) and construct allowances. kyo-lang's design keeps `match`, `while`, and local definitions rejected until each row has its conformance evidence, so the policy can veto a construct the core knows how to lower.
- **Shifts on pending-typed lambdas**: an entry fires not only when the lambda contains a sequencing marker but also, under a policy flag, when the lambda is already typed `A => B < S` with a non-`Any` row. kyo-lang sets the flag: its plugin wraps every lambda body in a boundary, so an effectful lambda arrives typed that way, and `chunk.map(f)` must still become `Kyo.foreach(chunk)(f)`. kyo-direct leaves the flag off: `xs.map(x => direct { ... })` deliberately builds a collection of computations today, and `Stream.map(x => direct { ... })` relies on it.

With these, kyo-lang's design rules land as follows. Rule 9 (effectful closures return computations) and rule 11 (effect-aware receivers keep their API) are plugin placement: the boundary in every lambda body gives the lambda its `A => B < S` type during typing, and a receiver that accepts such a function needs nothing more. Rule 10 (substitution) is R9 with the flag. Rule 8 (handlers) is R10 for a boundary the plugin placed.

What the core does not solve, and kyo-lang's design still has to: **placing boundaries by parameter kind**. A handler argument needs a boundary and no exposing marker; a strict value argument needs its effects exposed and bound before the call. The plugin runs before typing and cannot tell them apart, and the core sees only the result of the placement. The candidates are recorded in kyo-lang's design (a conversion-shaped marker inserted by the typer where a value is expected is the leading one) and are to be settled with fixtures there, after this reimplementation. Until then kyo-lang keeps rejecting handler arguments with effects, as it does today. The sentence "effectful closures land as a consequence" in an earlier draft was wrong in part: closures in lambda bodies do, handler arguments do not.

## Milestones

Each milestone is one local commit with every affected suite green and `-Xcheck-macros` on; nothing lands half-lowered. The core is developed behind a test-only entry point, `lowered { ... }`, a `transparent inline` defined in kyo-direct's test sources that calls the core's implementation method from main sources with kyo-direct's markers; `direct` itself switches in M3, which is when the module's suites become the gate.

**M1. Control flow.** `Markers`, `Policy`, erasure, the row, R1 to R8, R10, R11, R13, joins, owners, the frame constructor in kyo-data with its tests, `Validate` carried over and parameterized. Acceptance: a new `LowerTest` in kyo-direct exercising, through `lowered`, the block, conditional, boolean, application, pattern-matching, loop, nested-marker, nested-boundary, hygiene, and stack-safety cases, with concrete expected values and exact row checks.

**M2. Shifts and streams.** `Shifts`, `ShiftRuntime`, R9, R12, the pending-lambda flag. Acceptance: `LowerTest` cases for every entry of the table, including the container methods that are documented but untested today, one-shot and `Set` receivers, `withFilter`, partial-function callbacks, `Choice` multi-shot, and the stream rules.

**M3. Switch `direct` and delete dotty-cps-async.** `Direct.scala` calls the core; `AsyncShift`, `ChunkAsyncShift`, `ListAsyncShift`, `KyoCpsMonad` are removed; the dependency and `doctestFreshDriver` leave `build.sbt`; the two README sentences change; the statement-position nested-boundary test is added. Acceptance: every kyo-direct suite unchanged, the README doctests, and kyo-examples, on JVM, JS, Native, and Wasm. `lowered` and `LowerTest` stay as the core's own suite.

**M4. kyo-lang on the core.** `kyo.lang.internal.Body` becomes the strict body and field boundaries over the core, `kyo.lang.internal.Validate` becomes the admission hook, `Preparation` wraps lambda bodies in the body boundary, and the pending-lambda flag is on. kyo-lang's fixtures gain the effectful-closure and `Chunk.map` cases its design rules 9 to 11 call for, the driver suites stay green, and kyo-lang's `DESIGN.md` records the new status and the remaining boundary-placement question.

## Risks and how each is checked

- **Owner consistency** across moved and rebuilt lambdas: `-Xcheck-macros` on every test compile and the hygiene corpus.
- **Code size** from branch constructs: joins keep each continuation emitted once; a `LowerTest` case with nested conditionals inside a loop guards it.
- **Frames inside package `kyo`**: explicit frames everywhere; the `runLiftTest` suites are the check.
- **Overload selection for Kyo traversals**: `Select.overloaded` with explicit type arguments per receiver family; the `CollShiftMethodSupportTest` variants (`Seq`, `List`, `Vector`, `Chunk`, `Iterable`, one-shot, `Set`) are the check.
- **Multi-shot semantics**: the `Choice` cases in the shift suite and `PreludeTest`.
- **`.now` with a widening expected type**: unchanged limitation of the `now` extension's inference; its test stays as the record.
- **Handler type-argument inference**: unchanged mechanism; `PreludeTest`, `CoreTest`, `WhileTest`.

## Non-goals

Inferring boundaries inside `direct` blocks, admission inside `direct`, removing `.later`, and changing the names or types of `direct`, `now`, and `later`.

# kyo-direct: the lowering of `direct`, `.now`, and `.later`

Status: implemented. kyo-direct lowers `direct` blocks with a macro of its own, written on Kyo's kernel; dotty-cps-async is gone. The `.kyo` language (`kyo-lang`) is built on this same surface: its compiler plugin plants `direct` and `.now` where the language's rules call for them, and this macro does the rest. What the language needs from the macro, and what it still has to settle on its own side, is recorded at the end.

## Objective

Keep the `direct { ... }`, `.now`, `.later` paradigm as users know it, with an implementation that is direct and specialized to Kyo computations:

- `direct` is a `transparent inline` macro. The block's effect row is the union of the rows of the `.now` markers inside it, and the block's type is the lowered `A < S`.
- `.now` sequences an effect and exposes its result; `.later` keeps an effect pending as a value. Both are non-transparent `inline` methods whose expansion is an error, so a marker that survives lowering fails at its site, and both fail outside a block.
- The rejections and their diagnostics stay: `var`, `lazy val`, nested `class`/`trait`/`object`, `def` or lambda containing `.now`, `try`, `throw`, `synchronized`, mutable field reads through a selection, a bare `A < S` statement, `.now` in a by-name argument, `.now` inside a `Stream` lambda, nested `.now` inside `.later`.
- A `.now` inside a callback passed to a standard-library method, or to a method of `Maybe` or `Result`, runs per element through the `Kyo` operation with the same meaning; the receiver's collection type is preserved and multi-shot effects resume the callback once per choice.
- No runtime of its own: everything the macro emits is a call to `<.map`, `Kyo.lift`, `Loop`, or an operation of `Kyo`. The effectful counterparts the standard library lacks (`Kyo.find`, `Kyo.maxBy`, `Kyo.foreach` over `Either`, ...) are ordinary kernel API, usable without `direct`.

## Surface

Unchanged: the names, types, and diagnostics of `direct`, `now`, and `later`; a pure block has type `A < Any`; `TestSupport.runLiftTest` keeps working, and with it every suite that goes through it.

Two deliberate changes against the dotty-cps-async implementation:

1. **Recognition is by the receiver's type and the resolved method, not by name alone.** A call is rewritten when its receiver is a collection, `Maybe`, `Result`, `Option`, `Try`, `Either`, or `Either.LeftProjection` and the method is that type's own (declared there or overriding it) and in the table of `Counterparts`. A method that merely shares a name on another type is not rewritten.
2. **A nested `direct` used as a bare statement is rejected.** Any pending-typed expression in statement or value position under no marker is rejected with the "must explicitly use either .now or .later" message, nested blocks included.

## What the implementation does, as the tests pin it

| Family | Behavior | Suite |
|---|---|---|
| Blocks and bindings | `val x = e.now` binds once; later references reuse the result. Statements run in order. A discarded `e.now` still runs. Nested blocks, blocks inside tuples, `val (a, b) = (x.now, y.now)`. | `BlockTest`, `LowerTest` |
| Conditionals | Effectful condition, effectful branches, `val` bindings inside a branch, an `if` with an implicit `()` branch. | `IfTest`, `PreludeTest`, `LowerTest` |
| Boolean operators | `&&` and `||` with effectful operands short-circuit: the right operand runs only when the left does not decide. | `BooleanTest`, `LowerTest` |
| Pattern matching | Effectful scrutinee, effectful case bodies, pure guards, `val` bindings inside case bodies, a pattern-bound pending identifier used through `.now`, the refutable `val Some(a) = e.now`. An effectful guard is rejected. | `PatMatchTest`, `LowerTest` |
| Loops | `while` with effectful condition and body, compound `&&` conditions, `while true` ended by `Abort`, loops inside nested blocks. Stack safety over a hundred thousand iterations. | `WhileTest`, `LowerTest` |
| Nested markers | `f(x.now).now` binds inner then outer. `.later` inside `.later` is fine. `.now` inside `.later` is rejected. `.later` inside lambdas and local `def`s is erased there. | `HygieneTest`, `StreamTest`, `CounterpartsCollTest` |
| Handlers | A handler receives a nested `direct` block and the handler call takes `.now`, for strict, by-name, and lambda parameters. The inner block is its own boundary; handled effects do not reach the outer row. | `PreludeTest`, `CoreTest`, `WhileTest`, `LowerTest` |
| Counterparts on collections | `.now` inside the callback of `map`, `flatMap`, `flatten`, `collect`, `collectFirst`, `find`, `filter`, `filterNot`, `withFilter`, `dropWhile`, `takeWhile`, `partition`, `partitionMap`, `span`, `fold`, `foldLeft`, `foldRight`, `groupBy`, `groupMap`, `groupMapReduce`, `exists`, `forall`, `count`, `maxByOption`, `corresponds`, `foreach`, `tapEach`, `scanLeft`, `scanRight` on `Seq`, `List`, `Vector`, `Chunk`, `Iterable`, a one-shot `Iterable`, and `Set`. The collection type of the result is preserved. A one-shot iterable is consumed once. `Choice` inside `map`, `flatMap`, `foldLeft` yields every combination. A pure `def` inside a rewritten callback is allowed. | `CounterpartsCollTest`, `CounterpartsTest` |
| Counterparts on containers | `Maybe`, `Result`, `Option`, `Try`, `Either`, `Either.LeftProjection`: each method the type has among `map`, `flatMap`, `filter`, `filterNot`, `filterOrElse`, `filterToOption`, `exists`, `forall`, `foreach`, `collect`, `fold`, `orElse`, `getOrElse`, `recover`, `recoverWith`. A `Result.Panic` short-circuits every operation and keeps its identity. The by-name argument of `orElse`, `getOrElse`, `filterOrElse` may contain `.now`. | `CounterpartsContainerTest`, kyo-kernel `KyoContainerTest` |
| Streams | `.now` inside a `Stream` lambda is rejected with the lazy-structure diagnostic. `.later` inside the lambda and a nested `direct` inside the lambda are allowed. `.now` on a whole `Stream` expression works. | `StreamTest`, `PreludeTest` |
| Opaque receivers | `maybe.now.fold(...)` and `maybe.now.contains(...)` where the receiver type is opaque. | `HygieneTest` |
| Rejections | The list in the objective, each with its message. | `HygieneTest`, `CounterpartsHygieneTest`, `LowerTest` |
| Type contracts | A block's row is exactly the union of its markers' rows. `.now` whose expected type widens the result (`val n: Long = Sync.defer(7).now`) does not type, because the extension's `A` is bound by both the receiver and the expected type; the test records the limitation. | `HygieneTest`, `LowerTest` |
| Production use | kyo-examples' ledger uses `direct` for `Env`, `Async`, `Abort`, and `Sync` compositions, outside package `kyo`. `TestSupport.runLiftTest` is a main-source `transparent inline` in package `kyo.internal` that expands `direct`. | compiles |

Observations that shape the design:

- **Why nested blocks type.** `direct` is `transparent inline`, and the compiler expands transparent inline calls while typing the expression that contains them. A nested `direct` in a handler argument is therefore already the lowered `A < S'` when the handler's type arguments are inferred. `.now`, `.later`, and every other non-transparent inline method (`Maybe.map`, `<.map`) are not expanded at that point, so they reach the enclosing macro as calls, which is what lets it find the markers and recognize the method calls around them.
- **Why a lambda with `.now` is rejected outside a counterpart.** Not because the lambda could not be rebuilt; counterparts do rebuild lambdas. The receiving call and everything after it were typed against the lambda's original result type, and the macro cannot retype them. A counterpart is the case where the receiving call is replaced by one whose result shape is known.
- **`.later` is the explicit computation value.** It is erased wherever it appears inside a block.
- **The compile-time API types nothing.** `quotes.reflect` builds typed trees but does not run type inference: a polymorphic method can only be applied to explicit type arguments. Every emitted call therefore carries explicit type arguments computed from the typed subterms, and the `Kyo` operations are declared with their type parameters in the order the lowering can supply them.

## Architecture

```text
direct { body }                       .kyo definition body (kyo-lang)
   |  user-written .now / .later          |  plugin-planted direct and .now
   v                                      v
Validate      the restrictions and their diagnostics
Lower         erasure of .later, the row, the reduction rules, owners, frames
Counterparts  which calls have a Kyo operation standing in for them, and which one
Kyo           the operations themselves, in kyo-kernel
```

| File | Responsibility |
|---|---|
| `Direct.scala` | The public surface: `direct`, `now`, `later`, their outside-block diagnostics. `direct` runs `Validate` and then `Lower` on its body. |
| `internal/DirectSyntax.scala` | Recognizes `direct`, `.now`, and `.later` in a typed tree by their symbols. |
| `internal/Validate.scala` | The restriction checks and diagnostics. Asks `Counterparts` which calls may carry `.now` in their callbacks. |
| `internal/Lower.scala` | Erasure, the row, the reduction rules, joins, owners, frames, and the emission of counterpart calls. |
| `internal/Counterparts.scala` | The table from standard-library method to `Kyo` operation, the recognition of a call by receiver family and resolved method, and the selection of the `Kyo` overload. |
| `internal/TestSupport.scala` | `runLiftTest`, used by four suites. |
| `internal/Trees.scala` | The traversal helpers `Validate` uses. |
| kyo-kernel `Kyo.scala` | The effectful operations: the collection family over `IterableOps[A, CC, CC[A]]`, and the `Maybe`, `Result`, `Option`, `Try`, `Either`, and `Either.LeftProjection` families. |
| kyo-data `Frame.scala` | `Frame.fromPosition`, the macro-side constructor the lowering uses to build a frame at a marker's position. |

### The macro and the typer

`direct` is a `transparent inline` method whose right-hand side is a splice. The compiler expands it while typing the enclosing expression, so the lowered type `A < S` is what the enclosing code sees. Everything below runs inside that expansion on the already-typed body. Nothing re-types a tree: the rules rebuild only the parts that contain markers, through `Apply.copy`, `Select.copy`, `If.copy`, `Match.copy`, `ValDef.copy`, quotes, and `Lambda`, with types taken from the typed subterms.

## Erasure, then the row

**Erasure.** Before anything else, every `.later` inside the block is replaced by its argument. This runs over the whole tree, including lambda bodies, local `def` bodies, by-name arguments, and the surroundings of nested blocks, because `.later` is an inline macro that errors if it survives, and the corpus places it inside lambdas (`StreamTest`), inside local `def`s (`CounterpartsCollTest`, `fold`), and inside another `.later` (`HygieneTest`). A `.now` inside a `.later` is rejected before erasure.

**The row.** The row of a block is the intersection of the rows recorded by its `.now` markers, excluding markers inside a nested block. Rows are deduplicated structurally (`=:=`), an `AndType` contributed by a marker is split before deduplication, `Any` is dropped, and the order is source order. A block with no marker is pure: `direct` returns `Kyo.lift[A, Any](body)`. A block whose markers all record `Any` still lowers, with the row `Any`.

## Reduction rules

Each rule states the typed shape it matches and the term it produces. `K[·]` is the continuation, the rest of the block as a function of the rule's result term. `bind(e, x => K[x])` is the generated composition `e.map(x => K[x])`, with the `Frame` passed explicitly from the marker's position (see Frames). `now(e)` is a `.now` application. `S` is the block's row.

**R1. Subtree without a marker.** Left as it is. The lowering never descends into a tree it has no reason to change.

**R2. Sequence a marker.** `now(e)` becomes `bind(lower(e), x => K[x])`. The marker's own argument is lowered first, so `f(x.now).now` binds `x` and then the outer call.

**R3. Blocks.** Statements lower in order. `val x = rhs` with a marker in `rhs` becomes `lower(rhs)` with `K` redefining `x`, same symbol, bound to the result; references to `x` need no rewriting. A statement without a marker is kept as it is. A statement whose value is unused still lowers, and its continuation ignores the bound value. A `val` with a refutable pattern is lowered as Scala desugared it, a `match` whose scrutinee is the right-hand side (R7). A local `def` whose body has no marker is a pure closure and stays; with one it is rejected.

**R4. Conditionals.** `if c then a else b` lowers `c` first. If the conditional is the last expression of the block, each branch lowers with the block's final continuation. Otherwise the branches join: each lowers with `K0 = x => Kyo.lift[A0, S](x)` where `A0` is the type the typer gave the `If` node, giving two terms of type `A0 < S`; the join is `If.copy(c', a', b')` and is bound once into `K`. The continuation is never duplicated into branches.

**R5. Boolean operators.** `l && r` with a marker in `r` becomes `lower(l)` then the join `if l' then lower(r) else false`, bound into `K`; `||` is symmetric with `true`. With a marker only in `l`, R6 applies. These rules fire on the resolved `Boolean.&&` and `Boolean.||` symbols only.

**R6. Applications.** For `f(a1, ..., an)` the receiver (or function value) lowers first, then each strict argument in order, each bound before the next is evaluated, and the call is rebuilt with the bound terms through `Apply.copy`. A pure strict argument that precedes a marker argument is bound to a local as well, so `combine(pure(), e.now)` evaluates `pure()` first. A by-name argument is never bound: with no marker it stays in place and stays delayed; with one, the call is first checked against `Counterparts` (R9 covers the by-name arguments of `orElse`, `getOrElse`, and `filterOrElse`), and otherwise rejected. Type applications, selections, ascriptions, and repeated arguments follow the same order.

**R7. Pattern matching.** `s match { cases }` lowers `s`, then each case body as a block; patterns and their bound symbols are kept as they are, a pure guard stays a guard. If the match is the last expression, each body ends with the final continuation; otherwise the bodies lower with `K0` as in R4 and `Match.copy` builds the join, bound once into `K`. A guard containing a marker is rejected.

**R8. Loops.** `while c do b` becomes `Loop[Unit, Unit, S](())(_ => lower(c) then if c' then lower(b).map(_ => Loop.continue[Unit]) else Loop.done[Unit])`. No value is threaded through the loop because `var` is excluded; every state the loop observes lives behind an effect. `Loop` is stack safe. The loop is bound into `K` once.

**R9. Counterparts.** A call that `Counterparts` recognizes, and whose callbacks or by-name arguments contain a marker, is rewritten. The receiver lowers first, then the arguments in order: a lambda literal becomes a lambda returning a computation (below), a by-name argument becomes the computation `lower(arg)` of type `T < S`, a strict argument is lowered in place. The `Kyo` operation is applied to its type arguments explicitly, then to the receiver, then to the argument lists as the original call had them, then to the frame; the result is bound into `K`. The type arguments are, in order: the receiver's own (the constructor and element of a collection, read from its `IterableOps[A, CC, CC[A]]` base type; the type arguments of a container), the method's own as the compiler inferred them at the call, and `S`. The `Kyo` operations are declared in exactly that order.

A rebuilt lambda is `(p0, ...) => lower(body) : B < S` where `B` is the lambda's declared result type. Its parameters are fresh and references to the old ones are substituted; the body's definitions are re-owned (see Owners). A partial-function literal (`collect`, `collectFirst`, `recover`, `recoverWith`) arrives as a lambda whose body is a `match`; it becomes a total function to `Maybe[B]`, with each case yielding `Maybe(value)` and a fallback case yielding `Maybe.empty` unless the last case already matches everything, and the `Kyo` operation takes `A => Maybe[B] < S`.

Four methods adapt the operation's result to the method's: `find`, `collectFirst`, and `maxByOption` answer an `Option` where `Kyo` answers a `Maybe`, so the call is followed by `.map(_.toOption)`; `withFilter` answers a `WithFilter[A, CC]`, so the filtered collection `Kyo.filter` produces is followed by `.map(_.withFilter(_ => true))`. `maxByOption`'s `Ordering` joins the frame in `Kyo.maxBy`'s using clause.

The table, with the `Kyo` operation for each method (`foreach`, `foreachConcat`, `foreachDiscard` stand for `map`, `flatMap` and `flatten`, and `foreach`):

| Receiver family | Methods |
|---|---|
| A collection: `IterableOps[A, CC, CC[A]]` in its base types, with the method declared in `IterableOnceOps`, `IterableOps`, or `SeqOps` or overriding one that is | `map`, `flatMap`, `flatten`, `foreach`, `filter`, `filterNot`, `withFilter`, `collect`, `collectFirst` (`findFirst`), `find`, `exists`, `forall`, `count`, `fold` (`foldLeft`), `foldLeft`, `foldRight`, `scanLeft`, `scanRight`, `takeWhile`, `dropWhile`, `span`, `partition`, `partitionMap`, `groupBy`, `groupMap`, `groupMapReduce`, `maxByOption` (`maxBy`), `corresponds`, `tapEach` |
| `Maybe`, `Result` (extension methods, recognized by the receiver's type) | `map`, `flatMap`, `foreach`, `filter`, `filterNot`, `exists`, `forall`, `collect`, `fold`, `orElse`, `getOrElse`, where the type has the method |
| `Option`, `Try`, `Either`, `Either.LeftProjection` (members declared in the type or overriding one that is) | `map`, `flatMap`, `foreach`, `filter`, `filterNot`, `filterOrElse`, `filterToOption`, `exists`, `forall`, `collect`, `fold`, `orElse`, `getOrElse`, `recover`, `recoverWith`, where the type has the method |

**R10. Nested blocks.** A nested `direct` inside the body is a value of type `A < S'` as far as the outer block is concerned; its markers belonged to it. Under `.now` it is bound like any computation; under `.later` it is a value. Not under a marker it is a bare pending expression and is rejected, in value and in statement position.

**R11. Lambdas.** A lambda whose body contains a marker and that is not an argument of a recognized call is rejected. A lambda whose body is a nested block, or contained only `.later`, is left alone by R1.

**R12. Streams.** A marker inside a lambda passed to a `Stream` method is rejected with the lazy-structure diagnostic, regardless of the method. `.later` and nested blocks inside such a lambda are fine. In practice such a `.now` never types: the lambda's expected result type `V2 < S2` binds the marker's `A`, so it reaches the validator as an unresolved selection named `now`, and the rule recognizes that shape too.

**R13. End of a block.** The last expression lowers with `K = x => Kyo.lift[A, S](x)`, and the block's type is `A < S`.

### Owners

Every definition the lowering emits or keeps is owned by the macro while the tree is built: the body is re-owned to the macro before lowering, and so is the body of every callback the lowering dissolves and the right-hand side of every `val` whose definitions move out of its scope. With one owner throughout, each quote that splices a subtree moves it to its final owner, and `-Xcheck-macros`, which the build compiles with, checks the result on every test compile.

### Frames

Every generated call that takes a `Frame` receives one explicitly: `<.map`, `Loop.apply`, every `Kyo` operation. None relies on implicit `Frame` search, because that search derives at the macro expansion position and refuses package `kyo` outside test files, and `TestSupport.runLiftTest` expands `direct` in package `kyo.internal`. The frame is built from the marker's own position with `Frame.fromPosition`, so a trace through a block points at the `.now` that suspended. A marker planted by an inline method (kyo-lang's markers expand to `.now`) takes the position of the inlined call, which is the user's expression; the inline method's own source may not even be available to the compilation.

### Diagnostics

The messages are those of the dotty-cps-async implementation, since the tests pin them, plus the lowering's own for the shapes it refuses: a marker in a pattern guard, in a by-name argument of an unrecognized call, in a local definition, in a callback with more than two parameters.

## What kyo-lang needs, and what it still owns

kyo-lang uses this surface as it is: its compiler plugin plants `direct` around a `.kyo` definition body and `.now` where a computation's result is used as a value, before typing, and the macro lowers the result. Nothing in the lowering is parameterized for it: the rules, the counterparts, and the diagnostics are the same for a block a user wrote and a block the plugin planted.

What kyo-lang settles on its own side:

- **Boundary placement.** A handler argument needs a `direct` of its own and no `.now`; a strict value argument needs its computation exposed with `.now` and bound before the call. The plugin runs before typing and cannot tell the two apart from syntax alone; kyo-lang's design records the candidates and settles them with fixtures.
- **Admission.** The language's restrictions beyond `direct`'s (which constructs and which library surface a `.kyo` file may use) are checked by the plugin, not by this macro.
- **Pure definitions.** A `.kyo` method with no effect keeps its ordinary type; the plugin does not plant `direct` around a body with no markers.

## Risks and how each is checked

- **Owner consistency** across moved and rebuilt lambdas: `-Xcheck-macros` on every test compile and the hygiene corpus.
- **Code size** from branch constructs: joins keep each continuation emitted once.
- **Frames inside package `kyo`**: explicit frames everywhere; the `runLiftTest` suites are the check.
- **Type arguments of the `Kyo` operations**: the declared order is the contract the lowering relies on; the `CounterpartsCollTest` variants (`Seq`, `List`, `Vector`, `Chunk`, `Iterable`, one-shot, `Set`) and `CounterpartsContainerTest` are the check, together with the kernel's own `KyoForeachCollTest` and `KyoContainerTest`.
- **Multi-shot semantics**: the `Choice` cases in `CounterpartsCollTest` and `PreludeTest`.
- **`.now` with a widening expected type**: a limitation of the `now` extension's inference; its test stays as the record.

## Non-goals

Inferring boundaries inside `direct` blocks, admission inside `direct`, removing `.later`, and changing the names or types of `direct`, `now`, and `later`.

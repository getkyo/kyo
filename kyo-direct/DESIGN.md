# kyo-direct: an own lowering for `direct`, `.now`, and `.later`

Status: design for the reimplementation of kyo-direct without dotty-cps-async. The user-facing surface does not change. The lowering core this design describes is also the core the `.kyo` language (`kyo-lang`) is built on; its requirements are recorded here so the two stay one implementation.

## Objective

Keep the `direct { ... }`, `.now`, `.later` paradigm exactly as users know it, and replace the implementation underneath with a lowering owned by this repository:

- `direct` stays a `transparent inline` macro. The block's effect row is inferred from the markers inside it, and the block's type is the lowered `A < S`.
- `.now` sequences an effect and exposes its result; `.later` keeps an effect pending as a value. Both are errors outside a block.
- The rejections and their diagnostics stay: `var`, `lazy val`, nested `class`/`trait`/`object`, `def` or lambda containing `.now`, `try`, `throw`, `synchronized`, mutable field reads, a bare `A < S` statement, `.now` in a by-name argument, `.now` inside a `Stream` lambda, nested `.now` inside `.later`.
- The shift-aware higher-order methods keep working: `.now` inside a lambda passed to a recognized method on a recognized receiver runs per element through Kyo's traversals, with multi-shot semantics preserved.
- dotty-cps-async, `KyoCpsMonad`, the `AsyncShift` classes, and the `doctestFreshDriver` workaround in the build disappear.

The lowering core is written once, with the marker symbols and the restriction policy as parameters. kyo-direct uses it with `.now` as the marker and the permissive policy; kyo-lang uses it with its plugin-planted marker and the strict policy (API admission and `Checked`). Nothing about control flow lives in kyo-lang.

## What the current implementation does, as the tests pin it

Every item below has a test in `shared/src/test/scala/kyo` and is an acceptance criterion for the new implementation. The suites run unchanged.

| Family | Behavior | Pinned by |
|---|---|---|
| Blocks and bindings | `val x = e.now` binds once; later references reuse the result. Statements run in order. A discarded `e.now` still runs. Nested blocks, blocks inside tuples, `val (a, b) = (x.now, y.now)`. | `BlockTest` |
| Conditionals | Effectful condition, effectful branches, `val` bindings inside a branch, an `if` with an implicit `()` branch. | `IfTest`, `PreludeTest` emit |
| Boolean operators | `&&` and `||` with effectful operands short-circuit: the right operand runs only when the left does not decide. | `BooleanTest` |
| Pattern matching | Effectful scrutinee, effectful case bodies, pure guards, `val` bindings inside case bodies, a pattern-bound pending identifier used through `.now`, the refutable `val Some(a) = e.now`. | `PatMatchTest` |
| Loops | `while` with effectful condition and body, compound `&&` conditions, `while true` ended by `Abort`, loops inside nested blocks with `Abort`. Loops must be stack safe. | `WhileTest` |
| Nested markers | `f(x.now).now` binds inner then outer. `.later` inside `.later` is fine. `.now` inside `.later` is rejected. | `HygieneTest` |
| Handlers | A handler receives a nested `direct` block and the handler call takes `.now`: `Abort.run { direct { ... } }.now`, also `Env.run`, `Var.run`, `Emit.run`, `Random.withSeed`, `Meter.run`, `Fiber.initUnscoped`. The inner block is its own boundary; handled effects do not reach the outer row. | `PreludeTest`, `CoreTest`, `WhileTest` |
| Shifts on collections | `.now` inside the lambda of `map`, `flatMap`, `flatten`, `collect`, `collectFirst`, `find`, `filter`, `filterNot`, `withFilter`, `dropWhile`, `takeWhile`, `partition`, `partitionMap`, `span`, `fold`, `foldLeft`, `foldRight`, `groupBy`, `groupMap`, `groupMapReduce`, `exists`, `forall`, `count`, `maxByOption`, `corresponds`, `foreach`, `tapEach`, `scanLeft`, `scanRight` on `Seq`, `List`, `Vector`, `Chunk`, `Iterable`, a one-shot `Iterable`, and `Set`. The collection type of the result is preserved. A one-shot iterable is consumed once. `Choice` inside `map`, `flatMap`, `foldLeft` yields every combination. A pure `def` inside a shifted lambda is allowed. | `CollShiftMethodSupportTest`, `ShiftTest` |
| Shifts on containers | `Option`, `Try`, `Maybe`, `Result`: `map`, `flatMap`, `filter`, `filterNot`, `exists`, `forall`, `foreach`, `collect`, `fold`, `orElse`, `getOrElse`, with `.now` in the lambda or in the by-name argument of `orElse`/`getOrElse`. A `Result.Panic` short-circuits every operation and keeps its identity. | `ShiftMethodSupportTest` |
| Streams | `.now` inside a `Stream` lambda is rejected with the lazy-structure diagnostic. `.later` inside the lambda and a nested `direct` inside the lambda are allowed. `.now` on a whole `Stream` expression works. | `StreamTest`, `PreludeTest` stream |
| Opaque receivers | `maybe.now.fold(...)` and `maybe.now.contains(...)` where the receiver type is opaque. | `HygieneTest` (#993) |
| Rejections | The list in the objective, each with its message. `return` and a `for` over a non-monadic value fail with the compiler's own errors. | `HygieneTest`, `ShiftHygieneTest` |
| Type contracts | A block's row is exactly the union of its markers' rows: `val x: Unit < Emit[Int] = direct(default.now)` with `default: Unit < Abort[String]` is a type error. `.now` whose expected type widens the result (`val n: Long = Sync.defer(7).now`) fails today; the test codifies the current limitation. | `HygieneTest` |
| Production use | kyo-examples' ledger uses `direct` for `Env`, `Async`, `Abort`, and `Sync` compositions. | `kyo-examples` compiles |

Observations that shape the design:

- The handler boundary is already explicit and already works: a nested `direct` is a `transparent inline` call that expands while the argument is typed, so the handler's type arguments are inferred from the lowered computation. This is the mechanism kyo-lang's design calls "the unproven typing mechanism"; it is proven by `PreludeTest`. The new core keeps it as is.
- A lambda containing `.now` is rejected unless it is passed to a shift-aware method, because the lambda was typed as `A => B` and cannot be retyped after the fact. A user who wants an effectful closure writes `direct` inside it. kyo-lang plants that boundary automatically.
- `.later` is the explicit form of "pass this computation on as a value". It stays.
- `impl` today also rewrites a receiver that is an opaque `.now` result into a `val` before the call (the `hasOpaqueNow` preparation), a workaround for dotty-cps-async. The new lowering binds every `.now` result through a fresh `val` anyway, so the workaround has no counterpart; the #993 test decides.

## Architecture

```text
direct { body }                                   .kyo definition body (kyo-lang)
   |  user-written .now / .later                      |  plugin-planted markers and boundaries
   v                                                  v
Validate(policy)      -- restrictions (permissive) / restrictions + admission (strict)
Row                   -- the effect row: markers inside this boundary, not inside nested boundaries
Lower                 -- the CPS rules below, producing a Term of type A < S
Shifts                -- the resolved-symbol substitution table and its Kyo runtime
```

All of it lives in kyo-direct under `kyo.internal`, in these files:

| File | Responsibility |
|---|---|
| `Direct.scala` | The public surface: `direct`, `now`, `later`, their outside-block diagnostics. Calls the core with the `now`/`later` marker symbols and the permissive policy. |
| `internal/Markers.scala` | The marker description the core is parameterized by: which symbols sequence (`now`, kyo-lang's pending `force`), which pass through (`later`, kyo-lang's pure `force`), which symbol opens a nested boundary (`direct`, kyo-lang's body wrapper). |
| `internal/Validate.scala` | The restriction checks and diagnostics, unchanged in content, parameterized by the markers and a policy hook for admission. |
| `internal/Lower.scala` | The lowering: effect row, the reduction rules, join points, owners, frames. |
| `internal/Shifts.scala` | The substitution table: resolved method symbol and receiver type to the Kyo operation and the shape of its lambda. |
| `internal/ShiftRuntime.scala` | The handful of traversals Kyo does not already provide (`foldRight`, `scanRight`, `count`, `corresponds`, `maxByOption`, `groupMapReduce`, `collectFirst`, the `Option`/`Try`/`Either` operations), written on `Kyo.foldLeft` and friends. |

`Trees.scala` stays as the traversal helper. `AsyncShift.scala`, `ChunkAsyncShift.scala`, `ListAsyncShift.scala`, and `KyoCpsMonad.scala` are deleted with the dotty-cps-async dependency.

### The macro and the typer

`direct` is a `transparent inline` method whose right-hand side is a splice. The compiler expands it while typing the enclosing definition, so the lowered type `A < S` is what the enclosing code sees. Everything below runs inside that expansion on the already-typed body. Nothing re-types a tree: the rules rebuild only the parts that contain markers, through `Apply.copy`, `Select.copy`, fresh `ValDef`s, and quoted lambdas whose types come from the typed subterms.

## The effect row

The row of a boundary is the intersection of the `S` recorded by every sequencing marker inside it, excluding markers inside a nested boundary and excluding what `.later` passes through, deduplicated. `A & B` and `B & A` are the same type, so the order is irrelevant to a user, and the row is built in source order. A boundary with no sequencing marker returns its body unchanged with its ordinary type, which is what makes a pure `direct` block free.

## Reduction rules

Each rule states the typed shape it matches and the term it produces. `K[·]` is the continuation: the rest of the boundary, as a function of the rule's result term. `bind(e, K)` is the generated composition `e.map(x => K[x])`, with the lambda owned by the generated code and the `Frame` taken from the marker's own position (see Frames below). `now(e)` is a sequencing-marker application, `later(e)` a pass-through one.

**R1. Pure subtree.** A subtree with no marker is left as it is, after references to lowered locals are rebound. The lowering never descends into a tree it has no reason to change.

**R2. Sequence a marker.** `now(e)` becomes `bind(lower(e), x => K[x])`. The marker's own argument is lowered first, so `f(x.now).now` binds `x` and then the outer call.

**R3. Pass a marker through.** `later(e)` becomes `lower(e)` with `K` applied to the pending value itself. A sequencing marker inside `e` is rejected before lowering (`.now` inside `.later`).

**R4. Blocks.** Statements lower in order. A `val x = rhs` becomes `lower(rhs)` with `K` receiving a fresh local bound to the result; every later reference to `x` is rebound to that local. A statement whose value is unused still lowers, and its continuation ignores the bound value. A `val` with a refutable pattern is lowered as Scala desugars it: bind the scrutinee, then the generated match. A local `def` is allowed when its body has no marker, since it is then a pure closure; with a marker it is rejected (today's message).

**R5. Conditionals.** `if c then a else b` lowers `c`, then produces a join: `if c' then lower(a) else lower(b)` as one computation, followed by a single `bind` into `K`. The continuation is emitted once, never duplicated into both branches. When the conditional is the last expression of the boundary, no join is needed and each branch ends with the final lift.

**R6. Boolean operators.** `l && r` with a marker in `r` becomes `lower(l)` then `if l' then lower(r) else false`, then the join; `||` is symmetric with `true`. A marker only in `l` is R7. These rules fire on the resolved `Boolean.&&` and `Boolean.||` symbols only.

**R7. Applications.** For `f(a1, ..., an)` the receiver (or function value) lowers first, then each argument in order, each bound before the next is evaluated; the call is rebuilt with the bound terms. Type applications, selections, ascriptions, and repeated arguments follow the same order. A by-name parameter whose argument contains a sequencing marker is rejected, unless the call is a shift (R10). A pure argument before a marker keeps its original evaluation position by being bound to a local too, so `combine(pure(), e.now)` evaluates `pure()` first.

**R8. Pattern matching.** `s match { cases }` lowers `s`, then each case: a pure guard stays a guard; a case body lowers as a block; the cases join into one computation bound into `K` once. A guard containing a marker is rejected with a dedicated message, because evaluating it after the pattern and before fallback needs a rule that has not been written. Pattern-bound names stay the same symbols, so a pending identifier bound by a pattern can still take `.now` in the body.

**R9. Loops.** `while c do b` becomes a stack-safe loop over Kyo's `Loop`: evaluate `lower(c)`, and when true run `lower(b)` and continue, otherwise finish with `()`. No value is threaded through the loop because `var` is excluded; every state the loop observes lives behind an effect. The loop is bound into `K` once.

**R10. Shifts.** A call whose resolved method symbol and receiver type match an entry in `Shifts`, and whose lambda argument contains a sequencing marker, is rewritten to the Kyo operation of that entry, with the lambda body lowered into a computation `A => B < S` and the call's result bound into `K`. Entries are exact: `Chunk.map` to `Kyo.foreach`, `flatMap` to `Kyo.foreachConcat`, `foreach` to `Kyo.foreachDiscard`, `filter` to `Kyo.filter`, `collect` to `Kyo.collect` through a lifted partial function, `foldLeft` to `Kyo.foldLeft`, and so on for the list in the inventory, each on `Iterable`/`IterableOps`, `List`, `Chunk`, `Option`, `Try`, `Either`, `Maybe`, and `Result`. Results keep the receiver's collection type through its `iterableFactory`. A by-name argument of `orElse`/`getOrElse` with a marker lowers as a thunk returning a computation. A pure lambda keeps the original call. A lambda with a marker passed to a method with no entry is rejected with today's message. A method with a matching name but a different resolved symbol is not a shift.

**R11. Nested boundaries.** A nested `direct` inside the body is a value of type `A < S'` as far as the outer boundary is concerned. Its markers belong to it, not to the outer row. A bare nested boundary that is neither `.now`ed nor `.later`ed is a bare pending statement and is rejected like any other.

**R12. Lambdas.** A lambda whose body contains a sequencing marker and that is not the argument of a shift is rejected (today's message). A lambda whose body is a nested boundary, or contains only pass-through markers, is a pure closure and is left alone by R1.

**R13. Streams.** A sequencing marker inside a lambda passed to a `Stream` method is rejected with the lazy-structure diagnostic, regardless of the method name. Pass-through markers and nested boundaries inside such a lambda are fine.

**R14. End of a boundary.** The last expression lowers with `K = x => Kyo.lift[A, S](x)`, and the boundary's type is `A < S` for the row above.

### Owners and hygiene

Every generated lambda is created through a quote whose splice runs with that lambda as the splice owner, and every subtree moved into a generated lambda has its owner changed to that lambda. Fresh locals come from `ValDef.let` under the current owner. The build compiles with `-Xcheck-macros`, so an owner mistake is a compile error in this module's tests rather than a user's. The hygiene corpus (shadowing, nested blocks inside marker arguments, pattern-bound names) runs on every platform.

### Frames

Each generated `map` needs a `Frame`. Deriving it implicitly at the splice site fails inside package `kyo` outside test files, and `Frame.internal` loses the position. The lowering builds the frame from the marker's own source position, so a trace through a `direct` block points at the `.now` that suspended. This needs one additive helper in kyo-data: a `private[kyo]` macro-side constructor that produces a `Frame` expression for a given `Position`, factored out of the existing derivation.

### Diagnostics

The messages are those of today's `Validate`, since the tests pin them. New messages are needed for an effectful guard (R8) and for a sequencing marker in a lambda passed to a non-shift method where today's message speaks of method definitions; the latter keeps today's wording to keep the tests, and the wording is revisited only with its tests.

## What kyo-lang needs from the core

The language's plugin plants markers and boundaries; the core does the rest. The core must therefore expose:

- The marker description as a parameter: kyo-lang's `force` has a pure overload (pass-through) and a pending overload (sequencing), chosen by typing. Both symbols are registered the way `later` and `now` are.
- The boundary symbol as a parameter: kyo-lang's body wrapper is `direct` in strict mode under another name, and a nested wrapper is a nested boundary.
- The policy hook: strict mode runs API admission on every resolved call and reference and refuses what the permissive mode allows.
- The same rules for closures and handlers: kyo-lang's `Preparation` wraps lambda bodies and handler arguments in the boundary, which is what a kyo-direct user writes by hand with a nested `direct`. Whether the plugin can also decide value positions against computation positions (the conversion-marker idea) is a kyo-lang question and not a core change; in both answers the core sees ordinary boundaries.

Nothing in the core refers to `.kyo`, to admission, or to the plugin.

## Milestones

Each milestone is one local commit with its suites green and `-Xcheck-macros` on; nothing lands half-lowered.

**M1. Core with blocks, conditionals, booleans, applications, nested markers, boundaries.** `Lower`, `Markers`, the row, R1 to R7, R11, R12, R14; `Validate` carried over and parameterized; the frame constructor in kyo-data. A new `LowerTest` in kyo-direct drives the core directly on hand-written marker trees, in the style of `BlockTest`, so the core is tested before `direct` is switched.

**M2. Pattern matching, loops, refutable patterns.** R8, R9, the desugared `val` pattern; join points for `if` and `match`.

**M3. Shifts and streams.** `Shifts`, `ShiftRuntime`, R10, R13, including container shifts, one-shot iterables, collection-type preservation, and `Choice` multi-shot checks. The acceptance is `CollShiftMethodSupportTest` and `ShiftMethodSupportTest` passing against the core.

**M4. Switch `direct` to the core and delete dotty-cps-async.** `Direct.scala` calls the core; `AsyncShift`, `ChunkAsyncShift`, `ListAsyncShift`, `KyoCpsMonad` are removed; the dependency and `doctestFreshDriver` leave `build.sbt`; every kyo-direct suite, the README doctests, and kyo-examples compile and pass on JVM, JS, Native, and Wasm.

**M5. kyo-lang on the core.** `kyo.lang.internal.Body` becomes the strict-mode boundary over the core and `kyo.lang.internal.Validate` becomes the admission hook; `Preparation` emits the core's marker and wraps lambda bodies in the boundary (effectful closures land as a consequence). kyo-lang's fixtures and driver suites stay green and `DESIGN.md` there records the new status. The handler-argument placement question is settled next, with the fixtures already written for it.

## Risks and how each is checked

- **Owner consistency** across moved subtrees: checked by `-Xcheck-macros` on every test compile and by the hygiene corpus.
- **Code size** from branch constructs: join points (R5, R8) keep each continuation emitted once; a test with nested conditionals inside a loop guards against regression.
- **Frames inside package `kyo`**: the frame constructor removes the dependency on the splice location; `WhileTest` and `CoreTest` run in package `kyo`.
- **Collection type preservation and one-shot sources**: the `CollShiftMethodSupportTest` variants cover `Set`, `Vector`, `Chunk`, `Iterable`, and the one-shot iterable; `ShiftRuntime` materializes a one-shot source exactly once.
- **Multi-shot semantics**: the `Choice` cases in the shift suite and `PreludeTest`.
- **`.now` with a widening expected type**: a known limitation of the `now` extension's inference, unchanged by this work; its test stays as the record.
- **Inference of handler type arguments**: unchanged mechanism (nested `direct` expands during typing), covered by `PreludeTest` and `WhileTest`.

## Non-goals

Removing `.later`, inferring boundaries inside `direct` blocks, admission inside `direct`, and any change to the `direct`/`.now`/`.later` names or types. These are either kyo-lang's concerns or not wanted.

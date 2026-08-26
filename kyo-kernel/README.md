<!-- doctest:default scope=inherited expect=runs -->

# kyo-kernel

`A < S` is the type the whole module exists to serve: a value that will produce an `A` after performing the effects listed in `S`. It is a description, not a running program. `map` composes descriptions, nothing executes while one is being built, and `eval` only type-checks once `S` has been narrowed to `Any`, so the effect row is a checklist the compiler makes you empty before the computation can run. Plain values are computations already (`42` is an `Int < Any`), which is why `map` doubles as `flatMap` and why most code never mentions lifting at all.

An effect is a set of operations with no implementation. `ArrowEffect[Input, Output]` declares operations that take an input and answer with an output; `ContextEffect[A]` declares a value the computation expects to find bound around it. Performing an operation suspends: the description gains a node saying what was asked, and the rest of the computation becomes a continuation hanging off it. That continuation is an ordinary value, an `Arrow`, rather than a stack frame. A handler (`ArrowEffect.handleCont` and its loop-shaped siblings) or a binding (`ContextEffect.handle`) supplies the answers and removes that effect from the row, and because the handler is holding the continuation as a value it may apply it once, many times, or never. That single property is where backtracking, early exit, streaming and retry all come from.

Everything in this module stands on those two paragraphs. The concrete effects a program actually names (`Abort`, `Env`, `Var`, `Emit`, `Async`) are defined elsewhere in terms of them. The module compiles and runs on the JVM, JavaScript, Scala Native and Wasm, with only the safepoint and stack internals split per platform.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

def ask: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

val question: Int < Ask = ask.map(_ + 1)

val answered: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], question)([C] => (_, cont) => cont(41))

assert(answered.eval == 42)
```

`ask` performs the operation, so `question` is a description holding a suspension with `_ + 1` waiting behind it, and nothing has run. The handler answers with `41` by applying the continuation once, which discharges `Ask` and leaves `Int < Any`. Only then does `eval` type-check.

`Ask` is the first of a small vocabulary the examples below keep reusing. The handler above is worth a name too, because the next section needs one before handlers are the subject:

```scala
def answering[A, S](n: Int)(v: A < (Ask & S)): A < S =
    ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(n))
```

## The pending type

Reading a kyo signature is the first thing to learn, because every function in every kyo module returns one of these. The value type and the effect row are independent: the same combinators work whether the row is empty or names a dozen effects, and the row only ever shrinks, at a handler.

Effects accumulate as an intersection, and intersections are unordered. A row of `E1 & E2` says both effects may occur, not which occurs first.

### Composing computations

`map` is the monadic bind. It threads a function over the eventual value and accumulates that function's own effects into the row.

```scala
val twice: Int < Ask      = ask.map(a => ask.map(b => a + b))
val second: Int < Ask     = ask.andThen(ask)
val discarded: Unit < Ask = ask.unit

assert(answering(1)(twice).eval == 2)
```

`andThen` sequences two computations and keeps the second result. `unit` runs a computation for its effects and produces `Unit`, which is what a caller wants when only the suspension mattered.

> **Note:** `map` and `flatMap` are the same operation. `flatMap` exists only so for-comprehensions parse; `map` is the one to reach for everywhere else.

### Plain values are computations already

Nothing in the examples above had to be lifted, because a bare value is accepted wherever a computation is expected. That is an implicit conversion, and it is also why `map` never needs a `pure` sibling: the function passed to `map` may return `Int` or `Int < S` and both type-check.

Inference occasionally needs the conversion spelled out, most often across the branches of an `if`, where the two arms are typed before the expected type is known. `Kyo.lift` is that explicit form, and `Kyo.unit` is the `Unit` case:

```scala
val verbose: Boolean = false

val chosen: Int < Ask   = if verbose then Kyo.lift(0) else ask
val nothing: Unit < Any = Kyo.unit
```

Neither introduces a suspension. For an ordinary value both expand to the value itself, so the interpreter is never entered and there is nothing for it to unwrap later.

> **Note:** a computation is not liftable into another computation. Where `A < S` meets an expected `A < S < S2`, the `CanLift` constraint fails with an error naming the nested-effect problem and pointing at the two fixes, `.flatten` or splitting the expression into two statements.

> **Note:** a kyo module object refuses to lift as well. Writing `Abort.map(...)` is a compile error rather than a silently lifted `Abort.type < Any`, so a missing argument list is caught where it is written.

> **Note:** one implicit conversion in this module exists purely to fail. When a `Unit < S1` meets a mismatched expected row, `abortCastUnit` is selected and aborts compilation with an explanation of the effect mismatch; it never produces a value.

### Nesting, and flatten

The one shape the implicit conversion does produce is a nested computation, and it arises through a generic position rather than a literal. When a type parameter `A` happens to be instantiated to a computation, lifting an `A` nests it exactly once, and it stays inert data until someone flattens it:

```scala
val nested: (Int < Ask) < Any = Kyo.lift(ask.map(_ + 1))
val merged: Int < Ask         = nested.flatten

assert(answering(1)(merged).eval == 2)
```

`flatten` collapses `A < S < S2` into `A < (S & S2)`. This is the fix the nested-effect error points at, and it is also a deliberate tool: [`Isolate#nest`](#driving-the-phases) builds a nested computation on purpose so the caller can decide when the inner layer applies.

### Reading a stack of handlers left to right

Handlers wrap computations, so writing them out nests inside out and the first handler applied is the one furthest from the eye. `handle` inverts that by taking the transformations as a left-to-right pipeline. It is sugar over function application and nothing more: `answering(1)(ask).map(_ + 1).eval` and the pipeline below are the same value by the same steps.

```scala
val piped: Int = ask.handle(v => answering(1)(v), v => v.map(_ + 1), v => v.eval)

assert(piped == 2)
```

Ten arities are provided, so a pipeline can carry up to ten stages. The stages need not be handlers: any function from a computation to something else fits, which is what lets the last stage above be `eval`.

### Running

`eval` is defined only on `A < Any`. A row that still names an effect has no `eval` to call, which is the compile-time half of the guarantee that no operation reaches the interpreter unanswered.

```scala
val settled: Int < Any = 42

assert(settled.eval == 42)
```

> **Note:** a computation that never suspended short-circuits. `eval` on a settled value returns it without entering the interpreter at all, so lifting a value and immediately evaluating it costs nothing beyond the two casts.

### Printing

A pending value renders as its payload wrapped in `Kyo(...)`, with the payload rendered by its own `Render` instance:

```scala
assert(render"$settled" == "Kyo(42)")
```

A computation that has not settled renders as the operation it is waiting on, so a suspended value in a log line says what it is blocked on rather than printing an opaque address.

## Declaring an effect

An effect is a declaration with no implementation anywhere in it. The kernel offers exactly two shapes, and picking between them is the entire design decision: an operation that asks a question and gets an answer is an `ArrowEffect`, and a value that must be in scope is a `ContextEffect`. Both extend `Effect`, which has no other subclasses.

### Operations with an input and an answer

`ArrowEffect[Input, Output]` is parameterized by two type constructors rather than two types, because one effect usually carries a family of operations. An operation is a transformation from `Input[C]` to `Output[C]` for some `C`, and the handler must answer at whatever `C` the call site chose.

Two type constructors cover nearly every declaration. `Const[A]` ignores its parameter and always answers `A`; `Id[A]` passes the parameter through unchanged. `Ask` from the opening uses `Const` on both sides, since its operation takes nothing meaningful and always answers an `Int`. The effect that accompanies it through the rest of this document is shaped the same way, with a `String` going in and nothing coming back:

```scala
sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

def say(line: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], line)
```

`ArrowEffect.suspend` performs an operation. `suspendWith` performs it and fuses the transformation of the answer into the same node, which is what a helper wants when it would otherwise write `suspend(...).map(f)`:

```scala
def askPlus(n: Int): Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => a + n)

assert(answering(1)(askPlus(41)).eval == 42)
```

Both take a `Tag` identifying the effect and a `Frame` identifying the call site. Neither type is defined in this module; both come from `kyo-data`, and nearly every entry point here requires them. On an `inline` signature the convention is `Tag` before `Frame`.

### An effect whose operations answer at different types

`Const` on both sides is the common case, but it hides why the parameters are type constructors at all. An effect with several operations that answer differently needs the parameter, and that is where `Id` earns its place:

```scala
sealed trait Store extends ArrowEffect[Store.Op, Id]

object Store:
    enum Op[A]:
        case Get(key: String)                extends Op[Maybe[String]]
        case Put(key: String, value: String) extends Op[Unit]

    def get(key: String): Maybe[String] < Store =
        ArrowEffect.suspend[Maybe[String]](Tag[Store], Op.Get(key))

    def put(key: String, value: String): Unit < Store =
        ArrowEffect.suspend[Unit](Tag[Store], Op.Put(key, value))
end Store
```

The input type constructor is the operation family itself, and the output is `Id`, so an `Op[A]` answers with an `A`. A `Get` answers `Maybe[String]` and a `Put` answers `Unit`, from one effect and one handler. Writing that handler is the subject of the next chapter.

### A value bound around the computation

The other shape declares no operations at all. `ContextEffect[A]` says the computation expects to find a value of type `A` bound around it, and reading it is the only thing you can do:

```scala
sealed trait Level extends ContextEffect[Int]

def level: Int < Level = ContextEffect.suspend(Tag[Level])
```

Reads come in a required form and a defaulted form. The required form above adds `Level` to the row, so the compiler will not let the computation run until something binds a value. The defaulted form does not add anything to the row, because it cannot fail:

```scala
def levelOr(default: Int): Int < Any = ContextEffect.suspend(Tag[Level], default)
def levelPlus(n: Int): Int < Level   = ContextEffect.suspendWith(Tag[Level])(l => l + n)

assert(levelOr(-1).eval == -1)
```

That `eval` is the point: the defaulted read has an empty row, so it runs with nothing bound around it at all.

`suspendWith` fuses a transformation onto the read, in both the required and the defaulted form, the same way it does for an `ArrowEffect` operation.

> **Caution:** a required read whose binding is missing raises at evaluation time. The row is what normally makes that unreachable, so any cast that erases the row turns a compile-time error into a runtime one. That is the single reason not to cast a row away.

## Answering an effect

A handler supplies the implementation an effect declaration left out, and removes that effect from the row as it does. Three shapes are provided, and they differ in one axis only: how much of the continuation the clause is handed. Reach for the most convenient shape that still gives you the control you need, because handing the clause less also gives the evaluator more room.

### The continuation in hand

`handleCont` gives the clause the operation's input and the continuation as an `Arrow`, and asks for the region's value back. Applying `cont(answer)` resumes the computation from the suspension point.

```scala
val transformed: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
        [C] => (_, cont) => cont(2),
        a => a * 10
    )

assert(transformed.eval == 30)
```

The second clause is the `done` clause, which transforms the region's final value; the shorter overload used for `answering` earlier omits it and returns the body's own result.

Because `cont` is a value rather than a stack frame, a clause is free to apply it more than once, or not at all:

```scala
val bothAnswers: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], ask.map(_ * 10))(
        [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
        a => a
    )

assert(bothAnswers.eval == 30)
```

That is the whole mechanism behind non-determinism and backtracking: the region after the suspension ran twice, once per answer, and the clause combined the results. A clause that ignores `cont` entirely ends the computation at the operation, which is how early exit and short-circuiting are built.

> **Note:** the continuation is an `Arrow`, deliberately not a `Function1`. `Function1` is specialized on both parameters, so mixing it into every handler node would emit the whole forwarder grid, measured at 19776 generated definitions across this module with no genuine call site. An `Arrow` is applied the same way, `cont(value)`.

### One answer per occurrence

When the clause has nothing to say about the continuation and only wants to answer, `handleLoop` is the shape to use. It never materializes the continuation, and it controls flow through `Loop.Outcome` instead: `Loop.continue(answer)` answers this occurrence and lets the region carry on, `Loop.done(value)` ends the region right there.

```scala
def sayUntil[A, S](stop: String)(v: A < (Say & S)): Maybe[A] < S =
    ArrowEffect.handleLoop(Tag[Say], v)(
        [C] => line => if line == stop then Loop.done(Maybe.empty) else Loop.continue(Kyo.unit),
        a => Maybe(a)
    )

val chatter: Unit < Say = say("a").andThen(say("stop")).andThen(say("b"))

assert(sayUntil("stop")(chatter).eval == Maybe.empty[Unit])
```

The region ends with an empty `Maybe` at the first line equal to `stop`, and with the body's own result wrapped otherwise. Nothing was captured to make that happen, which is what makes this the cheaper shape.

### Carrying state between occurrences

`handleLoopState` is `handleLoop` with a value threaded from one occurrence to the next. The clause receives the current state alongside the input and answers with both the next state and the answer, and the `done` clause sees the final state.

```scala
def runSay[A, S](v: A < (Say & S)): (Chunk[String], A) < S =
    ArrowEffect.handleLoopState(Tag[Say], Chunk.empty[String], v)(
        [C] => (log, line) => Loop.continue(log.append(line), Kyo.unit),
        (log, a) => (log, a)
    )

assert(runSay(say("a").andThen(say("b"))).eval == ((Chunk("a", "b"), ())))
```

This is also where an effect with several operations is interpreted, since the state is usually what the operations read and write. The `Store` declaration from the previous chapter becomes a handler by matching on the operation and answering each at its own type:

```scala
def runStore[A, S](init: Map[String, String])(v: A < (Store & S)): (Map[String, String], A) < S =
    ArrowEffect.handleLoopState(Tag[Store], init, v)(
        [C] =>
            (state, op) =>
                op match
                    case Store.Op.Get(key) =>
                        val found: Maybe[String] = Maybe.fromOption(state.get(key))
                        Loop.continue(state, found)
                    case Store.Op.Put(key, value) =>
                        Loop.continue(state.updated(key, value), Kyo.unit),
        (state, a) => (state, a)
    )

val stored: (Map[String, String], Maybe[String]) < Any =
    runStore(Map.empty)(Store.put("k", "v").andThen(Store.get("k")))

assert(stored.eval == ((Map("k" -> "v"), Maybe("v"))))
```

> **Note:** every region combinator takes the effect row as a pair, `S` for the body and `S2` for whatever the clause adds beyond it. With a single row the typer would pin the body's row before it saw the clause, and a clause that introduces its own effect would need explicit instantiation at each call site.

### Fusing the region's continuation

Each of the three has a `*With` variant that takes what happens after the region as a separate parameter group. The region's result flows straight into that function instead of becoming a value first, which spares a node on a path that is often hot:

```scala
val scaled: Int < Any =
    ArrowEffect.handleContWith(Tag[Ask], ask.map(_ + 1))(
        [C] => (_, cont) => cont(2),
        a => a
    )(b => b * 10)

assert(scaled.eval == 30)
```

`handleLoopWith` and `handleLoopStateWith` are the same transformation applied to the other two shapes. Use them where the region's result is consumed immediately; use the plain form where it is not.

### Binding a value for a scope

A `ContextEffect` is discharged by binding rather than by answering. `ContextEffect.handle` installs a value for the extent of a computation and removes the effect from the row:

```scala
def withLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handle(Tag[Level], n, (_: Int) => n)(v)

assert(withLevel(2)(levelPlus(40)).eval == 42)
```

Two values are supplied, not one. The first is what to bind when nothing is bound already, the second is how to derive from what an enclosing binding holds, which is what makes bindings layer rather than replace:

```scala
val layered: Int < Any =
    withLevel(1)(ContextEffect.handle(Tag[Level], 0, (outer: Int) => outer + 10)(level))

assert(layered.eval == 11)
```

> **Note:** a binding resolves when it is installed, not when it is read. A computation captured under one binding and resumed under a different enclosing binding merges into the one it is resumed under, rather than carrying its original.

Three further parameters describe what happens at the edges of the extent, and each defaults to the plainest answer. `fork` is what a computation forked from here receives, `join` is what this scope holds once a fork ends, and `release` is what the value owes when the extent ends. They belong to [crossing an execution boundary](#crossing-an-execution-boundary), where the reason they are on this call becomes visible.

> **Note:** handler resolution and binding resolution follow different rules. A handler is found by subtyping, so a handler for a subtype answers a supertype's operations and not the reverse. A binding is found by exact tag equality, so a binding for one `ContextEffect` never answers a read of another.

### The continuation as a value

Most handlers never build an `Arrow`; they apply the one they are given. Handlers that construct their own continuations, and code that wants a reusable transformation as a value, build them directly:

```scala
val plusOne: Arrow[Int, Int, Any] = Arrow(x => x + 1)
val plusTwo: Arrow[Int, Int, Any] = plusOne.chain(plusOne)
val sumTo: Arrow[Int, Int, Any]   = Arrow.recursive((self, x) => if x == 0 then 0 else self(x - 1).map(_ + x))

assert(answering(1)(ask.map(a => plusTwo(a))).eval == 3)
assert(sumTo(4).eval == 10)
```

`Arrow.id` is the identity arrow, `chain` composes two, and `Arrow.recursive` hands the body a reference to the arrow being defined, so a recursion runs through the interpreter rather than the JVM stack. An `Arrow` also exposes `frame`, `head` and `tail`, which is what a consumer walking a continuation reads. `Effect.defer` reifies the application of a continuation as a node; it is kernel plumbing that the inline expansions and generated arrow classes call, not something user code writes.

## Failure and resources

Effects describe what a computation asks for. Exceptions are the other thing that can happen to it, and a computation can also simply be dropped, by a clause that never applies its continuation. Recovery and release have to cover all three endings.

### Recovering from a throw

`Effect.catching` installs a recovery scope over a computation. It covers the body being built, being evaluated, and being resumed after a suspension, because the scope is a stack entry the interpreter consults while unwinding rather than a `try` around a call:

```scala
val recovered: Int < Ask =
    Effect.catching(ask.map(a => if a < 0 then throw IllegalArgumentException("negative") else a))(_ => 0)

assert(answering(-1)(recovered).eval == 0)
assert(answering(7)(recovered).eval == 7)
```

> **Note:** the scope ends where the value flows back through it. A throw raised after the computation completes belongs to whatever encloses the scope, not to this one. Fatal errors pass every recovery untouched.

### Acquire, use, release

`Effect.bracket` binds a resource for the extent of a use and guarantees the release runs, whichever way the extent ends:

```scala
var openHandles = 0

val session: Int < Ask =
    Effect.bracket({ openHandles += 1; openHandles })(_ => openHandles -= 1)(id => ask.map(_ + id))

assert(answering(41)(session).eval == 42)
assert(openHandles == 0)
```

The second form of `bracket` also tells the release how the extent ended, which is what a release needs in order to commit on success and roll back otherwise:

```scala
var lastOutcome = ""

val reported: Int < Ask =
    Effect.bracket(1)((id: Int, outcome: Result[Any, Int]) =>
        lastOutcome =
            outcome match
                case Result.Success(v) => s"$id completed with $v"
                case _                 => s"$id did not complete"
    )(id => ask.map(_ + id))

assert(answering(1)(reported).eval == 2)
assert(lastOutcome == "1 completed with 2")
```

> **Note:** the release takes no effects, `Any < Any`, because it has to be able to run where nothing is installed to answer for it, which is all an interpreter that is ending can offer. Three outcomes reach it: the value the extent completed with, the failure an unwind carried through it, and `Finalizer.Abandoned` for an extent that never ended because nobody resumed the continuation holding it.

That third outcome is not hypothetical. A clause that discards its continuation drops the whole remainder of the computation, and every bracket outstanding in that remainder still releases, told it was abandoned:

```scala
var abandoned = 0

val dropped: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], Effect.bracket(1)(_ => abandoned += 1)(id => ask.map(_ + id)))(
        [C] => (_, _) => -1,
        a => a
    )

assert(dropped.eval == -1)
assert(abandoned == 1)
```

### A released scope, entered again

The mirror case is a clause that applies its continuation more than once with a bracket inside the region. The first branch ends the use, so the resource is released; the second branch would resume code that closed over a resource that no longer exists. Entering that scope again raises `Finalizer.Spent`, naming the frame where the resource was opened, rather than handing the second branch a released resource. Re-acquiring cannot rescue this shape, because the continuation starts in the middle of the use and the resource is already captured by the closures the use built.

The arrangement that does work is the other nesting: with the bracket enclosing the multi-shot region rather than sitting inside it, the release point is below the handler, never folded into the captured continuation, and the extent ends once after every branch has run.

### What a failure carries out

When an exception crosses an evaluation boundary, effect-level frames are attached to it as a suppressed `EffectTrace`, so a stack trace from a kyo program shows where the computation was in its own terms and not only in the interpreter's. Nothing is recorded while the computation runs: the frames are reconstructed at the boundary from the failing value and the stack the interpreter is already holding, so a program that never throws pays nothing. One cap of 64 frames covers every boundary an exception crosses.

## Iteration

Recursion through `map` is stack safe here, so any loop can be written as one. `Loop` is the shape that carries state and performs effects between rounds without allocating per round to do it, and it says what a round decided in the round's own answer rather than in a call.

### The general loops

A loop takes its initial state and a body that answers, per round, either the next state or a final value:

```scala
val counted: Int < Ask =
    Loop(0)(i => if i == 3 then Loop.done(i) else ask.map(a => Loop.continue(i + a)))

assert(answering(1)(counted).eval == 3)
```

Up to four state values are carried, each as its own parameter rather than a tuple, so no round allocates one:

```scala
val summed: Int < Any =
    Loop(0, 10)((i, acc) => if i == 3 then Loop.done(acc) else Loop.continue(i + 1, acc + i))

assert(summed.eval == 13)
```

`Loop.indexed` is the same set of shapes with a round counter supplied alongside the state, for the loops that would otherwise carry a counter as one of their four values:

```scala
val indexed: Int < Any =
    Loop.indexed(10)((idx, acc) => if idx == 3 then Loop.done(acc) else Loop.continue(acc + idx))

assert(indexed.eval == 13)
```

### What a round answers with

A round answers with an `Outcome`, built by `Loop.continue` and `Loop.done`. `Outcome2`, `Outcome3` and `Outcome4` are the same thing for the multi-state arities, and `Continue` through `Continue4` are what a continuing answer carries.

> **Note:** `Loop.continue` and `Loop.done` return pending values even though the outcome they carry is settled. That is what lets them type-check as region-clause answers behind nested lambdas, where a raw return would be typed before the conversion and the answer's type minimized. It is the same reason the handler clauses in the previous chapter could write `Loop.continue(...)` directly.

> **Note:** a `done` payload that is itself a computation stays unevaluated data. The loop hands it back as a value, and only the caller evaluating it runs it.

### The shapes that need no state

Four loops carry nothing between rounds and read better than the general form when there is nothing to carry. `Loop.foreach` repeats a body that answers with an outcome, `Loop.repeat(n)` runs a body a fixed number of times, `Loop.whileTrue` tests an effectful condition before each round, and `Loop.forever` never completes on its own:

```scala
var ticks = 0

val repeated: Unit < Any  = Loop.repeat(3)(Kyo.lift[Unit, Any] { ticks += 1 })
val ticker: Nothing < Say = Loop.forever(say("tick"))

repeated.eval
assert(ticks == 3)
assert(sayUntil("tick")(ticker).eval == Maybe.empty[Nothing])
```

`ticker` produces `Nothing`, so nothing downstream of it can run and only a handler can end it. `sayUntil` from the previous chapter is such a handler: it ends the region at the first `"tick"`, which is what "never completes on its own" means in practice.

> **Note:** `Loop.repeat(n)` checks the count before reaching the body, so the body is evaluated exactly `n` times rather than `n + 1`.

## Sequential collection operations

The standard collection methods do not accept an effectful function, so the `Kyo` object carries their counterparts. Each takes a function returning a computation, runs it over the elements one at a time, and answers with a computation of the collected result.

### Mapping over a collection

```scala
val shifted: Chunk[Int] < Ask = Kyo.foreach(Chunk(1, 2, 3))(n => ask.map(_ + n))

assert(answering(10)(shifted).eval == Chunk(11, 12, 13))
```

`foreachIndexed` supplies the position alongside the element, `foreachConcat` flattens what each step produces, and `foreachDiscard` keeps nothing:

```scala
val transcript: (Chunk[String], Unit) < Any = runSay(Kyo.foreachDiscard(Chunk("a", "b"))(say))

assert(transcript.eval == ((Chunk("a", "b"), ())))
```

When the computations already exist rather than being produced per element, `collectAll` runs a collection of them in order and `collectAllDiscard` runs them for their effects. `Kyo.fill(n)(v)` repeats one computation `n` times into a `Chunk`, and `Kyo.zip` runs between two and ten computations in sequence and tuples the results.

### Selecting and folding

Selection and aggregation follow the same pattern, with the predicate or the combining function returning a computation:

```scala
val total: Int < Ask = Kyo.foldLeft(Chunk(1, 2, 3))(0)((acc, n) => ask.map(a => acc + n * a))
val firstBig: Maybe[Int] < Ask =
    Kyo.findFirst(Chunk(1, 2, 3))(n => ask.map(a => if n > a then Maybe(n * 10) else Maybe.empty))

assert(answering(1)(total).eval == 6)
assert(answering(1)(firstBig).eval == Maybe(20))
```

`filter` and `collect` select, `findFirst` stops at the first element the function answers a present `Maybe` for, and `foldLeft`, `scanLeft`, `groupBy` and `groupMap` aggregate. A `Map` source adds `filterKeys` for the case where only the key decides.

### Splitting and branching

`takeWhile`, `dropWhile` and `span` cut a collection at the first element that fails an effectful predicate, and `partition` and `partitionMap` split it in two. Branching on an effectful condition is `Kyo.when` and `Kyo.unless`, which answer with a `Maybe` when there is no other branch to supply a value:

```scala
val announced: Maybe[Unit] < (Ask & Say) = Kyo.when(ask.map(_ > 0))(say("positive"))

assert(answering(1)(runSay(announced)).eval == ((Chunk("positive"), Maybe(()))))
```

`when` also has a two-branch form that takes both arms and answers with their common type, since neither branch is missing there.

### Where the concurrent variants live

Every combinator on the `Kyo` object is sequential. The concurrent counterparts of `foreach`, `collectAll` and the rest live on `Async`, in another module, and taking one of them is a deliberate choice rather than the default. The full set here is provided for `IterableOps`, `List`, `Seq`, `Chunk`, `Set` and `Map`, so the return type matches the collection that went in.

## Crossing an execution boundary

When a computation forks onto a fiber or a parallel branch, it leaves the evaluation that built it. Whatever effect state it was standing in has to be captured where the fork happens, carried into the new evaluation, and brought back when the fork ends. `Isolate` is that contract, and every operation that forks requires one.

### The three type parameters

`Isolate[Remove, Keep, Restore]` names three different roles, and reading them apart is most of understanding the abstraction:

- `Remove` is the effects the isolation itself satisfies. They are handled by the crossing.
- `Keep` is the effects that stay available inside the isolated computation.
- `Restore` is the effects that become available after the isolation completes.

`Remove` and `Restore` differ on purpose. That is what lets a fork capture a stateful effect, run with a copy of its state, and restore only the final value rather than every intermediate update.

Most effects supply their instance implicitly, and an intersection is derived from the components. For a lone `ContextEffect` the derived instance is the pass-through one, since a binding crosses on its own:

```scala
val levelIsolate: Isolate[Level, Any, Level] = Isolate.derive[Level, Any, Level]
```

> **Caution:** effects that short-circuit, `Abort` and `Choice` among them, deliberately provide no isolate. Automatic derivation fixes a handling order, and for those effects the order changes the result, so participation is refused rather than an order picked silently. Handle them before the operation that forks.

### The three phases

An isolate is three methods that run in order, plus the two abstract types they pass between themselves. `State` is what the crossing carries and `Transform[_]` is how the isolated computation's result is wrapped on the way back.

`capture` reads the state where the fork happens, so its row is `Remove` and nothing more. `isolate` runs the computation with that state, with only `Keep` available. `restore` unwraps the transformed result and makes `Restore` available. An instance is exactly those three methods over those two types.

### Driving the phases

`run` composes all three in one call and is what most code wants. The overload that takes an already-captured state exists for a fork that captures once and isolates many branches from that one capture.

```scala
val crossing: Int < (Level & Say) =
    levelIsolate.run(level.map(l => say(s"level $l").andThen(l)))

assert(withLevel(2)(runSay(crossing)).eval == ((Chunk("level 2"), 2)))
```

`Say` was never mentioned by the isolate, and it crossed the boundary untouched: the operation suspended inside the isolation, stayed pending through capture, isolation and restore, and was answered by the handler outside. An isolate manages state; it does not handle arbitrary operations.

`apply` is `run` with the consumer fused in, so the crossed computation is handed straight to whoever asked for it rather than becoming a value of its own. `use` supplies the instance as a `given` to an operation that requires one, which is how a caller picks a strategy for a specific block:

```scala
def forked(using i: Isolate[Level, Any, Level]): Int < Level = i.run(level)

assert(withLevel(3)(levelIsolate.use(forked)).eval == 3)
```

`nest` is the deliberate exception to all of this. Instead of applying `Restore` immediately, it tunnels the removed effects out as a nested `A < Restore`, and the caller decides when the inner layer applies. That is the one place in the module where nesting is the intent, and it is what lets other effects be handled between the two layers:

```scala
val nestingIsolate: Isolate[Level, Say, Any] = Isolate.derive[Level, Say, Any]

val body: Int < (Level & Say) = level.map(l => say(s"level $l").andThen(l))

val tunneled: (Int < Any) < (Level & Say)           = nestingIsolate.nest(body)
val transcribed: (Chunk[String], Int < Any) < Level = runSay(tunneled)
val finished: Int < Level                           = transcribed.map(_._2)

assert(withLevel(5)(finished).eval == 5)
```

`Say` was handled on the outer layer, while the isolated result was still sitting inert in the inner one. The last line is where the inner layer applies: binding through it with `map` collapses it, exactly as `.flatten` would on a value whose nesting is visible in the type.

### Composing and obtaining instances

`Isolate.apply` summons an instance, `Isolate.derive` derives one for an intersection, and the same derivation is also the default `given`, so most code never names either. `andThen` composes two instances by hand: the result combines both `Remove` sets, intersects the `Keep` sets, and combines both `Restore` sets. Reach for it when the order of capture and restore matters, or when a component effect offers several strategies and the derived choice is not the one you want.

> **Note:** derivation composes one instance per component effect, or fails compilation with a message listing four ways to proceed, from handling the effects before the operation to writing an instance by hand. `ContextEffect` components are filtered out of that fold on purpose, because the context half of every isolate already carries them.

### What the bindings themselves decide

That context half is where `ContextEffect.handle`'s remaining parameters come back. The isolate every crossing composes with reads what is bound at the fork and asks each binding what to do, and the three questions it asks are exactly `fork`, `join` and `release` on the call that installed the binding. Writing a binding and writing its crossing behavior are the same call.

`fork` answers what a computation forked from here receives. The default is the value itself, so a binding is inherited. Answering `Maybe.empty` makes the binding invisible to forked computations, which is what a value that must not cross says:

```scala
def withLocalLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handle(Tag[Level], n, (_: Int) => n, fork = (_: Int) => Maybe.empty[Int])(v)
```

`join` answers what this scope holds once a fork has ended, given what it holds and what the fork ended with. Keeping its own value is the default, and taking the fork's or merging the two is the whole of a strategy. `release` is what the bound value owes when the extent ends. A binding whose crossing never happened is left alone at the join, having nothing to be joined with.

## Hiding an effect from inner handlers

Occasionally a computation has to reach past the handlers wrapped around it, because an operation must be answered by the caller's caller rather than by the handler sitting in between. `Mask` is that escape, and it is scoped to one named effect.

```scala
val masked: Int < Mask[Ask]        = Mask[Ask](ask)
val innerAnswered: Int < Mask[Ask] = answering(1)(masked)
val exposed: Int < Ask             = Mask.run[Ask](innerAnswered)

assert(answering(42)(exposed).eval == 42)
```

The inner handler saw nothing, because inside the mask every `Ask` operation was translated into a `Mask[Ask]` operation carrying the original as an unevaluated payload. `Mask.run` is where each payload re-raises the original for the handlers outside that boundary, and the answer flows back into the masked computation.

The effect to hide is named explicitly, `Mask[Ask]` rather than inferred, and only that effect tunnels. Everything else in the row stays answerable where it is:

```scala
val mixed: Int < (Mask[Ask] & Say)                = Mask[Ask](ask.map(a => say(a.toString).andThen(a)))
val saidLocally: (Chunk[String], Int) < Mask[Ask] = runSay(mixed)
val outerAnswered: (Chunk[String], Int) < Ask     = Mask.run[Ask](saidLocally)

assert(answering(7)(outerAnswered).eval == ((Chunk("7"), 7)))
```

`Say` was handled by the local handler even though it sat inside the mask, and the transcript proves the local handler ran with the answer the outer one supplied. Masking the same effect twice behaves as one mask, so a computation that is already masked can be passed through a second mask without changing where its operations are answered.

Masking moves where a value is answered, and that moves where a scope ends with it. A bracket inside a masked computation releases when the outer handler is done with the tunneled continuation, not at the mask boundary. If that outer handler discards the continuation instead of resuming it, the bracket releases there, told the extent was abandoned, exactly as it would without the mask in between.

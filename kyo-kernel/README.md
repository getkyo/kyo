<!-- doctest:default scope=inherited expect=runs -->

# kyo-kernel

`A < S` is the type the whole module exists to serve: a value that will produce an `A` after performing the effects listed in `S`. It is a description of work rather than a running program: an operation in `S` does not happen until a handler answers it, and `eval` only type-checks once `S` has been narrowed to `Any`, so the effect row is a checklist the compiler makes you empty before the computation can run. What `map` does depends on what it is handed. Over a value that has already settled it applies the function on the spot, because there is nothing left to wait for; over one holding a suspension it composes onto the description instead, and the function runs when that suspension is answered. Plain values are computations already (`42` is an `Int < Any`), which is why `map` doubles as `flatMap` and why most code never mentions lifting at all.

An effect is a set of operations with no implementation. `ArrowEffect[Input, Output]` declares operations that take an input and answer with an output; `ContextEffect[A]` declares a value the computation expects to find bound around it. Performing an operation suspends: the description gains a node saying what was asked, and the rest of the computation becomes a continuation hanging off it. That continuation is an ordinary value, an `Arrow`, rather than a stack frame. A handler (`ArrowEffect.handleCont` and its loop-shaped siblings) or a binding (`ContextEffect.handleInheritable`) supplies the answers and removes that effect from the row, and because the handler is holding the continuation as a value it may apply it once, or never, or, where it declares that it will, more than once. That single property is where backtracking, early exit, streaming and retry all come from.

Everything in this module stands on those two paragraphs. The concrete effects a program actually names (`Abort`, `Env`, `Var`, `Emit`, `Async`) are defined elsewhere in terms of them. The module compiles and runs on the JVM, JavaScript, Scala Native and Wasm, with only the safepoint and its failure reporting split per platform.

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

Reading a kyo signature is the first thing to learn, because nearly every effectful function in every kyo module returns one of these. The value type and the effect row are independent: the same combinators work whether the row is empty or names a dozen effects, and composition only ever adds to the row, a handler being the one thing that takes something out of it.

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

`flatten` collapses `A < S < S2` into `A < (S & S2)`. This is the fix the nested-effect error points at, and it is also a deliberate tool: [`Isolate#nest`](#tunneling-instead-of-restoring) builds a nested computation on purpose so the caller can decide when the inner layer applies.

### Reading a stack of handlers left to right

Handlers wrap computations, so writing them out nests inside out and the first handler applied is the one furthest from the eye. `handle` inverts that by taking the transformations as a left-to-right pipeline. It is little more than function application: `answering(1)(ask).map(_ + 1).eval` and the pipeline below are the same value by the same steps. The one difference is that every stage takes its computation by name, so a stage that answers failures sees a throw raised while its own receiver was being built.

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

> **Note:** a computation that never suspended short-circuits. `eval` on a settled value returns it without entering the interpreter at all, so lifting a value and immediately evaluating it costs nothing beyond a type test and a cast.

### Printing

A pending value renders as its payload wrapped in `Kyo(...)`, with the payload rendered by its own `Render` instance:

```scala
assert(render"$settled" == "Kyo(42)")
```

A computation that has not settled renders as what it is waiting on rather than as an opaque address. A bare suspension prints the effect's tag and the call site it suspended at; one that has been composed prints the node holding them, with the suspension inside it. Either way a suspended value in a log line says what it is blocked on.

## Declaring an effect

An effect is a declaration with no implementation anywhere in it. The kernel offers exactly two shapes, and picking between them is the entire design decision: an operation that asks a question and gets an answer is an `ArrowEffect`, and a value that must be in scope is a `ContextEffect`. Both extend `Effect`. Its only other subclass is `Isolate.Disallowed`, the marker behind `Region.NoEscape`, which is a phantom rather than an effect a program declares.

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

Both take a `Tag` identifying the effect and a `Frame` identifying the call site. Neither type is defined in this module; both come from `kyo-data`, and nearly every entry point here requires them. On these two the `Frame` is a leading `using` clause the caller never writes and the tag is an ordinary argument, which is why a call site names the effect and nothing else.

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

A handler supplies the implementation an effect declaration left out, and removes that effect from the row as it does. Three shapes are provided: `handleCont` hands the clause the continuation, `handleLoop` hands it only the operation's input, and `handleLoopState` threads a value from one occurrence to the next. Reach for the most convenient shape that still gives you the control you need, because handing the clause less also gives the evaluator more room.

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

Because `cont` is a value rather than a stack frame, a clause can apply it more than once, or not at all:

```scala
val bothAnswers: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], ask.map(_ * 10))(
        [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
        a => a
    )

assert(bothAnswers.eval == 30)
```

That is the whole mechanism behind non-determinism and backtracking: the region after the suspension ran twice, once per answer, and the clause combined the results. A clause that ignores `cont` entirely ends the computation at the operation, which is how early exit and short-circuiting are built.

Of those three choices, applying it more than once is the one that costs something. The continuation carries the regions that stood between the handler and the suspension, and a plain `handleCont` hands those back when the clause returns, so the first application is what ends them. Where one of them is a bracket, the second application re-enters a scope that has already been released, and is refused. `handleContRepeated` is the same handler with that obligation held until the handler itself ends, and it is the shape for a clause that genuinely resumes more than once; the plain form stays right everywhere else, since holding keeps the obligation longer than a single-shot clause needs. The refusal and the ways around it are in [a released scope, entered again](#a-released-scope-entered-again).

> **Note:** the continuation is an `Arrow`, deliberately not a `Function1`. `Function1` is specialized on both parameters, so mixing it into every handler node would emit the whole forwarder grid, measured at 19776 generated definitions across this module with no genuine call site. An `Arrow` is applied the same way, `cont(value)`.

### One answer per occurrence

When the clause has nothing to say about the continuation and only wants to answer, `handleLoop` is the shape to use. It never hands the clause the continuation, and it controls flow through `Loop.Outcome` instead: `Loop.continue(answer)` answers this occurrence and lets the region carry on, `Loop.done(value)` ends the region right there.

```scala
def sayUntil[A, S](stop: String)(v: A < (Say & S)): Maybe[A] < S =
    ArrowEffect.handleLoop(Tag[Say], v)(
        [C] => line => if line == stop then Loop.done(Maybe.empty) else Loop.continue(Kyo.unit),
        a => Maybe(a)
    )

val chatter: Unit < Say = say("a").andThen(say("stop")).andThen(say("b"))

assert(sayUntil("stop")(chatter).eval == Maybe.empty[Unit])
```

The region ends with an empty `Maybe` at the first line equal to `stop`, and with the body's own result wrapped otherwise. The clause held nothing to make that happen, and the evaluator answers a run of occurrences of the same effect in one walk rather than rebuilding a handler node per occurrence, which is what makes this the cheaper shape.

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

`handleLoopWith` and `handleLoopStateWith` are the same transformation applied to the other two shapes. Use them where the region's result is consumed immediately; use the plain form where it is not. Each `*With` form has a single overload, with `handle` and `done` both required and no recovery arm, so a region that needs either of those takes the plain form and a `map`.

### Binding a value for a scope

A `ContextEffect` is discharged by binding rather than by answering. `ContextEffect.handleInheritable` installs a value for the extent of a computation and removes the effect from the row:

```scala
def withLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handleInheritable(Tag[Level], n, (_: Int) => n)(v)

assert(withLevel(2)(levelPlus(40)).eval == 42)
```

Two values are supplied, not one. The first is what to bind when nothing is bound already, the second is how to derive from what an enclosing binding holds, which is what makes bindings layer rather than replace:

```scala
val layered: Int < Any =
    withLevel(1)(ContextEffect.handleInheritable(Tag[Level], 0, (outer: Int) => outer + 10)(level))

assert(layered.eval == 11)
```

> **Note:** a binding resolves when it is installed, not when it is read. A computation captured under one binding and resumed under a different enclosing binding merges into the one it is resumed under, rather than carrying its original.

`handleInheritable` decides the edges of the extent for you: a forked computation receives the binding unchanged and the scope keeps its own value when that fork ends. `ContextEffect.handle` is the form that asks, and it always asks for both `fork`, what a computation forked from here receives, and `join`, what this scope holds once a fork ends. Whether it also asks about the extent ending depends on which overload the first argument selects: give it `ifUndefined` and `ifDefined` and there is nothing further, or a `release` as a fifth argument; give it a single `derive: Maybe[A] => A` instead and `done` and `release` are available with defaults. `fork` and `join` belong to [crossing an execution boundary](#crossing-an-execution-boundary), where the reason they are on this call becomes visible.

> **Note:** both a handler and a binding are found by subtyping, so a region installed at a subtype's tag answers a read at the supertype's, and not the reverse. A read takes the innermost binding whose tag it conforms to.

### A transformation you can hold

So far a computation has been the thing you compose and a handler the thing that consumes it. `Arrow[A, B, S]` is the third piece: a transformation from an `A` to a `B` that may perform `S` on the way, reified as an ordinary value. Reach for one when a transformation has to outlive the expression that built it, so it can be stored, passed around, composed with another, and applied whenever the holder decides.

```scala
val doubleIt: Arrow[Int, Int, Any]  = Arrow(n => n * 2)
val addAnswer: Arrow[Int, Int, Ask] = Arrow(n => ask.map(_ + n))
val pipeline: Arrow[Int, Int, Ask]  = doubleIt.chain(addAnswer)
val same: Arrow[Int, Int, Any]      = Arrow.id[Int]

assert(doubleIt(5).eval == 10)
assert(answering(1)(pipeline(5)).eval == 11)
assert(same.chain(doubleIt)(4).eval == 8)
```

`Arrow(f)` builds one from an ordinary `A => B < S`, and applying it with `arrow(v)` answers a computation rather than a value, which is how an arrow gets to perform effects of its own: `addAnswer` suspends `Ask`, and that shows up as the third type parameter. `chain` composes two by feeding the first result into the second, intersecting both rows. `Arrow.id` returns its input untouched and is the neutral element of that composition, so a fold over a collection of arrows has somewhere to start.

This is the same type a handler clause is handed. The `cont` in a `handleCont` clause is an `Arrow` from the operation's answer to the region's result, so it composes and applies like any other, and nothing ties it to the frame the operation suspended from.

```scala
val reused: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], ask.map(_ + 1))(
        [C] =>
            (_, cont) =>
                cont.chain(doubleIt)(0).map(a => cont.chain(doubleIt)(20).map(b => a + b)),
        a => a
    )

assert(reused.eval == 44)
```

The clause never treated `cont` as anything special. It composed it with an arrow written further up and applied that composition twice, with different answers, which is ordinary handling for a value of this type.

One thing does set it apart, and it is in the row rather than in anything the value does. A clause receives an `Arrow[O[C], A, E & S & S2 & Region.NoEscape]` and owes back an `A < (E & S & S2 & Region.NoEscape)`. That marker is the statement that the continuation carries the regions that stood between the handler and the suspension, a bracket among them, and that the handler releases what they carry when the clause returns. So the continuation is valid on this fiber, inside this clause, and nowhere else. Handing it back to the region compiles, because the region's own answer carries the marker and discharges it. Sending it elsewhere does not: crossing an execution boundary asks for an `Isolate` covering the row, no `Isolate` can be derived for the marker, and the derivation stops compilation saying so, rather than leaving a resource to be released under a computation still using it.

So a clause's continuation does not outlive the region it came from. What a scheduler parks when it suspends a fiber is the pending computation itself, not a continuation a clause was handed. And a handler that gives the rest of the computation out as a value, the way `Stream.splitAt` and `Emit.runFirst` do, receives an unmarked one from an entry point the kernel keeps for that purpose, with the obligations of the regions inside it traveling along with the value.

`Arrow.recursive` builds one that can re-enter itself. Its body receives the arrow being defined alongside the value, so a step that loops names `self` rather than constructing a fresh arrow per iteration:

```scala
val askTimes: Arrow[Int, Int, Ask] =
    Arrow.recursive((self, n) => if n == 0 then 0 else ask.map(a => self(n - 1).map(_ + a)))

assert(answering(2)(askTimes(3)).eval == 6)
```

`Arrow` also carries members belonging to the evaluator's protocol rather than to callers, the two-argument `apply` and the `head`/`tail` split among them: they are public only because inline expansions have to reach them, and user code has no reason to call them. `Effect.defer` is split the same way. Its `Arrow`-taking overloads reify the application of a continuation as a node for those expansions to hand back, while `Effect.defer(block)`, taking its block by name, is an ordinary call and the way to turn a piece of plain code into a node the evaluator reaches rather than something that runs where it is written.

## Failure and resources

Effects describe what a computation asks for. Exceptions are the other thing that can happen to it, and a computation can also simply be dropped, by a clause that never applies its continuation. Recovery and release have to cover all three endings.

### Recovery is a clause, not a wrapper

Nothing here installs a recovery scope around a computation. A throw is answered by the handler that is already discharging an effect, through a third clause standing alongside `handle` and `done`:

```scala
def answeringOrElse[S](n: Int, fallback: Int)(v: => Int < (Ask & S)): Int < S =
    ArrowEffect.handleCont(Tag[Ask], v)(
        [C] => (_, cont) => cont(n),
        a => a,
        _ => Maybe(fallback)
    )

val checked: Int < Ask = ask.map(a => if a < 0 then throw IllegalArgumentException("negative") else a)

assert(answeringOrElse(7, 0)(checked).eval == 7)
assert(answeringOrElse(-1, 0)(checked).eval == 0)
```

The clause's type is the teaching: `Throwable => Maybe[B < (S & S2)]`. That `Maybe` is a decline channel rather than an optional result. `Present(replacement)` substitutes the region's result and the region ends there; `Absent` declines, and the failure keeps unwinding past this region to whatever stands outside it. On `handleLoopState` the clause is handed the state as well, and for a throw raised while the input was being built the state it sees is the initial one, the only one the region has had.

Adding the third clause also changes when the receiver is evaluated. Only the three-clause overloads take the computation by name, which is what makes a throw raised while the receiver is being built the region's to answer too, and it is why `answeringOrElse` passes its own receiver on by name rather than forcing it first:

```scala
def checkedPlus(n: Int): Int < Ask =
    if n < 0 then throw IllegalArgumentException("negative") else ask.map(_ + n)

assert(answeringOrElse(1, 0)(checkedPlus(-1)).eval == 0)
```

The extent a recovery covers is the whole life of its region: the receiver being built, being evaluated, and being resumed after a park or after the evaluator's own budget rescue. That reach comes from the recovery being an entry the evaluator consults while unwinding rather than a `try` around a call. Two limits are worth naming, because both are places a reader guesses wrong. A throw the clause itself raises is not the inner region's to answer, so it passes over a recovery standing inside that region rather than being caught by it. And a recovery does not reach into a computation that was boxed rather than run, nothing in it having been evaluated yet. Fatal errors pass every recovery untouched.

> **Note:** failure a program declares in its own type, rather than a throw, is `Abort`, and it lives in kyo-prelude one layer above this module. The kernel answers throws.

### Acquire, use, release

`Bracket(acquire)(use)(release)` binds a resource for the extent of a use and runs the release exactly once, whichever way that extent ends. It is not exported into the `kyo` package, so it arrives through the `import kyo.kernel.*` at the top of this document; there is no `kyo.Bracket`.

What the release is told follows from what the previous chapter established. A clause holds the rest of the computation as a value and may apply it more than once, so an extent can end more than once, with a different value each time, and there is no single value to hand a release. What it is told instead is the resource and how its extent ended, never what the use produced:

```scala
var endings: Chunk[(Int, Maybe[Throwable])] = Chunk.empty[(Int, Maybe[Throwable])]

def note(id: Int, ending: Maybe[Throwable]): Unit =
    endings = endings.append((id, ending))

val session: Int < Ask = Bracket(1)(id => ask.map(_ + id))(note)

assert(answering(41)(session).eval == 42)
assert(endings == Chunk((1, Maybe.empty[Throwable])))
```

The ending is one `Maybe[Throwable]`. `Absent` is a clean end, which is what the transcript above recorded; `Present(t)` carries either the failure an unwind took through the extent or the signal an abandoned remainder is discarded with. Three ways for an extent to end, and two things a release is ever told.

> **Note:** the release is a plain `(A, Maybe[Throwable]) => Unit` rather than a computation, because it runs where nothing is installed to answer for an effect, which is all an evaluator that is ending can offer, and its result is discarded for the same reason. A release exists to act outside the computation, closing a socket or handing a permit back, so nothing it does is observable to the computation it belonged to.

The third ending is not hypothetical. A clause that discards its continuation drops the whole remainder of the computation, and every bracket outstanding in that remainder still releases, told the discard signal rather than `Absent`, its extent never having run to an end:

```scala
val dropped: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], session)(
        [C] => (_, _) => -1,
        a => a
    )

assert(dropped.eval == -1)
assert(endings.last._2.exists(_.isInstanceOf[KyoException]))
```

The clause answered `-1` without ever applying `cont`, so the `ask.map(_ + id)` behind it never ran and the region completed at the operation. The bracket inside the dropped remainder released on the way out, and this is the path where how the extent ended is information the release could not have worked out for itself.

`Bracket.ensuring(release)(body)` is the entry point for the case with nothing to acquire: release first, body second and by name, and the release handed only the ending. It is not sugar for `Bracket(())`, and the difference shows exactly here. `apply` cannot install its region until the acquire's value arrives, the release being owed that value, so a computation abandoned before it ever ran has no region and nothing to release. `ensuring` is a node from the start, and the abandonment walk finds it whether or not a single step ever ran.

> **Note:** a bracket closes only with the scope that installed it. An isolated child, a spawned fiber among them, gets an inert copy of the region that neither completes, releases, nor refuses, so a child never releases a resource the scope that acquired it is still using.

### A released scope, entered again

The mirror case is a clause that applies its continuation more than once with a bracket inside the region. The first branch ends the use, so the resource is released; the second branch would resume code that closed over a resource that no longer exists. Rather than hand that branch a released resource, entering the scope again is refused:

```scala
val branched: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], session)(
        [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        a => a
    )

val refused: String =
    try
        val _ = branched.eval
        "no refusal"
    catch case _: Closed => "refused"

assert(refused == "refused")
```

The refusal is a `kyo.Closed`, and it is also the one signal a caller gets that a release ran, since the scope could only be spent if the first branch had already released it. It carries no stack trace at all, which is why its message names the `Bracket` call site the resource was opened at, and why the message explains itself rather than leaving the reader a frame to chase: the first resumption ends the extent and releases, and a handler that resumes the same continuation more than once, as `Choice` does, has that effect whenever the bracket sits between the handler and the suspension it answers.

Three arrangements avoid it, and which is right depends on what the branches need. Acquire inside the branch, and every resumption gets a resource of its own. Put the bracket outside the handler, and its extent is not what gets replayed: the release point sits below the handler, never folded into the captured continuation, and the extent ends once after every branch has run. Or keep the shape and hand the job to `handleContRepeated`, which holds the region instead of giving it back when the clause returns, so one release fires where the handler itself ends.

### What a failure carries out

When an exception crosses an evaluation boundary, effect-level frames are attached to it as a suppressed `EffectTrace`, so a stack trace from a kyo program shows where the computation was in its own terms and not only in the interpreter's. Nothing is recorded while the computation runs: the frames are reconstructed at the boundary from the failing value and the stack the interpreter is already holding, so a program that never throws pays nothing. One cap of 64 frames covers every boundary an exception crosses.

## Iteration

Recursion through `map` is stack safe here, so any loop can be written as one. That safety has a shape worth knowing: a deep synchronous chain does not recurse freely, it suspends periodically and is resumed, so its depth is paid in heap rather than in stack. `Loop` is the shape that carries state and performs effects between rounds without building a fresh continuation per round to do it, the node that defers the rest of the loop being made once and reused across rounds, and it says what a round decided in the round's own answer rather than in a call.

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

`Loop.indexed` is the same set of shapes with a round counter supplied alongside the state, plus a state-free one carrying only the counter, for the loops that would otherwise spend one of their four values on it:

```scala
val indexed: Int < Any =
    Loop.indexed(10)((idx, acc) => if idx == 3 then Loop.done(acc) else Loop.continue(acc + idx))

assert(indexed.eval == 13)
```

### What a round answers with

A round answers with an `Outcome`, built by `Loop.continue` and `Loop.done`. `Outcome2`, `Outcome3` and `Outcome4` are the same thing for the multi-state arities, and `Continue` through `Continue4` are what a continuing answer carries.

> **Note:** `Loop.continue` and `Loop.done` answer with a computation even though the outcome they carry is settled, because a clause may suspend before it continues. It also lets them type-check as region-clause answers behind nested lambdas, where a raw return would be typed before the conversion and the answer's type minimized, which is why the handler clauses in the previous chapter could write `Loop.continue(...)` directly.

> **Note:** a `done` payload that is itself a computation stays unevaluated data. The loop hands it back as a value, and only the caller evaluating it runs it.

### The shapes that need no state

Four loops carry nothing between rounds and read better than the general form when there is nothing to carry. `Loop.foreach` repeats a body that answers with an outcome, `Loop.repeat(n)` runs a body a fixed number of times, `Loop.whileTrue` tests an effectful condition before each round, and `Loop.forever` never completes on its own:

```scala
val repeated: Unit < Say  = Loop.repeat(3)(say("tick"))
val ticker: Nothing < Say = Loop.forever(say("tick"))

assert(runSay(repeated).eval == ((Chunk("tick", "tick", "tick"), ())))
assert(sayUntil("tick")(ticker).eval == Maybe.empty[Nothing])
```

`ticker` produces `Nothing`, so nothing downstream of it can run and only a handler can end it. `sayUntil` from the previous chapter is such a handler: it ends the region at the first `"tick"`, which is what "never completes on its own" means in practice.

> **Note:** `Loop.repeat(n)` checks the count before reaching the body, so the body is evaluated exactly `n` times rather than `n + 1`. The transcript above is the evidence: three rounds, three lines, and a fourth round would have added a fourth.

## Sequential collection operations

The standard collection methods do not accept an effectful function, so the `Kyo` object carries their counterparts. Each takes a function returning a computation, runs it over the elements one at a time, and answers with a computation of the collected result.

### Mapping over a collection

The one to start from is `foreach`: it applies the function to each element in order, waits for each computation before beginning the next, and collects the results into the collection type that went in.

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

Every combinator on the `Kyo` object is sequential. The concurrent counterparts of `foreach`, `collectAll` and the rest live on `Async`, in another module, and taking one of them is a deliberate choice rather than the default. The set here is provided for `IterableOps`, `List`, `Seq`, `Chunk`, `Set` and `Map`, so the return type matches the collection that went in. `Map` is the one shape that differs, in both directions: it carries no `foreachIndexed`, since a map has no positions to hand out, and `foreach`, `foreachConcat` and `collect` each have a second overload for a function that does not answer with a pair, which answers with a `Chunk` because there is no map left to rebuild.

## Crossing an execution boundary

When a computation forks onto a fiber or a parallel branch, it leaves the evaluation that built it. Whatever effect state it was standing in has to be captured where the fork happens, carried into the new evaluation, and brought back when the fork ends. `Isolate` is that contract, and every operation that forks requires one.

### The three type parameters

`Isolate[Remove, Keep, Restore]` names three different roles, and reading them apart is most of understanding the abstraction:

- `Remove` is the effects the isolation itself satisfies. They are handled by the crossing.
- `Keep` is the effects that stay available inside the isolated computation.
- `Restore` is the effects that become available after the isolation completes.

`Remove` and `Restore` differ on purpose. That is what lets a fork capture a stateful effect, run with a copy of its state, and restore only the final value rather than every intermediate update.

Most effects supply their instance implicitly, and an intersection is derived from the components. For a lone `ContextEffect` the derived instance is the pass-through one, because the context half every isolate composes with already carries bindings across:

```scala
val levelIsolate: Isolate[Level, Any, Level] = Isolate.derive[Level, Any, Level]
```

> **Caution:** effects that short-circuit, `Abort` and `Choice` among them, deliberately provide no isolate. Automatic derivation fixes a handling order, and for those effects the order changes the result, so participation is refused rather than an order picked silently. Handle them before the operation that forks.

### The three phases

An isolate is three methods that run in order, plus the two abstract types they pass between themselves. `State` is what the crossing carries and `Transform[_]` is how the isolated computation's result is wrapped on the way back.

`capture` reads the state where the fork happens, so its row is `Remove` and nothing more. `isolate` runs the computation with that state, with only `Keep` available. `restore` unwraps the transformed result and makes `Restore` available. An instance is exactly those three methods over those two types.

### Running the phases

`run` composes all three in one call and is what most code wants. `Remove` is still in the row it answers with, because the capture at the front of it has to read the state from the evaluation the fork is leaving, and reading it means standing inside the effect. The overload that takes an already-captured state is the one that does not, and it exists for a fork that captures once and isolates many branches from that one capture.

```scala
val crossing: Int < (Level & Say) =
    levelIsolate.run(level.map(l => say(s"level $l").andThen(l)))

assert(withLevel(2)(runSay(crossing)).eval == ((Chunk("level 2"), 2)))
```

`Say` was never mentioned by the isolate, and it crossed the boundary untouched: the operation suspended inside the isolation, stayed pending through capture, isolation and restore, and was answered by the handler outside. An isolate manages state; it does not handle arbitrary operations.

The instance's own `apply` is `run` with the consumer fused in, so the crossed computation is handed straight to whoever asked for it rather than becoming a value of its own. `use` supplies the instance as a `given` to an operation that requires one, which is how a caller picks a strategy for a specific block:

```scala
def forked(using i: Isolate[Level, Any, Level]): Int < Level = i.run(level)

assert(withLevel(3)(levelIsolate.use(forked)).eval == 3)
```

### Tunneling instead of restoring

`nest` is the deliberate exception to all of this. Instead of applying `Restore` immediately, it hands the removed effects back as the row of a nested `A < Restore`, and the caller decides when that inner layer applies. That is the one place in the module where nesting is the intent, and it is what lets other effects be handled between the two layers:

```scala
val nestingIsolate: Isolate[Level, Say, Level] = Isolate.derive[Level, Say, Level]

val body: Int < (Level & Say) = level.map(l => say(s"level $l").andThen(l))

val tunneled: (Int < Level) < (Level & Say)           = nestingIsolate.nest(body)
val transcribed: (Chunk[String], Int < Level) < Level = runSay(tunneled)
val finished: Int < Level                             = transcribed.map(_._2)

assert(withLevel(5)(finished).eval == 5)
```

`Say` was handled on the outer layer, while the isolated result was still sitting inert in the inner one, whose row is the `Restore` the isolate names. The last line is where that inner layer applies: binding through it with `map` collapses it, exactly as `.flatten` would on a value whose nesting is visible in the type.

### Composing and obtaining instances

`Isolate.apply` summons an instance, `Isolate.derive` derives one for an intersection, and the same derivation is also the default `given`, so most code never names either. `andThen` composes two instances by hand, intersecting each of the three parameters with its counterpart. Reach for it when the order of capture and restore matters, or when a component effect offers several strategies and the derived choice is not the one you want.

> **Note:** derivation composes one instance per component effect, or fails compilation with a message listing four ways to proceed, from handling the effects before the operation to writing an instance by hand. `ContextEffect` components are filtered out of that fold on purpose, because the context half of every isolate already carries them.

### What the bindings themselves decide

That context half is where `ContextEffect.handle`'s remaining parameters come back. The isolate a crossing composes with reads what is bound where the fork happens and puts two questions to every binding it finds, and both are answered on the call that installed the binding. Writing a binding and writing its crossing behavior are the same call.

The first is `fork`, which computes the value a forked computation starts with from the value the parent holds. A binding always crosses; `fork` only decides with what. The default, and what `handleInheritable` installs, is the parent's own value, so the binding is inherited unchanged. A value that must not travel answers with a neutral one of its own type instead, which is why `kyo.Local` keeps its per-local "do not inherit" choice inside the value it binds rather than in this strategy.

The second is `join`, which computes what the scope holds once a fork has ended. It is handed three values, the parent's, the one the fork started with, and the one the fork ended with, and the middle one is what makes a merge possible: with it a strategy can apply the fork's delta rather than overwrite:

```scala
def withMergedLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handle(Tag[Level])(
        n,
        (outer: Int) => outer,
        (parent: Int) => parent,
        (parent: Int, started: Int, ended: Int) => parent + (ended - started)
    )(v)

assert(withMergedLevel(2)(levelPlus(40)).eval == 42)
```

Note the shape, since it is not `handleInheritable`'s: the tag stands alone in the first list, the strategies fill the second, and the computation comes last. In order the four are the two that decide the bound value, `ifUndefined` and `ifDefined`, then `fork` and `join`. Keeping the parent's value, which is what `handleInheritable`'s join does, is the do-nothing answer to the second question.

`done` and `release` answer a different question, and they fire whether or not anything ever forked. `done` is what the bound value owes when its region completes normally, and `release` is what it owes when the region ends with a failure, which it is handed. Between them they are how a bound value that owns something outside the computation gets to close it at either exit.

## Hiding an effect from inner handlers

Occasionally a computation has to reach past the handlers wrapped around it, because an operation must be answered by the caller's caller rather than by the handler sitting in between. `ArrowEffect.Mask` is that escape, and it is scoped to the effect type it is given, which may be an intersection where several effects have to tunnel together.

```scala
import kyo.kernel.ArrowEffect.Mask

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

Nor is masking limited to arrow effects. The region shadows its tag in the context as well as on the stack, so a `ContextEffect` read inside a mask of its effect tunnels past an inner binding and is answered by the binding outside, exactly as an operation tunnels past an inner handler.

Masking moves where a value is answered, and that moves where a scope ends with it. A bracket inside a masked computation releases when the outer handler is done with the tunneled continuation, not at the mask boundary. If that outer handler discards the continuation instead of resuming it, the bracket releases there, told the discard signal, exactly as it would without the mask in between.

## From description to result

One value can carry every move the module makes. `Ask`, `Say` and `Level` were declared once and have not changed since; `ask`, `say` and `level` suspend against them; `levelIsolate.run` prepares the whole thing to cross into another evaluation; and a handler per arrow effect, plus a binding for the context effect, take the row apart one call at a time, the innermost written first.

```scala
val program: Int < (Ask & Say & Level) =
    level.map(l =>
        Kyo.foreach(Chunk(1, 2))(n => ask.map(_ * n)).map(answers =>
            say(s"level $l saw ${answers.size} answers").andThen(answers.sum + l)
        )
    )

val prepared: Int < (Ask & Say & Level) = levelIsolate.run(program)

val result: (Chunk[String], Int) < Any = withLevel(10)(answering(3)(runSay(prepared)))

assert(result.eval == ((Chunk("level 10 saw 2 answers"), 19)))
```

The row on `program` is the list of what the value still needs: an answer for `ask`, a listener for `say`, and a level bound around it. Each of the last three calls discharges exactly one of them, and `eval` type-checks on the final line only because nothing is left in the row. Until that line, none of it had run.

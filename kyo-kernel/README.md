<!-- doctest:default scope=inherited expect=runs -->

# kyo-kernel

`A < S` is the type the whole module exists to serve: a value that will produce an `A` after performing the effects listed in `S`. It is a description of work rather than a running program. An operation in `S` does not happen until a handler answers it, and `eval` only type-checks once `S` has been narrowed to `Any`, so the effect row is a checklist the compiler makes you empty before the computation can run.

An effect is a set of operations with no implementation. `ArrowEffect[Input, Output]` declares operations that take an input and answer with an output; `ContextEffect[A]` declares a value the computation expects to find bound around it. Performing an operation suspends: the description gains a node saying what was asked, and the rest of the computation becomes a continuation hanging off it. That continuation is an ordinary value, an `Arrow`, rather than a stack frame. A handler (`ArrowEffect.handleCont` and its loop-shaped siblings) or a binding (`ContextEffect.handleInheritable`) supplies the answers and removes that effect from the row. Because the handler holds the continuation as a value, it may apply it once, more than once, or never, and that single property is where backtracking, early exit, streaming and retry all come from.

What `map` does depends on what it is handed. Over a value that has already settled it applies the function on the spot, because there is nothing left to wait for; over one holding a suspension it composes onto the description instead, and the function runs when that suspension is answered. Plain values are computations already (`42` is an `Int < Any`), which is why `map` doubles as `flatMap` and why most code never mentions lifting at all.

Everything in this module stands on those three paragraphs. The concrete effects a program actually names (`Abort`, `Env`, `Var`, `Emit`, `Async`) are defined elsewhere in terms of them. The module compiles and runs on the JVM, JavaScript, Scala Native and Wasm. Only the safepoint, the periodic check that bounds stack use and lets a scheduler stop a computation, and its failure reporting are split per platform.

```scala
import kyo.*
import kyo.kernel.*

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

object Ask:
    def get: Int < Ask =
        ArrowEffect.suspend[Any](Tag[Ask], ())

    def run[A, S](n: Int)(v: A < (Ask & S)): A < S =
        ArrowEffect.handleCont(Tag[Ask], v)([C] => (_, cont) => cont(n))
end Ask

val question: Int < Ask = Ask.get.map(_ + 1)

val answered: Int < Any = Ask.run(41)(question)

assert(answered.eval == 42)
```

`Ask.get` performs the operation, so `question` is a description holding a suspension with `_ + 1` waiting behind it, and nothing has run. `Ask.run` answers with `41` by applying the continuation once, which discharges `Ask` and leaves `Int < Any`. Only then does `eval` type-check.

The shape of that declaration is the convention every effect in kyo follows, and it is the one to copy. The trait declares the operations and carries no implementation. The companion owns both halves of the effect: the operations a caller performs, and the handlers that answer them. `Env`, `Var`, `Emit` and `Abort` are all built this way, which is why user code writes `Env.get` and `Env.run` and never names `ArrowEffect` at all. Reach for the kernel API when you are writing an effect, not when you are using one.

`Ask` is the first of a small vocabulary the examples below keep reusing.

## `A < S`: the pending type

Reading a kyo signature is the first thing to learn, because nearly every effectful function in every kyo module returns one of these. The value type and the effect row are independent: the same combinators work whether the row is empty or names a dozen effects. Composition only ever adds to the row; a handler is the one thing that takes something out of it.

Effects accumulate as an intersection, and intersections are unordered. A row of `E1 & E2` says both effects may occur, not which occurs first.

### Composing computations

`map` is the monadic bind. It threads a function over the eventual value and accumulates that function's own effects into the row.

```scala
val twice: Int < Ask      = Ask.get.map(a => Ask.get.map(b => a + b))
val second: Int < Ask     = Ask.get.andThen(Ask.get)
val discarded: Unit < Ask = Ask.get.unit

assert(Ask.run(1)(twice).eval == 2)
```

`andThen` sequences two computations and keeps the second result. `unit` runs a computation for its effects and produces `Unit`, which is what a caller wants when only the suspension mattered.

> **Note:** `map` and `flatMap` are the same operation. `flatMap` exists only so for-comprehensions parse; `map` is the one to reach for everywhere else.

### Plain values are computations already

Nothing in the examples above had to be lifted, because a bare value is accepted wherever a computation is expected. That is an implicit conversion, and it is also why `map` never needs a `pure` sibling: the function passed to `map` may return `Int` or `Int < S` and both type-check.

Inference occasionally needs the conversion spelled out, most often across the branches of an `if`, where the two arms are typed before the expected type is known. `Kyo.lift` is that explicit form, and `Kyo.unit` is the `Unit` case:

```scala
val verbose: Boolean = false

val chosen: Int < Ask   = if verbose then Kyo.lift(0) else Ask.get
val nothing: Unit < Any = Kyo.unit
```

Neither introduces a suspension. For an ordinary value both expand to the value itself, so the evaluator, the loop that `eval` runs, is never entered and there is nothing for it to unwrap later.

> **Note:** the lift accepts a plain value and nothing else, so the mistakes it might otherwise hide are caught where they are written. Lifting a computation into another computation is refused, and the fix is `.flatten` or splitting the expression into two statements. A bare module object is refused as well, which is how a forgotten argument list on something like `Abort` is caught rather than silently becoming a value. So is a `Unit` computation whose row does not match the one expected.

### Nesting, and flatten

The refusal above is what `CanLift` is for, and it works by asking whether the type being lifted is a computation. At a concrete type it can answer, and a computation is rejected. Inside a generic function it cannot: the type parameter is abstract, there is nothing to test, and the lift fires. So nesting is not something the conversion offers, it is what happens where the constraint cannot see what it is looking at:

```scala
def wrap[A](a: A): A < Any = a

val nested: (Int < Ask) < Any = wrap(Ask.get.map(_ + 1))
val merged: Int < Ask         = nested.flatten

assert(Ask.run(1)(merged).eval == 2)
```

Inside `wrap` the lift sees an abstract `A`. At the call site `A` is `Int < Ask`, so what comes back is a computation nested exactly once, and it stays inert data until someone flattens it.

`flatten` collapses `A < S < S2` into `A < (S & S2)`. This is the fix the nested-effect error points at, and it is also a deliberate tool: `Isolate#nest`, covered under [crossing an execution boundary](#nest-holding-the-restore-back), builds a nested computation on purpose so the caller can decide when the inner layer applies.

### Running

`eval` is defined only on `A < Any`. A row that still names an effect has no `eval` to call, which is the compile-time half of the guarantee that no operation reaches the evaluator unanswered.

```scala
val settled: Int < Any = 42

assert(settled.eval == 42)
```

> **Note:** a computation that never suspended costs nothing to run. `eval` on a settled value returns it without entering the evaluator at all, so lifting a value and immediately evaluating it is a type test and a cast.

### Printing

A pending value renders as its payload wrapped in `Kyo(...)`, with the payload rendered by its own `Render` instance:

```scala
assert(render"$settled" == "Kyo(42)")
```

A computation that has not settled renders as what it is waiting on rather than as an opaque address. A bare suspension prints the effect's tag and the call site it suspended at; one that has been composed prints the node holding them, with the suspension inside it. Either way a suspended value in a log line says what it is blocked on.

## Declaring an effect

An effect is a declaration with no implementation anywhere in it. The kernel offers exactly two shapes, and picking between them is the entire design decision: an operation that asks a question and gets an answer is an `ArrowEffect`, and a value that must be in scope is a `ContextEffect`. Both extend `Effect`. Its only other subclass is `Isolate.Disallowed`, a phantom marker rather than an effect a program declares: it is what `Region.NoEscape` names, the row entry that confines a handler's continuation to the handler, covered under [the continuation cannot leave its region](#regionnoescape-the-continuation-cannot-leave-its-region).

### `ArrowEffect`: operations with an input and an answer

`ArrowEffect[Input, Output]` is parameterized by two type constructors rather than two types, because one effect usually carries a family of operations. An operation is a transformation from `Input[C]` to `Output[C]` for some `C`, and the handler must answer at whatever `C` the call site chose.

Two type constructors cover nearly every declaration. `Const[A]` ignores its parameter and always answers `A`; `Id[A]` passes the parameter through unchanged. `Ask` from the opening uses `Const` on both sides, since its operation takes nothing meaningful and always answers an `Int`. The effect that accompanies it through the rest of this document is shaped the same way, with a `String` going in and nothing coming back:

```scala
sealed trait Say extends ArrowEffect[Const[String], Const[Unit]]

def say(line: String): Unit < Say = ArrowEffect.suspend[Any](Tag[Say], line)
```

`ArrowEffect.suspend` performs an operation. `suspendWith` performs it and fuses the transformation of the answer into the same node, which is what a helper wants when it would otherwise write `suspend(...).map(f)`:

```scala
def askPlus(n: Int): Int < Ask = ArrowEffect.suspendWith[Any](Tag[Ask], ())(a => a + n)

assert(Ask.run(1)(askPlus(41)).eval == 42)
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

### `ContextEffect`: a value bound around the computation

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

A required read cannot be reached without a binding: the effect is in the row, and a computation only evaluates once its row is empty, so the compiler has already ruled the case out across the whole program. The raise that sits behind a missing binding is reachable only by discarding the row with a cast, which is the one thing not to do here. Reach for a handler or a default.

## Answering an effect

A handler supplies the implementation an effect declaration left out, and removes that effect from the row as it does. Two words recur from here on. The function a handler runs at each operation is its clause. The computation a handler wraps, up to the result it answers with, is its region, and the run of a region from entry to end is its extent.

Three shapes are provided: `handleCont` hands the clause the continuation, `handleLoop` hands it only the operation's input, and `handleLoopState` threads a value from one occurrence to the next. Reach for the most convenient shape that still gives you the control you need, because handing the clause less also gives the evaluator more room.

| Handler | What the clause gets, and when to reach for it |
| --- | --- |
| `ArrowEffect.handleCont` | The input and the continuation as an `Arrow`. Apply it once to resume, more than once to branch, or never to discard the rest. |
| `ArrowEffect.handleLoop` | Only the input. Control flows through `Loop.Outcome`: `Loop.continue(answer)` answers this occurrence, `Loop.done(value)` ends the region. |
| `ArrowEffect.handleLoopState` | The input and a state value threaded from one occurrence to the next. |
| `ContextEffect.handleInheritable` | A value bound for the extent, inherited unchanged by a fork (a computation started from inside this one to run on its own fiber or branch). |
| `ContextEffect.handleNonInheritable` | The same, except a fork starts the region over rather than inheriting the value, for a value tied to one execution. |
| `ContextEffect.handle` | A value bound for the extent, with `fork` and `join` deciding what a fork starts from and what this scope keeps afterwards. |

Every `ArrowEffect` handler above takes an optional `done` clause, which transforms the region's result, and an optional `recover` clause, which answers a failure, covered under [failure and resources](#recovery-is-a-clause-not-a-wrapper). Each also has a `*With` variant that fuses what happens after the region into the handler itself, covered under [fusing the region's continuation](#the-with-variants-fusing-the-regions-continuation).

### `handleCont`: the continuation in hand

`handleCont` gives the clause the operation's input and the continuation as an `Arrow`, and asks for the region's value back. Applying `cont(answer)` resumes the computation from the suspension point.

```scala
val transformed: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], Ask.get.map(_ + 1))(
        handle = [C] => (_, cont) => cont(2),
        done = a => a * 10
    )

assert(transformed.eval == 30)
```

The second clause is the `done` clause, which transforms the region's final value; the shorter overload used for `Ask.run` earlier omits it and returns the body's own result.

Because `cont` is a value rather than a stack frame, a clause can apply it more than once, or not at all:

```scala
val bothAnswers: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], Ask.get.map(_ * 10))(
        handle = [C] => (_, cont) => cont(1).map(x => cont(2).map(y => x + y)),
        done = a => a
    )

assert(bothAnswers.eval == 30)
```

That is the whole mechanism behind non-determinism and backtracking: the region after the suspension ran twice, once per answer, and the clause combined the results. A clause that ignores `cont` entirely ends the computation at the operation, which is how early exit and short-circuiting are built.

What makes the second application safe is who owns what the continuation carries. Between the handler and the suspension stand the regions the computation entered on the way, and some of them own a resource: a `Bracket`, covered under [failure and resources](#bracket-acquire-use-release), holds one and owes its release when its extent ends. Rather than each application of the continuation ending those regions, the handler holds what they owe and settles it once, where its own region ends. So every application runs against a live resource, and a bracket inside the region releases once however many times the clause resumed. The one thing a clause may not do with the continuation is keep it past its region; [a released scope, entered again](#a-released-scope-entered-again) is what happens to a continuation that outlives the region that owned it.

### `handleLoop`: one answer per occurrence

When the clause has nothing to say about the continuation and only wants to answer, `handleLoop` is the shape to use. It never hands the clause the continuation. Control flows through `Loop.Outcome` instead, the answer type of the `Loop` combinator covered under [iteration](#iteration): `Loop.continue(answer)` answers this occurrence and lets the region carry on, `Loop.done(value)` ends the region right there.

```scala
def runSayUntil[A, S](stop: String)(v: A < (Say & S)): Maybe[A] < S =
    ArrowEffect.handleLoop(Tag[Say], v)(
        handle = [C] => line => if line == stop then Loop.done(Maybe.empty) else Loop.continue(Kyo.unit),
        done = a => Maybe(a)
    )

val chatter: Unit < Say = say("a").andThen(say("stop")).andThen(say("b"))

assert(runSayUntil("stop")(chatter).eval == Maybe.empty[Unit])
```

The region ends with an empty `Maybe` at the first line equal to `stop`, and with the body's own result wrapped otherwise. The clause held nothing to make that happen, and that is what makes this the cheaper shape. `handleCont` has to hand its clause the rest of the region as an `Arrow` it can apply anywhere, then rebuild the region around whatever comes back, since that result may perform the effect again. A `handleLoop` clause only answers, so no continuation is produced for it. The evaluator applies the answer to the one it is already holding and walks straight into the next occurrence of the same effect, in the same pass.

### `handleLoopState`: carrying state between occurrences

`handleLoopState` is `handleLoop` with a value threaded from one occurrence to the next. The clause receives the current state alongside the input and answers with both the next state and the answer, and the `done` clause sees the final state.

```scala
def runSay[A, S](v: A < (Say & S)): (Chunk[String], A) < S =
    ArrowEffect.handleLoopState(Tag[Say], Chunk.empty[String], v)(
        handle = [C] => (log, line) => Loop.continue(log.append(line), Kyo.unit),
        done = (log, a) => (log, a)
    )

assert(runSay(say("a").andThen(say("b"))).eval == ((Chunk("a", "b"), ())))
```

This is also where an effect with several operations is interpreted, since the state is usually what the operations read and write. The `Store` declaration from the previous chapter becomes a handler by matching on the operation and answering each at its own type:

```scala
def runStore[A, S](init: Map[String, String])(v: A < (Store & S)): (Map[String, String], A) < S =
    ArrowEffect.handleLoopState(Tag[Store], init, v)(
        handle = [C] =>
            (state, op) =>
                op match
                    case Store.Op.Get(key) =>
                        val found: Maybe[String] = Maybe.fromOption(state.get(key))
                        Loop.continue(state, found)
                    case Store.Op.Put(key, value) =>
                        Loop.continue(state.updated(key, value), Kyo.unit),
        done = (state, a) => (state, a)
    )

val stored: (Map[String, String], Maybe[String]) < Any =
    runStore(Map.empty)(Store.put("k", "v").andThen(Store.get("k")))

assert(stored.eval == ((Map("k" -> "v"), Maybe("v"))))
```

### Performing effects while handling

A clause is not restricted to what the body performs. It has a row of its own, the `S2` in every handler signature, and whatever it performs there joins the region's result row. The effect it performs may even be the one being handled. A loop clause sits outside the region it serves, so an operation it performs at the handled tag reaches whatever answers that tag further out, rather than arriving back at this clause.

> **Note:** every region combinator takes the effect row as a pair, `S` for the body and `S2` for whatever the clause adds beyond it. With a single row the typer would pin the body's row before it saw the clause, and a clause that introduces its own effect would need explicit instantiation at each call site.

```scala
def shout[A, S](v: A < (Say & S)): A < (Say & S) =
    ArrowEffect.handleLoop(Tag[Say], v)(
        handle = [C] => line => say(line.toUpperCase).andThen(Loop.continue(Kyo.unit))
    )

assert(runSay(shout(say("a").andThen(say("b")))).eval == ((Chunk("A", "B"), ())))
```

Every line the body said arrived at the clause, which said the upper-cased line in its place, and `runSay` outside collected those without ever seeing the originals. This is what a stream stage is: `Stream`'s `map`, `filter` and `tap` are each a `handleLoop` over `Emit` whose clause emits.

The split is in the rows. Only the answer handed back through `Loop.continue` is region currency, a value evaluated inside the region, so an operation at the handled tag in that position is answered by this handler rather than escaping. `handleCont` has no such split: its clause returns region currency directly, which is why a `handleCont` clause that performs the handled effect answers itself.

### The `*With` variants: fusing the region's continuation

Each of the three handler shapes has a `*With` variant that takes what happens after the region as a separate parameter group. The region's result flows straight into that function instead of becoming a value first, which spares a node on a path that is often hot:

```scala
val scaled: Int < Any =
    ArrowEffect.handleContWith(Tag[Ask], Ask.get.map(_ + 1))(
        handle = [C] => (_, cont) => cont(2),
        done = a => a
    )(b => b * 10)

assert(scaled.eval == 30)
```

`handleLoopWith` and `handleLoopStateWith` are the same transformation applied to the other two shapes:

```scala
val doubled: Int < Any =
    ArrowEffect.handleLoopWith(Tag[Say], say("a").andThen(say("b")).andThen(7))(
        handle = [C] => _ => Loop.continue(Kyo.unit),
        done = a => a
    )(n => n * 2)

val logged: String < Any =
    ArrowEffect.handleLoopStateWith(Tag[Say], Chunk.empty[String], say("a").andThen(say("b")).andThen(7))(
        handle = [C] => (log, line) => Loop.continue(log.append(line), Kyo.unit),
        done = (log, a) => (log, a)
    )((log, n) => s"${log.size} lines, answered $n")

assert(doubled.eval == 14)
assert(logged.eval == "2 lines, answered 7")
```

Use them where the region's result is consumed immediately; use the plain form where it is not. Each `*With` form has a single overload, with `handle` and `done` both required and no recovery arm, so a region that needs either of those takes the plain form and a `map`.

### `ContextEffect.handle`: binding a value for a scope

A `ContextEffect` is discharged by binding rather than by answering. `ContextEffect.handleInheritable` installs a value for the extent of a computation and removes the effect from the row:

```scala
def runLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handleInheritable(Tag[Level], n, (_: Int) => n)(v)

assert(runLevel(2)(levelPlus(40)).eval == 42)
```

Two values are supplied, not one. The first is what to bind when nothing is bound already, the second is how to derive from what an enclosing binding holds, which is what makes bindings layer rather than replace:

```scala
val layered: Int < Any =
    runLevel(1)(ContextEffect.handleInheritable(Tag[Level], 0, (outer: Int) => outer + 10)(level))

assert(layered.eval == 11)
```

> **Note:** a binding's value is derived when its region is entered, not when it is read. A region already entered keeps that value for its whole extent, and that includes a park (the evaluator handing the rest of a computation back as a value to resume later, which is how a scheduler preempts a fiber) followed by a resumption under a different enclosing binding. A region not yet entered derives from whatever is bound around it at the moment it is.

`handleInheritable` decides the edges of the extent for you: a fork receives the binding unchanged, and the scope keeps its own value when that fork ends. `ContextEffect.handle` is the form that asks. It always takes both `fork`, what a computation forked from here receives, and `join`, what this scope holds once a fork ends. `handleNonInheritable` is the other shorthand over it: a fork is handed the value the region would have taken with nothing bound outside it, so it starts the region over instead of continuing it, which is what a value tied to one execution needs. `handle` can also ask about the extent ending, through a `release` strategy handed the bound value and how the extent ended. With `ifUndefined` and `ifDefined` as the value arguments, `release` is an optional sixth argument; with a single `derive: Maybe[A] => A` in their place, it has a no-op default. `fork`, `join` and `release` are covered under [what the bindings themselves decide](#what-the-bindings-themselves-decide), where the reason they are on this call becomes visible.

> **Note:** both a handler and a binding are found by subtyping, so a region installed at a subtype's tag answers a read at the supertype's, and not the reverse. A read takes the innermost binding whose tag it conforms to.

### `handle`: reading a stack of handlers left to right

Handlers wrap computations, so a stack of them written out nests inside out and the one applied first sits furthest from the eye. `handle` inverts that, taking the handlers as a left-to-right pipeline that reads in the order they apply:

```scala
val asked: Int < (Ask & Say) = say("asking").andThen(Ask.get)

val stacked: Maybe[Int] < Any = asked.handle(v => runSayUntil("stop")(v), v => Ask.run(1)(v))

assert(stacked.eval == Maybe(1))
```

`runSayUntil` discharges `Say` and `Ask.run` discharges `Ask`, so the row empties as the eye moves right. The result is what `Ask.run(1)(runSayUntil("stop")(asked))` produces, by the same steps. Ten arities are provided, so a stack can be ten handlers deep.

Handlers are what it is for. Any function from a computation fits, since the combinator is little more than function application, but a `map` or an `eval` written as a stage only hides the method call it stands for. What the combinator does add is that every stage takes its computation by name, so a stage that answers failures (through the `recover` clause covered under [failure and resources](#recovery-is-a-clause-not-a-wrapper)) sees a throw raised while its own receiver was being built.

### `Arrow`: a transformation you can hold

So far a computation has been the thing you compose and a handler the thing that consumes it. `Arrow[A, B, S]` is the third piece: a transformation from an `A` to a `B` that may perform `S` on the way, reified as an ordinary value. Reach for one when a transformation has to outlive the expression that built it, so it can be stored, passed around, composed with another, and applied whenever the holder decides.

```scala
val doubleIt: Arrow[Int, Int, Any]  = Arrow(n => n * 2)
val addAnswer: Arrow[Int, Int, Ask] = Arrow(n => Ask.get.map(_ + n))
val pipeline: Arrow[Int, Int, Ask]  = doubleIt.chain(addAnswer)
val same: Arrow[Int, Int, Any]      = Arrow.id[Int]

assert(doubleIt(5).eval == 10)
assert(Ask.run(1)(pipeline(5)).eval == 11)
assert(same.chain(doubleIt)(4).eval == 8)
```

`Arrow(f)` builds one from an ordinary `A => B < S`, and applying it with `arrow(v)` answers a computation rather than a value, which is how an arrow gets to perform effects of its own: `addAnswer` suspends `Ask`, and that shows up as the third type parameter. `chain` composes two by feeding the first result into the second, intersecting both rows. `Arrow.id` returns its input untouched and is the neutral element of that composition, so a fold over a collection of arrows has somewhere to start.

This is the same type a handler clause is handed. The `cont` in a `handleCont` clause is an `Arrow` from the operation's answer to the region's result, so it composes and applies like any other, and nothing ties it to the frame the operation suspended from.

```scala
val reused: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], Ask.get.map(_ + 1))(
        handle = [C] =>
            (_, cont) =>
                cont.chain(doubleIt)(0).map(a => cont.chain(doubleIt)(20).map(b => a + b)),
        done = a => a
    )

assert(reused.eval == 44)
```

The clause never treated `cont` as anything special. It composed it with an arrow written further up and applied that composition twice, with different answers, which is ordinary handling for a value of this type.

One thing does set it apart, and it is in the row rather than in anything the value does: the continuation a clause receives carries `Region.NoEscape`, which confines it to that clause, for the reasons given under [the continuation cannot leave its region](#regionnoescape-the-continuation-cannot-leave-its-region) at the end of this chapter. An arrow you build yourself carries no such marker and goes wherever you send it. What a scheduler parks when it suspends a fiber is the pending computation itself, not a continuation a clause was handed.

> **Note:** the continuation is an `Arrow`, deliberately not a `Function1`. `Function1` is specialized on both parameters, so mixing it into every handler node would emit the whole grid of specialized `apply` forwarders, none with a genuine call site anywhere in the module. An `Arrow` is applied the same way, `cont(value)`.

`Arrow.recursive` builds one that can re-enter itself. Its body receives the arrow being defined alongside the value, so a step that loops names `self` rather than constructing a fresh arrow per iteration:

```scala
val askTimes: Arrow[Int, Int, Ask] =
    Arrow.recursive((self, n) => if n == 0 then 0 else Ask.get.map(a => self(n - 1).map(_ + a)))

assert(Ask.run(2)(askTimes(3)).eval == 6)
```

### `head` and `tail`: why an arrow exposes its own composition

An arrow is applied two ways: `arrow(value)`, and `apply(value, cont)` which takes the rest of the computation as a second argument so the intermediate never becomes a value. Every hot path uses the second.

`head` and `tail` split an arrow into the part that does work and the part that follows it: for a composition its two links, for an atom the arrow itself and `Arrow.id`. One expression then covers both.

```scala
val double: Arrow[Int, Int, Any] = Arrow(_ * 2)
val incr: Arrow[Int, Int, Any]   = Arrow(_ + 1)
val both: Arrow[Int, Int, Any]   = double.chain(incr)

assert(both(5).eval == 11)

// what a hot path writes, correct for a composition and an atom alike
assert(both.head(5, both.tail).eval == 11)
assert(double.head(5, double.tail).eval == 10)
```

`both(5)` reaches `Chain`, the node `chain` built, which does no work of its own and can only build a node for the evaluator to unfold. `both.head(5, both.tail)` runs the first link with the second behind it, so composition costs nothing where it is applied.

The payoff is fusion. `map` and `Arrow(f)` are `inline`, so every call site expands into a class of its own with the body inlined into its `apply`. The receiver there is one concrete class and the next step arrives as an argument, so a run of transformations becomes straight-line code rather than a series of trips:

```text
fused:      42 ──▶ (_ * 2) ──▶ (_ + 1) ──▶ 85
otherwise:  42 ──▶ [eval] ──▶ (_ * 2) ──▶ [eval] ──▶ (_ + 1) ──▶ [eval] ──▶ 85
```

```scala
assert(settled.map(_ * 2).map(_ + 1).eval == 85)
```

The evaluator's own loop, `Eval.loop`, is far past any inlining budget, and every effect in the program passes through it, so it never inlines and its dispatch is megamorphic. Anything routed back through it stops fusing, which is what `cont(value)` on a `Chain` would do at every composition boundary.

Both members are public for that reason, not as an invitation: reach for `chain` to compose and `arrow(value)` to apply. Two things follow. Fusion is per call site, so one `map` body reached from two places is two classes, each monomorphic. And a suspension ends a fused run, because the answer is not there yet and the evaluator takes over until a handler supplies it.

`Effect.defer` is the explicit way to hand something to the evaluator. `Effect.defer(v, cont)` reifies the application of a continuation as a node, and `Effect.defer(block)`, taking its block by name, is an ordinary call that turns a piece of plain code into a node the evaluator reaches rather than something that runs where it is written.

### `Region.NoEscape`: the continuation cannot leave its region

A clause is handed the rest of the computation as a value, and the sections above lean on that freely. There is one thing it may not do with it: let it out.

The reason is what the continuation carries, as the section on `handleCont` said: the regions that stood between this handler and the suspension, brackets among them, whose releases the handler settles when its own region ends. A continuation that outlived that region would be a computation whose resources have already been released, resumed by someone who has no idea.

The row says so. `Region.NoEscape` is added to the continuation's row, and to the row the clause owes back, so anything derived from the continuation carries the marker too. Only one thing discharges it, which is returning to the region that added it, so handing the continuation back compiles:

```scala
val resumed: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], Ask.get.map(_ + 1))([C] => (_, cont) => cont(41), a => a)

assert(resumed.eval == 42)
```

Keeping it does not. Stashing the continuation somewhere it can be resumed later is the plain case, and the row refuses it:

```scala doctest:expect=fails-compile scope=isolated
import kyo.*
import kyo.kernel.*

sealed trait Ask extends ArrowEffect[Const[Unit], Const[Int]]

object Ask:
    def get: Int < Ask = ArrowEffect.suspend[Any](Tag[Ask], ())

var stashed: Arrow[Int, Int, Ask] = Arrow.id

ArrowEffect.handleCont(Tag[Ask], Ask.get)(
    // Does not compile: `cont` carries Region.NoEscape in its row and `stashed` does not,
    // and the row is contravariant, so a holder without the marker refuses a value with
    // it. Were the assignment allowed, resuming `stashed` after this handler returned
    // would resume code whose resources the handler had already released.
    handle = [C] =>
        (_, cont) =>
            stashed = cont; 0
    ,
    done = (a: Int) => a
)
```

Sending it to another fiber is refused by the same row. Crossing an execution boundary asks for an `Isolate` covering the computation's effects, the subject of [crossing an execution boundary](#isolate-crossing-an-execution-boundary), and the derivation refuses a row naming the marker before it looks for any instance, so supplying one for the marker does not open the gate.

The marker has no runtime presence. Rows are phantom, and the discharge is a cast on the row alone, so confinement costs nothing at evaluation and is entirely a statement to the compiler.

## Failure and resources

Effects describe what a computation asks for. Exceptions are the other thing that can happen to it, and a computation can also simply be dropped, by a clause that never applies its continuation. Recovery and release have to cover all three endings.

### Recovery is a clause, not a wrapper

Nothing here installs a recovery scope around a computation. A throw is answered by the handler that is already discharging an effect, through a third clause standing alongside `handle` and `done`:

```scala
def runOrElse[S](n: Int, fallback: Int)(v: => Int < (Ask & S)): Int < S =
    ArrowEffect.handleCont(Tag[Ask], v)(
        handle = [C] => (_, cont) => cont(n),
        done = a => a,
        recover = _ => Maybe(fallback)
    )

val checked: Int < Ask = Ask.get.map(a => if a < 0 then throw IllegalArgumentException("negative") else a)

assert(runOrElse(7, 0)(checked).eval == 7)
assert(runOrElse(-1, 0)(checked).eval == 0)
```

The clause's type is the teaching: `Throwable => Maybe[B < (S & S2)]`. That `Maybe` is a decline channel rather than an optional result. `Present(replacement)` substitutes the region's result and the region ends there; `Absent` declines, and the failure keeps unwinding past this region to whatever stands outside it. On `handleLoopState` the clause is handed the state as well. For a throw raised while the input was being built, the state it sees is the initial one, the only one the region has had.

A recovery also covers building the receiver. Every overload evaluates it once, at the same point, before the region exists; what the third clause adds is that this point sits inside the recovery. So a throw raised while the computation being handled is still being constructed is this region's to answer rather than the caller's, and that is why `runOrElse` passes its own receiver on by name instead of forcing it first:

```scala
def checkedPlus(n: Int): Int < Ask =
    if n < 0 then throw IllegalArgumentException("negative") else Ask.get.map(_ + n)

assert(runOrElse(1, 0)(checkedPlus(-1)).eval == 0)
```

The extent a recovery covers is the whole life of its region: the receiver being built, being evaluated, and being resumed after a park or after the evaluator's own budget rescue (the periodic suspension that keeps a deep synchronous chain off the stack, covered under [iteration](#iteration)). That reach comes from the recovery being an entry the evaluator consults while unwinding rather than a `try` around a call. Two limits are worth naming, because both are places a reader guesses wrong. A throw the clause itself raises is not the inner region's to answer, so it passes over a recovery standing inside that region rather than being caught by it. And a recovery does not reach into a computation that was boxed rather than run, nothing in it having been evaluated yet. Fatal errors pass every recovery untouched.

> **Note:** failure a program declares in its own type, rather than a throw, is `Abort`, and it lives in kyo-prelude one layer above this module. The kernel answers throws.

### `ensureMap`: no gap between a value and what it owes

Every `map` polls the safepoint, the per-thread check through which a scheduler preempts or interrupts a computation, before applying its function. An interrupt pending when the value arrives therefore parks the computation, and the function never runs. That is right nearly everywhere, and wrong in one place: where the function records an obligation the value itself just created. The resource is open, its release is not registered yet, and a park landing between the two loses it, because nothing yet knows there is anything to close.

`ensureMap` is `map` with that poll removed. Its function runs as the value arrives, so an interrupt lands on one side of the pair or the other and never inside it:

```scala
class Connection:
    private var log                           = Chunk.empty[Maybe[Throwable]]
    def close(ending: Maybe[Throwable]): Unit = log = log.append(ending)
    def closings: Chunk[Maybe[Throwable]]     = log
end Connection

val registry = Chunk.newBuilder[Connection]

val acquired: Connection < Ask =
    Ask.get.ensureMap { _ =>
        val conn = Connection()
        val _    = registry += conn
        conn
    }

assert(Ask.run(1)(acquired).eval.closings.isEmpty)
```

The connection exists and the registry knows about it, with nothing schedulable in between. Under no interruption that is exactly what `map` would have done, which is why no example can show the difference: it is about the one scheduling in which the two diverge. Reach for it only for that pairing, a resource opened and its release registered, or a fiber spawned and its handle stored, and use `map` everywhere else, since skipping the poll also means the computation cannot be preempted at that point.

### `Bracket`: acquire, use, release

`Bracket(acquire)(use)(release)` binds a resource for the extent of a use and runs the release exactly once, whichever way that extent ends. It is not exported into the `kyo` package, so it arrives through the `import kyo.kernel.*` at the top of this document; there is no `kyo.Bracket`.

What the release is told follows from what [the continuation in hand](#handlecont-the-continuation-in-hand) established. A clause holds the rest of the computation as a value and may apply it more than once, so an extent can end more than once, with a different value each time, and there is no single value to hand a release. What it is told instead is the resource and how its extent ended, never what the use produced:

```scala
def session(c: Connection): Int < Ask =
    Bracket(c)(_ => Ask.get.map(_ + 1))((conn, ending) => conn.close(ending))

val clean = Connection()

assert(Ask.run(41)(session(clean)).eval == 42)
assert(clean.closings == Chunk(Maybe.empty[Throwable]))
```

The ending is one `Maybe[Throwable]`. `Absent` is a clean end, which is what `clean` recorded; `Present(t)` carries either the failure an unwind took through the extent or the signal an abandoned remainder is discarded with. Three ways for an extent to end, and two things a release is ever told.

> **Note:** the release is a plain `(A, Maybe[Throwable]) => Unit` rather than a computation, because it has to run where nothing is installed to answer an effect, and its result is discarded for the same reason. It exists to act outside the computation, closing a socket or handing a permit back, so nothing it does is observable to the computation it belonged to.

The third ending is not hypothetical. A clause that discards its continuation drops the whole remainder of the computation, and every bracket outstanding in that remainder still releases, told the discard signal rather than `Absent`, its extent never having run to an end:

```scala
val abandoned = Connection()

val dropped: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], session(abandoned))(
        handle = [C] => (_, _) => -1,
        done = a => a
    )

assert(dropped.eval == -1)
assert(abandoned.closings.head.exists(_.isInstanceOf[KyoException]))
```

The clause answered `-1` without ever applying `cont`, so the `Ask.get.map(_ + 1)` behind it never ran and the region completed at the operation. The bracket inside the dropped remainder released on the way out, told the discard signal rather than a clean ending: how the extent ended is information the release could not have worked out for itself.

`Bracket.ensuring(release)(body)` is the entry point for the case with nothing to acquire: release first, body second and by name, and the release handed only the ending. It is not sugar for `Bracket(())`, and the difference shows exactly here. `apply` cannot install its region until the acquire's value arrives, the release being owed that value, so a computation abandoned before it ever ran has no region and nothing to release. `ensuring` installs its region from the start, so its release runs whether or not a single step ever did.

> **Note:** a bracket closes only with the scope that installed it. An isolated child, a spawned fiber among them, gets an inert copy of the region that neither completes, releases, nor refuses, so a child never releases a resource the scope that acquired it is still using.

### A released scope, entered again

The mirror case, a clause that applies its continuation more than once with a bracket inside the region, looks like it should be one: each branch resumes code that closed over the resource, and the first branch reaches the end of the use. It is not, because the resource outlives that branch. As the section on `handleCont` said, the bracket's release moved to the handler holding the continuation, which settles it once where its own region ends. Both branches run against the live resource and the release runs once, after the second:

```scala
val held = Connection()

val branched: Int < Any =
    ArrowEffect.handleCont(Tag[Ask], session(held))(
        handle = [C] => (_, cont) => cont(10).map(a => cont(20).map(b => a + b)),
        done = a => a
    )

assert(branched.eval == 32)
assert(held.closings == Chunk(Maybe.empty[Throwable]))
```

What is refused is entering a scope after it has been released. The row rules that out for a clause's own continuation, so it is reachable only by a computation resumed after the region that owned it has ended. That is a parked computation resumed a second time, or a peeled remainder consumed after the computation that peeled it finished: a peel (`ArrowEffect.handleFirst`, the `private[kyo]` form behind the stream pipes) answers one operation and hands the rest of the region out as a value. Rather than hand that resumption a released resource, re-entering the scope is refused with a `kyo.Closed`. It carries no stack trace at all, which is why its message names the `Bracket` call site the resource was opened at, and why the message explains itself rather than leaving the reader a frame to chase. It says which of the two ways released the scope, an extent that ran to its end or an owning scope that exited with the remainder still unconsumed, and what to change in each case: acquire inside the branch, so every resumption gets a resource of its own; put the bracket outside the handler, so its extent is not what gets replayed; or consume a peeled remainder inside the scope that peeled it.

### `EffectTrace`: what a failure carries out

When an exception crosses an evaluation boundary, effect-level frames are attached to it as a suppressed `EffectTrace`, so a stack trace from a kyo program shows where the computation was in its own terms and not only in the evaluator's. Nothing is recorded while the computation runs: the frames are reconstructed at the boundary from the failing value and the stack the evaluator is already holding, so a program that never throws pays nothing. One cap of 64 frames covers every boundary an exception crosses.

## Iteration

Recursion through `map` is stack safe here, so any loop can be written as one. That safety has a shape worth knowing: a deep synchronous chain does not recurse freely, it suspends periodically and is resumed, so its depth is paid in heap rather than in stack. `Loop` is the shape that carries state and performs effects between rounds without building a fresh continuation per round: the node that defers the rest of the loop is made once and reused across rounds, and a round says what it decided in its own answer rather than in a call.

### `Loop`: the general loops

A `while` loop cannot carry an effect. Its condition and body are ordinary code, so a step that performs one hands back an `Int < Ask` where an `Int` is needed, and the only way to get the `Int` out is `eval`, which the row forbids. What is left is mutation over what has already been answered:

```scala
var i   = 0
var acc = 0

while i < 3 do
    acc += i
    i += 1

assert(acc == 3)
```

Recursion carries an effect where a `while` cannot, and it does not overflow the stack: the rounds go through the evaluator's trampoline rather than JVM frames.

```scala
def sumAsk(i: Int, acc: Int): Int < Ask =
    if i == 3 then acc
    else Ask.get.map(a => sumAsk(i + 1, acc + i + a))

assert(Ask.run(0)(sumAsk(0, 0)).eval == 3)
```

`Loop` is that recursion with the state passed as parameters and the rounds made cheaper. A body that answers without suspending stays in a plain tail-recursive loop and allocates nothing; one that does suspend reuses a single node across every round, where the recursion above builds a fresh `map` node per call. It takes the initial state and a body answering, per round, either the next state or a final value:

```scala
val counted: Int < Ask =
    Loop(0)(i => if i == 3 then Loop.done(i) else Ask.get.map(a => Loop.continue(i + a)))

assert(Ask.run(1)(counted).eval == 3)
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

### `Loop.Outcome`: what a round answers with

A round answers with an `Outcome`, built by `Loop.continue` and `Loop.done`. `Outcome2`, `Outcome3` and `Outcome4` are the same thing for the multi-state arities, and `Continue` through `Continue4` are what a continuing answer carries.

> **Note:** `Loop.continue` and `Loop.done` answer with a computation even though the outcome they carry is settled, because a clause may suspend before it continues. That is also what lets a handler clause write `Loop.continue(...)` directly, as the clauses under [answering an effect](#handleloop-one-answer-per-occurrence) do.

> **Note:** a `done` payload that is itself a computation stays unevaluated data. The loop hands it back as a value, and only the caller evaluating it runs it.

### The shapes that need no state

Four loops carry nothing between rounds and read better than the general form when there is nothing to carry. `Loop.foreach` repeats a body that answers with an outcome, `Loop.repeat(n)` runs a body a fixed number of times, `Loop.whileTrue` tests an effectful condition before each round, and `Loop.forever` never completes on its own:

```scala
val repeated: Unit < Say  = Loop.repeat(3)(say("tick"))
val ticker: Nothing < Say = Loop.forever(say("tick"))

assert(runSay(repeated).eval == ((Chunk("tick", "tick", "tick"), ())))
assert(runSayUntil("tick")(ticker).eval == Maybe.empty[Nothing])
```

`ticker` produces `Nothing`, so nothing downstream of it can run and only a handler can end it. `runSayUntil` from [answering an effect](#handleloop-one-answer-per-occurrence) is such a handler: it ends the region at the first `"tick"`, which is what "never completes on its own" means in practice.

> **Note:** `Loop.repeat(n)` checks the count before reaching the body, so the body is evaluated exactly `n` times rather than `n + 1`. The transcript above is the evidence: three rounds, three lines, and a fourth round would have added a fourth.

## Sequential collection operations

The standard collection methods do not accept an effectful function, so the `Kyo` object carries their counterparts. Each takes a function returning a computation, runs it over the elements one at a time, and answers with a computation of the collected result.

### Mapping over a collection

The one to start from is `foreach`: it applies the function to each element in order, waits for each computation before beginning the next, and collects the results into the collection type that went in.

```scala
val shifted: Chunk[Int] < Ask = Kyo.foreach(Chunk(1, 2, 3))(n => Ask.get.map(_ + n))

assert(Ask.run(10)(shifted).eval == Chunk(11, 12, 13))
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
val total: Int < Ask = Kyo.foldLeft(Chunk(1, 2, 3))(0)((acc, n) => Ask.get.map(a => acc + n * a))
val firstBig: Maybe[Int] < Ask =
    Kyo.findFirst(Chunk(1, 2, 3))(n => Ask.get.map(a => if n > a then Maybe(n * 10) else Maybe.empty))

assert(Ask.run(1)(total).eval == 6)
assert(Ask.run(1)(firstBig).eval == Maybe(20))
```

`filter` and `collect` select, `findFirst` stops at the first element the function answers a present `Maybe` for, and `foldLeft`, `scanLeft`, `groupBy` and `groupMap` aggregate. A `Map` source adds `filterKeys` for the case where only the key decides.

### Splitting and branching

`takeWhile`, `dropWhile` and `span` cut a collection at the first element that fails an effectful predicate, and `partition` and `partitionMap` split it in two. Branching on an effectful condition is `Kyo.when` and `Kyo.unless`, which answer with a `Maybe` when there is no other branch to supply a value:

```scala
val announced: Maybe[Unit] < (Ask & Say) = Kyo.when(Ask.get.map(_ > 0))(say("positive"))

assert(Ask.run(1)(runSay(announced)).eval == ((Chunk("positive"), Maybe(()))))
```

`when` also has a two-branch form that takes both arms and answers with their common type, since neither branch is missing there.

### The collection types, and where the concurrent variants live

Every combinator on the `Kyo` object is sequential. The concurrent counterparts of `foreach`, `collectAll` and the rest live on `Async`, in another module, and taking one of them is a deliberate choice rather than the default. The set here is provided for `IterableOps`, `List`, `Seq`, `Chunk`, `Set` and `Map`, so the return type matches the collection that went in. `Map` is the one shape that differs, in both directions: it carries no `foreachIndexed`, since a map has no positions to hand out, and `foreach`, `foreachConcat` and `collect` each have a second overload for a function that does not answer with a pair, which answers with a `Chunk` because there is no map left to rebuild.

## `Isolate`: crossing an execution boundary

When a computation forks onto a fiber or a parallel branch, it leaves the evaluation that built it. Whatever effect state it was standing in has to be captured where the fork happens, carried into the new evaluation, and brought back when the fork ends. `Isolate` is that contract, and every operation that forks requires one.

### The three type parameters

`Isolate[Remove, Keep, Restore]` names three different roles, and reading them apart is most of understanding the abstraction:

- `Remove` is the effects the isolation itself satisfies. They are handled by the crossing.
- `Keep` is the effects that stay available inside the isolated computation.
- `Restore` is the effects that become available after the isolation completes.

`Remove` and `Restore` differ on purpose: `restore` decides what the crossing hands back, which need not be what `capture` read. That is what lets a fork capture a stateful effect, run with a copy of its state, and restore only the final value rather than every intermediate update.

Most effects supply their instance implicitly, and an intersection is derived from the components. For a lone `ContextEffect` the derived instance passes the value straight through, because bindings cross by a mechanism of their own that every isolate composes with, covered under [what the bindings themselves decide](#what-the-bindings-themselves-decide):

```scala
val levelIsolate: Isolate[Level, Any, Level] = Isolate.derive[Level, Any, Level]
```

> **Caution:** effects that short-circuit, `Abort` and `Choice` among them, deliberately provide no isolate. Automatic derivation fixes a handling order, and for those effects the order changes the result, so participation is refused rather than an order picked silently. Handle them before the operation that forks.

### The three phases

An instance is three methods plus the two abstract types they hand between themselves. `State` is what the crossing carries from the old evaluation to the new one. `Transform[_]` is how the isolated computation's result comes back, carrying whatever the isolation needs to settle afterwards.

The three run in a fixed order, and each one's row says where it stands:

| Phase | Shape | Where it stands |
| --- | --- | --- |
| `capture` | `(State => A < S) => A < (Remove & S)` | Inside the effect being left, since reading its state means performing it. This is why `Remove` is in the row. |
| `isolate` | `(State, A < (S & Remove)) => Transform[A] < (Keep & S)` | Inside the new evaluation. `Remove` is gone, only `Keep` is available, and the answer is wrapped in `Transform`. |
| `restore` | `Transform[A] < S => A < (Restore & S)` | Back outside, unwrapping the transform and making `Restore` available. |

Spelled out, a crossing is those three nested in that order:

```scala
val body: Int < (Level & Say) = level.map(l => say(s"level $l").andThen(l))

val byPhases: Int < (Level & Say) =
    levelIsolate.capture(state => levelIsolate.restore(levelIsolate.isolate(state, body)))

assert(runLevel(2)(runSay(byPhases)).eval == ((Chunk("level 2"), 2)))
```

For the `Level` binding above both `Remove` and `Restore` are `Level` and the phases pass the value straight through, as the derived instance for a `ContextEffect` always does: the binding itself crosses by the mechanism every isolate composes with, so the three phases have nothing left to do.

`Say` is worth noticing in that example. It was never mentioned by the isolate, and it crossed untouched: the operation suspended inside the isolation, stayed pending through all three phases, and was answered by the handler outside. An isolate manages state; it does not handle arbitrary operations.

### `run`: the three phases composed

`run` is exactly the nesting written above, in one call, and is what most code wants:

```scala
val crossing: Int < (Level & Say) = levelIsolate.run(body)

assert(runLevel(2)(runSay(crossing)).eval == ((Chunk("level 2"), 2)))
```

`Remove` stays in the row it answers with, because the `capture` at the front has to read the state from the evaluation being left. The overload taking an already-captured state is the one that does not, and it exists for a fork that captures once and isolates many branches from that single capture.

The instance's own `apply` is `run` with the consumer fused in, so the crossed computation is handed straight to whoever asked for it rather than becoming a value of its own. `use` supplies the instance as a `given` to an operation that requires one, which is how a caller picks a strategy for a specific block:

```scala
def forked(using i: Isolate[Level, Any, Level]): Int < Level = i.run(level)

assert(runLevel(3)(levelIsolate.use(forked)).eval == 3)
```

### `nest`: holding the restore back

`nest` runs the same first two phases as `run`, on the same state, and differs in one step at the end. Where `run` applies the restore and hands back an `A`, `nest` wraps the restored computation as a value, handing back an `A < Restore` nested one layer deep. Nothing is skipped; the restore is held rather than applied, and the caller decides when it lands.

That gap between the two layers is the point. Whatever effects the body still owes the outer world can be handled while the isolated result sits inert inside:

```scala
val nestingIsolate: Isolate[Level, Say, Level] = Isolate.derive[Level, Say, Level]

val tunneled: (Int < Level) < (Level & Say)           = nestingIsolate.nest(body)
val transcribed: (Chunk[String], Int < Level) < Level = runSay(tunneled)
val finished: Int < Level                             = transcribed.map(_._2)

assert(runLevel(5)(finished).eval == 5)
```

`Say` was handled on the outer layer while the isolated result was still boxed in the inner one, whose row is the `Restore` the isolate names. The last line is where that inner layer applies: binding through it with `map` collapses it, exactly as `.flatten` would on a value whose nesting is visible in the type. This is the one place in the module where nesting is the intent rather than a mistake the lift refuses.

### Composing and obtaining instances

`Isolate.apply` summons an instance, `Isolate.derive` derives one for an intersection, and the same derivation is also the default `given`, so most code never names either. `andThen` composes two instances by hand, intersecting each of the three parameters with its counterpart. Reach for it when the order of capture and restore matters, or when a component effect offers several strategies and the derived choice is not the one you want.

> **Note:** derivation composes one instance per component effect, or fails compilation with a message listing four ways to proceed, from handling the effects before the operation to writing an instance by hand. `ContextEffect` components are filtered out of that fold on purpose, because bindings cross by the mechanism of their own that the next section describes.

### What the bindings themselves decide

Bindings cross by a mechanism of their own, composed in front of whatever isolate a crossing names, and it is where `ContextEffect.handle`'s remaining parameters come back. It reads what is bound where the fork happens and puts two questions to every binding it finds, and both are answered on the call that installed the binding. Writing a binding and writing its crossing behavior are the same call.

The first is `fork`, which computes the value a forked computation starts with from the value the parent holds. A binding always crosses; `fork` only decides with what. The default, and what `handleInheritable` installs, is the parent's own value, so the binding is inherited unchanged. A value that must not travel answers with a neutral one of its own type instead, which is why `kyo.Local` keeps its per-local "do not inherit" choice inside the value it binds rather than in this strategy.

The second is `join`, which computes what the scope holds once a fork has ended. It is handed three values, the parent's, the one the fork started with, and the one the fork ended with, and the middle one is what makes a merge possible: with it a strategy can apply the fork's delta rather than overwrite:

```scala
def withMergedLevel[A, S](n: Int)(v: A < (Level & S)): A < S =
    ContextEffect.handle(
        Tag[Level],
        n,
        (outer: Int) => outer,
        (parent: Int) => parent,
        (parent: Int, started: Int, ended: Int) => parent + (ended - started)
    )(v)

assert(withMergedLevel(2)(levelPlus(40)).eval == 42)
```

The shape is `handleInheritable`'s with two more strategies in the same list and the computation last. In order the four are the two that decide the bound value, `ifUndefined` and `ifDefined`, then `fork` and `join`. Keeping the parent's value, which is what `handleInheritable`'s join does, is the do-nothing answer to the second question.

`release` answers a different question, and it fires whether or not anything ever forked. It is what the bound value owes when its region ends, and it is handed the value together with how the extent ended: `Absent` for a clean end, the failure when an unwind took the region down. A remainder a clause discarded is told `Absent` as well; only `Bracket` tells that ending apart from a clean one, through its own cell. It is how a bound value that owns something outside the computation gets to close it at either exit, and it is the same strategy `Bracket` is built on.

## `Mask`: hiding an effect from inner handlers

Occasionally a computation has to reach past the handlers wrapped around it, because an operation must be answered by the caller's caller rather than by the handler sitting in between. `ArrowEffect.Mask` is that escape, and it is scoped to the effect type it is given, which may be an intersection where several effects have to tunnel together.

```scala
import kyo.kernel.ArrowEffect.Mask

val masked: Int < Mask[Ask]        = Mask[Ask](Ask.get)
val innerAnswered: Int < Mask[Ask] = Ask.run(1)(masked)
val exposed: Int < Ask             = Mask.run[Ask](innerAnswered)

assert(Ask.run(42)(exposed).eval == 42)
```

The inner handler saw nothing, because inside the mask every `Ask` operation was translated into a `Mask[Ask]` operation carrying the original as an unevaluated payload. `Mask.run` is where each payload re-raises the original for the handlers outside that boundary, and the answer flows back into the masked computation.

The effect to hide is named explicitly, `Mask[Ask]` rather than inferred, and only that effect tunnels. Everything else in the row stays answerable where it is:

```scala
val mixed: Int < (Mask[Ask] & Say)                = Mask[Ask](Ask.get.map(a => say(a.toString).andThen(a)))
val saidLocally: (Chunk[String], Int) < Mask[Ask] = runSay(mixed)
val outerAnswered: (Chunk[String], Int) < Ask     = Mask.run[Ask](saidLocally)

assert(Ask.run(7)(outerAnswered).eval == ((Chunk("7"), 7)))
```

`Say` was handled by the local handler even though it sat inside the mask, and the transcript proves the local handler ran with the answer the outer one supplied. Masking the same effect twice behaves as one mask, so a computation that is already masked can be passed through a second mask without changing where its operations are answered.

Nor is masking limited to arrow effects. The masking region shadows its tag for context reads as well as for operations, so a `ContextEffect` read inside a mask of its effect tunnels past an inner binding and is answered by the binding outside, exactly as an operation tunnels past an inner handler.

Masking moves where a value is answered, and that moves where a scope ends with it. A bracket inside a masked computation releases when the outer handler is done with the tunneled continuation, not at the mask boundary. If that outer handler discards the continuation instead of resuming it, the bracket releases when that handler ends, told the discard signal, exactly as it would without the mask in between.

## Putting it together

One program can use all three kinds of effect at once, and the row on it is the list of what it still needs: an answer for `Ask.get`, a listener for `say`, and a level bound around it.

```scala
val program: Int < (Ask & Say & Level) =
    level.map(l =>
        Kyo.foreach(Chunk(1, 2))(n => Ask.get.map(_ * n)).map(answers =>
            say(s"level $l saw ${answers.size} answers").andThen(answers.sum + l)
        )
    )
```

Nothing has run. Each handler discharges exactly one entry, and the type after each line says what is left:

```scala
val afterSay: (Chunk[String], Int) < (Ask & Level) = runSay(program)
val afterAsk: (Chunk[String], Int) < Level         = Ask.run(3)(afterSay)
val afterLevel: (Chunk[String], Int) < Any         = runLevel(10)(afterAsk)

assert(afterLevel.eval == ((Chunk("level 10 saw 2 answers"), 19)))
```

`eval` appears only on the last line, and only because the row reached `Any`. That is the whole contract of the module: an effect is a promise the compiler holds you to, and a handler is how you keep it. Until the final line, every one of these values was a description that had not run.

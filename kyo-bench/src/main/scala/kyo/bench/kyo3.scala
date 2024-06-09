package kyo3

import core.*
import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
import kyo.Chunks
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.targetName

// This new design moves away from plurals for effect names.
// See the complete core and effect implementations below!

//////////////////////
// Nesting support! //
//////////////////////

object memoTest extends App:

    def memoize[T](io: T < IO): T < IO < IO =
        IO.defer {
            val a = new AtomicReference[Maybe[T]](Maybe.Empty)
            IO {
                a.get() match
                    case Maybe.Empty =>
                        io.map { r =>
                            IO(a.compareAndSet(Maybe.Empty, Maybe.Defined(r)))
                                .unit.andThen(r)
                        }
                    case Maybe.Defined(v) =>
                        v
            }
        }

    var calls = 0

    val io: (Int, Int, Int) < IO =
        for
            memo <- memoize(IO { calls += 1; println("called"); calls })
            a    <- memo
            b    <- memo
            c    <- memo
        yield (a, b, c)

    println(IO.run(io).pure)
    // called
    // (1,1,1)

    println(calls)
    // 1

end memoTest

////////////////
// FIESTA! 🕺 //
////////////////

object effectfulFiesta extends App:

    case class Ingredient(name: String, quantity: Int)

    def prepareIngredient(ingredient: Ingredient) =
        for
            _ <- Stream.emit(s"Adding ${ingredient.quantity} ${ingredient.name}(s)!")
            _ <- IO(println(s"Chopping ${ingredient.name}..."))
            _ <- Var.update[Int](_ + ingredient.quantity)
            _ <- Sum.add(ingredient.quantity)
            _ <- IO(println(s"${ingredient.name} added to the mix!"))
        yield ()

    def prepareFiestaMix(ingredients: List[Ingredient]) =
        for
            _     <- Stream.emit("Let's get this fiesta started!")
            _     <- Seqs.foreach(ingredients)(prepareIngredient)
            total <- Var.get[Int]
            _     <- Stream.emit(s"Fiesta mix ready with $total ingredients!")
            env   <- Env.get[String]
            _     <- Abort.when(env != "fiesta")("Dude, this is a fiesta! 😤")
        yield total

    val ingredients = List(
        Ingredient("tomato", 5),
        Ingredient("avocado", 3),
        Ingredient("lime", 2),
        Ingredient("chili pepper", 1),
        Ingredient("tortilla chip", 20)
    )

    val fiestaResult: Int < (Stream.Emit[String] & Var[Int] & Sum[Int] & IO & Env[String] & Abort[String]) =
        for
            _     <- Var.set(0)
            total <- prepareFiestaMix(ingredients)
            _     <- Abort.get(Right(()))
            _     <- IO(println(s"Mix ready! Total ingredients: $total"))
            _     <- IO(println("Fiesta time!!! 🕺"))
        yield total

    val fiestaMix: (Chunk[Int], (Chunk[String], Int)) < (IO & Env[String] & Abort[String]) =
        Sum.run[Int] {
            Var.run(0) {
                Stream.init(fiestaResult).take(3).runChunk
            }
        }

    println(IO.run(Abort.run(Env.run("fiesta")(fiestaMix))).pure)
    // Chopping tomato...
    // tomato added to the mix!
    // Chopping avocado...
    // avocado added to the mix!
    // Chopping lime...
    // lime added to the mix!
    // Chopping chili pepper...
    // chili pepper added to the mix!
    // Chopping tortilla chip...
    // tortilla chip added to the mix!
    // Mix ready! Total ingredients: 31
    // Fiesta time!!! 🕺
    // Right((Chunk(5, 3, 2, 1, 20),(Chunk(Let's get this fiesta started!, Adding 5 tomato(s)!, Adding 3 avocado(s)!),31)))

    println(IO.run(Abort.run(Env.run("work")(fiestaMix))).pure)
    // Chopping tomato...
    // tomato added to the mix!
    // Chopping avocado...
    // avocado added to the mix!
    // Chopping lime...
    // lime added to the mix!
    // Chopping chili pepper...
    // chili pepper added to the mix!
    // Chopping tortilla chip...
    // tortilla chip added to the mix!
    // Left(Dude, this is a fiesta! 😤)
end effectfulFiesta

import core.*

////////
// IO //
////////

class IO extends Effect[Const[Unit], Const[Unit], IO]

object IO:

    def apply[T, S](f: => T < S)(using tag: Tag[IO]): T < (IO & S) =
        suspend(tag, (), _ => f)

    // This is the same as `apply` but `f` returns a `T`, which
    // triggers the `AnyVal` boxing if `f` returns a Kyo computation.
    // Basically, `apply` flattens the result and `defer` doesn't.
    def defer[T](f: => T)(using tag: Tag[IO]): T < IO =
        suspend(tag, (), _ => f)

    def run[R, T, S](v: T < (IO & S))(using tag: Tag[IO]): T < S =
        // Unfortunately Scala doesn't allow omitting the `C` type param
        handle(tag, v)([C] => (_, cont) => cont(()))
end IO

/////////
// ENV //
/////////

class Env[R] extends Effect[Const[Unit], Const[R], Env[R]]

object Env:

    def get[R](using tag: Tag[Env[R]]): R < Env[R] =
        suspend(tag, ())

    class UseDsl[R](ign: Unit):
        def apply[T, S](f: R => T < S)(
            using tag: Tag[Env[R]]
        ): T < (Env[R] & S) =
            suspend(tag, (), f)
    end UseDsl

    def use[R]: UseDsl[R] = UseDsl(())

    def run[R, T, S](e: R)(v: T < (Env[R] & S))(using tag: Tag[Env[R]]): T < S =
        handle(tag, v)([C] => (_, cont) => cont(e))
end Env

///////////
// ABORT //
///////////

class Abort[E] extends Effect[Const[Left[E, Nothing]], Const[Nothing], Abort[E]]

object Abort:

    def abort[E](value: E)(
        using tag: Tag[Abort[E]]
    ): Nothing < Abort[E] =
        suspend(tag, Left(value))

    def when[E](b: Boolean)(value: E)(
        using tag: Tag[Abort[E]]
    ): Unit < Abort[E] =
        if b then abort(value)
        else ()

    def get[E, T](either: Either[E, T])(
        using tag: Tag[Abort[E]]
    ): T < Abort[E] =
        either match
            case Right(value) => value
            case Left(value)  => abort(value)

    class RunDsl[E](ign: Unit):
        def apply[T, S](v: T < (Abort[E] & S))(using tag: Tag[Abort[E]]): Either[E, T] < S =
            handle(tag, v.map(Right(_): Either[E, T])) {
                [C] => (command, _) => command
            }
    end RunDsl

    def run[E]: RunDsl[E] = RunDsl(())
end Abort

/////////
// VAR //
/////////

// This was a tough one to make type safe!
class Var[V] extends Effect[Var.Input[V, *], Id, Var[V]]

object Var:
    sealed trait Input[V, X]
    case class Get[V]()             extends Input[V, V]
    case class Set[V](value: V)     extends Input[V, Unit]
    case class Update[V](f: V => V) extends Input[V, Unit]

    def get[V](using tag: Tag[Var[V]]): V < Var[V] =
        suspend(tag, Get())

    class UseDsl[V](ign: Unit):
        def apply[T, S](f: V => T < S)(
            using tag: Tag[Var[V]]
        ): T < (Var[V] & S) =
            suspend(tag, Get(), f)
    end UseDsl

    def use[V]: UseDsl[V] = UseDsl(())

    def set[V](value: V)(using tag: Tag[Var[V]]): Unit < Var[V] =
        suspend(tag, Set(value))

    def update[V](f: V => V)(using tag: Tag[Var[V]]): Unit < Var[V] =
        suspend(tag, Update(f))

    def run[V, T, S](st: V)(v: T < (Var[V] & S))(using tag: Tag[Var[V]]): T < S =
        handle.state(tag, st, v) {
            [C] =>
                (input, state, cont) =>
                    input match
                        // It's quite impressive that this is fully type-safe!
                        // For example, it fails to compile if I don't resume
                        // with a `V` for `Get` input or `Unit` for `Set`.
                        case Get() =>
                            (state, cont(state))
                        case Set(value) =>
                            (value, cont(()))
                        case Update(f) =>
                            (f(state), cont(()))
        }
end Var

/////////
// SUM //
/////////

class Sum[V] extends Effect[Const[V], Const[Unit], Sum[V]]

object Sum:
    def add[V](v: V)(using tag: Tag[Sum[V]]): Unit < Sum[V] =
        suspend(tag, v)

    class RunDsl[V](ign: Unit):
        def apply[T, S](v: T < (Sum[V] & S))(using tag: Tag[Sum[V]]): (Chunk[V], T) < S =
            handle.state(tag, Chunks.init[V], v)(
                handle = [C] => (command, state, cont) => (state.append(command), cont(())),
                done = (state, result) => (state, result)
            )
    end RunDsl

    def run[V >: Nothing]: RunDsl[V] = RunDsl(())
end Sum

//////////
// LOOP //
//////////

object Loop:

    private case class Continue[Input](input: Input)

    opaque type Result[Input, Output] = Output | Continue[Input]

    def done[T]: Result[T, Unit] = ()

    def continue[Input, Output, S](v: Input): Result[Input, Output] = Continue(v)

    def transform[Input, Output, S](
        input: Input
    )(
        run: Input => Result[Input, Output] < S
    ): Output < S =
        def loop(input: Input): Output < S =
            run(input).map {
                case r: Continue[Input] @unchecked =>
                    loop(r.input)
                case r =>
                    r.asInstanceOf[Output]
            }
        end loop
        loop(input)
    end transform
end Loop

//////////
// SEQS //
//////////

// TODO What should we use here if we don't use plurals anymore?
object Seqs:

    def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S): Unit < S =
        Loop.transform(seq) {
            case Nil          => Loop.done
            case head :: tail => f(head).andThen(Loop.continue(tail))
        }
end Seqs

////////////
// STREAM //
////////////

case class Stream[T, V, S](get: T < (Stream.Emit[V] & S)):

    def take(n: Int)(using tag: Tag[Stream.Emit[V]]): Stream[T, V, S] =
        Stream {
            handle.state(tag, n, get) {
                [C] =>
                    (input, st, cont) =>
                        st match
                            case 0 => (0, cont(()))
                            case n => Stream.emit(input).andThen((n - 1, cont(())))
            }
        }

    def runChunk(using tag: Tag[Stream.Emit[V]]): (Chunk[V], T) < S =
        handle.state(tag, Chunk.empty[V], get)(
            handle = [C] => (input, st, cont) => (st.append(input), cont(())),
            done = (st, r) => (st, r)
        )
end Stream

object Stream:

    class Emit[V] extends Effect[Const[V], Const[Unit], Emit[V]]

    def init[V, T, S](v: T < (Emit[V] & S)): Stream[T, V, S] =
        Stream(v)

    def initSeq[V](seq: Seq[V])(using tag: Tag[Emit[V]]): Stream[Unit, V, Any] =
        def loop(seq: Seq[V]): Unit < Emit[V] =
            seq match
                case Seq()        => ()
                case head +: tail => emit(head).andThen(loop(tail))
        Stream(loop(seq))
    end initSeq

    def emit[V](v: V)(using tag: Tag[Emit[V]]): Unit < Emit[V] =
        suspend(tag, v)
end Stream

// I've removed a few optimizations and unused
// code to make the gist easier to follow.
object core:

    type Id[T]    = T
    type Const[T] = [U] =>> T

    import internal.*

    // Note that there isn't a `Command` type anymore, effects need to define
    // type lambdas for the `Input` and `Output` types instead. This enables
    // fully type-safe effect handling without the need for explicit type
    // parameters!
    abstract class Effect[Input[_], Output[_], E <: Effect[Input, Output, E]]

    def suspend[Input[_], Output[_], E <: Effect[Input, Output, E], T, U, S](
        _tag: Tag[E],
        _input: Input[T],
        _cont: Output[T] => U < S = (v: Output[T]) => v
    ): U < (E & S) =
        <(new Suspend[Input, Output, E, T, U, S]:
            def tag                 = _tag
            def input               = _input
            def apply(v: Output[T]) = _cont(v)
        )

    object handle:
        def apply[Input[_], Output[_], E <: Effect[Input, Output, E], T, S, S2](
            tag: Tag[E],
            v: T < (E & S)
        )(
            handle: [C] => (Input[C], Output[C] => T < S) => T < (E & S & S2)
        ): T < (S & S2) =
            def handleLoop(v: T < (E & S & S2)): T < (S & S2) =
                v match
                    case <(kyo: Suspend[Input, Output, E, Any, T, S] @unchecked) if kyo.tag =:= tag =>
                        handleLoop(handle(kyo.input, kyo(_)))
                    case <(kyo: Suspend[MX, MX, EX, Any, T, E & S] @unchecked) =>
                        <(new Suspend[MX, MX, EX, Any, T, S & S2]:
                            val tag   = kyo.tag
                            val input = kyo.input
                            def apply(v: MX[Any]) =
                                handleLoop(kyo(v))
                        )
                    case <(v) =>
                        v.asInstanceOf[T]
            handleLoop(v)
        end apply

        def state[Input[_], Output[_], E <: Effect[Input, Output, E], State, T, S, S2, R](
            tag: Tag[E],
            st: State,
            v: T < (E & S)
        )(
            handle: [C] => (Input[C], State, Output[C] => T < S) => (State, T < (E & S)) < S2,
            done: (State, T) => R = (_: State, v: T) => v
        ): R < (S & S2) =
            def handleStateLoop(st: State, v: T < (E & S & S2)): R < (S & S2) =
                v match
                    case <(kyo: Suspend[Input, Output, E, Any, T, S] @unchecked) if kyo.tag =:= tag =>
                        handle(kyo.input, st, kyo(_)).map { (st2, v2) =>
                            handleStateLoop(st2, v2)
                        }
                    case <(kyo: Suspend[MX, MX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[MX, MX, EX, Any, R, S & S2]:
                            val tag   = kyo.tag
                            val input = kyo.input
                            def apply(v: MX[Any]) =
                                handleStateLoop(st, kyo(v))
                        )
                    case <(v) =>
                        done(st, v.asInstanceOf[T])
            handleStateLoop(st, v)
        end state
    end handle

    final case class <[+T, -S](private val state: T | Kyo[T, S]) extends AnyVal:

        def flatMap[U, S2](f: T => U < S2): U < (S & S2) =
            map(f)

        def andThen[U, S2](f: => U < S2)(using ev: T => Unit): U < (S & S2) =
            map(_ => f)

        def unit: Unit < S = map(_ => ())

        def map[U, S2](f: T => U < S2): U < (S & S2) =
            def mapLoop(curr: T < S): U < (S & S2) =
                curr match
                    case <(kyo: Suspend[MX, MX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[MX, MX, EX, Any, U, S & S2]:
                            val tag   = kyo.tag
                            val input = kyo.input
                            override def apply(v: MX[Any]): U < (S & S2) =
                                mapLoop(kyo(v))
                        )
                    case <(v) =>
                        f(v.asInstanceOf[T])
            mapLoop(this)
        end map

        def pure: T =
            require(!state.isInstanceOf[Suspend[?, ?, ?, ?, ?, ?]])
            state.asInstanceOf[T]
    end <

    object `<`:
        implicit def lift[T](v: T): T < Any = <(v)

    private object internal:
        type MX[_]
        type EX <: Effect[MX, MX, EX]

        sealed abstract class Kyo[+T, -S]

        abstract class Suspend[Input[_], Output[_], E <: Effect[Input, Output, E], T, U, S]
            extends Kyo[U, S]:

            def tag: Tag[E]
            def input: Input[T]
            def apply(v: Output[T]): U < S

            override def toString(): String = ???
        end Suspend

    end internal
end core

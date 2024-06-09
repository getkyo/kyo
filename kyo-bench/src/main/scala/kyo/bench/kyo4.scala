package kyo4

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
import kyo.Chunks
import kyo.Maybe
import kyo.Tag
import kyo.internal.Trace
import language.implicitConversions
import scala.annotation.targetName
import scala.reflect.ClassTag

// This new iteration introduces support for variant type parameters
// in effects like `Abort` and `Env`. Effect handling is still type-safe
// but `suspend` now requires a type parameter. The compilation properly
// fails if the parameter isn't defined, though.

// `Env` and `Abort` still need a type cast because of `HasEnv`/`HasAbort`.
// I think this `Has` mechanism would be rare in user-defined effects.
object core:

    type Id[T]    = T
    type Const[T] = [U] =>> T

    import internal.*

    // Removed the third `E` type param. It was unused.
    // Also renamed Input/Output to I/O. I was having trouble to read
    // the large type signatures but we can go back to Input/Output
    // later
    abstract class Effect[-I[_], +O[_]]

    case class SuspendDsl[T](ign: Unit) extends AnyVal:
        def apply[I[_], O[_], E <: Effect[I, O], U, S](
            _tag: Tag[E],
            _input: I[T],
            f: O[T] => U < S = (v: O[T]) => v
        )(using _trace: Trace): U < (E & S) =
            <(new Suspend[I, O, E, T, U, S]:
                def tag   = _tag
                def input = _input
                def trace = _trace
                def apply(v: O[T]): U < S =
                    f(v)
            )
    end SuspendDsl

    def suspend[T]: SuspendDsl[T] = SuspendDsl[T](())

    object handle:
        def apply[I[_], O[_], E <: Effect[I, O], T, S, S2](
            tag: Tag[E],
            v: T < (E & S)
        )(
            handle: [C] => (I[C], O[C] => T < (E & S & S2)) => T < (E & S & S2)
        )(using _trace: Trace): T < (S & S2) =
            def handleLoop(v: T < (E & S & S2)): T < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag <:< kyo.tag =>
                        handleLoop(handle(kyo.input, kyo(_)))
                    case <(kyo: Suspend[IX, OX, EX, Any, T, E & S & S2] @unchecked) =>
                        <(new Suspend[IX, OX, EX, Any, T, S & S2]:
                            val tag            = kyo.tag
                            val input          = kyo.input
                            def trace          = _trace
                            override def stack = trace :: kyo.stack
                            def apply(v: OX[Any]) =
                                handleLoop(kyo(v))
                        )
                    case <(v) =>
                        v.asInstanceOf[T]
            handleLoop(v)
        end apply

        def state[I[_], O[_], E <: Effect[I, O], State, T, U, S, S2](
            tag: Tag[E],
            st: State,
            v: T < (E & S)
        )(
            handle: [C] => (I[C], State, O[C] => T < (E & S & S2)) => (State, T < (E & S & S2)) < (S & S2),
            done: (State, T) => U = (_: State, v: T) => v
        )(using _trace: Trace): U < (S & S2) =
            def handleStateLoop(st: State, v: T < (E & S & S2)): U < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag <:< kyo.tag =>
                        handle(kyo.input, st, kyo(_)).map(handleStateLoop)
                    case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[IX, OX, EX, Any, U, S & S2]:
                            val tag            = kyo.tag
                            val input          = kyo.input
                            def trace          = _trace
                            override def stack = trace :: kyo.stack
                            def apply(v: OX[Any]) =
                                handleStateLoop(st, kyo(v))
                        )
                    case <(v) =>
                        done(st, v.asInstanceOf[T])
            handleStateLoop(st, v)
        end state
    end handle

    final case class <[+T, -S](private val state: T | Pending[T, S]) extends AnyVal:

        def flatMap[U, S2](f: T => U < S2)(using Trace): U < (S & S2) =
            map(f)

        def andThen[U, S2](f: => U < S2)(using ev: T => Unit, t: Trace): U < (S & S2) =
            map(_ => f)

        def unit(using Trace): Unit < S = map(_ => ())

        def map[U, S2](f: T => U < S2)(using _trace: Trace): U < (S & S2) =
            def mapLoop(curr: T < S): U < (S & S2) =
                curr match
                    case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[IX, OX, EX, Any, U, S & S2]:
                            val tag            = kyo.tag
                            val input          = kyo.input
                            def trace          = _trace
                            override def stack = trace :: kyo.stack
                            def apply(v: OX[Any]) =
                                mapLoop(kyo(v))
                        )
                    case <(v) =>
                        f(v.asInstanceOf[T])
            mapLoop(this)
        end map

        def pure: T =
            require(!state.isInstanceOf[Suspend[?, ?, ?, ?, ?, ?]], state.toString)
            state.asInstanceOf[T]
    end <

    object `<`:
        implicit def lift[T](v: T): T < Any = <(v)

    private object internal:
        type IX[_]
        type OX[_]
        type EX <: Effect[IX, OX]

        sealed abstract class Pending[+T, -S]

        abstract class Suspend[I[_], O[_], E <: Effect[I, O], T, U, S]
            extends Pending[U, S]:

            def tag: Tag[E]
            def input: I[T]
            def trace: Trace
            def stack: List[Trace] = List(trace)
            def apply(v: O[T]): U < S

            override def toString() =
                s"Kyo(${tag.show}, Input($input), ${trace.position}, ${trace.snippet})\n${stack.map(t => (t.position, t.snippet)).mkString("\n")}"
        end Suspend

    end internal

end core

import core.*

////////
// IO //
////////

class IO extends Effect[Const[Unit], Const[Unit]]

object IO:

    def apply[T, S](f: => T < S)(using tag: Tag[IO], t: Trace): T < (IO & S) =
        // Since I/O are `Const`s, the type parameter is actually unused
        // so I'm setting it to `Any`
        suspend[Any](tag, (), _ => f)

    def defer[T](f: => T)(using tag: Tag[IO], t: Trace): T < IO =
        suspend[Any](tag, (), _ => f)

    def run[R, T, S](v: T < (IO & S))(using tag: Tag[IO], trace: Trace): T < S =
        handle(tag, v)([C] => (_, cont) => cont(()))
end IO

/////////
// ENV //
/////////

class Env[+R] extends Effect[Const[Unit], Const[R]]

object Env:

    def get[R](using tag: Tag[Env[R]], t: Trace): R < Env[R] =
        suspend[Any](tag, ())

    class UseDsl[R](ign: Unit):
        def apply[T, S](f: R => T < S)(
            using
            tag: Tag[Env[R]],
            t: Trace
        ): T < (Env[R] & S) =
            suspend[Any](tag, (), f)
    end UseDsl

    def use[R]: UseDsl[R] = UseDsl(())

    def run[R >: Nothing: Tag, T, S, VS, VR](env: R)(v: T < (Env[VS] & S))(
        using HasEnv[R, VS] { type Remainder = VR }
    )(using tag: Tag[Env[R]], t: Trace): T < (S & VR) =
        // TODO I can't see a way to make the `HasEnv` mechanism not
        // require a type cast
        handle(tag, v)([C] => (_, cont) => cont(env)).asInstanceOf[T < (S & VR)]

    sealed trait HasEnv[V, +VS]:
        type Remainder

    trait LowPriorityHasEnv:
        given hasEnv[V, VR]: HasEnv[V, V & VR] with
            type Remainder = Env[VR]

    object HasEnv extends LowPriorityHasEnv:
        given isEnv[V]: HasEnv[V, V] with
            type Remainder = Any
    end HasEnv
end Env

///////////
// ABORT //
///////////

class Abort[-E] extends Effect[Const[Left[E, Nothing]], Const[Unit]]

object Abort:

    def fail[E](value: E)(
        using tag: Tag[Abort[E]]
    ): Nothing < Abort[E] =
        // The compiler has trouble if I define `O` as `Const[Nothing]`
        // so I'm using a continuation that will never be called to
        // produce a `Nothing` result type.
        suspend[Any](tag, Left(value), _ => ???)

    def when[E](b: Boolean)(value: E)(
        using
        tag: Tag[Abort[E]],
        t: Trace
    ): Unit < Abort[E] =
        if b then fail(value)
        else ()

    def get[E, T](either: Either[E, T])(
        using
        tag: Tag[Abort[E]],
        t: Trace
    ): T < Abort[E] =
        either match
            case Right(value) => value
            case Left(value)  => fail(value)

    class RunDsl[E](ign: Unit):
        def apply[E0 <: E, T, S, ES, ER](v: T < (Abort[ES] & S))(
            using
            h: HasAbort[E0, ES] { type Remainder = ER },
            tag: Tag[Abort[E]],
            ct: ClassTag[E0],
            trace: Trace
        ): Either[E, T] < (ER & S) =
            // TODO Also needs a type cast because of `HasAbort`
            handle(tag, v.map(Right(_): Either[E, T])) {
                [C] => (command, _) => command
            }.asInstanceOf[Either[E, T] < (ER & S)]
    end RunDsl

    def run[E]: RunDsl[E] = RunDsl(())

    sealed trait HasAbort[V, VS]:
        type Remainder

    trait LowPriorityHasAbort:
        given hasAbort[V, VR]: HasAbort[V, V | VR] with
            type Remainder = Abort[VR]

    object HasAbort extends LowPriorityHasAbort:
        given isAbort[V]: HasAbort[V, V] with
            type Remainder = Any
end Abort

/////////
// VAR //
/////////

class Var[V] extends Effect[Var.Input[V, *], Id]

object Var:
    sealed trait Input[V, X]
    case class Get[V]()             extends Input[V, V]
    case class Set[V](value: V)     extends Input[V, Unit]
    case class Update[V](f: V => V) extends Input[V, Unit]

    def get[V](using tag: Tag[Var[V]], t: Trace): V < Var[V] =
        suspend[V](tag, Get())

    class UseDsl[V](ign: Unit):
        def apply[T, S](f: V => T < S)(
            using
            tag: Tag[Var[V]],
            t: Trace
        ): T < (Var[V] & S) =
            suspend[V](tag, Get(), f)
    end UseDsl

    def use[V]: UseDsl[V] = UseDsl(())

    def set[V](value: V)(using tag: Tag[Var[V]], t: Trace): Unit < Var[V] =
        suspend(tag, Set(value))

    def update[V](f: V => V)(using tag: Tag[Var[V]], t: Trace): Unit < Var[V] =
        suspend(tag, Update(f))

    def run[V, T, S](st: V)(v: T < (Var[V] & S))(using tag: Tag[Var[V]], t: Trace): T < S =
        handle.state(tag, st, v) {
            [C] =>
                (input, state, cont) =>
                    input match
                        // This is still fully type-safe!
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

class Sum[V] extends Effect[Const[V], Const[Unit]]

object Sum:
    def add[V](v: V)(using tag: Tag[Sum[V]], t: Trace): Unit < Sum[V] =
        suspend[Any](tag, v)

    class RunDsl[V](ign: Unit):
        def apply[T, S](v: T < (Sum[V] & S))(using tag: Tag[Sum[V]], t: Trace): (Chunk[V], T) < S =
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
    )(using Trace): Output < S =
        // not stack-safe, just for demonstration
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

    def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S)(using Trace): Unit < S =
        Loop.transform(seq) {
            case Nil          => Loop.done
            case head :: tail => f(head).andThen(Loop.continue(tail))
        }
end Seqs

////////////
// STREAM //
////////////

case class Stream[T, V, S](get: T < (Stream.Emit[V] & S)):

    def take(n: Int)(using tag: Tag[Stream.Emit[V]], t: Trace): Stream[T, V, S] =
        Stream {
            handle.state(tag, n, get) {
                [C] =>
                    (input, st, cont) =>
                        st match
                            case 0 => (0, cont(()))
                            case n => Stream.emit(input).andThen((n - 1, cont(())))
            }
        }

    def runChunk(using tag: Tag[Stream.Emit[V]], t: Trace): (Chunk[V], T) < S =
        handle.state(tag, Chunk.empty[V], get)(
            handle = [C] => (input, st, cont) => (st.append(input), cont(())),
            done = (st, r) => (st, r)
        )
end Stream

object Stream:

    class Emit[V] extends Effect[Const[V], Const[Unit]]

    def init[V, T, S](v: T < (Emit[V] & S)): Stream[T, V, S] =
        Stream(v)

    def initSeq[V](seq: Seq[V])(using tag: Tag[Emit[V]], t: Trace): Stream[Unit, V, Any] =
        def loop(seq: Seq[V]): Unit < Emit[V] =
            seq match
                case Seq()        => ()
                case head +: tail => emit(head).andThen(loop(tail))
        Stream(loop(seq))
    end initSeq

    def emit[V](v: V)(using tag: Tag[Emit[V]], t: Trace): Unit < Emit[V] =
        suspend[Any](tag, v)
end Stream

////////////
// ATOMIC //
////////////

// exploring a different encoding for atomic values
object Atomic:
    opaque type OfInt = AtomicInteger
    object OfInt:
        def apply(v: Int): OfInt < IO = IO(new AtomicInteger)
        extension (self: OfInt)
            def get: Int < IO                           = IO(self.get())
            def cas(curr: Int, next: Int): Boolean < IO = IO(self.compareAndSet(curr, next))
    end OfInt

    opaque type OfRef[T] = AtomicReference[T]
    object OfRef:
        def apply[T](v: T): OfRef[T] < IO = IO(new AtomicReference[T](v))
        extension [T](self: OfRef[T])
            def get: T < IO                         = IO(self.get())
            def cas(curr: T, next: T): Boolean < IO = IO(self.compareAndSet(curr, next))
        end extension
    end OfRef
end Atomic

//////////////
// Variance //
//////////////

// a few scenarios from Kyo's test suite
object variance extends App:

    val effect1: Int < Abort[String | Boolean] =
        Abort.fail("failure")
    val handled1: Either[String, Int] < Abort[Boolean] =
        Abort.run[String](effect1)
    val handled2: Either[Boolean, Either[String, Int]] =
        Abort.run[Boolean](handled1).pure
    require(handled2 == Right(Left("failure")))

    val t1: Int < Abort[Int | String | Boolean | Float | Char | Double] =
        18
    val t2 = Abort.run[Int](t1)
    val t3 = Abort.run[String](t2)
    val t4 = Abort.run[Boolean](t3)
    val t5 = Abort.run[Float](t4)
    val t6 = Abort.run[Char](t5)
    val t7 = Abort.run[Double](t6).pure
    require(t7 == Right(Right(Right(Right(Right(Right(18)))))))

    trait Super:
        def i = 42
    case class Sub() extends Super
    require(Env.run(Sub())(Env.use[Super](_.i)).pure == 42)

    def t1(v: Int < Env[Int & String]) =
        Env.run(1)(v)
    val _: Int < Env[String] =
        t1(42)
    def t2(v: Int < (Env[Int] & Env[String])) =
        Env.run("s")(v)
    val _: Int < Env[Int] =
        t2(42)
    def t3(v: Int < Env[String]) =
        Env.run("a")(v)
    val _: Int < Any =
        t3(42)

    val t: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
    val res =
        Env.run(0.23d)(
            Env.run('a')(
                Env.run(0.23f)(
                    Env.run(false)(
                        Env.run("a")(
                            Env.run(42)(t)
                        )
                    )
                )
            )
        )
    require(res.pure == 18)

end variance

import kyo4.*

//////////////////////
// Nesting support! //
//////////////////////

object memoTest extends App:

    def memoize[T](io: T < IO): T < IO < IO =
        Atomic.OfRef(Maybe.empty[T]).map { ref =>
            IO.defer {
                ref.get.map {
                    case Maybe.Empty =>
                        io.map { r =>
                            ref.cas(Maybe.empty, Maybe.defined(r))
                                .unit.andThen(r)
                        }
                    case Maybe.Defined(v) =>
                        v
                }
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
            _     <- Abort.get(Right[String, Unit](()))
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

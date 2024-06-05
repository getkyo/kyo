package kyo2

import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
import kyo.Chunks
import kyo.Maybe
import kyo.Tag
import kyo.internal.Trace
import language.implicitConversions
import scala.annotation.targetName

object core:

    import internal.*

    type Id[T]    = T
    type Const[T] = [U] =>> T

    abstract class Effect[Command[_], E <: Effect[Command, E]]

    class SuspendDsl[R](ign: Unit) extends AnyVal:

        inline def apply[Command[_], E <: Effect[Command, E]](
            inline _tag: Tag[E],
            inline _command: Command[R]
        )(using inline _trace: Trace): R < E =
            <(new Suspend[Command, E, R, R, E]:
                def tag         = _tag
                def command     = _command
                def trace       = _trace
                def apply(v: R) = v
            )

        inline def apply[Command[_], E <: Effect[Command, E], T, R, S](
            inline _tag: Tag[E],
            inline _command: Command[T],
            inline _cont: T => R < S
        )(using inline _trace: Trace): R < (E & S) =
            <(new Suspend[Command, E, T, R, S]:
                def tag         = _tag
                def command     = _command
                def trace       = _trace
                def apply(v: T) = _cont(v)
            )
    end SuspendDsl

    // TODO Failures if effects don't explicitly specify `R`
    inline def suspend[R]: SuspendDsl[R] = SuspendDsl(())

    object handle:

        inline def apply[Command[_], E <: Effect[Command, E], T, S, S2](
            inline tag: Tag[E],
            inline v: T < (E & S)
        )(
            inline handle: (Command[Any], Any => T < S) => T < (E & S & S2)
        )(using inline _trace: Trace): T < (S & S2) =
            def handleLoop(v: T < (E & S & S2)): T < (S & S2) =
                v match
                    case <(kyo: Suspend[Command, E, Any, T, S] @unchecked) if kyo.tag =:= tag =>
                        handleLoop(handle(kyo.command, kyo(_)))
                    case <(kyo: Suspend[MX, EX, Any, T, E & S] @unchecked) =>
                        <(new Suspend[MX, EX, Any, T, S & S2]:
                            val tag     = kyo.tag
                            val command = kyo.command
                            def trace   = _trace
                            def apply(v: Any) =
                                handleLoop(kyo(v))
                        )
                    case <(v) =>
                        v.asInstanceOf[T]
            handleLoop(v)
        end apply

        inline def state[Command[_], E <: Effect[Command, E], State, T, S, S2, R](
            inline tag: Tag[E],
            inline st: State,
            inline v: T < (E & S)
        )(
            inline handle: (Command[Any], State, Any => T < S) => (State, T < (E & S)) < S2,
            inline done: (State, T) => R = (_: State, v: T) => v
        )(using inline _trace: Trace): R < (S & S2) =
            def handleStateLoop(st: State, v: T < (E & S & S2)): R < (S & S2) =
                v match
                    case <(kyo: Suspend[Command, E, Any, T, S] @unchecked) if kyo.tag =:= tag =>
                        handle(kyo.command, st, kyo(_)).map { (st2, v2) =>
                            handleStateLoop(st2, v2)
                        }
                    case <(kyo: Suspend[MX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[MX, EX, Any, R, S & S2]:
                            val tag     = kyo.tag
                            def command = kyo.command
                            def trace   = _trace
                            def apply(v: Any) =
                                handleStateLoop(st, kyo(v))
                        )
                    case <(v) =>
                        done(st, v.asInstanceOf[T])
            handleStateLoop(st, v)
        end state

    end handle

    final case class <[+T, -S](private val state: T | Kyo[T, S]) extends AnyVal:

        inline def flatMap[U, S2](inline f: T => U < S2): U < (S & S2) =
            map(f)

        inline def andThen[U, S2](inline f: => U < S2)(
            using inline ev: T => Unit
        ): U < (S & S2) =
            map(_ => f)

        inline def map[U, S2](inline f: T => U < S2)(
            using inline _trace: Trace
        ): U < (S & S2) =
            def mapLoop(curr: T < S): U < (S & S2) =
                curr match
                    case <(kyo: Suspend[MX, EX, Any, T, S] @unchecked) =>
                        <(new Suspend[MX, EX, Any, U, S & S2]:
                            val tag     = kyo.tag
                            val command = kyo.command
                            def trace   = _trace
                            override def apply(v: Any): U < (S & S2) =
                                mapLoop(kyo(v))
                        )
                    case <(v) =>
                        f(v.asInstanceOf[T])
            mapLoop(this)
        end map

        inline def pure: T =
            state match
                case kyo: Suspend[?, ?, ?, ?, ?] =>
                    ???
                case v =>
                    v.asInstanceOf[T]

        override def toString() = s"Kyo($state)"
    end <

    object `<`:
        implicit inline def lift[T](inline v: T): T < Any = <(v)

    private object internal:

        type MX[_]
        type EX <: Effect[MX, EX]

        sealed abstract class Kyo[+T, -S]

        abstract class Suspend[Command[_], E <: Effect[Command, E], T, U, S]
            extends Kyo[U, S]:
            def command: Command[T]
            def tag: Tag[E]
            def trace: Trace
            def apply(v: T): U < S

            override def toString() = s"Kyo(${tag.show},Command($command),${trace.show})"
        end Suspend

    end internal

end core

import core.*

////////
// IO //
////////

class IO extends Effect[Const[Unit], IO]

object IO:

    inline def apply[T, S](inline f: => T < S)(using inline tag: Tag[IO]): T < (IO & S) =
        suspend[T](tag, (), _ => f)

    inline def defer[T](inline f: => T)(using inline tag: Tag[IO]): T < IO =
        suspend[T](tag, (), _ => f)

    def run[R, T, S](v: T < (IO & S))(using tag: Tag[IO], trace: Trace): T < S =
        handle(tag, v)((_, cont) => cont(()))
end IO

/////////
// ENV //
/////////

class Env[R] extends Effect[Const[Unit], Env[R]]

object Env:

    inline def get[R](using inline tag: Tag[Env[R]]): R < Env[R] =
        suspend[R](tag, ())

    class UseDsl[R](ign: Unit) extends AnyVal:
        inline def apply[T, S](inline f: R => T < S)(
            using inline tag: Tag[Env[R]]
        ): T < (Env[R] & S) =
            suspend[T](tag, (), f)
    end UseDsl

    inline def use[R]: UseDsl[R] = UseDsl(())

    def run[R, T, S](e: R)(v: T < (Env[R] & S))(using tag: Tag[Env[R]], trace: Trace): T < S =
        handle(tag, v)((_, cont) => cont(e))
end Env

///////////
// ABORT //
///////////

class Abort[E] extends Effect[Const[Left[E, Nothing]], Abort[E]]

object Abort:

    inline def abort[E](inline value: E)(
        using inline tag: Tag[Abort[E]]
    ): Nothing < Abort[E] =
        suspend[Nothing](tag, Left(value))

    inline def when[E](b: Boolean)(inline value: E)(
        using inline tag: Tag[Abort[E]]
    ): Unit < Abort[E] =
        if b then abort(value)
        else ()

    inline def get[E, T](either: Either[E, T])(
        using inline tag: Tag[Abort[E]]
    ): T < Abort[E] =
        either match
            case Right(value) => value
            case Left(value)  => abort(value)

    class RunDsl[E](ign: Unit) extends AnyVal:
        def apply[T, S](v: T < (Abort[E] & S))(using tag: Tag[Abort[E]], trace: Trace): Either[E, T] < S =
            handle(tag, v.map(Right(_): Either[E, T])) { (command, _) =>
                command
            }
    end RunDsl

    inline def run[E]: RunDsl[E] = RunDsl(())
end Abort

/////////
// VAR //
/////////

class Var[V] extends Effect[Const[Var.internal.Op[V]], Var[V]]

object Var:

    import internal.*

    inline def get[V](using inline tag: Tag[Var[V]]): V < Var[V] =
        suspend[V](tag, Get)

    class UseDsl[V](ign: Unit) extends AnyVal:
        inline def apply[T, S](inline f: V => T < S)(
            using inline tag: Tag[Var[V]]
        ): T < (Var[V] & S) =
            suspend[T](tag, Get, f)
    end UseDsl

    inline def use[V]: UseDsl[V] = UseDsl(())

    inline def set[V](inline value: V)(
        using inline tag: Tag[Var[V]]
    ): Unit < Var[V] =
        suspend[Unit](tag, Set(value))

    inline def update[V](inline f: V => V)(
        using inline tag: Tag[Var[V]]
    ): Unit < Var[V] =
        suspend[Unit](tag, v => f(v))

    def run[V, T, S](st: V)(v: T < (Var[V] & S))(using tag: Tag[Var[V]], trace: Trace): T < S =
        handle.state(tag, st, v) { (command, state, cont) =>
            command match
                case _: Get.type             => (state, cont(state))
                case Set(value)              => (state, cont(()))
                case u: Update[V] @unchecked => (u(state), cont(()))
        }

    object internal:
        case object Get
        case class Set[V](v: V)
        trait Update[V]:
            def apply(v: V): V
        type Op[V] = Get.type | Set[V] | Update[V]
    end internal
end Var

/////////
// SUM //
/////////

class Sum[V] extends Effect[Const[V], Sum[V]]

object Sum:
    inline def add[V](inline v: V)(using inline tag: Tag[Sum[V]]): Unit < Sum[V] =
        suspend[Unit](tag, v)

    class RunDsl[V](ign: Unit) extends AnyVal:
        def apply[T, S](v: T < (Sum[V] & S))(using tag: Tag[Sum[V]], trace: Trace): (Chunk[V], T) < S =
            handle.state(tag, Chunks.init[V], v)(
                handle = (command, state, cont) => (state.append(command), cont(())),
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

    private val _continueUnit = Continue[Unit](())

    inline def continue[T]: Result[Unit, T] = _continueUnit

    @targetName("done0")
    def done[T]: Result[T, Unit] = ()
    @targetName("done1")
    def done[Input, Output](v: Output): Result[Input, Output] = v

    inline def continue[Input, Output, S](v: Input): Result[Input, Output] = Continue(v)

    inline def transform[Input, Output, S](
        input: Input
    )(
        inline run: Input => Result[Input, Output] < S
    )(using inline trace: Trace): Output < S =
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

    inline def indexed[Output, S](
        inline run: Int => Result[Unit, Output] < S
    )(using inline trace: Trace): Output < S =
        def loop(idx: Int): Output < S =
            run(idx).map {
                case next: Continue[Unit] @unchecked =>
                    loop(idx + 1)
                case res =>
                    res.asInstanceOf[Output]
            }
        loop(0)
    end indexed
end Loop

//////////
// SEQS //
//////////

// TODO What should we use here if we don't use plurals anymore?
object Seqs:

    def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S)(using Trace): Unit < S =
        seq.size match
            case 0 =>
            case 1 => f(seq(0))
            case size =>
                seq match
                    case seq: IndexedSeq[T] =>
                        Loop.indexed { idx =>
                            if idx == size then Loop.done
                            else f(seq(idx)).andThen(Loop.continue)
                        }
                    case seq: List[T] =>
                        Loop.transform(seq) {
                            case Nil          => Loop.done
                            case head :: tail => f(head).andThen(Loop.continue(tail))
                        }
                    case seq =>
                        ??? // Chunks.initSeq(seq).foreach(f)
                end match
        end match
    end foreach

end Seqs

////////////
// STREAM //
////////////

case class Stream[T, V, S](v: T < (Stream.Emit[V] & S))

object Stream:

    class Emit[V] extends Effect[Const[V], Emit[V]]

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
        suspend[Unit](tag, v)

    extension [T, V, S](s: Stream[T, V, S])(using tag: Tag[Stream.Emit[V]])

        def get: T < (Emit[V] & S) = s.v

        def take(n: Int): Stream[T, V, S] =
            Stream {
                handle.state(tag, n, s.v) { (command, st, cont) =>
                    st match
                        case 0 => (0, cont(()))
                        case n => Stream.emit(command).andThen((n - 1, cont(())))
                }
            }

        def drop(n: Int): Stream[T, V, S] =
            Stream {
                handle.state(tag, n, s.v) { (command, st, cont) =>
                    st match
                        case 0 => Stream.emit(command).andThen((0, cont(())))
                        case n => (n - 1, cont(()))
                }
            }

        def takeWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            Stream[T, V, S & S2] {
                handle.state(tag, true, s.v) { (command, st, cont) =>
                    st match
                        case false => (false, cont(()))
                        case true =>
                            f(command).map {
                                case false => (false, cont(()))
                                case true  => Stream.emit(command).andThen((true, cont(())))
                            }
                }
            }

        def runSeq: (Seq[V], T) < S =
            handle.state(tag, Seq.empty[V], s.v)(
                handle = (command, st, cont) => (command +: st, cont(())),
                done = (st, r) => (st, r)
            )
    end extension
end Stream

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
                            a.compareAndSet(Maybe.Empty, Maybe.Defined(r))
                            r
                        }
                    case Maybe.Defined(v) =>
                        v
            }
        }

    var calls = 0

    val io =
        for
            memo <- memoize(IO { calls += 1; println("called"); calls })
            a    <- memo
            b    <- memo
            c    <- memo
        yield (a, b, c)

    println(IO.run(io))
    println(calls)
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

    val fiestaMix: (Chunk[Int], (Seq[String], Int)) < (IO & Env[String] & Abort[String]) =
        Sum.run[Int] {
            Var.run(0) {
                Stream.init(fiestaResult).runSeq
            }
        }

    println(IO.run(Abort.run(Env.run("fiesta")(fiestaMix))).pure)
    println(IO.run(Abort.run(Env.run("work")(fiestaMix))).pure)

end effectfulFiesta

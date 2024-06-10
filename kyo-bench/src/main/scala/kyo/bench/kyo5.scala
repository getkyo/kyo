package kyo5

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kyo.Chunk
import kyo.Chunks
import kyo.Locals
import kyo.Maybe
import kyo.Tag
import kyo.internal.Trace
import language.implicitConversions
import scala.annotation.targetName
import scala.quoted.*
import scala.reflect.ClassTag
import scala.util.NotGiven
import scala.util.Random
import scala.util.control.NonFatal

object core:

    type Id[T]    = T
    type Const[T] = [U] =>> T

    import internal.*

    abstract class Effect[-I[_], +O[_]]

    final case class <[+T, -S](private val state: T | Pending[T, S]) extends AnyVal

    object `<`:
        implicit inline def lift[T](inline v: T): T < Any = <(v)

    case class SuspendDsl[T](ign: Unit) extends AnyVal:
        inline def apply[I[_], O[_], E <: Effect[I, O], U, S](
            inline _tag: Tag[E],
            inline _input: I[T],
            inline cont: O[T] => U < S = (v: O[T]) => v
        )(using _trace: Trace): U < (E & S) =
            <(new Suspend[I, O, E, T, U, S]:
                def tag   = _tag
                def input = _input
                def trace = _trace
                def apply(v: O[T], s: Safepoint, l: Locals.State): U < S =
                    if s.preempt() then
                        s.suspend(cont(v))
                    else
                        cont(v)
            )
    end SuspendDsl

    inline def suspend[T]: SuspendDsl[T] = SuspendDsl(())

    inline def bind[T, U, S, S2](v: T < S)(inline f: T => U < S2)(using inline _trace: Trace): U < (S & S2) =
        def bindLoop(curr: T < S): U < (S & S2) =
            curr match
                case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            val r = kyo(v, s, l)
                            if s.preempt() then
                                s.suspend(bindLoop(r))
                            else
                                bindLoop(r)
                            end if
                        end apply
                    )
                case <(v) =>
                    f(v.asInstanceOf[T])
        bindLoop(v)
    end bind

    abstract class Continuation[T, U, S]:
        def apply(v: T): U < S

    object handle:
        inline def apply[I[_], O[_], E <: Effect[I, O], T, S, S2](
            inline tag: Tag[E],
            inline v: T < (E & S)
        )(
            inline handle: [C] => (I[C], Continuation[O[C], T, E & S & S2]) => T < (E & S & S2)
        )(using inline _trace: Trace): T < (S & S2) =
            def handleLoop(v: T < (E & S & S2)): T < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag <:< kyo.tag =>
                        handleLoop(handle(kyo.input, kyo))
                    case <(kyo: Suspend[IX, OX, EX, Any, T, E & S & S2] @unchecked) =>
                        <(new Continue[IX, OX, EX, Any, T, S & S2](kyo):
                            def trace = _trace
                            def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                                val r = kyo(v, s, l)
                                if s.preempt() then
                                    s.suspend(handleLoop(r))
                                else
                                    handleLoop(r)
                                end if
                            end apply
                        )
                    case <(v) =>
                        v.asInstanceOf[T]
            handleLoop(v)
        end apply

        inline def state[I[_], O[_], E <: Effect[I, O], State, T, U, S, S2](
            inline tag: Tag[E],
            inline st: State,
            inline v: T < (E & S)
        )(
            inline handle: [C] => (I[C], State, Continuation[O[C], T, E & S & S2]) => (State, T < (E & S & S2)) < (S & S2),
            inline done: (State, T) => U < (S & S2) = (_: State, v: T) => v
        )(using inline _trace: Trace): U < (S & S2) =
            def handleStateLoop(st: State, v: T < (E & S & S2)): U < (S & S2) =
                v match
                    case <(kyo: Suspend[I, O, E, Any, T, E & S & S2] @unchecked) if tag <:< kyo.tag =>
                        bind(handle(kyo.input, st, kyo))(handleStateLoop)
                    case <(kyo: Suspend[IX, OX, EX, Any, T, S] @unchecked) =>
                        <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                            def trace = _trace
                            def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                                val r = kyo(v, s, l)
                                if s.preempt() then
                                    s.suspend(handleStateLoop(st, r))
                                else
                                    handleStateLoop(st, r)
                                end if
                            end apply
                        )
                    case <(v) =>
                        done(st, v.asInstanceOf[T])
            handleStateLoop(st, v)
        end state
    end handle

    inline def eval[T, S, S2](v: => T < S)(
        inline resume: (Safepoint, () => T < (S & S2)) => T < (S & S2),
        inline done: (Safepoint, T) => T < (S & S2) = (s: Safepoint, v: T) => v,
        inline suspend: T < (S & S2) => T < (S & S2) = (v: T < (S & S2)) => v
    )(using inline _trace: Trace): T < (S & S2) =
        def evalLoop(s: Safepoint, v: T < (S & S2)): T < (S & S2) =
            v match
                case <(kyo: Suspend[IX, OX, EX, Any, T, S & S2] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, T, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            suspend(evalLoop(s, resume(s, () => kyo(v, s, l))))
                    )
                case <(v) =>
                    suspend(done(s, v.asInstanceOf[T]))
        val s = Safepoint.noop
        evalLoop(s, suspend(resume(s, () => v)))
    end eval

    inline def catching[T, S, U >: T, S2](v: => T < S)(
        inline pf: PartialFunction[Throwable, U < S2]
    )(using inline _trace: Trace): U < (S & S2) =
        def catchingLoop(v: U < (S & S2)): U < (S & S2) =
            v match
                case <(kyo: Suspend[IX, OX, EX, Any, U, S & S2] @unchecked) =>
                    <(new Continue[IX, OX, EX, Any, U, S & S2](kyo):
                        def trace = _trace
                        def apply(v: OX[Any], s: Safepoint, l: Locals.State) =
                            try
                                catchingLoop(kyo(v, s, l))
                            catch
                                case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                                    pf(ex)
                    )
                case <(v) =>
                    v.asInstanceOf[T]
        try
            catchingLoop(v)
        catch
            case ex: Throwable if (NonFatal(ex) && pf.isDefinedAt(ex)) =>
                pf(ex)
        end try
    end catching

    private object internal:
        type IX[_]
        type OX[_]
        type EX <: Effect[IX, OX]

        trait Safepoint:
            def preempt(): Boolean
            def suspend[T, S](v: => T < S): T < S

        object Safepoint:
            val noop = new Safepoint:
                def preempt()                  = false
                def suspend[T, S](v: => T < S) = v
        end Safepoint

        sealed trait Pending[+T, -S]

        abstract class Suspend[I[_], O[_], E <: Effect[I, O], T, U, S]
            extends Continuation[O[T], U, S] with Pending[U, S]:

            def tag: Tag[E]
            def input: I[T]
            def trace: Trace

            def apply(v: O[T]): U < S = apply(v, Safepoint.noop, Locals.State.empty)
            def apply(v: O[T], s: Safepoint, l: Locals.State): U < S

            override def toString() =
                s"Kyo(${tag.show}, Input($input), ${trace.position}, ${trace.snippet})"
        end Suspend

        abstract class Continue[I[_], O[_], E <: Effect[I, O], T, U, S](
            prev: Suspend[I, O, E, T, ?, ?]
        ) extends Suspend[I, O, E, T, U, S]:
            val tag   = prev.tag
            val input = prev.input
        end Continue

    end internal

end core

// import core.*

// // ///////////
// // // Locals //
// // ///////////

// // abstract class Locals[T]

// // object Locals:
// //     type State = Map[Locals[?], Any]
// //     object State:
// //         def empty: State = Map.empty
// // end Locals

// ////////
// // IO //
// ////////

// sealed trait IO extends Effect[Const[Unit], Const[Unit]]

// object IO:

//     inline def apply[T, S](inline f: => T < S)(using inline tag: Tag[IO]): T < (IO & S) =
//         suspend[Any](tag, (), _ => f)

//     inline def defer[T](inline f: => T)(using inline tag: Tag[IO]): T < IO =
//         suspend[Any](tag, (), _ => f)

//     def run[R, T, S](v: T < (IO & S))(using tag: Tag[IO], trace: Trace): T < S =
//         handle(tag, v)([C] => (_, cont) => cont(()))
// end IO

// /////////
// // ENV //
// /////////

// sealed trait Env[+R] extends Effect[Const[Unit], Const[R]]

// object Env:

//     inline def get[R](using inline tag: Tag[Env[R]]): R < Env[R] =
//         suspend[Any](tag, ())

//     class UseDsl[R](ign: Unit) extends AnyVal:
//         inline def apply[T, S](inline f: R => T < S)(
//             using inline tag: Tag[Env[R]]
//         ): T < (Env[R] & S) =
//             suspend[Any](tag, (), f)
//     end UseDsl

//     inline def use[R]: UseDsl[R] = UseDsl(())

//     def run[R >: Nothing: Tag, T, S, VS, VR](env: R)(v: T < (Env[VS] & S))(
//         using HasEnv[R, VS] { type Remainder = VR }
//     )(using tag: Tag[Env[R]], t: Trace): T < (S & VR) =
//         handle(tag, v)([C] => (_, cont) => cont(env)).asInstanceOf[T < (S & VR)]

//     sealed trait HasEnv[V, +VS]:
//         type Remainder

//     trait LowPriorityHasEnv:
//         given hasEnv[V, VR]: HasEnv[V, V & VR] with
//             type Remainder = Env[VR]

//     object HasEnv extends LowPriorityHasEnv:
//         given isEnv[V]: HasEnv[V, V] with
//             type Remainder = Any
//     end HasEnv

// end Env

// ///////////
// // ABORT //
// ///////////

// sealed trait Abort[-E] extends Effect[Const[Left[E, Nothing]], Const[Unit]]

// object Abort:

//     inline def fail[E](inline value: E)(
//         using inline tag: Tag[Abort[E]]
//     ): Nothing < Abort[E] =
//         suspend[Any](tag, Left(value), _ => ???)

//     inline def when[E](b: Boolean)(inline value: E)(
//         using inline tag: Tag[Abort[E]]
//     ): Unit < Abort[E] =
//         if b then fail(value)
//         else ()

//     inline def get[E, T](either: Either[E, T])(
//         using inline tag: Tag[Abort[E]]
//     ): T < Abort[E] =
//         either match
//             case Right(value) => value
//             case Left(value)  => fail(value)

//     class RunDsl[E](ign: Unit) extends AnyVal:
//         def apply[E0 <: E, T, S, ES, ER](v: T < (Abort[ES] & S))(
//             using
//             h: HasAbort[E0, ES] { type Remainder = ER },
//             tag: Tag[Abort[E]],
//             trace: Trace
//         ): Either[E, T] < (ER & S) =
//             handle(tag, v.map(Right(_): Either[E, T])) {
//                 [C] => (input, _) => input
//             }.asInstanceOf[Either[E, T] < (ER & S)]
//     end RunDsl

//     inline def run[E]: RunDsl[E] = RunDsl(())

//     sealed trait HasAbort[V, VS]:
//         type Remainder

//     trait LowPriorityHasAbort:
//         given hasAbort[V, VR]: HasAbort[V, V | VR] with
//             type Remainder = Abort[VR]

//     object HasAbort extends LowPriorityHasAbort:
//         given isAbort[V]: HasAbort[V, V] with
//             type Remainder = Any
// end Abort

// /////////
// // VAR //
// /////////

// sealed trait Var[V] extends Effect[Var.Input[V, *], Id]

// object Var:

//     sealed trait Input[V, X]
//     private object Input:
//         case class Get[V]()             extends Input[V, V]
//         case class Set[V](value: V)     extends Input[V, Unit]
//         case class Update[V](f: V => V) extends Input[V, Unit]
//         val _get   = Get[Any]()
//         def get[V] = _get.asInstanceOf[Get[V]]
//     end Input

//     import Input.*

//     inline def get[V](using inline tag: Tag[Var[V]]): V < Var[V] =
//         suspend[V](tag, Input.get[V])

//     class UseDsl[V](ign: Unit) extends AnyVal:
//         inline def apply[T, S](inline f: V => T < S)(
//             using inline tag: Tag[Var[V]]
//         ): T < (Var[V] & S) =
//             suspend[V](tag, Get(), f)
//     end UseDsl

//     inline def use[V]: UseDsl[V] = UseDsl(())

//     inline def set[V](inline value: V)(using inline tag: Tag[Var[V]]): Unit < Var[V] =
//         suspend(tag, Set(value))

//     inline def update[V](inline f: V => V)(using inline tag: Tag[Var[V]]): Unit < Var[V] =
//         suspend(tag, Update(f))

//     def run[V, T, S](st: V)(v: T < (Var[V] & S))(using tag: Tag[Var[V]], t: Trace): T < S =
//         handle.state(tag, st, v) {
//             [C] =>
//                 (input, state, cont) =>
//                     input match
//                         case Get() =>
//                             (state, cont(state))
//                         case Set(value) =>
//                             (value, cont(()))
//                         case Update(f) =>
//                             (f(state), cont(()))
//         }

// end Var

// /////////
// // SUM //
// /////////

// sealed trait Sum[V] extends Effect[Const[V], Const[Unit]]

// object Sum:
//     inline def add[V](inline v: V)(using inline tag: Tag[Sum[V]]): Unit < Sum[V] =
//         suspend[Any](tag, v)

//     class RunDsl[V](ign: Unit) extends AnyVal:
//         def apply[T, S](v: T < (Sum[V] & S))(using tag: Tag[Sum[V]], t: Trace): (Chunk[V], T) < S =
//             handle.state(tag, Chunks.init[V], v)(
//                 handle = [C] => (input, state, cont) => (state.append(input), cont(())),
//                 done = (state, result) => (state, result)
//             )
//     end RunDsl

//     inline def run[V >: Nothing]: RunDsl[V] = RunDsl(())
// end Sum

// //////////
// // LOOP //
// //////////

// object Loop:

//     private case class Continue[Input](input: Input)

//     opaque type Result[Input, Output] = Output | Continue[Input]

//     def done[T]: Result[T, Unit] = ()

//     def continue[Input, Output, S](v: Input): Result[Input, Output] = Continue(v)

//     inline def transform[Input, Output, S](
//         input: Input
//     )(
//         inline run: Input => Result[Input, Output] < S
//     )(using inline trace: Trace): Output < S =
//         def loop(input: Input): Output < S =
//             run(input) match
//                 case <(r: Continue[Input] @unchecked) =>
//                     loop(r.input) // becomes tailrec
//                 case r =>
//                     r.map {
//                         case r: Continue[Input] =>
//                             loop(r.input)
//                         case r =>
//                             r.asInstanceOf[Output]
//                     }
//         end loop
//         loop(input)
//     end transform
// end Loop

// //////////
// // SEQS //
// //////////

// // TODO What should we use here if we don't use plurals anymore?
// object Seqs:

//     def foreach[T, U, S](seq: Seq[T])(f: T => Unit < S)(using Trace): Unit < S =
//         Loop.transform(seq) {
//             case Nil          => Loop.done
//             case head :: tail => f(head).andThen(Loop.continue(tail))
//         }
// end Seqs

// ////////////
// // STREAM //
// ////////////

// case class Stream[T, V, S](get: T < (Stream.Emit[V] & S)):

//     def take(n: Int)(using tag: Tag[Stream.Emit[V]], t: Trace): Stream[T, V, S] =
//         Stream {
//             handle.state(tag, n, get) {
//                 [C] =>
//                     (input, st, cont) =>
//                         st match
//                             case 0 => (0, cont(()))
//                             case n => Stream.emit(input).andThen((n - 1, cont(())))
//             }
//         }

//     def runChunk(using tag: Tag[Stream.Emit[V]], t: Trace): (Chunk[V], T) < S =
//         handle.state(tag, Chunk.empty[V], get)(
//             handle = [C] => (input, st, cont) => (st.append(input), cont(())),
//             done = (st, r) => (st, r)
//         )
// end Stream

// object Stream:

//     sealed trait Emit[V] extends Effect[Const[V], Const[Unit]]

//     def init[V, T, S](v: T < (Emit[V] & S)): Stream[T, V, S] =
//         Stream(v)

//     def initSeq[V](seq: Seq[V])(using tag: Tag[Emit[V]], t: Trace): Stream[Unit, V, Any] =
//         def loop(seq: Seq[V]): Unit < Emit[V] =
//             seq match
//                 case Seq()        => ()
//                 case head +: tail => emit(head).andThen(loop(tail))
//         Stream(loop(seq))
//     end initSeq

//     inline def emit[V](inline v: V)(using inline tag: Tag[Emit[V]]): Unit < Emit[V] =
//         suspend[Any](tag, v)
// end Stream

// ////////////
// // ATOMIC //
// ////////////

// // exploring a different encoding for atomic values
// object Atomic:
//     opaque type OfInt = AtomicInteger
//     object OfInt:
//         def apply(v: Int): OfInt < IO = IO(new AtomicInteger)
//         extension (self: OfInt)
//             def get: Int < IO                           = IO(self.get())
//             def cas(curr: Int, next: Int): Boolean < IO = IO(self.compareAndSet(curr, next))
//     end OfInt

//     opaque type OfRef[T] = AtomicReference[T]
//     object OfRef:
//         def apply[T](v: T): OfRef[T] < IO = IO(new AtomicReference[T](v))
//         extension [T](self: OfRef[T])
//             def get: T < IO                         = IO(self.get())
//             def cas(curr: T, next: T): Boolean < IO = IO(self.compareAndSet(curr, next))
//         end extension
//     end OfRef
// end Atomic

// //////////////
// // Variance //
// //////////////

// // a few scenarios from Kyo's test suite
// object variance extends App:

//     val effect1: Int < Abort[String | Boolean] =
//         Abort.fail("failure")
//     val handled1: Either[String, Int] < Abort[Boolean] =
//         Abort.run[String](effect1)
//     val handled2: Either[Boolean, Either[String, Int]] =
//         Abort.run[Boolean](handled1).pure
//     require(handled2 == Right(Left("failure")))

//     val t1: Int < Abort[Int | String | Boolean | Float | Char | Double] =
//         18
//     val t2 = Abort.run[Int](t1)
//     val t3 = Abort.run[String](t2)
//     val t4 = Abort.run[Boolean](t3)
//     val t5 = Abort.run[Float](t4)
//     val t6 = Abort.run[Char](t5)
//     val t7 = Abort.run[Double](t6).pure
//     require(t7 == Right(Right(Right(Right(Right(Right(18)))))))

//     trait Super:
//         def i = 42
//     case class Sub() extends Super
//     require(Env.run(Sub())(Env.use[Super](_.i)).pure == 42)

//     def t1(v: Int < Env[Int & String]) =
//         Env.run(1)(v)
//     val _: Int < Env[String] =
//         t1(42)
//     def t2(v: Int < (Env[Int] & Env[String])) =
//         Env.run("s")(v)
//     val _: Int < Env[Int] =
//         t2(42)
//     def t3(v: Int < Env[String]) =
//         Env.run("a")(v)
//     val _: Int < Any =
//         t3(42)

//     val t: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
//     val res =
//         Env.run(0.23d)(
//             Env.run('a')(
//                 Env.run(0.23f)(
//                     Env.run(false)(
//                         Env.run("a")(
//                             Env.run(42)(t)
//                         )
//                     )
//                 )
//             )
//         )
//     require(res.pure == 18)

// end variance

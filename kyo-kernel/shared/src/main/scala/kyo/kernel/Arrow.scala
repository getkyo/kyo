package kyo.kernel.exp

import <.Arrow
import java.util.ArrayDeque
import kyo.Const
import kyo.Frame
import kyo.Id
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.kernel.exp.<.ArrowEffect
import kyo.kernel.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.compiletime.erasedValue
import scala.language.implicitConversions

object ttt extends App:
    import `<`.*
    sealed trait Eff1 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Eff2 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait Eff3 extends ArrowEffect[Const[Int], Const[Int]]

    val x =
        for
            v1 <- ArrowEffect.suspend(Tag[Eff1], 1)
            a = 1
            b = 1
            c = 1
            v2 <- ArrowEffect.suspend(Tag[Eff2], 1)
            v3 <- ArrowEffect.suspend(Tag[Eff3], 1)
        yield v1 + v2 + v3

    val a = ArrowEffect.handle(Tag[Eff1], x)([C] => (i, c) => c(i + 1)).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
    val b = ArrowEffect.handle(Tag[Eff2], a)([C] => (i, c) => c(i + 2)).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)
    val c = ArrowEffect.handle(Tag[Eff3], b)([C] => (i, c) => c(i + 3)).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1).map(_ + 1)

    println(c.eval)

    // val a = (1: )
end ttt

opaque type <[+A, -S] >: Arrow[Any, A, S] = A | Arrow[Any, A, S]

object `<`:

    implicit inline def lift[A, S](v: A): A < S = v

    import Arrow.internal.*

    extension [A, S](self: A < S)

        inline def flatMap[B, S2](inline f: A => Safepoint ?=> B < S)(using safepoint: Safepoint, inline frame: Frame): B < (S & S2) =
            map(Arrow.init(f(_)))

        inline def map[B, S2](inline f: A => Safepoint ?=> B < S)(using safepoint: Safepoint, inline frame: Frame): B < (S & S2) =
            map(Arrow.init(f(_)))

        inline def map[B, S2](f: Arrow[A, B, S2])(using Safepoint): B < (S & S2) =
            f(self)

        def eval(using S =:= Any): A =
            def loop(v: A < S): A =
                v match
                    case arrow: Arrow[Any, A, S] @unchecked =>
                        loop(arrow(()))
                    case done =>
                        done.asInstanceOf[A]
            loop(self)
        end eval

    end extension

    trait ArrowEffect[I[_], O[_]]

    object ArrowEffect:

        @nowarn("msg=anonymous")
        inline def suspend[I[_], O[_], E <: ArrowEffect[I, O], A](
            inline tag: Tag[E],
            inline input: I[A]
        )(using inline frame: Frame): O[A] < E =
            new Suspend[I, O, E, A]:
                def _frame: Frame = frame
                def _tag          = tag
                def _input        = input

        private val _identityFunction: Any => Any = v => v

        inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S](
            inline tag: Tag[E],
            inline v: A < (E & S)
        )(
            inline handle: [C] => (I[C], O[C] => A < (E & S)) => A < (E & S)
        ): A < S =
            def handleLoop(v: A < (E & S)): A < S =
                v match
                    case kyo: Arrow[Any, Any, S] @unchecked =>
                        val stack = Stack.acquire()
                        val root  = Stack.load(stack)(kyo)
                        val result =
                            root match
                                case kyo: Suspend[I, O, E, A] @unchecked if kyo._tag =:= tag =>
                                    val cont: O[A] => A < (E & S) =
                                        stack.size() match
                                            case 0 =>
                                                _identityFunction.asInstanceOf[O[A] => A < (E & S)]
                                            case 1 =>
                                                stack.pop()(_)
                                            case _ =>
                                                val cont = stack.dump()
                                                Chain.unsafeEval(_, cont)
                                    handleLoop(handle(kyo._input, cont))

                                case kyo: Arrow[Any, Any, S] @unchecked =>
                                    stack.size() match
                                        case 0 =>
                                            kyo.asInstanceOf[A < S]
                                        case 1 =>
                                            AndThen(kyo, stack.pop())
                                        case _ =>
                                            val cont = stack.dump()
                                            AndThen(kyo, Arrow.init(r => handleLoop(Chain.unsafeEval(r, cont))))
                                case v =>
                                    v.asInstanceOf[A]
                            end match
                        end result
                        stack.release()
                        result
                    case _ =>
                        v.asInstanceOf[A]
            end handleLoop
            handleLoop(v)
        end handle

        @tailrec private def root(v: Any < Any, stack: ArrayDeque[Arrow[Any, Any, Any]]): Any < Any =
            v match
                case Chain(array) =>
                    def loop(idx: Int): Unit =
                        if idx < array.length then
                            stack.push(array(idx))
                            loop(idx + 1)
                    loop(1)
                    array(0)
                case AndThen(a, b) =>
                    stack.push(b.asInstanceOf[Arrow[Any, Any, Any]])
                    root(a, stack)
                case _ =>
                    v

    end ArrowEffect

    abstract class Arrow[-A, +B, -S]:

        def apply[S2 <: S](v: A < S2)(using Safepoint): B < (S & S2)

        def andThen[C, S2](other: Arrow[B, C, S2]): Arrow[A, C, S & S2] =
            if this eq Arrow._identity then
                other.asInstanceOf[Arrow[A, C, S & S2]]
            else
                AndThen(this, other)
    end Arrow

    object Arrow:

        private[Arrow] val _identity: Arrow[Any, Any, Any] = init(identity(_))

        def apply[A]: Arrow[A, A, Any] = _identity.asInstanceOf[Arrow[A, A, Any]]

        def const[A](v: A)(using Frame): Arrow[Any, A, Any] = init(_ => v)

        def defer[A, S](v: => A < S)(using Frame): Arrow[Any, A, S] = init(_ => v)

        @nowarn("msg=anonymous")
        inline def init[A, B, S](inline f: Safepoint ?=> A => B < S)(using inline frame: Frame): Arrow[A, B, S] =
            new Arrow[A, B, S]:
                println("init")
                def apply[S2 <: S](v: A < S2)(using safepoint: Safepoint) =
                    val r = preEval(v, this, frame)
                    if IsPure.equals(r) then
                        val r = f(v.asInstanceOf[A])
                        safepoint.exit()
                        r
                    else
                        r.asInstanceOf[B < (S & S2)]
                    end if
                end apply

        object internal:

            def preEval[A, B, S](v: A < S, arrow: Arrow[A, B, S], frame: Frame)(using safepoint: Safepoint): (B < S) | IsPure =
                v match
                    case kyo: Arrow[Any, A, S] @unchecked =>
                        kyo.andThen(arrow)
                    case _ =>
                        val value = v.asInstanceOf[A]
                        if !safepoint.enter(frame, value) then
                            defer(arrow(v))
                        else
                            IsPure
                        end if

            opaque type Stack = ArrayDeque[Arrow[Any, Any, Any]]

            object Stack:
                def acquire(): Stack = new ArrayDeque[Arrow[Any, Any, Any]]
                extension (self: Stack)
                    def release(): Unit = ()
                    def load(v: Any < Any): Any < Any =
                        v match
                            case Chain(array) =>
                                def loop(idx: Int): Unit =
                                    if idx < array.length then
                                        self.push(array(idx))
                                        loop(idx + 1)
                                loop(1)
                                array(0)
                            case AndThen(a, b) =>
                                self.push(b.asInstanceOf[Arrow[Any, Any, Any]])
                                load(a)
                            case _ =>
                                v
                    def pop[A, B, S](): Arrow[A, B, S] = self.pop().asInstanceOf[Arrow[A, B, S]]
                    def dump(): IArray[Arrow[Any, Any, Any]] =
                        IArray.unsafeFromArray(self.toArray(Chain.emptyArray))
                    def empty(): Boolean = self.isEmpty()
                    def size(): Int      = self.size()
                end extension
            end Stack

            sealed trait IsPure
            case object IsPure extends IsPure

            case class Chain[A, B, S](array: IArray[Arrow[Any, Any, Any]]) extends Arrow[A, B, S]:
                println("Chain")
                def apply[S2 <: S](v: A < S2)(using Safepoint) =
                    Chain.unsafeEval(v, array)
            end Chain

            object Chain:
                val emptyArray = new Array[Arrow[Any, Any, Any]](0)
                def unsafeEval[A, B, S](v: A < S, array: IArray[Arrow[Any, Any, Any]]): B < S =
                    def loop(v: Any < Any, idx: Int): Any < Any =
                        if idx == array.length then
                            v
                        else
                            array(idx)(v) match
                                case kyo: Arrow[Any, Any, Any] @unchecked =>
                                    val left = array.length - idx
                                    if left == 1 then
                                        kyo.asInstanceOf[B < S]
                                    else
                                        val newArray = new Array[Arrow[Any, Any, Any]](left)
                                        newArray(0) = kyo
                                        System.arraycopy(array, idx + 1, newArray, 1, left - 1)
                                        Chain(IArray.unsafeFromArray(newArray))
                                    end if
                                case v =>
                                    loop(v, idx + 1)
                    loop(v, 0).asInstanceOf[B < S]
                end unsafeEval
            end Chain

            case class AndThen[A, B, C, S](a: Arrow[A, B, S], b: Arrow[B, C, S]) extends Arrow[A, C, S]:
                println("AndThen")
                def apply[S2 <: S](v: A < S2)(using Safepoint): C < (S & S2) = b(a(v))

            sealed abstract class Suspend[I[_], O[_], E <: ArrowEffect[I, O], A] extends Arrow[Any, O[A], E]:
                println("Suspend")
                def _frame: Frame
                def _tag: Tag[E]
                def _input: I[A]

                def apply[S2 <: E](v: Any < S2)(using Safepoint): O[A] < (E & S2) = this
            end Suspend
        end internal
    end Arrow
end `<`

package kyo.kernel

import internal.*
import kyo.Flat
import kyo.Frame
import kyo.Tag
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

abstract class ArrowEffect[-I[_], +O[_]] extends Effect

object ArrowEffect:

    final class SuspendOps[A](dummy: Unit) extends AnyVal:

        @nowarn("msg=anonymous")
        inline def apply[I[_], O[_], E <: ArrowEffect[I, O]](
            inline _tag: Tag[E],
            inline _input: I[A]
        )(using inline _frame: Frame): O[A] < E =
            new KyoSuspend[I, O, E, A, O[A], E]:
                def frame = _frame
                def tag   = _tag
                def input = _input
                def apply(v: O[A], context: Context)(using Safepoint) =
                    v
    end SuspendOps

    inline def suspend[A]: SuspendOps[A] = SuspendOps(())

    @nowarn("msg=anonymous")
    final class SuspendAndMapOps[A](dummy: Unit) extends AnyVal:
        inline def apply[I[_], O[_], E <: ArrowEffect[I, O], B, S](
            inline _tag: Tag[E],
            inline _input: I[A]
        )(
            inline _cont: Safepoint ?=> O[A] => B < S
        )(using inline _frame: Frame): B < (S & E) =
            new KyoSuspend[I, O, E, A, B, S & E]:
                def frame = _frame
                def tag   = _tag
                def input = _input
                def apply(v: O[A], context: Context)(using Safepoint) =
                    Safepoint.handle(v)(
                        suspend = _cont(v),
                        continue = _cont(v)
                    )
    end SuspendAndMapOps

    inline def suspendAndMap[A]: SuspendAndMapOps[A] = SuspendAndMapOps(())

    object handle:
        inline def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, S3](
            inline tag: Tag[E],
            v: A < (E & S)
        )(
            inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S & S2)) => A < (E & S & S2),
            inline done: A => B < S3 = (v: A) => v,
            inline accept: [C] => I[C] => Boolean = [C] => (v: I[C]) => true
        )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): B < (S & S2 & S3) =
            @nowarn("msg=anonymous")
            def handleLoop(v: A < (E & S & S2 & S3), context: Context)(using Safepoint): B < (S & S2 & S3) =
                v match
                    case kyo: KyoSuspend[I, O, E, Any, A, E & S & S2] @unchecked if tag =:= kyo.tag && accept(kyo.input) =>
                        Safepoint.handle(kyo.input)(
                            eval = handle[Any](kyo.input, kyo(_, context)),
                            continue = handleLoop(_, context),
                            suspend = handleLoop(kyo, context)
                        )
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S & S2 & S3] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, B, S & S2 & S3](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                handleLoop(kyo(v, context), context)
                        end new
                    case kyo =>
                        done(kyo.asInstanceOf[A])
                end match
            end handleLoop
            handleLoop(v, Context.empty)
        end apply

        inline def apply[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], A, S, S2](
            inline tag1: Tag[E1],
            inline tag2: Tag[E2],
            v: A < (E1 & E2 & S)
        )(
            inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2),
            inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2)
        )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): A < (S & S2) =
            @nowarn("msg=anonymous")
            def handle2Loop(kyo: A < (E1 & E2 & S & S2), context: Context)(using Safepoint): A < (S & S2) =
                kyo match
                    case kyo: KyoSuspend[I1, O1, E1, Any, A, E1 & E2 & S & S2] @unchecked if tag1 =:= kyo.tag =>
                        Safepoint.handle(kyo.input)(
                            eval = handle1[Any](kyo.input, kyo(_, context)),
                            suspend = handle2Loop(kyo, context),
                            continue = handle2Loop(_, context)
                        )
                    case kyo: KyoSuspend[I2, O2, E2, Any, A, E1 & E2 & S & S2] @unchecked if tag2 =:= kyo.tag =>
                        Safepoint.handle(kyo.input)(
                            eval = handle2[Any](kyo.input, kyo(_, context)),
                            suspend = handle2Loop(kyo, context),
                            continue = handle2Loop(_, context)
                        )
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, E1 & E2 & S & S2] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                handle2Loop(kyo(v, context), context)
                        end new
                    case kyo =>
                        kyo.asInstanceOf[A]
                end match
            end handle2Loop
            handle2Loop(v, Context.empty)
        end apply

        inline def apply[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], I3[_], O3[_], E3 <: ArrowEffect[
            I3,
            O3
        ], A, S, S2](
            inline tag1: Tag[E1],
            inline tag2: Tag[E2],
            inline tag3: Tag[E3],
            v: A < (E1 & E2 & E3 & S)
        )(
            inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2),
            inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2),
            inline handle3: [C] => (I3[C], Safepoint ?=> O3[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2)
        )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): A < (S & S2) =
            @nowarn("msg=anonymous")
            def handle3Loop(v: A < (E1 & E2 & E3 & S & S2), context: Context)(using Safepoint): A < (S & S2) =
                v match
                    case kyo: KyoSuspend[I1, O1, E1, Any, A, E1 & E2 & E3 & S & S2] @unchecked if tag1 =:= kyo.tag =>
                        Safepoint.handle(kyo.input)(
                            eval = handle1[Any](kyo.input, kyo(_, context)),
                            suspend = handle3Loop(kyo, context),
                            continue = handle3Loop(_, context)
                        )
                    case kyo: KyoSuspend[I2, O2, E2, Any, A, E1 & E2 & E3 & S & S2] @unchecked if tag2 =:= kyo.tag =>
                        Safepoint.handle(kyo.input)(
                            eval = handle2[Any](kyo.input, kyo(_, context)),
                            suspend = handle3Loop(kyo, context),
                            continue = handle3Loop(_, context)
                        )
                    case kyo: KyoSuspend[I3, O3, E3, Any, A, E1 & E2 & E3 & S & S2] @unchecked if tag3 =:= kyo.tag =>
                        Safepoint.handle(kyo.input)(
                            eval = handle3[Any](kyo.input, kyo(_, context)),
                            suspend = handle3Loop(kyo, context),
                            continue = handle3Loop(_, context)
                        )
                    case kyo: KyoSuspend[IX, OX, EX, Any, A, E1 & E2 & E3 & S & S2] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                handle3Loop(kyo(v, context), context)
                        end new
                    case kyo =>
                        kyo.asInstanceOf[A]
                end match
            end handle3Loop
            handle3Loop(v, Context.empty)
        end apply
    end handle

    inline def handleState[I[_], O[_], E <: ArrowEffect[I, O], State, A, B, S, S2, S3](
        inline tag: Tag[E],
        inline state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], State, Safepoint ?=> O[C] => A < (E & S & S2)) => (State, A < (E & S & S2)) < S3,
        inline done: (State, A) => B < (S & S2 & S3) = (_: State, v: A) => v,
        inline accept: [C] => I[C] => Boolean = [C] => (v: I[C]) => true
    )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): B < (S & S2 & S3) =
        @nowarn("msg=anonymous")
        def handleLoop(state: State, v: A < (E & S & S2 & S3), context: Context)(using Safepoint): B < (S & S2 & S3) =
            v match
                case kyo: KyoSuspend[I, O, E, Any, A, E & S & S2] @unchecked if tag =:= kyo.tag && accept(kyo.input) =>
                    handle(kyo.input, state, kyo(_, context)).map(handleLoop(_, _, context))
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S & S2 & S3](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handleLoop(state, kyo(v, context), context)
                    end new
                case kyo =>
                    done(state, kyo.asInstanceOf[A])
            end match
        end handleLoop
        handleLoop(state, v, Context.empty)
    end handleState

    inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, S3](
        inline tag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S & S2)) => A < (E & S & S2),
        inline done: A => B < S3 = (v: A) => v,
        inline accept: [C] => I[C] => Boolean = [C] => (v: I[C]) => true,
        inline recover: Throwable => B < (S & S2 & S3)
    )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): B < (S & S2 & S3) =
        @nowarn("msg=anonymous")
        def handleLoop(v: A < (E & S & S2 & S3), context: Context)(using Safepoint): B < (S & S2 & S3) =
            v match
                case kyo: KyoSuspend[I, O, E, Any, A, E & S & S2] @unchecked if tag =:= kyo.tag && accept(kyo.input) =>
                    Safepoint.handle(kyo.input)(
                        eval = handle[Any](kyo.input, kyo(_, context)),
                        continue = handleLoop(_, context),
                        suspend = handleLoop(kyo, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S & S2 & S3] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S & S2 & S3](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            try handleLoop(kyo(v, context), context)
                            catch
                                case ex if NonFatal(ex) =>
                                    Safepoint.enrich(ex)
                                    recover(ex)
                        end apply
                    end new
                case kyo =>
                    done(kyo.asInstanceOf[A])
            end match
        end handleLoop

        try handleLoop(v, Context.empty)
        catch
            case ex if NonFatal(ex) =>
                Safepoint.enrich(ex)
                recover(ex)
        end try
    end handleCatching

    private[kyo] inline def handlePartial[
        I1[_],
        O1[_],
        E1 <: ArrowEffect[I1, O1],
        I2[_],
        O2[_],
        E2 <: ArrowEffect[I2, O2],
        A,
        S,
        S2
    ](
        inline tag1: Tag[E1],
        inline tag2: Tag[E2],
        v: A < (E1 & E2 & S),
        context: Context
    )(
        inline stop: => Boolean,
        inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2),
        inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2)
    )(using inline _frame: Frame, inline flat: Flat[A], safepoint: Safepoint): A < (E1 & E2 & S & S2) =
        def partialLoop(v: A < (E1 & E2 & S & S2), context: Context)(using safepoint: Safepoint): A < (E1 & E2 & S & S2) =
            if stop then v
            else
                v match
                    case kyo: KyoSuspend[?, ?, ?, ?, ?, ?] =>
                        type Suspend[I[_], O[_], E <: ArrowEffect[I, O]] = KyoSuspend[I, O, E, Any, A, E1 & E2 & S & S2]
                        if kyo.tag =:= Tag[Defer] then
                            val k = kyo.asInstanceOf[Suspend[Const[Unit], Const[Unit], Defer]]
                            partialLoop(k((), context), context)
                        else
                            safepoint.pushFrame(kyo.frame)
                            if tag1 =:= kyo.tag then
                                val k = kyo.asInstanceOf[Suspend[I1, O1, E1]]
                                partialLoop(handle1[Any](k.input, k(_, context)), context)
                            else if tag2 =:= kyo.tag then
                                val k = kyo.asInstanceOf[Suspend[I2, O2, E2]]
                                partialLoop(handle2[Any](k.input, k(_, context)), context)
                            else
                                v
                            end if
                        end if
                    case _ =>
                        v
                end match
        end partialLoop
        partialLoop(v, context)
    end handlePartial
end ArrowEffect

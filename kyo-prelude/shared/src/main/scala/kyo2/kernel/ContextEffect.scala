package kyo2.kernel

import internal.*
import kyo.Tag
import kyo2.bug
import kyo2.isNull
import scala.util.NotGiven

abstract class ContextEffect[+A]

object ContextEffect:

    inline def suspend[A, E <: ContextEffect[A]](inline tag: Tag[E]): A < E =
        suspendMap(tag)(identity)

    inline def suspendMap[A, E <: ContextEffect[A], B, S](
        inline tag: Tag[E]
    )(
        inline f: Safepoint ?=> A => B < S
    ): B < (E & S) =
        suspendMap(tag, bug("Unexpected pending context effect: " + tag.show))(f)

    inline def suspend[A, E <: ContextEffect[A]](
        inline tag: Tag[E],
        inline default: => A
    ): A < Any =
        suspendMap(tag, default)(identity)

    inline def suspendMap[A, E <: ContextEffect[A], B, S](
        inline _tag: Tag[E],
        inline default: => A
    )(
        inline f: Safepoint ?=> A => B < S
    )(using inline _frame: Frame): B < S =
        new KyoDefer[B, S]:
            def frame = _frame
            def apply(v: Unit, context: Context)(using Safepoint) =
                Safepoint.handle(v)(
                    suspend = this,
                    continue = f(context.getOrElse(_tag, default).asInstanceOf[A])
                )

    inline def handle[A, E <: ContextEffect[A], B, S](
        inline _tag: Tag[E],
        inline ifUndefined: A,
        inline ifDefined: A => A
    )(v: B < (E & S))(
        using inline _frame: Frame
    ): B < S =
        def handleLoop(v: B < (E & S))(using Safepoint): B < S =
            v match
                case <(kyo: KyoSuspend[IX, OX, EX, Any, B, S] @unchecked) =>
                    new KyoSuspend[IX, OX, EX, Any, B, S]:
                        val tag   = kyo.tag
                        val input = kyo.input
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            val tag = _tag // avoid inlining the tag multiple times
                            val updated =
                                if !context.contains(tag) then context.set(tag, ifUndefined)
                                else context.set(tag, ifDefined(context.get(tag)))
                            handleLoop(kyo(v, updated))
                        end apply
                case <(kyo) =>
                    kyo.asInstanceOf[B]
        handleLoop(v)
    end handle
end ContextEffect

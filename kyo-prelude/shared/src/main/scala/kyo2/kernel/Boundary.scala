package kyo2.kernel

import internal.*

object Boundary:

    type WithoutContextEffects[S] = S match
        case ContextEffect[x] => Any
        case s1 & s2          => WithoutContextEffects[s1] & WithoutContextEffects[s2]
        case _                => S

    def apply[A, B, S, S2](v: A < S)(
        f: A < WithoutContextEffects[S] => B < S2
    )(using _frame: Frame): B < (S & S2) =
        new KyoDefer[B, S & S2]:
            def frame = _frame
            def apply(dummy: Unit, context: Context)(using safepoint: Safepoint) =
                val state = safepoint.save(context)
                def boundaryLoop(v: A < S): A < S =
                    v match
                        case <(kyo: KyoSuspend[IX, OX, EX, Any, A, S] @unchecked) =>
                            new KyoSuspend[IX, OX, EX, Any, A, S]:
                                val tag   = kyo.tag
                                val input = kyo.input
                                def frame = _frame
                                def apply(v: OX[Any], context: Context)(using Safepoint) =
                                    val parent = Safepoint.local.get()
                                    Safepoint.local.set(Safepoint(parent.depth, state))
                                    val r =
                                        try kyo(v, state.context)
                                        finally Safepoint.local.set(parent)
                                    boundaryLoop(r)
                                end apply
                        case _ =>
                            v
                f(Effect.defer(boundaryLoop(v).asInstanceOf[A < WithoutContextEffects[S]]))
            end apply
        end new
    end apply
end Boundary

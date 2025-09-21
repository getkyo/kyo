package kyo.experimental

import kyo.*
import kyo.kernel.ContextEffect

// Based on ContextEffect (which is not an ArrowEffect) - similar to what's used by Env.
// Uses R[Any] <: R[S] because when handled, S will be added to the pending effect set.
opaque type Use[+R[-_]] = ContextEffect[TypeMap[R[Any]]]

// Helper type alias for combining two effect requirements R1 and R2 into a single requirement
infix type &&[+R1[-_], +R2[-_]] = [S] =>> R1[S] & R2[S]

object Use:

    // Helper type used for type erasure
    private[kyo] trait AnyT[-A]

    // Creates a suspended computation that requests and uses a context value R.
    // The R[Use[R]] type ensures proper tracking of the Use effect in the type system,
    // even when lifted into nested contexts like Stream or (A < S1) < S2.
    def use[R[-_]](using frame: Frame)[A, S1](f: R[Use[R]] => A < S1)(using tag: Tag[R[Any]]): A < (Use[R] & S1) =
        ContextEffect.suspendWith(internal.erasedTag[R]): map =>
            f(map.asInstanceOf[TypeMap[R[Any]]].get(using tag))

    // Runs an effectful computation by providing an implementation R[S1].
    // Adds S1 to the pending effect set and provides R[?] to satisfy Use[R].
    def run[R[-_], S1](r: R[S1])[A, S2](a: A < (Use[R] & S2))(using tag: Tag[R[Any]], frame: Frame): A < (S1 & S2) =
        val env: TypeMap[R[Any]] = TypeMap(r.asInstanceOf[R[Any]])
        ContextEffect.handle(internal.erasedTag[R], env, _.union(env))(a)

    // Retrieves the context value of type R within a Use[R] effect context
    def get[R[-_]](using frame: Frame, tag: Tag[R[Any]]): R[Use[R]] < Use[R] = use[R](identity)

    private[kyo] object internal:
        // Internal utilities for type tag handling
        def erasedTag[R[-_]]: Tag[Use[R]] = Tag[Use[AnyT]].asInstanceOf[Tag[Use[R]]]

end Use

// Extension of Use that includes async capability
opaque type UseAsync[+R[-_]] <: (Use[R] & kyo.Async) = ContextEffect[TypeMap[R[Any]]] & kyo.Async

object UseAsync:

    // supported effects for A < UseAsync[SomeService]
    // those effects are what are commonly found in ZIO / CE
    // for example, Var, Emit, Check, ... are not supported for now
    private type SupportedEffects = Async & Abort[Any] & Env[Nothing]

    // Type class instance for isolating async effects
    inline given [R[-_]]: Isolate[UseAsync[R], kyo.Async, UseAsync[R]] =
        Isolate.derive[SupportedEffects, kyo.Async, SupportedEffects].asInstanceOf

    // Async variant of Use.use that includes Async effect
    def use[R[-_]](using frame: Frame)[A, S1](f: R[UseAsync[R]] => A < S1)(using tag: Tag[R[Any]]): A < (UseAsync[R] & S1) =
        Use.use(f)

    // Async variant of Use.run that be used in an Async context
    def run[R[-_], S1 >: SupportedEffects](r: R[S1])(using
        frame: Frame
    )[A, S2](a: A < (UseAsync[R] & S2))(using tap: Tag[R[Any]]): A < (S1 & S2) =
        Use.run(r)(a.asInstanceOf[A < (Use[R] & S2)])
    end run

end UseAsync

abstract class Service[+R[-_], -S] extends Serializable:
    inline def and[R1[-_], S1](service: Service[R1, S1]): Service[R && R1, S & S1] =
        Service.internal.And(this, service)

end Service

object Service:
    def apply[R[-_], S1, S2](v: => R[S1] < S2)(using Tag[R[Any]], Frame): Service[R, S1 & S2] =
        internal.FromKyo_0[R, S1 & S2](() => v.map(r => TypeMap(r.asInstanceOf[R[Any]])))

    private object internal:
        case class And[R0[-_], R1[-_], S0, S1](lhs: Service[R0, S0], rhs: Service[R1, S1]) extends Service[R0 && R1, S0 & S1]

        case class FromKyo_0[Out[-_], S](v: () => TypeMap[Out[Any]] < S)(using val tag: Tag[Out[Any]]) extends Service[Out, S]

        case class FromKyo_1[In[-_], Out[-_], S](v: () => TypeMap[Out[Any]] < (Use[In] & S))(using val tag: Tag[Out[Any]])
            extends Service[Out, Use[In] & S]
    end internal
end Service

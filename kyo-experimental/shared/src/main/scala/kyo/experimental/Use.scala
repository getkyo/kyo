package kyo.experimental

import kyo.*
import kyo.experimental.Service.internal
import kyo.kernel.ContextEffect
import scala.annotation.implicitNotFound
import scala.collection.immutable.TreeSeqMap

// Helper type alias for combining two effect requirements R1 and R2 into a single requirement
infix type &&[+R1[-_], +R2[-_]] = [S] =>> R1[S] & R2[S]

opaque type Use[+R[-_]] = ContextEffect[ServiceMap[R, Any]]

object Use:

    // Helper type used for type erasure
    private[kyo] trait AnyT[-A]

    // Creates a suspended computation that requests and uses a context value R.
    // The R[Use[R]] type ensures proper tracking of the Use effect in the type system,
    // even when lifted into nested contexts like Stream or (A < S1) < S2.
    def use[R[-_]](using frame: Frame)[A, S1](f: R[Use[R]] => A < S1)(using tag: Tag[R[Any]]): A < (Use[R] & S1) =
        ContextEffect.suspendWith(internal.erasedTag[R])(map => f(map.get))

    // Runs an effectful computation by providing an implementation R[S1].
    // Adds S1 to the pending effect set and provides R[?] to satisfy Use[R].
    def run[R[-_], S1](r: R[S1])[A, S2](a: A < (Use[R] & S2))(using tag: Tag[R[Any]], frame: Frame): A < (S1 & S2) =
        val env: ServiceMap[R, S1] = ServiceMap(r)
        ContextEffect.handle(internal.erasedTag[R], env, _.union(env))(a)

    // Retrieves the context value of type R within a Use[R] effect context
    def get[R[-_]](using frame: Frame, tag: Tag[R[Any]]): R[Use[R]] < Use[R] = use[R](identity)

    private[kyo] object internal:
        // Internal utilities for type tag handling
        def erasedTag[R[-_]]: Tag[Use[R]] = Tag[Use[AnyT]].asInstanceOf[Tag[Use[R]]]

end Use

// Extension of Use that includes async capability
opaque type UseAsync[+R[-_]] <: (Use[R] & kyo.Async) = ContextEffect[ServiceMap[R, Any]] & kyo.Async

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
    def run[R[-_], S1](r: R[S1])(using
        frame: Frame,
        @implicitNotFound(
            "UseAsync.run requires the service effect S to be limited to Async, Abort[Error & ...], Env[Resource & ...]. Provided: ${S1}. " +
                "\nHint: limit your service effect types to those effects, or use Use.run for non-async services."
        )
        ev: SupportedEffects <:< S1
    )[A, S2](a: A < (UseAsync[R] & S2))(using tap: Tag[R[Any]]): A < (S1 & S2) =
        Use.run(r)(a.asInstanceOf[A < (Use[R] & S2)])
    end run

end UseAsync

extension [A, R[-_], S](v: A < (Use[R] & S))
    def toUseAsync: A < (UseAsync[R] & S) = v

sealed abstract class Service[+R[-_], -S] extends Serializable:
    self =>

    infix def and[R1[-_], S1](service: Service[R1, S1]): Service[R && R1, S & S1] =
        Service.internal.Provide(self, service)

    infix def provide[R1[-_], S1](that: Service[R1, S1 & Use[R]]): Service[R && R1, S & S1] =
        Service.internal.Provide(self, that)

    def run[S1, A](v: A < (S1 & Use[R]))(using frame: Frame): A < (S1 & S) =
        this match
            case internal.Provide(first, second) => ???
            case k0: internal.FromKyo_0[?, ?] =>
                import k0.tag
                k0.v().map(r => Use.run(r)(v))
            case internal.FromKyo_1(v) => ???

end Service

object Service:
    def apply[R[-_], S1, S2](v: => R[S1] < S2)(using Tag[R[Any]], Frame): Service[R, S1 & S2] =
        internal.FromKyo_0[R, S1 & S2](() => v)

    def using[R0[-_]](using
        Frame,
        Tag[R0[Any]]
    )[R1[-_], S1, S2](v: R0[Use[R0]] => R1[S1] < (S2 & Use[R0]))(using Tag[R1[Any]]): Service[R1, Use[R0] & S1 & S2] =
        internal.FromKyo_1[R0, R1, S1 & S2](() => Use.use[R0](v))

    private object internal:
        case class Provide[R0[-_], R1[-_], S0, S1](first: Service[R0, S0], second: Service[R1, S1 & Use[R0]])
            extends Service[R0 && R1, S0 & S1]

        case class FromKyo_0[Out[-_], S](v: () => Out[S] < S)(using val tag: Tag[Out[Any]]) extends Service[Out, S]
        case class FromKyo_1[In[-_], Out[-_], S](v: () => Out[Use[In] & S] < (Use[In] & S))
            extends Service[Out, Use[In] & S]
    end internal
end Service

package kyo.experimental

import kyo.*
import kyo.kernel.ContextEffect

// Custom effect that can be defined outside of Kyo
// It's based on ContextEffect (which is not an ArrowEffect)
// The same thing use by Env
//
// We can use R[Any] <: R[S] here, because when handled,
//   S will be added to the pending set.

opaque type Use[+R[-_]] = ContextEffect[TypeMap[R[Any]]]

infix type &&[R1[-_], R2[-_]] = [S] =>> R1[S] & R2[S]

object Use:

    opaque type Async[+R[-_]] <: (Use[R] & kyo.Async) = ContextEffect[TypeMap[R[Any]]] & kyo.Async

    private case class IsolateHolder(isolate: Isolate[?, kyo.Async, ?])
    private object noOpIsolate extends Isolate[Any, Any, Any]:
        type State        = Unit
        type Transform[A] = A

        override def capture[A, S](f: Unit => A < S)(using Frame): A < S        = f(())
        override def isolate[A, S](state: Unit, v: A < S)(using Frame): <[A, S] = v
        override def restore[A, S](v: <[A, S])(using Frame): A < S              = v
    end noOpIsolate

    private object IsolateFromEnv extends Isolate[Any, Any, Any]:
        type State        = Any
        type Transform[A] = Any

        override def capture[A, S](f: IsolateFromEnv.State => A < S)(using Frame): A < S =
            Env.use[IsolateHolder](_.isolate.capture(f)).asInstanceOf

        override def isolate[A, S](state: IsolateFromEnv.State, v: A < S)(using Frame): <[Any, S] =
            Env.use[IsolateHolder](_.isolate.isolate(state.asInstanceOf, v)).asInstanceOf

        override def restore[A, S](v: <[Any, S])(using Frame): A < S =
            Env.use[IsolateHolder](_.isolate.restore(v.asInstanceOf)).asInstanceOf
    end IsolateFromEnv

    inline given [R[-_]]: Isolate[Use.Async[R], kyo.Async, Use.Async[R]] = IsolateFromEnv.asInstanceOf

    private trait AnyT[-A]

    // We can erase R in the Tag because the implementation rely on a TypeMap that will do all the work
    private def erasedTag[R[-_]] = Tag[Use[AnyT]].asInstanceOf[Tag[Use[R]]]

    // R[Use[R]] will force the use of R to add Use[R] to the pending set ... that has already Use[R]
    // it also solves cases where the effect is lifted (in a Stream, in a (A < S) < S2, ...
    // so we don't lose the type marker for Use[R] if it's not use directly
    // (if you would have used Context function for that, it's an use case for Caprese)
    def use[R[-_]](using frame: Frame)[A, S1](f: R[Use[R]] => A < S1)(using tag: Tag[R[Any]]): A < (Use[R] & S1) =
        ContextEffect.suspendWith(erasedTag[R]): map =>
            f(map.asInstanceOf[TypeMap[R[Any]]].get(using tag))

    def useAsync[R[-_]](using frame: Frame)[A, S1](f: R[Use.Async[R]] => A < S1)(using tag: Tag[R[Any]]): A < (Use.Async[R] & S1) =
        use(f)

    // run with R[S1] will provide R[?] to the A < (Use[R] & S2), and add S1 in the pending set
    def run[R[-_], S1](r: R[S1])[A, S2](a: A < (Use[R] & S2))(using tag: Tag[R[Any]], frame: Frame): A < (S1 & S2) =
        val env: TypeMap[R[Any]] = TypeMap(r.asInstanceOf[R[Any]])
        ContextEffect.handle(erasedTag[R], env, _.union(env))(a)

    def runAsync[R[-_], S1](r: R[S1])(using
        isolate: Isolate[S1, kyo.Async, S1]
    )[A, S2](a: A < (Use.Async[R] & S2))(using tap: Tag[R[Any]], frame: Frame): A < (S1 & S2 & kyo.Async) =
        val env: TypeMap[R[Any]] = TypeMap(r.asInstanceOf[R[Any]])
        ContextEffect.handle(erasedTag[R], env, _.union(env))(
            Env.run(IsolateHolder(Option(isolate).getOrElse(noOpIsolate)))(a)
        )
    end runAsync

    def get[R[-_]](using frame: Frame, tag: Tag[R[Any]]): R[Use[R]] < Use[R] = use[R](identity)
end Use

abstract class Service[+R[-_], -S] extends Serializable

object Service:
    def apply[A, S](v: => A < S): Service[Const[A], S] = ??? // TODO

package kyo2

import kyo.Tag
import kyo2.*
import kyo2.kernel.*
import scala.util.NotGiven

sealed trait Env[+R] extends ContextEffect[TypeMap[R]]

object Env:

    given eliminateEnv: Reducible.Eliminable[Env[Any]] with {}
    private inline def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]

    inline def get[R](using inline tag: Tag[R])(using inline frame: Frame): R < Env[R] =
        use[R](identity)

    def run[R >: Nothing: Tag, T, S, VR](env: R)(v: T < (Env[R & VR] & S))(
        using
        reduce: Reducible[Env[VR]],
        frame: Frame
    ): T < (S & reduce.SReduced) =
        runTypeMap(TypeMap(env))(v)

    def runTypeMap[R >: Nothing, T, S, VR](env: TypeMap[R])(v: T < (Env[R & VR] & S))(
        using
        reduce: Reducible[Env[VR]],
        frame: Frame
    ): T < (S & reduce.SReduced) =
        reduce(ContextEffect.handle(erasedTag[R], env, _.union(env))(v): T < (Env[VR] & S))

    final class UseOps[R >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A, S](inline f: R => A < S)(
            using tag: Tag[R]
        ): A < (Env[R] & S) =
            ContextEffect.suspendMap(erasedTag[R]) { map =>
                f(map.asInstanceOf[TypeMap[R]].get(using tag))
            }
    end UseOps

    inline def use[R >: Nothing]: UseOps[R] = UseOps(())

end Env

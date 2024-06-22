package kyo2

import java.awt.Window.Type
import kyo.Tag
import kyo2.*
import kyo2.kernel.*
import scala.util.NotGiven

sealed trait Env[+R] extends RuntimeEffect[TypeMap[R]]

object Env:

    private inline def erasedTag[R] = Tag[Env[Any]].asInstanceOf[Tag[Env[R]]]

    inline def get[R](using inline tag: Tag[R]): R < Env[R] =
        use[R](identity)

    def run[R >: Nothing: Tag, T, S, RS, RR](env: R)(value: T < (Env[RS] & S))(
        using
        HasEnv[R, RS] { type Remainder = RR },
        Frame
    ): T < (S & RR) =
        runTypeMap(TypeMap(env))(value)

    def runTypeMap[R >: Nothing, T, S, RS, RR](env: TypeMap[R])(value: T < (Env[RS] & S))(
        using
        HasEnv[R, RS] { type Remainder = RR },
        Frame
    ): T < (S & RR) =
        RuntimeEffect.handle(erasedTag[R], env, _.union(env))(value).asInstanceOf[T < (S & RR)]

    class UseOps[R >: Nothing](dummy: Unit) extends AnyVal:
        inline def apply[A, S](inline f: R => A < S)(
            using tag: Tag[R]
        ): A < (Env[R] & S) =
            def loop(): A < (Env[R] & S) =
                RuntimeEffect.suspend(erasedTag[R]) { map =>
                    val value = map.getOrElse(null)(using tag.erased).asInstanceOf[R]
                    if isNull(value) then loop()
                    else f(value)
                }
            end loop
            loop()
        end apply
    end UseOps

    inline def use[R >: Nothing]: UseOps[R] = UseOps(())

    sealed trait HasEnv[R, +RS]:
        type Remainder
    end HasEnv

    trait LowPriorityHasEnv:
        given hasEnv[R, RR]: HasEnv[R, R & RR] with
            type Remainder = Env[RR]

    object HasEnv extends LowPriorityHasEnv:
        given isEnv[R]: HasEnv[R, R] with
            type Remainder = Any
    end HasEnv

end Env

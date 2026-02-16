package kyo

import java.util.UUID
import kyo.internal.Inputs

/** Factory methods for HttpPath captures, defined outside the opaque type boundary. */
private[kyo] trait HttpPathFactory:

    private inline def capture[N <: String & Singleton, A](name: N, parse: String => A)(
        c: Inputs.Field[EmptyTuple, N, A]
    ): HttpPath[c.Out] =
        Inputs.addField[EmptyTuple, N, A](using c)
        HttpPath.mkCapture(name, parse).asInstanceOf[HttpPath[c.Out]]
    end capture

    inline def int[N <: String & Singleton](inline name: N)(using c: Inputs.Field[EmptyTuple, N, Int]): HttpPath[c.Out] =
        capture(name, _.toInt)(c)

    inline def long[N <: String & Singleton](inline name: N)(using c: Inputs.Field[EmptyTuple, N, Long]): HttpPath[c.Out] =
        capture(name, _.toLong)(c)

    inline def string[N <: String & Singleton](inline name: N)(using c: Inputs.Field[EmptyTuple, N, String]): HttpPath[c.Out] =
        capture(name, identity)(c)

    inline def uuid[N <: String & Singleton](inline name: N)(using c: Inputs.Field[EmptyTuple, N, UUID]): HttpPath[c.Out] =
        capture(name, UUID.fromString)(c)

    inline def boolean[N <: String & Singleton](inline name: N)(using c: Inputs.Field[EmptyTuple, N, Boolean]): HttpPath[c.Out] =
        capture(name, _.toBoolean)(c)

end HttpPathFactory

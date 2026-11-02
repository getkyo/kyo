package kyo

import scala.quoted.*

/** Test entry point for `Frame.fromPosition`: the frame of an argument expression's own position. */
object FramePositionMacro:

    inline def frameOf(inline value: Any): Frame = ${ impl('value) }

    private def impl(value: Expr[Any])(using Quotes): Expr[Frame] =
        import quotes.reflect.*
        Frame.fromPosition(value.asTerm.pos)
end FramePositionMacro

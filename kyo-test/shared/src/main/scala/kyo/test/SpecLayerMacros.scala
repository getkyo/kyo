package kyo.test

import scala.quoted.*
import kyo.*
import kyo.internal.macros.*
import kyo.internal.macros.LayerMacroUtils.*

object SpecLayerMacros:
    def provideImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[Layer[_, _]]])(using
        Quotes
    ): Expr[Spec[R0, E]] =
        val expr = LayerMacroUtils.constructLayer[R0, R, E](layer)
        '{ $spec.provideLayer($expr) }
    end provideImpl

    def provideSharedImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[Layer[_, _]]])(using
        Quotes
    ): Expr[Spec[R0, E]] =
        val expr = LayerMacroUtils.constructLayer[R0, R, E](layer)
        '{ $spec.provideLayerShared($expr) }
    end provideSharedImpl
end SpecLayerMacros

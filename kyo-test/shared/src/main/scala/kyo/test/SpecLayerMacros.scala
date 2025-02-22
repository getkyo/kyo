package zio.test

import scala.quoted.*
import zio.*
import zio.internal.macros.*
import zio.internal.macros.LayerMacroUtils.*

object SpecLayerMacros:
    def provideImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[ZLayer[_, E, _]]])(using
        Quotes
    ): Expr[Spec[R0, E]] =
        val expr = LayerMacros.constructLayer[R0, R, E](layer)
        '{ $spec.provideLayer($expr) }
    end provideImpl

    def provideSharedImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[ZLayer[_, E, _]]])(using
        Quotes
    ): Expr[Spec[R0, E]] =
        val expr = LayerMacros.constructLayer[R0, R, E](layer)
        '{ $spec.provideLayerShared($expr) }
    end provideSharedImpl
end SpecLayerMacros

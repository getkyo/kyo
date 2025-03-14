package kyo.test

import kyo.*
import scala.quoted.*

private[test] trait KyoSpecAbstractVersionSpecific:

    /** This implicit conversion macro will ensure that the provided Kyo Spec effect does not require more than the provided environment. It
      * assumes that the underlying effect type is defined as A < Env[R] & Abort[E].
      *
      * If it is missing requirements, it will report a descriptive error message. Otherwise, the effect will be returned unmodified.
      */
    implicit inline def validateEnv[R1, R, E, A](inline spec: Spec[R, E]): Spec[R1, E] =
        ${ KyoSpecAbstractSpecificMacros.validate[R1, R, E]('spec) }
end KyoSpecAbstractVersionSpecific

private[test] object KyoSpecAbstractSpecificMacros:
    def validate[Provided: Type, Required: Type, E: Type](spec: Expr[Spec[Required, E]])(using ctx: Quotes) =
        new KyoSpecAbstractSpecificMacros(ctx).validate[Provided, Required, E](spec)

private[test] class KyoSpecAbstractSpecificMacros(val ctx: Quotes):
    given Quotes = ctx
    import ctx.reflect.*

    def validate[Provided: Type, Required: Type, E: Type](spec: Expr[Spec[Required, E]]) =

        val required = flattenAnd(TypeRepr.of[Required])
        val provided = flattenAnd(TypeRepr.of[Provided])

        val missing =
            required.toSet -- provided.toSet

        if missing.nonEmpty then
            val message = TerminalRendering.missingLayersForZIOSpec(missing.map(_.show))
            report.errorAndAbort(message)

        spec.asInstanceOf[Expr[Spec[Provided, E]]]
    end validate

    def flattenAnd(typeRepr: TypeRepr): List[TypeRepr] =
        typeRepr.dealias match
            case AndType(left, right) =>
                flattenAnd(left) ++ flattenAnd(right)
            case _ =>
                List(typeRepr)
end KyoSpecAbstractSpecificMacros

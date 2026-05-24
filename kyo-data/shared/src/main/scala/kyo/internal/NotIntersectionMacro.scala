package kyo.internal

import scala.quoted.*

private[kyo] object NotIntersectionMacro:
    def derive[A: Type](using Quotes): Expr[NotIntersection[A]] =
        import quotes.reflect.*
        TypeRepr.of[A].dealiasKeepOpaques match
            case _: AndType =>
                report.errorAndAbort(
                    s"Intersection types are not supported here. Found: ${TypeRepr.of[A].show}"
                )
            case _ =>
                '{ NotIntersection.singleton.asInstanceOf[NotIntersection[A]] }
        end match
    end derive
end NotIntersectionMacro

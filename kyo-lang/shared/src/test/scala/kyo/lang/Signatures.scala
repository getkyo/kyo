package kyo.lang

import scala.quoted.*

/** Reads a compiled definition's result type from its symbol and checks type equivalence with an expectation.
  *
  * Type equivalence is stronger than assignment compatibility: the definition is never given an expected type, so no conversion or
  * widening can hide a difference. A pure `Int` and a pending `Int < Any` are therefore distinct, as are two rows that differ by one
  * effect.
  */
object Signatures:

    inline def exact[Expected](inline module: String, inline method: String): Unit =
        ${ check[Expected]('module, 'method) }

    private def check[Expected: Type](module: Expr[String], method: Expr[String])(using Quotes): Expr[Unit] =
        import quotes.reflect.*
        val owner  = Symbol.requiredModule(module.valueOrAbort)
        val name   = method.valueOrAbort
        val symbol = owner.methodMember(name) match
            case List(single) => single
            case _            => report.errorAndAbort(s"Expected one method: ${owner.fullName}.$name")
        def result(tpe: TypeRepr): TypeRepr = tpe match
            case method: MethodType => result(method.resType)
            case method: ByNameType => result(method.underlying)
            case other              => other
        val actual   = result(owner.termRef.memberType(symbol))
        val expected = TypeRepr.of[Expected]
        if !(actual =:= expected) then
            report.errorAndAbort(s"Signature mismatch for ${owner.fullName}.$name: ${actual.show}; expected ${expected.show}")
        '{ () }
    end check
end Signatures

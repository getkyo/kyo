package kyo.internal

import scala.quoted.*
import scala.util.hashing.MurmurHash3

/** Helper functions for codec/schema generation macros.
  *
  * Note: The Codec derivation logic has been removed. Schema now handles serialization directly. These are just utility functions used by
  * FocusMacro.
  */
object CodecMacro:

    /** Computes a stable field ID from a field name using MurmurHash3.
      *
      * The ID is a 21-bit positive integer (0 to 2,097,151), which fits within protobuf's field number range (1 to 536,870,911) while
      * providing good collision resistance for typical schemas.
      */
    def fieldId(name: String): Int =
        (MurmurHash3.stringHash(name) & 0x1fffff) + 1

    /** Creates Array[Array[Byte]] without ClassTag (avoids scala.Array.apply varargs allocation) */
    def mkFieldBytesPublic(exprs: List[Expr[Array[Byte]]])(using Quotes): Expr[Array[Array[Byte]]] =
        import quotes.reflect.*
        val n       = exprs.size
        val arrType = TypeRepr.of[Array[Array[Byte]]]
        val arrSym  = Symbol.newVal(Symbol.spliceOwner, "$fieldBytesArr", arrType, Flags.EmptyFlags, Symbol.noSymbol)
        val arrDef  = ValDef(arrSym, Some('{ new Array[Array[Byte]](${ Expr(n) }) }.asTerm))
        val arrRef  = Ref(arrSym)
        val sets = exprs.zipWithIndex.map { (e, i) =>
            Apply(Select.unique(arrRef, "update"), List(Literal(IntConstant(i)), e.asTerm))
        }
        Block(arrDef :: sets, arrRef).asExprOf[Array[Array[Byte]]]
    end mkFieldBytesPublic

    /** Extracts case class default values for use during deserialization. Returns a Map from field name to default value expression.
      */
    def caseClassDefaultsPublic(using Quotes)(tpe: quotes.reflect.TypeRepr): Map[String, Expr[Any]] =
        import quotes.reflect.*

        val sym        = tpe.typeSymbol
        val caseFields = sym.caseFields

        caseFields.zipWithIndex.flatMap { (field, idx) =>
            MacroUtils.getDefault(sym, idx).map(field.name -> _)
        }.toMap
    end caseClassDefaultsPublic

end CodecMacro

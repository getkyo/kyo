package kyo.internal

import kyo.Maybe
import kyo.SqlSchema
import scala.compiletime.constValue
import scala.compiletime.erasedValue
import scala.compiletime.error
import scala.compiletime.summonFrom
import scala.deriving.Mirror

/** The low-priority givens: the row-level nullability wrappers and the product/tuple derivation. They live below the [[SqlSchema]]
  * companion's own givens so an installed single-column codec (a [[SqlSchema.Column]]) always wins over the derivation at a row position,
  * and so `maybe`/`option` over a scalar inner never competes with the row-level wrappers.
  */
private[kyo] trait LowPrioritySqlSchema:

    /** A whole nullable embedded row: `Maybe` of a product that is itself a row. Gated on `Mirror.ProductOf` so it never competes with
      * [[SqlSchema.maybe]] for a scalar inner.
      */
    given maybeRow[P](using m: Mirror.ProductOf[P], inner: SqlSchema[P]): SqlSchema[Maybe[P]] =
        SqlSchema.maybeRowOf(inner)

    /** A whole nullable embedded row: `Option` of a product that is itself a row. */
    given optionRow[P](using m: Mirror.ProductOf[P], inner: SqlSchema[P]): SqlSchema[Option[P]] =
        SqlSchema.optionRowOf(inner)

    /** A case class or tuple is a row iff every field is a single column. The derivation assembles the row codec from the per-field
      * [[SqlSchema.Column]]s, so the columns that prove support are the columns that serialize, and no separate coherence check exists or
      * is needed. A field without `Column` evidence is a compile error naming it. Column names honor `@column`.
      */
    inline given derived[P](using m: Mirror.ProductOf[P]): SqlSchema[P] =
        SqlSchema.row[P](
            kyo.internal.SqlMacros.sqlFieldNames[P],
            columnsOf[m.MirroredElemLabels, m.MirroredElemTypes]
        )

    /** Walks the field tuple gathering each field's [[SqlSchema.Column]]; a field without one is a positioned compile error naming it. */
    private inline def columnsOf[Labels <: Tuple, Types <: Tuple]: List[SqlSchema.Column[?]] =
        inline erasedValue[Types] match
            case _: EmptyTuple => Nil
            case _: (t *: ts) =>
                inline erasedValue[Labels] match
                    case _: (l *: ls) =>
                        val head = summonFrom {
                            case c: SqlSchema.Column[`t`] => c
                            case _ =>
                                error(
                                    "Field '" + constValue[l & String] +
                                        "' is not a single-column SQL type. Supported column types are the SqlSchema base set, " +
                                        "Maybe/Option of them, and installed custom columns (Sql.jsonColumn, Sql.enumText, SqlSchema.of)."
                                )
                        }
                        head :: columnsOf[ls, ts]
    end columnsOf

end LowPrioritySqlSchema

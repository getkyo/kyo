package kyo.internal

import kyo.Maybe
import kyo.SqlSchema.Naming
import scala.quoted.*

/** Macro-side lifter for [[kyo.Naming]].
  *
  * Provides `FromExpr[Naming]` so that the static-SQL macros can recover a `Naming` value from an
  * `Expr[Naming]` when the strategy is one of the two built-in singletons (`identity` or `snakeCase`). User-defined strategies
  * (anonymous `new Naming { ... }` or custom objects) are not liftable and return `None`; the macro falls back to the runtime
  * renderer path.
  *
  * The derived `FromExpr[Maybe[Naming]]` is also provided so that `Schema.namingStrategy` (typed `Maybe[Naming]`) can be
  * lifted directly.
  */
object SqlSchemaNamingFromExpr:

    given FromExpr[Naming] with
        def unapply(x: Expr[Naming])(using Quotes): Option[Naming] =
            x match
                case '{ Naming.snakeCase } => Some(Naming.snakeCase)
                case '{ Naming.identity }  => Some(Naming.identity)
                case _                     => None
    end given

    given FromExpr[Maybe[Naming]] with
        def unapply(x: Expr[Maybe[Naming]])(using Quotes): Option[Maybe[Naming]] =
            x match
                case '{ Maybe.empty[Naming] }         => Some(Maybe.empty)
                case '{ Maybe.Absent }                => Some(Maybe.empty)
                case '{ Maybe(${ Expr(v) }: Naming) } => Some(Maybe(v))
                case _                                => None
    end given

end SqlSchemaNamingFromExpr

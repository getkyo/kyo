package kyo.internal

import kyo.Maybe
import kyo.NamingStrategy
import scala.quoted.*

/** Macro-side lifter for [[kyo.NamingStrategy]].
  *
  * Provides `FromExpr[NamingStrategy]` so that the Phase 3 static-SQL macros can recover a `NamingStrategy` value from an
  * `Expr[NamingStrategy]` when the strategy is one of the two built-in singletons (`identity` or `snakeCase`). User-defined strategies
  * (anonymous `new NamingStrategy { ... }` or custom objects) are not liftable and return `None`; the macro falls back to the runtime
  * renderer path.
  *
  * The derived `FromExpr[Maybe[NamingStrategy]]` is also provided so that `Schema.namingStrategy` (typed `Maybe[NamingStrategy]`) can be
  * lifted directly.
  */
object NamingStrategyFromExpr:

    given FromExpr[NamingStrategy] with
        def unapply(x: Expr[NamingStrategy])(using Quotes): Option[NamingStrategy] =
            x match
                case '{ NamingStrategy.snakeCase } => Some(NamingStrategy.snakeCase)
                case '{ NamingStrategy.identity }  => Some(NamingStrategy.identity)
                case _                             => None
    end given

    given FromExpr[Maybe[NamingStrategy]] with
        def unapply(x: Expr[Maybe[NamingStrategy]])(using Quotes): Option[Maybe[NamingStrategy]] =
            x match
                case '{ Maybe.empty[NamingStrategy] }         => Some(Maybe.empty)
                case '{ Maybe.Absent }                        => Some(Maybe.empty)
                case '{ Maybe(${ Expr(v) }: NamingStrategy) } => Some(Maybe(v))
                case _                                        => None
    end given

end NamingStrategyFromExpr

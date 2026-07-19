package kyo.internal

import kyo.*
import scala.quoted.*

/** Macro backing [[kyo.EventLog.RouteCoverage]]'s low-priority union derivation. For a bare
  * union `A = M1 | M2 | ...` (which has no `scala.deriving.Mirror.SumOf`), decomposes `A`'s
  * `OrType` into its leaf `TypeRepr`s ONCE and emits both facets from that single
  * decomposition: the coverage check (for each leaf `Mi`, the accumulated `Covered`
  * intersection is a subtype of `Mi`, an intersection being a subtype of exactly its
  * components, so a routed leaf satisfies `Covered <:< Mi`; a compile error names the first
  * unrouted leaf) and the runtime dispatch matcher (`value.isInstanceOf[Mi]` selects the leaf
  * and yields its `Schema[Mi].structure.name`, the exact key [[kyo.EventLog.Builder.define]]
  * stores that member under; `isInstanceOf` is cross-platform, and routing by structure name
  * requires distinct member structure names, so two leaves that erase to one structure name are
  * not disambiguated here and are caught at `build` from `Event.Routes.duplicates`). Kept in a
  * SEPARATE file from [[kyo.EventLog]]: a Scala 3 macro cannot be called from the same source file
  * that defines it, mirroring [[kyo.internal.EventInterpolatorMacros]].
  */
private[kyo] object RouteCoverageMacros:

    def unionCoverageImpl[A: Type, Covered: Type](using Quotes): Expr[EventLog.RouteCoverage[A, Covered]] =
        import quotes.reflect.*
        val coveredRepr = TypeRepr.of[Covered]

        def leaves(repr: TypeRepr): List[TypeRepr] =
            repr.dealias match
                case OrType(left, right) => leaves(left) ++ leaves(right)
                case leaf                => List(leaf)

        val leafReprs = leaves(TypeRepr.of[A])

        val unrouted = leafReprs.filterNot(leaf => coveredRepr <:< leaf)
        if unrouted.nonEmpty then
            report.errorAndAbort(
                s"EventLog.builder is missing a .define for union member ${unrouted.head.show}; every member of the domain type must be routed before build"
            )
        end if

        def matchBody(value: Expr[A]): Expr[String] =
            leafReprs.foldRight[Expr[String]](
                '{ throw new IllegalStateException("unreachable: the union decomposition enumerates every member of the domain type") }
            ) { (leaf, rest) =>
                leaf.asType match
                    case '[m] =>
                        val schemaExpr = Expr.summon[Schema[m]].getOrElse(
                            report.errorAndAbort(s"no Schema instance for union member ${leaf.show}")
                        )
                        '{ if $value.isInstanceOf[m] then $schemaExpr.structure.name else $rest }
            }

        val matcher = '{ (value: A) => ${ matchBody('value) } }
        '{ EventLog.RouteCoverage.unchecked[A, Covered]($matcher) }
    end unionCoverageImpl

end RouteCoverageMacros

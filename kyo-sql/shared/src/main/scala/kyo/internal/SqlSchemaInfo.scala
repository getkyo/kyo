package kyo.internal

import kyo.Maybe
import kyo.NamingStrategy
import kyo.SqlSchema
import scala.quoted.*

/** Macro-side extractor that recovers the override info from a `SqlSchema[T]` construction expression at macro expansion time.
  *
  * # Why this exists
  *
  * Macros like [[SqlMacros.tableNameImpl]] and [[SqlMacros.columnNamesImpl]] want to constant-fold the resolved SQL names so the static-SQL
  * macro can lift them via `FromExpr.derived`. Lifting the whole `SqlSchema[T]` instance is not possible because the underlying `Schema`
  * case class carries function-typed fields (writeFn, readFn, etc.) that have no `FromExpr` instance. The workaround is to pattern-match on
  * the schema's construction expression and extract just the three pieces of override info that affect SQL name resolution.
  *
  * # `inline given` is required for static folding
  *
  * For [[extract]] to succeed, the user must declare the schema with `inline given`:
  *
  * {{{
  * case class User(id: Long, firstName: String)
  * object User:
  *     inline given SqlSchema[User] = SqlSchema.derived[User].withNaming(NamingStrategy.snakeCase)
  * }}}
  *
  * With `inline given`, `Expr.summon[SqlSchema[T]]` returns the inlined construction expression (the transforms expand into a chain of
  * `SqlSchema.applyNamingStrategy` / `applyTableNameOverride` / `applyRenamedField` calls), which [[extract]] can pattern-match
  * recursively.
  *
  * With plain `given SqlSchema[T] = ...`, the Scala 3 macro API exposes only a reference to a `final lazy val given_SqlSchema_T:
  * SqlSchema[T]` — the construction RHS is not visible (`sym.tree.rhs` returns `None`). In that case [[extract]] returns `None`, and the
  * caller emits runtime [[SqlNameResolver]] calls. The dynamic execution paths (`.run` opportunistic / `.runDynamic`) work correctly; only
  * `.runStatic` fails to fold, and the static-SQL macro should report a positioned error pointing the user at `inline given`.
  */
object SqlSchemaInfo:

    /** The three pieces of info needed to resolve SQL column and table names. */
    final case class Info(
        tableNameOverride: Maybe[String],
        namingStrategy: Maybe[NamingStrategy],
        renamedFields: List[(String, String)]
    )

    /** The empty info: no overrides set. Equivalent to a bare `SqlSchema.derived[T]`. */
    val empty: Info = Info(Maybe.empty, Maybe.empty, Nil)

    /** Attempts to extract [[Info]] from a `SqlSchema[T]` expression. Returns `None` when the construction is not statically recognisable
      * (for example, when the user declared the schema as plain `given` rather than `inline given`, so the construction is hidden behind a
      * lazy-val reference).
      */
    def extract[T: Type](e: Expr[SqlSchema[T]])(using Quotes): Option[Info] =
        import quotes.reflect.*

        given FromExpr[NamingStrategy] = NamingStrategyFromExpr.given_FromExpr_NamingStrategy

        /** True if the expression is a reference (`Ident` or `Select`) to a non-`inline` `val` / `lazy val` / `def` definition. A plain
          * (non-inline) `given` is summoned as such a reference — the macro sees the symbol but not the construction body. Returning early
          * here forces the caller to emit runtime code rather than risk silently producing wrong SQL for an override-bearing schema whose
          * body is invisible. `.runStatic` later reports a positioned error pointing the user at `inline given`; `.run` falls back
          * transparently to the runtime renderer.
          *
          * An `inline given` is summoned as a reference to an `inline`-flagged definition, but the body IS available via the inliner — the
          * macro's downstream pattern matching can see the construction once Scala 3 inlines it. So inline-flagged symbols are NOT treated
          * as opaque here.
          */
        def isOpaqueReference(expr: Expr[SqlSchema[T]]): Boolean =
            val term = expr.asTerm
            term match
                case _: Ident | _: Select =>
                    val sym = term.symbol
                    (sym.isValDef || sym.isDefDef) && !sym.flags.is(Flags.Inline)
                case _ => false
            end match
        end isOpaqueReference

        /** True if the expression's source mentions any of the override-applying methods. The string check is a fast pre-filter — when no
          * override method appears anywhere in the expression, the schema is plain and we can return [[empty]] without further work.
          */
        def hasOverrides(expr: Expr[SqlSchema[T]]): Boolean =
            val source = expr.asTerm.show
            source.contains("applyNamingStrategy") ||
            source.contains("applyTableNameOverride") ||
            source.contains("applyRenamedField")
        end hasOverrides

        def loop(expr: Expr[SqlSchema[T]]): Option[Info] =
            if isOpaqueReference(expr) then None
            else if !hasOverrides(expr) then Some(empty)
            else
                expr match
                    case '{ SqlSchema.applyNamingStrategy[T]($base, ${ Expr(strategy) }) } =>
                        loop(base).map(_.copy(namingStrategy = Maybe(strategy)))
                    case '{ SqlSchema.applyTableNameOverride[T]($base, ${ Expr(name) }) } =>
                        loop(base).map(_.copy(tableNameOverride = Maybe(name)))
                    case '{ SqlSchema.applyRenamedField[T]($base, ${ Expr(from) }, ${ Expr(to) }) } =>
                        loop(base).map(info => info.copy(renamedFields = info.renamedFields :+ (from, to)))
                    case _ => None

        loop(e)
    end extract

end SqlSchemaInfo

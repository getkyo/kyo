package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import scala.quoted.*

/** Test-only wrapper around [[kyo.internal.SqlStaticMacro]].
  *
  * Exposes the compile-time render pipeline as a value so parity tests can assert that the compile-time renderer (used by `.run` /
  * `.runStatic`) produces byte-identical SQL to the runtime renderer (used by `ast.render(dialect)` and `.runDynamic`). Any divergence would
  * surface as `.run` and `.runDynamic` returning different execution results on the same query.
  *
  * Lives in test sources only: the compile-time SQL emitter is intentionally NOT a user-facing API, and a probe kept next to the parity
  * tests is a coverage tool rather than a shipped surface. Mirrors the same-file inline-def-plus-macro-impl separation pattern that
  * [[SqlLiftHarness]] uses to bridge test callers to a main-source macro.
  */
object SqlStaticProbe:

    /** Renders `q` at compile time for every dialect on the compile classpath and returns the resulting [[Sql.Rendered]].
      *
      * `q` must be a fully-inline expression, `SqlStaticMacro` cannot lift a `val` reference. Callers should write the query directly as the
      * argument.
      */
    inline def render(inline q: Sql.Executable[?]): Sql.Rendered = ${ renderImpl('q) }

    private def renderImpl(q: Expr[Sql.Executable[?]])(using Quotes): Expr[Sql.Rendered] =
        kyo.internal.SqlStaticMacro.impl(q)

    /** Renders `q` the way `.run` does: a dialect that cannot express the statement is left out rather than failing the compile.
      *
      * Needed as soon as a construct is not renderable by EVERY dialect on the classpath. [[render]] is `.runStatic`'s behaviour, where one
      * dialect refusing is the whole render refusing. That is right for a caller demanding a static rendering and wrong for a test asserting
      * what each dialect does with a construct only some of them have.
      *
      * Answers [[Absent]] when nothing could be rendered at all, which is also what `.run` falls back from.
      */
    inline def renderOpportunistic(inline q: Sql.Executable[?]): Maybe[Sql.Rendered] = ${ renderOpportunisticImpl('q) }

    private def renderOpportunisticImpl(q: Expr[Sql.Executable[?]])(using Quotes): Expr[Maybe[Sql.Rendered]] =
        kyo.internal.SqlStaticMacro.tryImpl(q) match
            case Present(rendered) => '{ Maybe($rendered) }
            case Absent            => '{ Maybe.empty[Sql.Rendered] }

end SqlStaticProbe

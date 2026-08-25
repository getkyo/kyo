package kyo.internal.macros

import kyo.*
import kyo.Sql.*
import scala.annotation.tailrec
import scala.quoted.*

/** Custom `FromExpr` instances for the three AST nodes that carry a bind value, keeping the value as a call-site expression instead of
  * lifting it.
  *
  * The static-SQL macro lifts the inlined AST tree with `FromExpr.derived`, renders it while compiling, and splices the resulting SQL as a
  * constant. A bind value cannot make that trip. `Literal(minAge, schema)` holds whatever `minAge` evaluates to at run time, and the generic
  * product walker would try to reconstruct that value at splice time, which only succeeds for a compile-time constant. The consequence would
  * be that a query with any runtime bind does not fold at all, which is the opposite of what a bind is for: the SQL text does not depend on the
  * value, only the parameter does.
  *
  * So the value position travels as a [[BindHole]] holding the call site's own `Expr`. The renderer reads a bind's SCHEMA (for the column
  * count and the placeholder) and never its value, so the render proceeds normally with a hole sitting where the value would be, and
  * `SqlStaticMacro.liftParams` splices the held expressions into the emitted params chunk. The value is therefore evaluated exactly where
  * the user wrote it, at run time, and never reconstructed.
  *
  * The column-codec argument is NOT resolved to an instance: the compile-time render never reads a bind schema's behavior
  * (`Idiom.Ctx.appendBind` emits exactly one placeholder per bind, an invariant of the `SqlSchema.Column` type), and the runtime
  * statement binds through the spliced original schema expression. The lifted node carries [[renderStandIn]] instead, which is what
  * lets a bind fold when its schema given has no loadable class at expansion time, above all a `derives SqlSchema.Column` enum or a
  * custom column codec defined in the very module being compiled.
  *
  * Answering `None` fails closed: `.run` falls back to the runtime renderer, `.runStatic` reports a compile error naming the call site.
  *
  * Summon reachability: these givens are NOT in any companion's implicit scope, and `FromExprDerived` only consults the implicit scope for
  * the types in its interception set (`sqlCustomLiftTypeSyms`), which names all three of these. Call sites MUST import them via
  * `import kyo.internal.macros.BindFromExpr.given`.
  */
object BindFromExpr:

    /** Splice-time stand-in for a bind value inside a lifted AST.
      *
      * Occupies the `value` field of a lifted `Literal` / `Fragment.Bind` / `BoundValue`, whose declared type it does not satisfy. That is
      * sound only because no render path reads a bind's value. It reads the schema's width to reject multi-column binds, and it appends the
      * value to the params buffer without inspecting it. A node that DID branch on a bind value would render differently at compile time
      * than at run time, which is why absence testing is [[kyo.Sql.IsAbsent]] rather than a comparison against an `Absent` literal.
      *
      * Never escapes a macro expansion: `liftParams` consumes every hole in the rendered params, and a hole reaching any other consumer is
      * an internal error that aborts the compile rather than emitting a wrong constant.
      */
    final private[kyo] class BindHole(val valueExpr: Expr[Any], val schemaExpr: Expr[Any])

    /** `FromExpr[Literal[?]]`, the bind a predicate carries (`col >= minAge`, `Sql.literal(v)`). */
    given fromExprLiteral[A]: scala.quoted.FromExpr[Literal[A]] with
        def unapply(x: Expr[Literal[A]])(using q: Quotes): Option[Literal[A]] =
            // `Literal` is phantom in `A` at runtime; the hole, the resolved schema, and the type name are the whole value.
            MacroSupport.narrowOption(new Walk[q.type].bindCarrier(x, "Literal").map { (hole, schema, typeName) =>
                Literal[Any](MacroSupport.narrowPhantom[Any](hole), schema, typeName)
            })
    end fromExprLiteral

    /** `FromExpr[Fragment.Bind[?]]`, an interpolated argument of an `sql"..."` fragment. */
    given fromExprFragmentBind[A]: scala.quoted.FromExpr[Fragment.Bind[A]] with
        def unapply(x: Expr[Fragment.Bind[A]])(using q: Quotes): Option[Fragment.Bind[A]] =
            MacroSupport.narrowOption(new Walk[q.type].bindCarrier(x, "Bind").map { (hole, schema, typeName) =>
                Fragment.Bind[Any](MacroSupport.narrowPhantom[Any](hole), schema, typeName)
            })
    end fromExprFragmentBind

    /** `FromExpr[Sql.BoundValue[?]]`, one cell of an INSERT row built by `SqlMacros.rowValues`. */
    given fromExprBoundValue[A]: scala.quoted.FromExpr[Sql.BoundValue[A]] with
        def unapply(x: Expr[Sql.BoundValue[A]])(using q: Quotes): Option[Sql.BoundValue[A]] =
            MacroSupport.narrowOption(new Walk[q.type].bindCarrier(x, "BoundValue").map { (hole, schema, typeName) =>
                Sql.BoundValue[Any](MacroSupport.narrowPhantom[Any](hole), schema, typeName)
            })
    end fromExprBoundValue

    /** Render-time stand-in occupying the schema field of a lifted bind carrier.
      *
      * Sound because the instance is never read: the compile-time render appends the bind and a placeholder without touching the
      * schema, the cross-dialect bind-agreement check compares positions and holes, and `SqlStaticMacro.liftParams` splices the
      * call site's own schema expression into the emitted params, so this value never escapes an expansion. The write and read arms
      * say so loudly if that ever changes.
      */
    final private[kyo] val renderStandIn: SqlSchema.Column[Any] =
        SqlSchema.of[Any](
            write = (_, _) => throw new AssertionError("BindFromExpr.renderStandIn: compile-time stand-in reached a runtime write"),
            read = _ => throw new AssertionError("BindFromExpr.renderStandIn: compile-time stand-in reached a runtime read")
        )

    /** Per-invocation walker, parameterised on the singleton `Quotes` so `q.reflect.Term` is well-formed in every signature. One instance per
      * `unapply` call, so concurrent macro expansion is safe. Mirrors `ColumnFromExpr.Walk`.
      */
    private class Walk[Q <: Quotes & Singleton](using val q: Q):
        import q.reflect.*
        given CanEqual[String, String] = CanEqual.derived

        /** Matches a three-argument constructor apply whose head is `name`, and answers the value hole, the resolved schema, and the lifted
          * type name.
          *
          * All three carriers are emitted as plain three-argument applies: `Literal(value, s, typeName)` by `Sql.lit` and by every raw-value
          * comparison overload, `Fragment.Bind[t](arg, schema, typeName)` by `SqlFragmentMacro`, `BoundValue[ft](field, schema, typeName)`
          * by `SqlMacros.rowValuesImpl`. The three `Apply` spellings below are the same set `ColumnFromExpr.Walk.cast` handles.
          */
        def bindCarrier(x: Expr[?], name: String): Option[(BindHole, SqlSchema.Column[Any], String)] =
            // Runs on the binding-resolved tree: inline expansion leaves constructor arguments behind block-local vals,
            // and the hole must hold the argument's own expression rather than a reference the splice site cannot see.
            unwrap(kyo.internal.FromExprDerived.resolveBindings(x.asTerm)) match
                case Apply(TypeApply(Select(qual, "apply"), _), List(valueE, schemaE, typeNameE)) if heads(qual, name) =>
                    build(valueE, schemaE, typeNameE)
                case Apply(Select(qual, "apply"), List(valueE, schemaE, typeNameE)) if heads(qual, name) =>
                    build(valueE, schemaE, typeNameE)
                case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(valueE, schemaE, typeNameE)) if headsTpt(tpt, name) =>
                    build(valueE, schemaE, typeNameE)
                case _ => None

        private def heads(qual: Term, name: String): Boolean =
            qual.symbol.name == name || qual.symbol.name == s"$name$$"

        private def headsTpt(tpt: Tree, name: String): Boolean =
            tpt.symbol.name == name || tpt.symbol.name == s"$name$$"

        /** Keeps the value term as a hole, stands in for the schema instance, and reads the lifted type-name literal. `None` only when
          * the type name is not a literal, which no carrier emits.
          */
        private def build(valueE: Term, schemaE: Term, typeNameE: Term): Option[(BindHole, SqlSchema.Column[Any], String)] =
            for
                typeName <- strLit(typeNameE)
            yield (BindHole(valueE.asExpr, unwrap(schemaE).asExpr), renderStandIn, typeName)

        private def strLit(t: Term): Option[String] =
            unwrap(t) match
                case Literal(StringConstant(s)) => Some(s)
                case _                          => None

        /** `resolveBindings` has already substituted every `val` binding, so any `Inlined` / `Block` statement list is dead; strip the
          * wrapper regardless of its now-unused bindings.
          *
          * The `substituteCo` / `substituteContra` arm is the same one [[ColumnFromExpr]] and [[RecordFromExpr]] carry, and it is not
          * optional here: `Sql` applies `ev.substituteCo` at ten sites to retype a term through a type equality, and a bind carrier under
          * one of them arrived wrapped in that evidence application. Without the arm the wrapper is the term, no `BindHole` is built, and
          * the whole query silently loses its compile-time fold.
          */
        @tailrec
        private def unwrap(t: Term): Term =
            t match
                case Inlined(_, _, inner) => unwrap(inner)
                case Block(_, inner)      => unwrap(inner)
                case Typed(inner, _)      => unwrap(inner)
                case TypeApply(Select(inner, "asInstanceOf" | "$asInstanceOf$"), _) =>
                    unwrap(inner)
                case Apply(TypeApply(Select(_, "substituteCo" | "substituteContra"), _), List(i)) =>
                    unwrap(i)
                case other => other
    end Walk

end BindFromExpr

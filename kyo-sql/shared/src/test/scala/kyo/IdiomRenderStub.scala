package kyo

import kyo.db.Idiom

/** An [[kyo.db.Idiom]] that exists only to render, so core can test the renderer without a backend.
  *
  * [[kyo.db.Idiom]] is flavor-blind by construction: it walks the AST and asks the dialect at every point two SQL flavors disagree. Testing
  * that walk needs some dialect, and reaching for a real one would tie a core test to a backend, which is exactly the coupling the module
  * split removes. This is the alternative: a dialect owned by the core test scope, diverging from the baseline only where a test needs a
  * divergence to pin.
  *
  * Its syntax is deliberately unlike either shipped flavor, `[ident]` for identifiers and `?1` for placeholders, so a string rendered through
  * it can never be mistaken for real Postgres or MySQL output. A test that cares which text a server accepts belongs beside that server's
  * dialect; a test that cares about bind order, placeholder numbering, or the shape of the walk belongs here.
  */
object IdiomRenderStub extends Idiom:

    val id: Idiom.Id = Idiom.Id("stub")

    /** Every construct is available at every version of a flavor that has no releases, so the floor is the lowest triple. */
    val capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(1, 0, 0)

    def quoteIdent(ident: String): String = s"[${ident.replace("]", "]]")}]"

    def placeholder(position: Int): String = s"?$position"

    override def supportsReturning: Boolean = true

    /** Names each portable type after its own case, which is enough to tell one cast target from another without claiming a real flavor's
      * spelling. `Array` nests, and an extension type belongs to a backend this dialect is not, so it has no name here.
      */
    override def castTypeName(target: SqlType.Type): Maybe[String] = target match
        case SqlType.Type.Array(element) => castTypeName(element).map(name => s"$name[]")
        case SqlType.Type.Extension(_)   => Absent
        case SqlType.Type.Numeric(_, _)  => Present("NUMERIC")
        case other                       => Present(other.toString.toUpperCase)

    /** The one integral quotient this stub spells apart from the baseline: a cast to NUMERIC before dividing. The truncated form and the four
      * shared operators keep the baseline spelling.
      */
    override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        ar.op match
            case Sql.Arithmetic.Op.DivideIntegral =>
                ctx.append("(CAST(")
                term(ctx, ar.left)
                ctx.append(" AS NUMERIC) / ")
                term(ctx, ar.right)
                ctx.append(")")
            case _ => super.arithmetic(ctx, ar)

    /** Concatenates through a function call, the shape a flavor with no `||` operator produces. */
    override def concat(ctx: Idiom.Ctx, cn: Sql.Concat): Unit =
        ctx.append("CONCAT(")
        ctx.joinWith(", ")(cn.parts)(p => term(ctx, p))
        ctx.append(")")
    end concat

    /** A dedicated case-insensitive operator, the shape a flavor with one produces. */
    override def caseInsensitiveLike(ctx: Idiom.Ctx, expr: Sql.Term[String], pattern: Sql.Term[String], negated: Boolean): Unit =
        binary(ctx, expr, if negated then "NOT ILIKE" else "ILIKE", pattern)

    override def onConflictDoNothing(ctx: Idiom.Ctx, doNothing: Sql.Insert.OnConflict.DoNothing[?]): Unit =
        ctx.append(" ON CONFLICT DO NOTHING")

    override def onConflictDoUpdate(ctx: Idiom.Ctx, doUpdate: Sql.Insert.OnConflict.DoUpdate[?]): Unit =
        ctx.append(" ON CONFLICT DO UPDATE SET ")
        ctx.joinWith(", ")(doUpdate.sets)(spec => assignment(ctx, spec))
    end onConflictDoUpdate

end IdiomRenderStub

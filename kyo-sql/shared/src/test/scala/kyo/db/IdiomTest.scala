package kyo.db

import kyo.*
import scala.compiletime.testing.typeCheckErrors

/** A two-column row, for the leaf that pins what a bind position refuses. Declared at the top level so a `typeCheckFailure` snippet can
  * name it.
  */
case class IdiomTestAmount(value: Long, currency: String) derives SqlSchema

/** Pins the [[kyo.db.Idiom]] contract as its scaladoc states it: the vocabulary ([[Idiom.Id]], [[Idiom.ServerVersion]]), the capability
  * defaults and derived gates, the per-render state ([[Idiom.Ctx]]), the [[Idiom.render]] envelope, and the promises the emitting methods
  * make about statement text, binds, version gating, and typed failure.
  *
  * Every scenario renders through a stub dialect owned by this suite, whose identifier quoting (double quotes) and placeholder spelling
  * (`$n`) are the suite's own choices: the four abstract members are the test's inputs, and everything asserted beyond them is what the
  * baseline promises on top. Exact text is asserted only where the contract pins the spelling; where it pins structure (keyword presence,
  * ordering, gating, bind positions) the assertion is structural.
  */
class IdiomTest extends Test:

    // Bounded so a body that blocks rather than raises still terminates the run.
    override protected def timeout: Duration = 30.seconds

    // Sql.BoundValue's existential value field cannot satisfy CanEqual derivation, so bind values are compared
    // after widening to Any.
    given CanEqual[Any, Any] = CanEqual.derived

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class Pair(x: Int, y: String) derives SqlSchema

    /** The suite's spelling for a quoted identifier: double quotes, embedded quotes doubled. */
    private def q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

    /** An alias-qualified quoted column, the shape the projection and predicates emit. */
    private def qc(alias: String, column: String): String = q(alias) + "." + q(column)

    private def v(major: Int, minor: Int, patch: Int): Idiom.ServerVersion = Idiom.ServerVersion(major, minor, patch)

    /** Minimal dialect: only the four abstract members, so every other behavior asserted is the baseline's. */
    private class StubIdiom extends Idiom:
        def id: Idiom.Id                         = Idiom.Id("stub")
        def capabilityFloor: Idiom.ServerVersion = Idiom.ServerVersion(1, 0, 0)
        def quoteIdent(ident: String): String    = "\"" + ident.replace("\"", "\"\"") + "\""
        def placeholder(position: Int): String   = "$" + position
    end StubIdiom

    /** Every version-gated feature carries its own distinct floor, so a gate answering from the wrong floor is visible. */
    private class GatedIdiom extends StubIdiom:
        override def lateralSince: Maybe[Idiom.ServerVersion]           = Present(Idiom.ServerVersion(2, 0, 0))
        override def recursiveCteSince: Maybe[Idiom.ServerVersion]      = Present(Idiom.ServerVersion(3, 0, 0))
        override def intersectExceptSince: Maybe[Idiom.ServerVersion]   = Present(Idiom.ServerVersion(4, 0, 0))
        override def valuesConstructorSince: Maybe[Idiom.ServerVersion] = Present(Idiom.ServerVersion(5, 0, 0))
    end GatedIdiom

    private class ReturningIdiom extends StubIdiom:
        override def supportsReturning: Boolean = true

    private class NoGroupingSetsIdiom extends StubIdiom:
        override def supportsGroupingSets: Boolean = false

    private class CastNamingIdiom extends StubIdiom:
        override def castTypeName(target: SqlType.Type): Maybe[String] = Present("MYTYPE")

    private class NoCastIdiom extends StubIdiom:
        override def castTypeName(target: SqlType.Type): Maybe[String] = Absent

    /** The interception shape the class scaladoc documents: one arm treated specially, the rest delegated through super. */
    private class ModFunctionIdiom extends StubIdiom:
        override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit = ar.op match
            case Sql.Arithmetic.Op.Mod =>
                ctx.append("MOD(")
                term(ctx, ar.left)
                ctx.append(", ")
                term(ctx, ar.right)
                ctx.append(")")
            case _ => super.arithmetic(ctx, ar)
    end ModFunctionIdiom

    /** The same interception with the fallback line omitted, which the contract says fails with a MatchError at render time. */
    private class ModOnlyIdiom extends StubIdiom:
        override def arithmetic(ctx: Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit = (ar.op: @unchecked) match
            case Sql.Arithmetic.Op.Mod =>
                ctx.append("MOD(")
                term(ctx, ar.left)
                ctx.append(", ")
                term(ctx, ar.right)
                ctx.append(")")
    end ModOnlyIdiom

    /** Replaces pagination wholesale, to observe that every path reaches the override through `this`. */
    private class PaginatedIdiom extends StubIdiom:
        override def limit(ctx: Idiom.Ctx, l: Sql.Limit[?]): Unit =
            query(ctx, l.sql)
            ctx.append(" PAGINATED")
    end PaginatedIdiom

    private val stub           = new StubIdiom
    private val gated          = new GatedIdiom
    private val returningIdiom = new ReturningIdiom
    private val noGroupingSets = new NoGroupingSetsIdiom
    private val castNaming     = new CastNamingIdiom
    private val noCast         = new NoCastIdiom
    private val modFn          = new ModFunctionIdiom
    private val modOnly        = new ModOnlyIdiom
    private val paginated      = new PaginatedIdiom

    private def rendered(idiom: Idiom, ast: Sql[?], version: Maybe[Idiom.ServerVersion] = Absent)(using Frame): Sql.Rendered =
        idiom.render(ast, version, summon[Frame])

    private def sqlText(idiom: Idiom, ast: Sql[?], version: Maybe[Idiom.ServerVersion] = Absent)(using Frame): String =
        rendered(idiom, ast, version).onlySql.get

    private def mkCtx(idiom: Idiom, version: Maybe[Idiom.ServerVersion] = Absent)(using Frame): Idiom.Ctx =
        new Idiom.Ctx(idiom, version, summon[Frame])

    // --- Idiom.Id ---

    "an id wraps its raw name and exposes it back" in {
        assert(Idiom.Id("postgres").value == "postgres")
        assert(Idiom.Id("").value == "")
    }

    "id equality follows the wrapped string" in {
        assert(Idiom.Id("postgres") == Idiom.Id("postgres"))
        assert(Idiom.Id("postgres") != Idiom.Id("mysql"))
    }

    // --- Idiom.ServerVersion ---

    "a server version packs and reads back its triple" in {
        val version = v(15, 6, 2)
        assert(version.major == 15)
        assert(version.minor == 6)
        assert(version.patch == 2)
        assert(version.show == "15.6.2")
    }

    "server version equality follows the triple" in {
        assert(v(8, 0, 31) == v(8, 0, 31))
        assert(v(8, 0, 31) != v(8, 0, 30))
        assert(v(8, 0, 31) != v(8, 31, 0))
        assert(v(8, 0, 0) != v(0, 0, 8))
    }

    "server version ordering is lexicographic over the triple" in {
        val ord = summon[Ordering[Idiom.ServerVersion]]
        assert(ord.compare(v(8, 0, 30), v(8, 0, 31)) < 0)
        assert(ord.compare(v(8, 0, 31), v(8, 1, 0)) < 0)
        assert(ord.compare(v(8, 4, 0), v(9, 0, 0)) < 0)
        assert(ord.compare(v(9, 0, 0), v(8, 4, 0)) > 0)
        assert(ord.compare(v(8, 0, 31), v(8, 0, 31)) == 0)
    }

    "a larger patch never outranks a minor or major bump" in {
        assert(v(8, 0, 9999).below(v(8, 1, 0)))
        assert(v(0, 9999, 9999).below(v(1, 0, 0)))
    }

    "atLeast is inclusive at the gate and below is strict" in {
        val gate = v(8, 0, 31)
        assert(v(8, 0, 31).atLeast(gate))
        assert(v(8, 0, 32).atLeast(gate))
        assert(v(9, 0, 0).atLeast(gate))
        assert(!v(8, 0, 30).atLeast(gate))
        assert(!v(8, 0, 31).below(gate))
        assert(v(8, 0, 30).below(gate))
        assert(!v(9, 0, 0).below(gate))
    }

    "parse takes the leading version run and stops at build detail" in {
        assert(Idiom.ServerVersion.parse("8.0.34-log") == Present(v(8, 0, 34)))
        assert(Idiom.ServerVersion.parse("15.6 (Debian 15.6-1.pgdg120+2)") == Present(v(15, 6, 0)))
        assert(Idiom.ServerVersion.parse("17beta1") == Present(v(17, 0, 0)))
    }

    "parse reads an omitted component as zero" in {
        assert(Idiom.ServerVersion.parse("16") == Present(v(16, 0, 0)))
        assert(Idiom.ServerVersion.parse("8.0") == Present(v(8, 0, 0)))
    }

    "parse answers Absent for a string with no leading version" in {
        assert(Idiom.ServerVersion.parse("beta").isEmpty)
        assert(Idiom.ServerVersion.parse("").isEmpty)
        assert(Idiom.ServerVersion.parse("v16").isEmpty)
    }

    // --- capability defaults and derived gates ---

    "supportsReturning defaults to the standard's honest false" in {
        assert(!stub.supportsReturning)
    }

    "supportsGroupingSets defaults to true" in {
        assert(stub.supportsGroupingSets)
    }

    "the four capability floors default to Absent" in {
        assert(stub.lateralSince.isEmpty)
        assert(stub.recursiveCteSince.isEmpty)
        assert(stub.intersectExceptSince.isEmpty)
        assert(stub.valuesConstructorSince.isEmpty)
    }

    "a derived gate with no floor claims every version" in {
        assert(stub.supportsLateral(v(0, 0, 1)))
        assert(stub.supportsRecursiveCte(v(0, 0, 1)))
        assert(stub.supportsIntersectExcept(v(0, 0, 1)))
        assert(stub.supportsValuesConstructor(v(0, 0, 1)))
    }

    "each derived gate reads its own floor and is inclusive at it" in {
        assert(gated.supportsLateral(v(2, 0, 0)))
        assert(!gated.supportsLateral(v(1, 9, 9)))
        assert(gated.supportsRecursiveCte(v(3, 0, 0)))
        assert(!gated.supportsRecursiveCte(v(2, 9, 9)))
        assert(gated.supportsIntersectExcept(v(4, 0, 0)))
        assert(!gated.supportsIntersectExcept(v(3, 9, 9)))
        assert(gated.supportsValuesConstructor(v(5, 0, 0)))
        assert(!gated.supportsValuesConstructor(v(4, 9, 9)))
        // One version, four answers: each gate reads its own floor, not a shared one.
        assert(gated.supportsLateral(v(3, 5, 0)))
        assert(gated.supportsRecursiveCte(v(3, 5, 0)))
        assert(!gated.supportsIntersectExcept(v(3, 5, 0)))
        assert(!gated.supportsValuesConstructor(v(3, 5, 0)))
    }

    "insertKeyword answers INSERT INTO with the trailing space whatever the resolution" in {
        assert(stub.insertKeyword(Absent) == "INSERT INTO ")
        assert(stub.insertKeyword(Present(Sql.Insert.OnConflict.DoNothing[Any](Chunk.empty))) == "INSERT INTO ")
    }

    "castTypeName passes an extension type's declared name through unchanged" in {
        assert(stub.castTypeName(SqlType.Type.Extension("hstore")) == Present("hstore"))
        assert(stub.castTypeName(SqlType.Type.Extension("citext")) == Present("citext"))
    }

    // The exact standard spellings are not pinned by the contract, but Absent means "cannot cast to that type at
    // all", so the default answering Absent for a type the standard spells would be a contract violation.
    "castTypeName answers a spelling for the standard portable types" in {
        val types = Chunk[SqlType.Type](
            SqlType.Type.Text,
            SqlType.Type.SmallInt,
            SqlType.Type.Int,
            SqlType.Type.BigInt,
            SqlType.Type.Boolean,
            SqlType.Type.Float32,
            SqlType.Type.Float64,
            SqlType.Type.Numeric(),
            SqlType.Type.Date,
            SqlType.Type.Time,
            SqlType.Type.DateTime,
            SqlType.Type.Timestamp
        )
        types.foreach(t => assert(stub.castTypeName(t).isDefined, t.toString))
    }

    // --- Idiom.Ctx ---

    "serverVersion resolves to the caller's version when named" in {
        val ctx = mkCtx(stub, Present(v(12, 3, 4)))
        assert(ctx.serverVersion.show == "12.3.4")
    }

    "serverVersion falls back to the capability floor" in {
        val ctx = mkCtx(stub)
        assert(ctx.serverVersion.show == "1.0.0")
    }

    "frame carries the call site the render was asked for" in {
        val fr  = summon[Frame]
        val ctx = new Idiom.Ctx(stub, Absent, fr)
        assert(ctx.frame == fr)
    }

    "append accumulates text verbatim in call order" in {
        val ctx = mkCtx(stub)
        ctx.append("SELECT ")
        ctx.append("1")
        assert(ctx.text == "SELECT 1")
    }

    "quoted and appendQuoted answer through the dialect's quoteIdent" in {
        val ctx = mkCtx(stub)
        assert(ctx.quoted("name") == "\"name\"")
        assert(ctx.quoted("a\"b") == "\"a\"\"b\"")
        ctx.appendQuoted("name")
        assert(ctx.text == "\"name\"")
    }

    "joinWith separates consecutive applications only" in {
        val many = mkCtx(stub)
        many.joinWith(", ")(List("a", "b", "c"))(s => many.append(s))
        assert(many.text == "a, b, c")
        val single = mkCtx(stub)
        single.joinWith(", ")(List("a"))(s => single.append(s))
        assert(single.text == "a")
        val none = mkCtx(stub)
        none.joinWith(", ")(List.empty[String])(s => none.append(s))
        assert(none.text == "")
    }

    "appendBind emits the placeholder for each 1-based position and collects binds in order" in {
        val ctx = mkCtx(stub)
        ctx.appendBind(Sql.BoundValue(1, summon[SqlSchema.Column[Int]], "Int"))
        ctx.appendBind(Sql.BoundValue("x", summon[SqlSchema.Column[String]], "String"))
        assert(ctx.text == "$1$2")
        assert(ctx.binds.size == 2)
        assert((ctx.binds(0).value: Any) == 1)
        assert((ctx.binds(1).value: Any) == "x")
        assert(kyo.internal.SqlColumns.eqRef(ctx.binds(0).schema, summon[SqlSchema.Column[Int]]))
        assert(kyo.internal.SqlColumns.eqRef(ctx.binds(1).schema, summon[SqlSchema.Column[String]]))
    }

    // A placeholder stands for one parameter, so a bind carries a `SqlSchema.Column`, which is one column by construction. A row is
    // therefore refused where the bind is BUILT, not where it is appended, and the seam needs no runtime arity check at all.
    "a bind cannot be built from a row, so appendBind has no multi-column case to refuse" in {
        typeCheckFailure("""Sql.BoundValue(IdiomTestAmount(1L, "USD"), summon[SqlSchema[IdiomTestAmount]], "IdiomTestAmount")""")
    }

    "unsupported raises the typed failure naming the feature, dialect, and floor" in {
        val ctx = mkCtx(stub)
        val ex = intercept[SqlUnsupportedDialectFeatureException] {
            ctx.unsupported("row locks", Present(v(9, 9, 9)))
        }
        assert(ex.feature == "row locks")
        assert(ex.dialect.value == "stub")
        assert(ex.requiredVersion.map(_.show) == Present("9.9.9"))
    }

    "unsupported reports the handshake version only when the caller named one" in {
        val withVersion = intercept[SqlUnsupportedDialectFeatureException] {
            mkCtx(stub, Present(v(14, 2, 0))).unsupported("feature", Absent)
        }
        assert(withVersion.serverVersion.map(_.show) == Present("14.2.0"))
        val withoutVersion = intercept[SqlUnsupportedDialectFeatureException] {
            mkCtx(stub).unsupported("feature", Absent)
        }
        assert(withoutVersion.serverVersion.isEmpty)
    }

    // --- the render envelope ---

    "render fills one entry keyed by this dialect's id" in {
        val r = rendered(stub, Sql.raw[Int]("SELECT 1"))
        assert(r.perDialect.size == 1)
        assert(r.sqlFor(Idiom.Id("stub")) == Present("SELECT 1"))
    }

    "render records the capability floor when the caller names no version" in {
        val r = rendered(stub, Sql.raw[Int]("SELECT 1"))
        assert(r.perDialect.values.head.version.show == "1.0.0")
    }

    "render records the caller's version when named" in {
        val r = rendered(stub, Sql.raw[Int]("SELECT 1"), Present(v(7, 7, 7)))
        assert(r.perDialect.values.head.version.show == "7.7.7")
    }

    "raw SQL reaches the output verbatim with no binds" in {
        val r = rendered(stub, Sql.raw[Int]("SELECT 1 FROM t"))
        assert(r.onlySql == Present("SELECT 1 FROM t"))
        assert(r.params.isEmpty)
    }

    "a fragment's literal parts reach the output verbatim around this dialect's placeholders" in {
        val frag = Sql.Fragment[Long](Chunk(
            Sql.Fragment.Lit("SELECT count(*) FROM t WHERE id = "),
            Sql.Fragment.Bind(42L, summon[SqlSchema.Column[Long]], "Long"),
            Sql.Fragment.Lit(" AND name <> "),
            Sql.Fragment.Bind("bo", summon[SqlSchema.Column[String]], "String")
        ))
        val r = rendered(stub, frag)
        assert(r.onlySql == Present("SELECT count(*) FROM t WHERE id = $1 AND name <> $2"))
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == 42L)
        assert((r.params(1).value: Any) == "bo")
    }

    "a literal renders as a bind, never as text" in {
        val r = rendered(stub, Sql.literal(5))
        assert(r.onlySql == Present("$1"))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == 5)
    }

    "statement text depends on the tree's shape, not its values" in {
        val adult = rendered(stub, Sql.from[Person]("p").where(c => c.p.age >= 18))
        val elder = rendered(stub, Sql.from[Person]("p").where(c => c.p.age >= 99))
        assert(adult.onlySql == elder.onlySql)
        assert((adult.params(0).value: Any) == 18)
        assert((elder.params(0).value: Any) == 99)
    }

    // --- queries ---

    // The one exact full-statement assertion: SELECT, the alias-qualified columns in declaration order, FROM, and
    // the quoted table followed by its quoted alias are each pinned; the single-space and comma-space separators
    // are the conventional statement spelling.
    "a bare table renders its projection explicitly, alias qualified, in declaration order" in {
        val t = Sql.from[Person]("p")
        val r = rendered(stub, t)
        val expected =
            "SELECT " + t.columnNames.map(c => qc("p", c)).mkString(", ") + " FROM " + q(t.tableName) + " " + q("p")
        assert(r.onlySql == Present(expected))
        assert(r.params.isEmpty)
    }

    "a filter renders WHERE with the predicate's literal bound" in {
        val r   = rendered(stub, Sql.from[Person]("p").where(c => c.p.age >= 18))
        val sql = r.onlySql.get
        assert(sql.contains(" WHERE "))
        assert(sql.indexOf("FROM") < sql.indexOf("WHERE"))
        assert(sql.contains(qc("p", "age")))
        assert(sql.contains("$1"))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == 18)
    }

    "limit renders LIMIT, and OFFSET only above zero" in {
        val plain = sqlText(stub, Sql.from[Person]("p").limit(5))
        assert(plain.contains("LIMIT"))
        assert(!plain.contains("OFFSET"))
        val withOffset = sqlText(stub, Sql.from[Person]("p").limit(5, 3))
        assert(withOffset.contains("LIMIT"))
        assert(withOffset.contains("OFFSET"))
        assert(withOffset.indexOf("LIMIT") < withOffset.indexOf("OFFSET"))
        val zeroOffset = sqlText(stub, Sql.from[Person]("p").limit(5, 0))
        assert(!zeroOffset.contains("OFFSET"))
    }

    "order by renders the direction keyword and the standard null placements" in {
        val asc = sqlText(stub, Sql.from[Person]("p").orderBy(c => c.p.age.asc))
        assert(asc.contains("ORDER BY"))
        assert(asc.contains("ASC"))
        assert(!asc.contains("NULLS"))
        val descLast = sqlText(stub, Sql.from[Person]("p").orderBy(c => c.p.age.descAbsentLast))
        assert(descLast.contains("DESC"))
        assert(descLast.contains("NULLS LAST"))
        val ascFirst = sqlText(stub, Sql.from[Person]("p").orderBy(c => c.p.age.ascAbsentFirst))
        assert(ascFirst.contains("NULLS FIRST"))
    }

    "DISTINCT is emitted only when the projection carries it" in {
        val plain = sqlText(stub, Sql.from[Person]("p").select(c => c.p.name))
        assert(!plain.contains("DISTINCT"))
        val distinct = sqlText(stub, Sql.from[Person]("p").select(c => c.p.name).distinct)
        assert(distinct.contains("SELECT DISTINCT"))
    }

    "set operations join the operands around the operator keyword" in {
        val a     = Sql.from[Person]("a")
        val b     = Sql.from[Person]("b")
        val union = sqlText(stub, a.union(b))
        assert(union.contains("UNION"))
        assert(union.indexOf("SELECT") < union.indexOf("UNION"))
        assert(union.indexOf("UNION") < union.lastIndexOf("SELECT"))
        assert(sqlText(stub, a.unionAll(b)).contains("UNION ALL"))
        assert(sqlText(stub, a.intersect(b)).contains("INTERSECT"))
        assert(sqlText(stub, a.except(b)).contains("EXCEPT"))
    }

    "INTERSECT and EXCEPT fail typed below the dialect's floor and render at it" in {
        val a  = Sql.from[Person]("a")
        val b  = Sql.from[Person]("b")
        val ex = intercept[SqlUnsupportedDialectFeatureException](rendered(gated, a.intersect(b)))
        assert(ex.requiredVersion.map(_.show) == Present("4.0.0"))
        assert(ex.dialect.value == "stub")
        interceptThrown[SqlUnsupportedDialectFeatureException](rendered(gated, a.except(b)))
        assert(sqlText(gated, a.intersect(b), Present(v(4, 0, 0))).contains("INTERSECT"))
        // UNION is not version-gated, so the same dialect still renders it at its floor.
        assert(sqlText(gated, a.union(b)).contains("UNION"))
    }

    "LATERAL is gated by lateralSince and takes its alias with no AS" in {
        val lat = Sql.lateral[Person]("l", Sql.from[Person]("x"))
        val ex  = intercept[SqlUnsupportedDialectFeatureException](rendered(gated, lat))
        assert(ex.requiredVersion.map(_.show) == Present("2.0.0"))
        val sql = sqlText(gated, lat, Present(v(2, 0, 0)))
        assert(sql.contains("LATERAL"))
        assert(sql.contains(" " + q("l")))
        assert(!sql.contains(" AS "))
        assert(sqlText(stub, lat).contains("LATERAL"))
    }

    "WITH renders each common table as its quoted name, AS, and the parenthesized query" in {
        val cte = Sql.commonTable("c", Sql.from[Person]("x"))
        val sql = sqlText(stub, Sql.commonTables(cte)(Sql.from[Person]("p")))
        assert(sql.startsWith("WITH "))
        assert(sql.contains(q("c") + " AS ("))
        // The prelude is not gated: a dialect with a recursive-CTE floor still renders the non-recursive form.
        assert(sqlText(gated, Sql.commonTables(cte)(Sql.from[Person]("p"))).startsWith("WITH "))
    }

    "WITH RECURSIVE is gated by recursiveCteSince" in {
        val cte = Sql.commonTable("c", Sql.from[Person]("x"))
        val ast = Sql.commonTablesRecursive(cte)(Sql.from[Person]("p"))
        assert(sqlText(stub, ast).startsWith("WITH RECURSIVE"))
        val ex = intercept[SqlUnsupportedDialectFeatureException](rendered(gated, ast))
        assert(ex.requiredVersion.map(_.show) == Present("3.0.0"))
        assert(sqlText(gated, ast, Present(v(3, 0, 0))).startsWith("WITH RECURSIVE"))
    }

    "a VALUES source binds every cell in row-major order and renames its columns" in {
        val vs  = Sql.values[Pair]("v", Pair(1, "a"), Pair(2, "b"))
        val r   = rendered(stub, vs)
        val sql = r.onlySql.get
        assert(sql.contains("(VALUES "))
        assert(sql.contains("($1, $2)"))
        assert(sql.contains("($3, $4)"))
        assert(sql.contains(q("v")))
        assert(sql.contains(q("x")))
        assert(sql.contains(q("y")))
        assert(r.params.size == 4)
        assert((r.params(0).value: Any) == 1)
        assert((r.params(1).value: Any) == "a")
        assert((r.params(2).value: Any) == 2)
        assert((r.params(3).value: Any) == "b")
    }

    "a VALUES source is gated by valuesConstructorSince" in {
        val vs = Sql.values[Pair]("v", Pair(1, "a"))
        val ex = intercept[SqlUnsupportedDialectFeatureException](rendered(gated, vs))
        assert(ex.requiredVersion.map(_.show) == Present("5.0.0"))
        assert(sqlText(gated, vs, Present(v(5, 0, 0))).contains("VALUES"))
    }

    "a join renders both sides around the kind's keyword with ON and the predicate" in {
        val j   = Sql.from[Person]("a").innerJoin(Sql.from[Person]("b")).on(c => c.a.id == c.b.deptId)
        val sql = sqlText(stub, j)
        assert(sql.contains("JOIN"))
        assert(sql.contains(" ON "))
        assert(sql.indexOf("JOIN") < sql.indexOf(" ON "))
        assert(sql.contains(qc("a", "id")))
        assert(sql.contains(qc("b", "deptId")))
    }

    "a cross join renders both sides around CROSS JOIN" in {
        val sql = sqlText(stub, Sql.from[Person]("a").crossJoin(Sql.from[Person]("b")))
        assert(sql.contains("CROSS JOIN"))
    }

    "group by lists its keys comma separated" in {
        val sql = sqlText(stub, Sql.from[Person]("p").groupBy(c => (c.p.deptId, c.p.age)).select(view => view.deptId))
        assert(sql.contains("GROUP BY"))
        assert(sql.contains(qc("p", "deptId") + ", " + qc("p", "age")))
    }

    "HAVING is emitted only when the grouping carries a predicate" in {
        val without = sqlText(stub, Sql.from[Person]("p").groupBy(c => c.p.deptId).select(view => view.deptId))
        assert(without.contains("GROUP BY"))
        assert(!without.contains("HAVING"))
        val sql = sqlText(
            stub,
            Sql.from[Person]("p").groupBy(c => c.p.deptId).having(view => view.age.count > 0L).select(view => view.deptId)
        )
        assert(sql.contains("HAVING"))
        assert(sql.indexOf("GROUP BY") < sql.indexOf("HAVING"))
    }

    "CUBE and GROUPING SETS fail typed where the dialect lacks grouping sets" in {
        val cube = Sql.from[Person]("p").groupByCube(c => c.p.deptId).select(view => view.deptId)
        assert(sqlText(stub, cube).contains("CUBE"))
        // Not version-gated: a flavor that lacks them lacks them at every release, so no floor is named.
        val ex = intercept[SqlUnsupportedDialectFeatureException](rendered(noGroupingSets, cube))
        assert(ex.requiredVersion.isEmpty)
        val sets = Sql.from[Person]("p")
            .groupByGroupingSets(c => Chunk(Chunk[Sql.Term[?]](c.p.deptId), Chunk.empty[Sql.Term[?]]))
            .select(view => view.p.deptId)
        assert(sqlText(stub, sets).contains("GROUPING SETS"))
        interceptThrown[SqlUnsupportedDialectFeatureException](rendered(noGroupingSets, sets))
    }

    "ROLLUP is a spelling, not a capability, so it renders without grouping sets" in {
        val rollup = Sql.from[Person]("p").groupByRollup(c => c.p.deptId).select(view => view.deptId)
        assert(sqlText(stub, rollup).contains("ROLLUP"))
        assert(sqlText(noGroupingSets, rollup).contains("ROLLUP"))
    }

    "a row lock renders its mode, quoted OF list, and wait behavior" in {
        val base  = Sql.from[Person]("p")
        val plain = sqlText(stub, base.forUpdate)
        assert(plain.contains("FOR UPDATE"))
        assert(!plain.contains("NOWAIT"))
        assert(!plain.contains("SKIP"))
        val ofList = sqlText(stub, base.forUpdate("p"))
        val tail   = ofList.substring(ofList.indexOf("FOR UPDATE"))
        assert(tail.contains("OF"))
        assert(tail.contains(q("p")))
        assert(sqlText(stub, base.forUpdateNoWait).contains("NOWAIT"))
        assert(sqlText(stub, base.forUpdateSkipLocked).contains("SKIP LOCKED"))
    }

    "an aggregate wraps a limited source as a derived table and flattens a filter" in {
        val wrapped = sqlText(stub, Sql.from[Person]("p").limit(3).count)
        assert(wrapped.contains("COUNT(*)"))
        assert(wrapped.contains("FROM ("))
        assert(wrapped.indexOf("FROM (") < wrapped.indexOf("LIMIT"))
        assert(!wrapped.contains(" AS "))
        val flat = sqlText(stub, Sql.from[Person]("p").where(c => c.p.age >= 18).count)
        assert(!flat.contains("FROM ("))
        assert(flat.contains("WHERE"))
    }

    "a windowed term renders OVER with the parenthesized spec" in {
        val sql = sqlText(
            stub,
            Sql.from[Person]("p").select(c => Sql.windowSpec.partitionBy(c.p.deptId).orderBy(c.p.age.asc).rowNumber)
        )
        assert(sql.contains("ROW_NUMBER"))
        assert(sql.contains("OVER ("))
        assert(sql.contains("PARTITION BY"))
        assert(sql.contains("ORDER BY"))
        assert(sql.indexOf("OVER (") < sql.indexOf("PARTITION BY"))
        assert(sql.indexOf("PARTITION BY") < sql.indexOf("ORDER BY"))
    }

    // --- terms ---

    "IN parenthesizes its value list and binds each element" in {
        val r   = rendered(stub, Sql.from[Person]("p").where(c => c.p.age.in(1, 2)))
        val sql = r.onlySql.get
        assert(sql.contains("IN ($1, $2)"))
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == 1)
        assert((r.params(1).value: Any) == 2)
        val negated = sqlText(stub, Sql.from[Person]("p").where(c => c.p.age.notIn(1, 2)))
        assert(negated.contains("NOT IN ($1, $2)"))
    }

    "an empty IN list renders the parenthesized constant instead" in {
        val col     = Sql.Column["age", Int]("p", "age", "age")
        val plain   = sqlText(stub, Sql.InValues(col, Chunk.empty[Sql.Term[Int]]))
        val negated = sqlText(stub, Sql.NotInValues(col, Chunk.empty[Sql.Term[Int]]))
        assert(plain.startsWith("("))
        assert(plain.endsWith(")"))
        assert(negated.startsWith("("))
        assert(negated.endsWith(")"))
        assert(plain != negated)
        // The routing is inList's own promise: the empty list reaches emptyIn, so the two spell identically.
        val plainCtx = mkCtx(stub)
        stub.emptyIn(plainCtx, false)
        assert(plainCtx.text == plain)
        val negatedCtx = mkCtx(stub)
        stub.emptyIn(negatedCtx, true)
        assert(negatedCtx.text == negated)
    }

    "IN over a subquery parenthesizes the subquery" in {
        val sub = Sql.from[Person]("s").select(c => c.s.age)
        val sql = sqlText(stub, Sql.from[Person]("p").where(c => c.p.age.in(sub)))
        assert(sql.contains("IN ("))
        assert(sql.indexOf("SELECT") < sql.lastIndexOf("SELECT"))
        val negated = sqlText(stub, Sql.from[Person]("p").where(c => c.p.age.notIn(sub)))
        assert(negated.contains("NOT IN ("))
    }

    "CAST takes castTypeName's spelling" in {
        assert(sqlText(castNaming, Sql.literal(1).cast[String]) == "CAST($1 AS MYTYPE)")
    }

    "a cast the flavor cannot spell fails typed instead of reaching the server" in {
        // String HAS a SqlType, so the cast compiles; the flavor answers Absent for it, so the refusal is at render.
        interceptThrown[SqlUnsupportedException](rendered(noCast, Sql.literal(1).cast[String]))
    }

    "a cast to a type with no portable SQL type is a compile error, not a render refusal" in {
        // A multi-column case class has no `given SqlType`, so `.cast` to it never type-checks and cannot reach render.
        val errors = typeCheckErrors(
            """
            import kyo.*
            kyo.Sql.literal(1).cast[kyo.db.IdiomTestAmount]
            """
        )
        val message = errors.map(_.message).mkString(" ")
        assert(errors.nonEmpty, "casting to a multi-column type must not compile")
        assert(message.contains("SqlType"), s"the error should name the missing SqlType evidence, got: $message")
    }

    "case-insensitive matching folds both sides to lower case around LIKE" in {
        val r   = rendered(stub, Sql.from[Person]("p").where(c => c.p.name.ilike("a%")))
        val sql = r.onlySql.get
        assert(sql.contains("LIKE"))
        assert(sql.sliding(5).count(_ == "LOWER") == 2)
        assert((r.params(0).value: Any) == "a%")
        val sensitive = sqlText(stub, Sql.from[Person]("p").where(c => c.p.name.like("a%")))
        assert(sensitive.contains("LIKE"))
        assert(!sensitive.contains("LOWER"))
        val negated = sqlText(stub, Sql.from[Person]("p").where(c => c.p.name.notIlike("a%")))
        assert(negated.contains("NOT"))
        assert(negated.sliding(5).count(_ == "LOWER") == 2)
    }

    "SUBSTRING renders FROM and only optionally FOR" in {
        val r   = rendered(stub, Sql.literal("abcdef").substring(2, 3))
        val sql = r.onlySql.get
        assert(sql.contains("SUBSTRING"))
        assert(sql.contains(" FROM "))
        assert(sql.contains(" FOR "))
        assert(r.params.size == 3)
        val noLength = sqlText(stub, Sql.literal("abcdef").substring(2))
        assert(noLength.contains("SUBSTRING"))
        assert(!noLength.contains(" FOR "))
    }

    "a CASE expression renders WHEN, THEN, ELSE, END in order" in {
        val sql   = sqlText(stub, Sql.when(Sql.literal(true)).to(1).otherwise(2))
        val iCase = sql.indexOf("CASE")
        val iWhen = sql.indexOf("WHEN")
        val iThen = sql.indexOf("THEN")
        val iElse = sql.indexOf("ELSE")
        val iEnd  = sql.indexOf("END")
        assert(iCase >= 0)
        assert(iCase < iWhen)
        assert(iWhen < iThen)
        assert(iThen < iElse)
        assert(iElse < iEnd)
        val absentForm = sqlText(stub, Sql.when(Sql.literal(true)).to(1).otherwiseAbsent)
        assert(!absentForm.contains("ELSE"))
        assert(absentForm.contains("END"))
    }

    "functions render applied under their standard names" in {
        assert(sqlText(stub, Sql.literal(5).abs) == "ABS($1)")
        assert(sqlText(stub, Sql.literal("a").upper) == "UPPER($1)")
        assert(sqlText(stub, Sql.literal("a").length) == "LENGTH($1)")
    }

    "concatenation joins the parts with the standard operator, parenthesized" in {
        val r   = rendered(stub, Sql.literal("a") ++ Sql.literal("b"))
        val sql = r.onlySql.get
        assert(sql.startsWith("("))
        assert(sql.endsWith(")"))
        assert(sql.contains("||"))
        assert(r.params.size == 2)
        assert((r.params(0).value: Any) == "a")
        assert((r.params(1).value: Any) == "b")
    }

    "EXCLUDED references the conflict row's column with the majority qualifier" in {
        val sql = sqlText(stub, Sql.Excluded(Sql.Column["name", String]("", "name", "name")))
        assert(sql == "EXCLUDED." + q("name"))
    }

    // --- overriding ---

    "the documented interception shape retargets one arm and delegates the rest" in {
        assert(sqlText(modFn, Sql.literal(7) % Sql.literal(3)) == "MOD($1, $2)")
        val delegated = sqlText(modFn, Sql.literal(7) + Sql.literal(3))
        assert(delegated.startsWith("("))
        assert(delegated.endsWith(")"))
        assert(delegated.contains("+"))
    }

    "an override that omits the fallback fails with MatchError at render time" in {
        interceptThrown[MatchError](rendered(modOnly, Sql.literal(7) + Sql.literal(3)))
        // The arm the override does treat still renders.
        assert(sqlText(modOnly, Sql.literal(7) % Sql.literal(3)) == "MOD($1, $2)")
    }

    "overriding limit retargets pagination everywhere it is reached" in {
        val direct = sqlText(paginated, Sql.from[Person]("p").limit(5))
        assert(direct.contains("PAGINATED"))
        assert(!direct.contains("LIMIT"))
        val underSetOp = sqlText(paginated, Sql.from[Person]("a").limit(5).union(Sql.from[Person]("b")))
        assert(underSetOp.contains("PAGINATED"))
        assert(!underSetOp.contains("LIMIT"))
    }

    // --- actions ---

    // INSERT INTO with the trailing space is pinned by insertKeyword; the quoted column list and the parenthesized
    // comma-separated bound rows by insertValues and decomposedRows. The single spaces are the conventional spelling.
    "an INSERT emits every column and binds every cell in row-then-column order" in {
        val ins  = Sql.insert[Person].values(Person(1L, "Alice", 30, 2L))
        val r    = rendered(stub, ins)
        val cols = ins.columnNames.map(q).mkString(", ")
        assert(r.onlySql == Present("INSERT INTO " + q(ins.tableName) + " (" + cols + ") VALUES ($1, $2, $3, $4)"))
        assert(r.params.size == 4)
        assert((r.params(0).value: Any) == 1L)
        assert((r.params(1).value: Any) == "Alice")
        assert((r.params(2).value: Any) == 30)
        assert((r.params(3).value: Any) == 2L)
    }

    "a multi-row INSERT numbers placeholders across rows without restarting" in {
        val r = rendered(stub, Sql.insert[Person].values(Person(1L, "Alice", 30, 1L), Person(2L, "Bob", 25, 2L)))
        assert(r.onlySql.get.contains("VALUES ($1, $2, $3, $4), ($5, $6, $7, $8)"))
        assert(r.params.size == 8)
        assert((r.params(4).value: Any) == 2L)
        assert((r.params(5).value: Any) == "Bob")
    }

    "overriding a column with DEFAULT keeps the row's own value out of the binds" in {
        val r   = rendered(stub, Sql.insert[Person].values(Person(7L, "Alice", 30, 1L)).overriding(_.id := Sql.default))
        val sql = r.onlySql.get
        assert(sql.contains("(DEFAULT, $1, $2, $3)"))
        assert(r.params.size == 3)
        assert((r.params(0).value: Any) == "Alice")
        assert((r.params(1).value: Any) == 30)
        assert((r.params(2).value: Any) == 1L)
    }

    "the auto-key comes back through RETURNING only where the flavor has the clause" in {
        val ins    = Sql.insert[Person].values(Person(1L, "A", 20, 1L))
        val withIt = sqlText(returningIdiom, ins)
        assert(withIt.contains("RETURNING"))
        assert(withIt.substring(withIt.indexOf("RETURNING")).contains(q("id")))
        assert(!sqlText(stub, ins).contains("RETURNING"))
        // When the caller names columns, the clause carries those and not the auto-key.
        val explicit = sqlText(returningIdiom, ins.returning(_.name))
        val tail     = explicit.substring(explicit.indexOf("RETURNING"))
        assert(tail.contains(q("name")))
        assert(!tail.contains(q("id")))
    }

    "an explicit returning request fails typed where the flavor lacks the clause" in {
        val upd = Sql.update[Person].set(_.name := "x").returning(_.id).build
        val ex  = intercept[SqlUnsupportedDialectFeatureException](rendered(stub, upd))
        // Not version-gated: the flavor either has RETURNING or never had it, so no floor is named.
        assert(ex.requiredVersion.isEmpty)
        val sql = sqlText(returningIdiom, upd)
        assert(sql.contains("RETURNING"))
        assert(sql.substring(sql.indexOf("RETURNING")).contains(q("id")))
    }

    "an UPDATE renders its quoted table, SET list, and optional WHERE" in {
        val upd = Sql.update[Person].set(_.name := "x", _.age := 31).where(c => c.id == 5L)
        val r   = rendered(stub, upd)
        val sql = r.onlySql.get
        assert(sql.contains("UPDATE " + q(upd.tableName)))
        assert(sql.contains("SET"))
        assert(sql.contains(q("name") + " = $1"))
        assert(sql.contains(q("age") + " = $2"))
        assert(sql.contains(" WHERE "))
        assert(r.params.size == 3)
        assert((r.params(0).value: Any) == "x")
        assert((r.params(1).value: Any) == 31)
        assert((r.params(2).value: Any) == 5L)
        assert(!sqlText(stub, Sql.update[Person].set(_.name := "x").build).contains("WHERE"))
    }

    "a DELETE renders DELETE FROM with the optional WHERE" in {
        val del = Sql.delete[Person].where(c => c.id == 7L)
        val r   = rendered(stub, del)
        val sql = r.onlySql.get
        assert(sql.contains("DELETE FROM " + q(del.tableName)))
        assert(sql.contains(" WHERE "))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == 7L)
        assert(!sqlText(stub, Sql.delete[Person].build).contains("WHERE"))
    }

    "a partial-values INSERT sends only the caller's columns" in {
        val r   = rendered(stub, Sql.insert[Person].partialValues(_.name := "amy"))
        val sql = r.onlySql.get
        assert(sql.contains(q("name")))
        assert(!sql.contains(q("id")))
        assert(!sql.contains(q("age")))
        assert(!sql.contains(q("deptId")))
        assert(sql.contains("$1"))
        assert(r.params.size == 1)
        assert((r.params(0).value: Any) == "amy")
    }

    "conflict clauses render the majority spelling with EXCLUDED reachable in the update arm" in {
        val row     = Person(1L, "Alice", 30, 1L)
        val nothing = sqlText(stub, Sql.insert[Person].values(row).onConflictDoNothing(_.id))
        val nTail   = nothing.substring(nothing.indexOf("ON CONFLICT"))
        assert(nTail.contains("DO NOTHING"))
        assert(nTail.contains(q("id")))
        assert(nTail.indexOf(q("id")) < nTail.indexOf("DO NOTHING"))
        val update = sqlText(
            stub,
            Sql.insert[Person].values(row)
                .onConflictDoUpdate(_.id)
                .where(c => c.age > 0)(c => c.name := Sql.Excluded(c.name))
        )
        val uTail = update.substring(update.indexOf("ON CONFLICT"))
        assert(uTail.contains("DO UPDATE"))
        assert(uTail.contains(q("name") + " = EXCLUDED." + q("name")))
        assert(uTail.contains("WHERE"))
    }

    "an INSERT from SELECT refuses a column override typed rather than dropping it" in {
        val base = Sql.insert[Person].fromSelect(_.id, _.name, _.age, _.deptId)(Sql.from[Person]("s"))
        val sql  = sqlText(stub, base)
        assert(sql.contains("INSERT INTO"))
        assert(sql.contains("SELECT"))
        assert(sql.contains(q("name")))
        interceptThrown[SqlUnsupportedException](rendered(stub, base.overriding(_.id := Sql.default)))
    }

end IdiomTest

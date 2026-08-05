package kyo

import kyo.*
import kyo.Sql.*
import kyo.Test
import scala.annotation.unused
import scala.compiletime.testing.typeChecks

/** SqlTest, verifies the Sql DSL by rendering each query to SQL and asserting on the rendered string and bind parameters.
  *
  * Backend: Postgres (placeholders `$1`, `$2`, …, identifiers double-quoted). Negative tests at the bottom verify the type system rejects
  * invalid queries at compile time.
  */
class SqlTest extends Test:

    case class Person(id: Long, name: String, age: Int, deptId: Long) derives SqlSchema
    case class Department(id: Long, name: String, budget: BigDecimal) derives SqlSchema
    case class Order(id: Long, userId: Long, total: BigDecimal, createdAt: Instant) derives SqlSchema
    case class OrderItem(id: Long, orderId: Long, productId: Long, quantity: Int) derives SqlSchema
    case class Product(id: Long, name: String, price: BigDecimal) derives SqlSchema
    case class NameAge(name: String, age: Int) derives SqlSchema
    case class Customer(id: Long, name: String, email: Maybe[String], suspended: Boolean) derives SqlSchema
    case class Survey(id: Long, opinion: Maybe[Boolean]) derives SqlSchema
    case class Reading(id: Long, celsius: Maybe[Int], ratio: Double) derives SqlSchema
    case class Sale(region: String, product: Maybe[String], amount: Int) derives SqlSchema

    val people      = Sql.from[Person]("p")
    val departments = Sql.from[Department]("d")
    val orders      = Sql.from[Order]("o")
    val orderItems  = Sql.from[OrderItem]("oi")
    val products    = Sql.from[Product]("pr")
    val customers   = Sql.from[Customer]("c")
    val surveys     = Sql.from[Survey]("s")

    // --- SELECT ---

    // --- WHERE ---

    // --- EXISTS ---

    // --- JOIN ---

    // --- GROUP BY ---

    // --- Eager groupBy view materialization ---

    "AST GroupBy carries a non-empty view: Record[F] after groupBy" in {
        val g = Sql.from[Person]("p").groupBy(_.p.deptId)
        assert(g.productElementNames.contains("view"))
        g.productElement(g.productElementNames.indexOf("view")) match
            case v: Record[?] =>
                val dictMap = v.dict.toMap
                assert(dictMap.contains("deptId"))
                // Grouped key wrapped as GroupedColumn
                assert(dictMap("deptId").isInstanceOf[GroupedColumn[?, ?]])
                // Non-key columns wrapped as UngroupedView
                assert(dictMap.contains("age"))
                assert(dictMap("age").isInstanceOf[UngroupedView[?]])
            case other =>
                fail(s"Expected Record[?] for view field, got: ${other.getClass.getName}")
        end match
    }

    // --- ORDER BY ---

    // --- LIMIT / DISTINCT ---

    // --- SET OPS ---

    // --- CTE ---

    // --- Aggregates ---

    // --- Window functions ---

    // --- Expression DSL ---

    // --- CASE WHEN ---

    // --- Cast / call / raw ---

    // --- Fragments (sql"..." interpolator) ---

    // --- INSERT ---

    // --- UPDATE ---

    // --- DELETE ---

    // --- Locks ---

    // --- MySQL backend ---

    // --- Aggregate and division result types ---
    //
    // Each leaf pins the type in BOTH directions: the type the servers actually return typechecks, and the operand's own
    // type does not. The negative half is what makes these exact, since an aggregate typed as its operand would satisfy
    // the positive half alone.

    "whole-table sum widens Int to Long and is nullable" in {
        assert(typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Person]("p").sum(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Int] = Sql.from[Person]("p").sum(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Long] = Sql.from[Person]("p").sum(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Maybe[Int]] = Sql.from[Person]("p").sum(_.p.age)"""))
    }

    "whole-table sum widens Long to BigDecimal, which is what Postgres returns to avoid overflowing int8" in {
        assert(typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Person]("p").sum(_.p.deptId)"""))
        assert(!typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Person]("p").sum(_.p.deptId)"""))
    }

    "whole-table avg is BigDecimal over an exact operand and Double over an approximate one" in {
        assert(typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Person]("p").avg(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Maybe[Int]] = Sql.from[Person]("p").avg(_.p.age)"""))
        assert(typeChecks("""val q: Query[Maybe[Double]] = Sql.from[Reading]("r").avg(_.r.ratio)"""))
        assert(!typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Reading]("r").avg(_.r.ratio)"""))
    }

    "whole-table min and max keep the operand's width, which is the one both servers return unchanged, and are nullable" in {
        assert(typeChecks("""val q: Query[Maybe[Int]] = Sql.from[Person]("p").min(_.p.age)"""))
        assert(typeChecks("""val q: Query[Maybe[String]] = Sql.from[Person]("p").max(_.p.name)"""))
        assert(!typeChecks("""val q: Query[Int] = Sql.from[Person]("p").min(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Person]("p").min(_.p.age)"""))
    }

    "a whole-table aggregate over a nullable column stays at one level of Maybe" in {
        assert(typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Reading]("r").sum(_.r.celsius)"""))
        assert(!typeChecks("""val q: Query[Maybe[Maybe[Long]]] = Sql.from[Reading]("r").sum(_.r.celsius)"""))
        assert(typeChecks("""val q: Query[Maybe[Int]] = Sql.from[Reading]("r").min(_.r.celsius)"""))
        assert(!typeChecks("""val q: Query[Maybe[Maybe[Int]]] = Sql.from[Reading]("r").min(_.r.celsius)"""))
    }

    "a filtered aggregate is nullable too, since a predicate that matches nothing returns one NULL row" in {
        assert(typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Person]("p").where(c => c.p.age > 200).sum(_.p.age)"""))
        assert(!typeChecks("""val q: Query[Long] = Sql.from[Person]("p").where(c => c.p.age > 200).sum(_.p.age)"""))
    }

    // --- Type-level assertions on the types the DSL assigns ---
    //
    // Every negated `typeChecks` below is paired, in its own leaf, with a positive one over the SAME expression
    // differing only in the type it ascribes. That pairing is the control: a negation alone certifies nothing,
    // because any defect in the string (a wrong arity, a mangled type, a typo in the expression) makes it pass
    // for a reason unrelated to the property under test, and neither the compiler nor the test run can see
    // inside a string literal.
    //
    // The pairing catches a defect that hits both strings; it does not catch one that hits only the negated
    // string. The check for that is a mutation: replace a negation's ascribed type with its positive sibling's and
    // it must flip to failing, one negation at a time so a leaf holding two cannot mask its second. Worth re-running
    // after any edit to these strings.
    "a grouped aggregate carries the widened type and is NOT nullable for a non-nullable operand" in {
        assert(typeChecks("""val q: Select[?, Long, ?] = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(v => v.age.sum)"""))
        assert(!typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(v => v.age.sum)"""))
        assert(!typeChecks("""val q: Select[?, Maybe[Long], ?] = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(v => v.age.sum)"""))
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(v => v.age.avg)"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").groupBy(c => c.p.deptId).select(v => v.age.min)"""))
    }

    "a grouped aggregate over a nullable operand IS nullable, because a group of all-NULL values sums to NULL" in {
        assert(typeChecks("""val q: Select[?, Maybe[Long], ?] = Sql.from[Reading]("r").groupBy(c => c.r.id).select(v => v.celsius.sum)"""))
        assert(!typeChecks("""val q: Select[?, Long, ?] = Sql.from[Reading]("r").groupBy(c => c.r.id).select(v => v.celsius.sum)"""))
    }

    "a window aggregate carries the widened type" in {
        assert(typeChecks("""val q: Select[?, Long, ?] = Sql.from[Person]("p").select(c => c.p.age.sum.over(Sql.windowSpec))"""))
        assert(!typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age.sum.over(Sql.windowSpec))"""))
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Person]("p").select(c => c.p.age.avg.over(Sql.windowSpec))"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age.max.over(Sql.windowSpec))"""))
    }

    "a rollup or cube key is nullable, because a super-aggregate row carries NULL for it" in {
        assert(
            typeChecks("""val q: Select[?, Maybe[String], ?] = Sql.from[Sale]("s").groupByRollup(c => c.s.region).select(v => v.region)""")
        )
        assert(!typeChecks("""val q: Select[?, String, ?] = Sql.from[Sale]("s").groupByRollup(c => c.s.region).select(v => v.region)"""))
        assert(
            typeChecks("""val q: Select[?, Maybe[String], ?] = Sql.from[Sale]("s").groupByCube(c => c.s.region).select(v => v.region)""")
        )
        assert(!typeChecks("""val q: Select[?, String, ?] = Sql.from[Sale]("s").groupByCube(c => c.s.region).select(v => v.region)"""))
    }

    "a rollup key that was already nullable stays at one level of Maybe" in {
        assert(
            typeChecks(
                """val q: Select[?, Maybe[String], ?] = Sql.from[Sale]("s").groupByRollup(c => c.s.product).select(v => v.product)"""
            )
        )
        assert(
            !typeChecks("""val q: Select[?, Maybe[Maybe[String]], ?] = Sql.from[Sale]("s").groupByRollup(c => c.s.product).select(v =>
                v.product)""")
        )
    }

    "a tuple-keyed rollup makes every key nullable and leaves the non-key fields ungrouped" in {
        assert(typeChecks(
            """val q: Select[?, (Maybe[String], Maybe[String], Long), ?] =
                 Sql.from[Sale]("s").groupByRollup(c => (c.s.region, c.s.product)).select(v => (v.region, v.product, v.amount.sum))"""
        ))
        assert(!typeChecks(
            """val q: Select[?, (String, Maybe[String], Long), ?] =
                 Sql.from[Sale]("s").groupByRollup(c => (c.s.region, c.s.product)).select(v => (v.region, v.product, v.amount.sum))"""
        ))
    }

    "a plain groupBy key is NOT nullable, since every row of a plain grouping carries its key" in {
        assert(typeChecks("""val q: Select[?, String, ?] = Sql.from[Sale]("s").groupBy(c => c.s.region).select(v => v.region)"""))
        assert(!typeChecks("""val q: Select[?, Maybe[String], ?] = Sql.from[Sale]("s").groupBy(c => c.s.region).select(v => v.region)"""))
    }

    "division yields the fractional quotient, not the operand type" in {
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Person]("p").select(c => c.p.age / c.p.age)"""))
        assert(!typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age / c.p.age)"""))
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Person]("p").select(c => c.p.age / 4)"""))
        assert(typeChecks("""val q: Select[?, Double, ?] = Sql.from[Reading]("r").select(c => c.r.ratio / c.r.ratio)"""))
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Order]("o").select(c => c.o.total / c.o.total)"""))
    }

    "divideTruncating keeps the integral type, and is not offered for a non-integral operand" in {
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age.divideTruncating(c.p.age))"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age.divideTruncating(4))"""))
        assert(typeChecks("""val q: Select[?, Long, ?] = Sql.from[Person]("p").select(c => c.p.deptId.divideTruncating(c.p.deptId))"""))
        assert(!typeChecks("""Sql.from[Order]("o").select(c => c.o.total.divideTruncating(c.o.total))"""))
        assert(!typeChecks("""Sql.from[Reading]("r").select(c => c.r.ratio.divideTruncating(c.r.ratio))"""))
    }

    "the other four arithmetic operators keep the operand type, which both servers agree on" in {
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age + c.p.age)"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age - c.p.age)"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age * c.p.age)"""))
        assert(typeChecks("""val q: Select[?, Int, ?] = Sql.from[Person]("p").select(c => c.p.age % c.p.age)"""))
    }

    // --- Negative tests, compile-time rejection of invalid queries ---

    "rejects Sql.lit (no literal escape hatch in user-facing surface)" in {
        assert(!typeChecks("Sql.lit(42)"))
    }

    "rejects ungrouped column access without aggregate post-groupBy" in {
        assert(!typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).having(view => view.age > 18)"))
        assert(!typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).select(view => view.age + 1)"))
    }

    "rejects cross-type column comparison" in {
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age == c.p.deptId)"))
    }

    "rejects accessing a non-existent grouped field" in {
        assert(!typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).select(view => view.nonExistentField)"))
    }

    "rejects nested aggregates via Column" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.deptId.count.sum)"))
    }

    "rejects Boolean ops on non-Boolean terms" in {
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age && c.p.age)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.name || c.p.name)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => !c.p.age)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.isTrue)"))
    }

    "rejects String ops on non-String terms" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.age.upper)"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.age.length)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.like(\"foo\"))"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.age ++ \"bar\")"))
    }

    "rejects Numeric ops on non-numeric terms" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.name + c.p.name)"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.name * 2)"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.name.abs)"))
    }

    "rejects window .over() on a plain Column" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.age.over(Sql.windowSpec))"))
    }

    "rejects Select.to[B] when projection's value types don't match B" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => (c.p.age, c.p.name)).to[NameAge]"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.name).to[NameAge]"))
    }

    "rejects accessing a column from a non-aliased table" in {
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.o.userId == 1L)"))
    }

    "rejects GroupTerm.sum / avg on non-numeric V" in {
        assert(!typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).select(view => view.name.sum)"))
        assert(!typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).select(view => view.name.avg)"))
    }

    "rejects assigning a wrong-typed value to a column" in {
        assert(!typeChecks("Sql.update[Person].set(_.name := 42).build"))
        assert(!typeChecks("Sql.update[Person].set(_.age := \"oops\").build"))
    }

    "rejects aggregates inside WHERE" in {
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.id.count > 5)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.sum > 100)"))
    }

    "rejects nested aggregates (SUM(COUNT(x)))" in {
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.id.count.sum.over(Sql.windowSpec))"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => c.p.age.sum.avg.over(Sql.windowSpec))"))
    }

    // Absence is a `Maybe`, so `== Absent` is how a query asks whether a column is absent, and the column's own
    // type is what decides whether the question can be asked: `Absent` is one of a `Maybe[String]`'s values and is
    // not one of an `Int`'s. The rule is carried by the types alone. The positives keep the negatives honest, since
    // a rejection for any unrelated reason would read the same.
    "comparing against Absent requires the column to be a Maybe" in {
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email == Absent)"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email != Absent)"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email == Present(\"a@b\"))"))

        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age == Absent)"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.name != Absent)"))
    }

    // `Sql.default` is the `DEFAULT` keyword, which every server accepts in an assignment and nowhere else. It is
    // a SetValue rather than a Term, so the positions that take a term do not take it, and the two positives pin
    // the assignment positions where it does belong.
    "rejects Sql.default anywhere but a column assignment" in {
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age == Sql.default[Int])"))
        assert(!typeChecks("Sql.from[Person](\"p\").select(c => Sql.default[Int])"))
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age > Sql.default[Int])"))
        assert(!typeChecks("Sql.call[Int](\"greatest\", Sql.default[Int])"))

        assert(typeChecks("Sql.update[Person].set(_.age := Sql.default).build"))
        assert(typeChecks("Sql.insert[Person].values(Person(1L, \"a\", 2, 3L)).overriding(_.id := Sql.default)"))
    }

    // A projection is not the end of a query: SQL follows it with ORDER BY and LIMIT, and the ordering may name a
    // source column the projection leaves out, which is the case the third positive covers. Without these the only
    // spelling for an ordered projection is a wrap in `Sql.nested`.
    "a projected query orders and limits, and can order by a column it does not project" in {
        assert(typeChecks("Sql.from[Person](\"p\").select(c => c.p.name).orderBy(c => c.p.name.asc)"))
        assert(typeChecks("Sql.from[Person](\"p\").select(c => c.p.name).limit(10)"))
        assert(typeChecks("Sql.from[Person](\"p\").select(c => c.p.name).orderBy(c => c.p.age.desc).limit(10)"))
        assert(typeChecks("Sql.from[Person](\"p\").groupBy(c => c.p.deptId).select(v => v.age.sum).orderBy(v => v.age.sum.desc)"))

        // The row type of an ordered projection is the projection's, not the source's.
        assert(typeChecks("""val q: OrderBy[String] = Sql.from[Person]("p").select(c => c.p.name).orderBy(c => c.p.name.asc)"""))
        assert(!typeChecks("""val q: OrderBy[Person] = Sql.from[Person]("p").select(c => c.p.name).orderBy(c => c.p.name.asc)"""))
    }
end SqlTest

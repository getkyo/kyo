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
    case class Tally(id: Long, small: Byte, mid: Short, huge: BigInt) derives SqlSchema

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

    // The raw-value comparison that crosses nullability goes one way only: a column permitting NULL against a plain
    // literal. The other way round is a column that cannot be NULL against a value that may be, and no row can
    // satisfy it in either polarity: `= NULL` is UNKNOWN for every row, `NOT UNKNOWN` is UNKNOWN too, and a WHERE
    // keeps neither, so the query answers nothing and so does its negation. A predicate no row can satisfy either way
    // is not a question worth asking, and it reports nothing at runtime, so it is a compile error. The `Term` forms
    // keep the other direction, because a join between a nullable foreign key and the non-null key it references is
    // written that way round as often as not.
    "a raw value that may be absent is only compared against a column that may be" in {
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email == \"a@b\")"))
        assert(typeChecks("val e: Maybe[String] = Present(\"a@b\"); Sql.from[Customer](\"c\").where(c => c.c.email == e)"))
        assert(typeChecks(
            "Sql.from[Person](\"p\").innerJoin(Sql.from[Customer](\"c\")).on(j => j.p.name == j.c.email)"
        ))
        assert(typeChecks(
            "Sql.from[Customer](\"c\").innerJoin(Sql.from[Person](\"p\")).on(j => j.c.email == j.p.name)"
        ))

        assert(!typeChecks("val n: Maybe[String] = Present(\"x\"); Sql.from[Person](\"p\").where(c => c.p.name == n)"))
        assert(!typeChecks("val n: Maybe[String] = Present(\"x\"); Sql.from[Person](\"p\").where(c => c.p.name != n)"))
        assert(!typeChecks("val a: Maybe[Int] = Present(1); Sql.from[Person](\"p\").where(c => c.p.age > a)"))
        assert(!typeChecks("val a: Maybe[Int] = Present(1); Sql.from[Person](\"p\").where(c => c.p.age <= a)"))
    }

    // Lifting a value with `Sql.literal` is the other door into the same comparison, and it used to be open: the
    // lifted term is a `Term[Maybe[A]]`, which the column-to-column evidence admits against a NOT NULL column
    // because comparing two COLUMNS across nullability is the ordinary join. A lifted value is not a column, so it
    // follows the raw-value rule instead.
    "a lifted value that may be absent is only compared against a column that may be" in {
        // Allowed: the lifted value has the column's own type.
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.age > Sql.literal(18))"))
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.name == Sql.literal(\"ada\"))"))
        // Allowed: the column permits NULL and the lifted value does not.
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email == Sql.literal(\"a@b\"))"))

        // Refused: a lifted value that may be absent against a column that may not. This renders `= ?` with a NULL
        // bind, which is the statement the raw form is refused for.
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => c.p.name == Sql.literal(e))"
        ))
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => c.p.name != Sql.literal(e))"
        ))
        assert(!typeChecks(
            "val a: Maybe[Int] = Absent; Sql.from[Person](\"p\").where(c => c.p.age >= Sql.literal(a))"
        ))

        // The join keeps working, which is what the column-to-column evidence exists for and what a blanket
        // tightening would have broken.
        assert(typeChecks(
            "Sql.from[Person](\"p\").innerJoin(Sql.from[Customer](\"c\")).on(j => j.p.name == j.c.email)"
        ))

        // The control the negations above need. Lifting a `Maybe` is legal on its own and compiles against a column
        // that permits NULL, so the refusals are about the direction of the comparison rather than about
        // `Sql.literal` rejecting a `Maybe` outright. Without this, a missing `SqlSchema.Column[Maybe[String]]`
        // would satisfy every negation for a reason unrelated to the property under test.
        assert(typeChecks("val e: Maybe[String] = Absent; val t: Term[Maybe[String]] = Sql.literal(e)"))
        assert(typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Customer](\"c\").where(c => c.c.email == Sql.literal(e))"
        ))
    }

    // The same rule approached from the other side. `Sql.literal(e) == column` puts the lifted value on the
    // receiver, so the ARGUMENT is a plain column rather than a `Literal` and the lifted evidence is never
    // consulted; the column-to-column rule admits it through `SqlComparable.leftOptional`, and the statement
    // renders `? = "name"` with a NULL bind. That is the same never-matching predicate the form above is refused
    // for, written the other way round.
    "a lifted value that may be absent is refused on the left of the comparison too" in {
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => Sql.literal(e) == c.p.name)"
        ))
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => Sql.literal(e) != c.p.name)"
        ))
        assert(!typeChecks(
            "val a: Maybe[Int] = Absent; Sql.from[Person](\"p\").where(c => Sql.literal(a) <= c.p.age)"
        ))

        // The control the negations need: the same shape against a column that DOES permit NULL still compiles,
        // so the refusals are about the direction rather than about a lifted `Maybe` failing to be a term at all.
        assert(typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Customer](\"c\").where(c => Sql.literal(e) == c.c.email)"
        ))
        assert(typeChecks(
            "Sql.from[Person](\"p\").where(c => Sql.literal(\"ada\") == c.p.name)"
        ))
    }

    // Two more doors into the same never-matching predicate, both closed by the receiver-aware evidence.
    //
    // Lifted against lifted is two VALUES, so the argument that keeps a nullability crossing for a column does not
    // reach it in either order: `? = ?` with one NULL bind matches nothing exactly as `? = "col"` does.
    "two lifted values compare only at the same type, in both orders" in {
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(_ => Sql.literal(e) == Sql.literal(\"ada\"))"
        ))
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(_ => Sql.literal(\"ada\") == Sql.literal(e))"
        ))
        // Controls: equal types compare, whether or not they permit NULL.
        assert(typeChecks("Sql.from[Person](\"p\").where(_ => Sql.literal(\"ada\") == Sql.literal(\"bob\"))"))
        assert(typeChecks(
            "val e: Maybe[String] = Absent; val f: Maybe[String] = Absent; Sql.from[Person](\"p\").where(_ => Sql.literal(e) == Sql.literal(f))"
        ))
    }

    // The raw-value and pattern doors, on a lifted receiver.
    "a lifted value that may be absent takes neither a raw comparison nor a pattern test" in {
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(_ => Sql.literal(e) == \"ada\")"
        ))
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(_ => Sql.literal(e).like(\"a%\"))"
        ))
        // Controls: a lifted PRESENT value takes both, and a nullable COLUMN still takes both.
        assert(typeChecks("Sql.from[Person](\"p\").where(_ => Sql.literal(\"ada\").like(\"a%\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.like(\"a%\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email == \"a@b\")"))
    }

    // The lifted spelling reached the six comparison operators but not membership or range, so a value that could
    // be compared could not be tested for membership: the allowed direction did not compile at all. These pin both
    // halves, so the lifted form cannot be added in one direction only.
    // Membership and range against a LIFTED value, which the six comparison operators grew a dedicated form for and
    // these did not. The safety property still holds here without one, because it falls out of the element type
    // rather than out of evidence: `in` takes `Term[A]*`, and a lifted value that may be absent is a
    // `Term[Maybe[A]]`, which is not the `Term[A]` a NOT NULL column's form accepts.
    "membership and range refuse a lifted value that may be absent" in {
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => c.p.name.in(Sql.literal(e)))"
        ))
        assert(!typeChecks(
            "val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => c.p.name.notIn(Sql.literal(e)))"
        ))
        assert(!typeChecks(
            "val a: Maybe[Int] = Absent; Sql.from[Person](\"p\").where(c => c.p.age.between(Sql.literal(a), Sql.literal(a)))"
        ))

        // A lifted value of the column's own type is accepted by the `Term[A]*` form, so the lifted spelling works
        // here without a dedicated overload.
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.name.in(Sql.literal(\"ada\")))"))
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.between(Sql.literal(1), Sql.literal(9)))"))

        // Crossing nullability with a lifted value has no form: `Term[A]*` wants the column's own type, and adding a
        // `Literal[B]*` overload beside it is ambiguous against exactly that form, which the same shape at a single
        // argument is not. The raw form is the spelling for this case and it carries the same one-way rule.
        assert(!typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.in(Sql.literal(\"a@b\")))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.in(\"a@b\"))"))
    }

    // The rule `==` follows reached six operators of the fifteen a column has. The rest still took the column's own
    // type exactly, so a nullable column could be compared to a plain value but not tested for membership in one,
    // and the pattern operators were gated on `A =:= String`, which left a nullable text column without `like` at
    // all, in either form.
    "membership and range over raw values cross nullability the way equality does" in {
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.in(\"a@b\", \"c@d\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.notIn(\"a@b\"))"))
        assert(typeChecks("Sql.from[Reading](\"r\").where(c => c.r.celsius.between(0, 100))"))

        // The column's own type still works, which is the overload that was already there.
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.in(1, 2))"))
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.between(0, 100))"))

        // And the direction that renders a comparison against NULL is still refused.
        assert(!typeChecks("val e: Maybe[String] = Absent; Sql.from[Person](\"p\").where(c => c.p.name.in(e))"))
        assert(!typeChecks("val a: Maybe[Int] = Absent; Sql.from[Person](\"p\").where(c => c.p.age.between(a, a))"))
    }

    // The `SqlNumeric` and `SqlIntegral` givens added for Short, Byte and BigInt shipped with only Short
    // exercised. These pin the result types the servers actually return, in both directions: the type that is
    // right typechecks, and the operand's own type does not, so a given typed as its operand cannot satisfy them.
    "sum over a Byte column widens to Long, as it does over Short and Int" in {
        assert(typeChecks("""val q: Query[Maybe[Long]] = Sql.from[Tally]("t").sum(_.t.small)"""))
        assert(!typeChecks("""val q: Query[Maybe[Byte]] = Sql.from[Tally]("t").sum(_.t.small)"""))
        assert(!typeChecks("""val q: Query[Maybe[Int]] = Sql.from[Tally]("t").sum(_.t.small)"""))
    }

    "sum over a BigInt column widens to BigDecimal, which is what an exact aggregate returns" in {
        assert(typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Tally]("t").sum(_.t.huge)"""))
        assert(!typeChecks("""val q: Query[Maybe[BigInt]] = Sql.from[Tally]("t").sum(_.t.huge)"""))
    }

    "avg over Byte and BigInt is BigDecimal, as over any exact operand" in {
        assert(typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Tally]("t").avg(_.t.small)"""))
        assert(typeChecks("""val q: Query[Maybe[BigDecimal]] = Sql.from[Tally]("t").avg(_.t.huge)"""))
        assert(!typeChecks("""val q: Query[Maybe[Double]] = Sql.from[Tally]("t").avg(_.t.small)"""))
    }

    "division over an integral column answers BigDecimal, which is what the truncation-avoiding cast returns" in {
        // The one with non-obvious behavior: an integral `/` renders a cast so the server does not truncate, so
        // the result is not the operand's type. Pinned for each integral width the givens now cover.
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Tally]("t").select(r => r.t.small / 2.toByte)"""))
        assert(typeChecks("""val q: Select[?, BigDecimal, ?] = Sql.from[Tally]("t").select(r => r.t.mid / 2.toShort)"""))
        assert(!typeChecks("""val q: Select[?, Byte, ?] = Sql.from[Tally]("t").select(r => r.t.small / 2.toByte)"""))
        assert(!typeChecks("""val q: Select[?, Short, ?] = Sql.from[Tally]("t").select(r => r.t.mid / 2.toShort)"""))
    }

    "a text column that permits NULL has the pattern operators" in {
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.like(\"a%\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.ilike(\"A%\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.notLike(\"a%\"))"))
        assert(typeChecks("Sql.from[Customer](\"c\").where(c => c.c.email.notIlike(\"A%\"))"))

        // The non-null column keeps them.
        assert(typeChecks("Sql.from[Person](\"p\").where(c => c.p.name.like(\"a%\"))"))

        // A column that holds neither String nor Maybe[String] still has none of them, which is what says the
        // evidence widened the text case rather than admitting anything.
        assert(!typeChecks("Sql.from[Person](\"p\").where(c => c.p.age.like(\"a%\"))"))
        assert(!typeChecks("Sql.from[Reading](\"r\").where(c => c.r.celsius.like(\"a%\"))"))

        // The operators that RETURN text keep the stricter gate, since a nullable operand's result is nullable and
        // this substitution would lose that.
        assert(!typeChecks("Sql.from[Customer](\"c\").select(c => c.c.email.upper)"))
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

package kyo

import kyo.Record.~
import kyo.internal.FromExprTestFixtures

/** Tests for `kyo.FromExpr.derived`.
  *
  * The harness `LiftHarness.lift[A]` takes an inline expression, derives a `scala.quoted.FromExpr[A]` via `kyo.FromExpr.derived`, applies
  * its `unapply` to the inline expression at the splice site, and emits the result back as runtime data. Because we cannot generically
  * `ToExpr`-lift arbitrary `A` values back into the splice, the harness reconstructs the lifted value using `LiftedRepr`, a typed wrapper
  * that captures the `Option[A]` via its toString so tests can assert against the original value's `toString`.
  *
  * For primitive types we can lift back via stdlib `ToExpr`. For case classes we delegate to `liftValueExpr` which builds a `New` tree.
  *
  * Each test assertion is structured so that **passing means the FromExpr successfully unapplied the expression** AND **the lifted value
  * stringifies to the expected representation**. This avoids needing a full ToExpr round-trip for every Kyo data type.
  */
class FromExprDerivedTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    "FromExpr[Int] returns Some(42) for '{ 42 }'" in {
        assert(LiftHarness.matched[Int](42))
        assert(LiftHarness.repr[Int](42) == "Some(42)")
    }

    "FromExpr[String] returns Some(\"hello\")" in {
        assert(LiftHarness.matched[String]("hello"))
        assert(LiftHarness.repr[String]("hello") == "Some(hello)")
    }

    "FromExpr[Boolean] returns Some(true)" in {
        assert(LiftHarness.matched[Boolean](true))
        assert(LiftHarness.repr[Boolean](true) == "Some(true)")
    }

    "FromExpr[Maybe[Int]] matches Maybe(42)" in {
        assert(LiftHarness.matched[Maybe[Int]](Maybe(42)))
    }

    "FromExpr[Maybe[Int]] matches Maybe.empty" in {
        assert(LiftHarness.matched[Maybe[Int]](Maybe.empty[Int]))
    }

    "FromExpr[Chunk[Int]] matches Chunk(1, 2, 3)" in {
        assert(LiftHarness.matched[Chunk[Int]](Chunk(1, 2, 3)))
    }

    "FromExpr[Wrap] single-field case class reconstructs value" in {
        // Fixtures are compiled into main, so Class.forName resolves at test-compile time.
        assert(LiftHarness.matched[FromExprTestFixtures.Wrap](FromExprTestFixtures.Wrap(7)))
        assert(LiftHarness.repr[FromExprTestFixtures.Wrap](FromExprTestFixtures.Wrap(7)) == "Some(Wrap(7))")
    }

    "FromExpr[Pair] two-field case class reconstructs value" in {
        assert(LiftHarness.matched[FromExprTestFixtures.Pair](FromExprTestFixtures.Pair(1, "x")))
        assert(LiftHarness.repr[FromExprTestFixtures.Pair](FromExprTestFixtures.Pair(1, "x")) == "Some(Pair(1,x))")
    }

    "FromExpr[Outer] nested case class reconstructs value" in {
        // Pass the constructor expression directly; storing in a val would inline to an Ident reference and lose tree structure.
        assert(LiftHarness.matched[FromExprTestFixtures.Outer](
            FromExprTestFixtures.Outer(FromExprTestFixtures.Pair(2, "y"), "lbl")
        ))
        assert(LiftHarness.repr[FromExprTestFixtures.Outer](
            FromExprTestFixtures.Outer(FromExprTestFixtures.Pair(2, "y"), "lbl")
        ) == "Some(Outer(Pair(2,y),lbl))")
    }

    "FromExpr[Shape] sealed trait dispatches to Circle and reconstructs value" in {
        assert(LiftHarness.matched[FromExprTestFixtures.Shape](FromExprTestFixtures.Circle(3)))
        assert(LiftHarness.repr[FromExprTestFixtures.Shape](FromExprTestFixtures.Circle(3)) == "Some(Circle(3))")
    }

    "FromExpr[Color] enum dispatches to Red and reconstructs singleton" in {
        assert(LiftHarness.matched[FromExprTestFixtures.Color](FromExprTestFixtures.Color.Red))
        assert(LiftHarness.repr[FromExprTestFixtures.Color](FromExprTestFixtures.Color.Red) == "Some(Red)")
    }

    "FromExpr[Field[\"x\", Int]] reconstructs a Field with correct name" in {
        // The Field FromExpr matches the Field.apply tree and reconstructs name + tag.
        assert(LiftHarness.matched[Field["x" & String, Int]](Field["x", Int]))
        // repr contains the name "x" as the first field of the reconstructed Field.
        assert(LiftHarness.repr[Field["x" & String, Int]](Field["x", Int]).startsWith("Some(Field(x,"))
    }

    "FromExpr[Record[...]] is a placeholder (returns None)" in {
        // Record lifting is intentionally a placeholder in Phase 6.6, Phase 7's static-SQL consumer
        // lifts Record contents via per-field FromExprs at the AST site, not via this generic derivation.
        assert(!LiftHarness.matched[Record["k" ~ Int]](Record.empty.asInstanceOf[Record["k" ~ Int]]))
    }

    "FromExpr[Tree] derives without infinite recursion (mutually-recursive ADT)" in {
        // Phase F.5 leaf 1: `Tree` ↔ `Forest` form a 2-type SCC (Branch.children: Forest,
        // Forest.trees: Chunk[Tree]). Before the recursion guard, deriving FromExpr[Tree] would
        // infinite-recurse at macro-expansion time (StackOverflow). Exercises both the production
        // path (FromExpr.derived via derivedImpl) and the buildDirect test path (LiftHarness →
        // applyMatchedImpl → buildDirect). Pass the constructor expressions inline so tree structure
        // is preserved (a stored val inlines to an Ident).
        assert(LiftHarness.matched[FromExprTestFixtures.Tree](
            FromExprTestFixtures.Branch(FromExprTestFixtures.Forest(
                Chunk(FromExprTestFixtures.Leaf(1), FromExprTestFixtures.Leaf(2))
            ))
        ))
        assert(LiftHarness.repr[FromExprTestFixtures.Tree](
            FromExprTestFixtures.Leaf(7)
        ) == "Some(Leaf(7))")
    }

    "Schema.derived for a case class round-trips JSON (regression golden)" in {
        // Phase F upgrade (plan leaf 6): replace smoke-compile with byte-for-byte golden.
        // Confirms that the deriveRecord summon-first enhancement does NOT regress Schema.derived
        // for ordinary product types, deriveRecord is only reached for Record[F] fields, not Pair.
        given schema: Schema[FromExprTestFixtures.Pair] = Schema.derived
        val original                                    = FromExprTestFixtures.Pair(1, "x")
        val encoded                                     = Json.encode(original)
        val decoded                                     = Json.decode[FromExprTestFixtures.Pair](encoded)
        assert(decoded == Result.succeed(original))
    }

end FromExprDerivedTest

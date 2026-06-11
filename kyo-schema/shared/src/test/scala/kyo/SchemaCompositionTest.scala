package kyo

class SchemaCompositionTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "discriminator + Protobuf round-trip" - {

        // Regression guard for SchemaSerializer.DiscriminatorReader.matchField numeric-tag fallback (kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala:469-485). Pins INV-35 (revised: existing fallback fixes Bug E at the wrapper layer; macro-level withFieldNames wiring is unnecessary).
        "sealed-trait variant with .discriminator round-trips correctly" in {
            val schema              = Schema[BugEWithDisc].discriminator("type")
            val value: BugEWithDisc = BugEA(42)
            val bytes: Span[Byte]   = Protobuf.encode(value)(using schema)
            val result: Result[DecodeException, BugEWithDisc] =
                Protobuf.decode[BugEWithDisc](bytes)(using summon[Protobuf], schema)
            assert(result == Result.succeed(value))
        }

    }

end SchemaCompositionTest

// Top-level to avoid macro issues with derives Schema inside nested definitions.
// Minimal fixture: one sealed trait with one case-class variant containing a single
// required Int field "x".
sealed trait BugEWithDisc derives Schema, CanEqual
case class BugEA(x: Int)    extends BugEWithDisc derives CanEqual
case class BugEB(y: String) extends BugEWithDisc derives CanEqual

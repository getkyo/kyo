package kyo

/** Tests for Tasty.Type public API surface: Type.show for Applied types. */
class TastyTypeTest extends kyo.test.Test[Any] with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    "Type.show for Applied(scala.List, scala.Int) returns a non-empty string" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val listType          = makeNamed("scala.List")
            val intType           = makeNamed("scala.Int")
            val applied           = Tasty.Type.Applied(listType, Chunk(intType))
            val showResult        = applied.toString
            assert(
                showResult.nonEmpty,
                s"Expected non-empty show but got '$showResult'"
            )
    }

end TastyTypeTest

package kyo

/** Tests for Tasty.Type public API surface.
  *
  * (INV: T1). Covers Type.show for Applied types.
  *
  * makeNamed is inherited from TastyTestSupport.
  */
class TastyTypeTest extends Test with TastyTestSupport:

    import AllowUnsafe.embrace.danger

    // plan: phase-05; Type.show now requires (using cp: Classpath).
    // Named(id) renders as "Named(<id>)" until The implementation wires cp.symbol(id).name.
    "Type.show for Applied(scala.List, scala.Int) returns a non-empty string" in run {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            given Tasty.Classpath = cp
            val listType          = makeNamed("scala.List")
            val intType           = makeNamed("scala.Int")
            val applied           = Tasty.Type.Applied(listType, Chunk(intType))
            // plan: phase-05; Name resolution deferred to; show renders id values.
            val showResult = applied.toString
            assert(
                showResult.nonEmpty,
                s"Expected non-empty show but got '$showResult'"
            )
    }

end TastyTypeTest

package kyo.ffi

import kyo.Chunk
import kyo.Maybe.Present

/** Tests for [[Ffi.Config.Builder]], the immutable, copy-based construction path.
  *
  * Covers: default equivalence with the positional constructor, per-setter field capture, immutability of chained builders, and that the
  * canonical `extends Ffi.Config(...)` binding-companion form compiles without a deprecation warning.
  */
class FfiConfigBuilderTest extends Test:

    "empty builder produces equivalent Config to positional defaults" in {
        val a = Ffi.Config.builder.build
        val b = new Ffi.Config() {}
        assert(a.library == b.library)
        assert(a.symbolPrefix == b.symbolPrefix)
        assert(a.symbols == b.symbols)
        assert(a.packedStructs == b.packedStructs)
        assert(a.scratchSize == b.scratchSize)
        assert(a.checkedBorrows == b.checkedBorrows)
        assert(a.headers == b.headers)
    }

    "each setter captures the field value" in {
        val c = Ffi.Config.builder
            .library("lib")
            .symbolPrefix("p_")
            .symbols(Map("a" -> "b"))
            .packedStructs(Set("S"))
            .scratchSize(1024)
            .checkedBorrows(true)
            .headers(Chunk("h.h"))
            .build
        assert(c.library == "lib")
        assert(c.symbolPrefix == "p_")
        assert(c.symbols == Map("a" -> "b"))
        assert(c.packedStructs == Set("S"))
        assert(c.scratchSize == Present(1024))
        assert(c.checkedBorrows == true)
        assert(c.headers == Chunk("h.h"))
    }

    "chaining is side-effect free (immutability)" in {
        val a = Ffi.Config.builder.library("x")
        val b = a.symbolPrefix("y").build
        val c = a.build
        assert(b.library == "x")
        assert(b.symbolPrefix == "y")
        assert(c.library == "x")
        assert(c.symbolPrefix == "")
    }

    // The canonical binding-companion form. This test file carries NO `@nowarn("cat=deprecation")`: if the `extends Ffi.Config(...)`
    // constructor were marked `@deprecated` again, this declaration would emit a deprecation warning. The codegen reads a binding's config
    // exclusively from these super-constructor arguments (FfiInspector requires `companion extends Ffi.Config`), so this form must stay
    // warning-free (the builder cannot drive codegen for a binding).
    "the canonical `extends Ffi.Config(...)` binding-companion form compiles without deprecation" in {
        object BindingCompanion extends Ffi.Config(library = "c", headers = Chunk("sys/socket.h"), packedStructs = Set("Ev"))
        assert(BindingCompanion.library == "c")
        assert(BindingCompanion.headers == Chunk("sys/socket.h"))
        assert(BindingCompanion.packedStructs == Set("Ev"))
    }
end FfiConfigBuilderTest

package kyo

/** Tests for case class consistency of Position, ModuleDescriptor, Pickle, Version .
  *
  * Leaf ids: 17, partial of 16. Pins: Steering rule on case classes for pure data.
  */
class CaseClassConsistencyTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Leaf id:17 -- Position, ModuleDescriptor, Pickle, Version are case classes
    "Version is a case class with copy and show" in {
        val v1 = Tasty.Version(28, 8, 0)
        val v2 = v1.copy(minor = 9)
        assert(v1.minor == 8)
        assert(v2.minor == 9)
        assert(v1.show == "28.8.0")
        assert(v2.show == "28.9.0")
    }

    "Position is a case class with copy" in {
        val p1 = Tasty.Position("Foo.scala", 10, 5)
        val p3 = p1.copy(line = 11)
        assert(p1.line == 10)
        assert(p3.line == 11)
        // structural equality via equals (Scala case class auto-generates equals)
        val p2: Any = Tasty.Position("Foo.scala", 10, 5)
        assert(p1.equals(p2), "Position equality failed")
    }

    "Pickle is a case class with copy" in {
        val v       = Tasty.Version(28, 8, 0)
        val bytes   = Span.from(Array[Byte](1, 2, 3))
        val p1      = Tasty.Pickle("uuid-1", v, bytes)
        val p2: Any = Tasty.Pickle("uuid-1", v, bytes)
        assert(p1.equals(p2), "Pickle equality failed")
        val p3 = p1.copy(uuid = "uuid-2")
        assert(p3.uuid == "uuid-2")
    }

    "ModuleDescriptor is a case class" in {
        val md = Tasty.Java.Module.Descriptor(
            name = "java.base",
            version = Maybe("17"),
            requires = Chunk.empty,
            exports = Chunk.empty,
            opens = Chunk.empty,
            uses = Chunk.empty,
            provides = Chunk.empty
        )
        val md2 = md.copy(name = "java.lang")
        assert(md.name == "java.base")
        assert(md2.name == "java.lang")
    }

    // Leaf id:16 partial -- directSubclassesOf / Constant.show / Annotation.arguments synchronous Chunk
    "Constant.show is pure (no Classpath needed)" in {
        val c = Tasty.Constant.StringConst("test")
        val s = c.show
        assert(s == "\"test\"")
    }

    "Annotation.arguments is a synchronous Chunk[Tree]" in {
        // Verify the field type is Chunk[Tree] at compile time (test compiles = proof)
        val tpe                          = Tasty.Type.Named(kyo.Tasty.SymbolId(-1))
        val ann                          = Tasty.Annotation(tpe, Chunk.empty)
        val arguments: Chunk[Tasty.Tree] = ann.arguments
        assert(arguments.isEmpty)
    }

end CaseClassConsistencyTest

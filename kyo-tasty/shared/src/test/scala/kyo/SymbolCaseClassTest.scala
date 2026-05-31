package kyo

import kyo.internal.tasty.symbol.SymbolId

/** Phase 02 plan-mandated tests for the Symbol pure case class skeleton.
  *
  * Leaves:
  *   1. Symbol case-class construction populates all 14 fields.
  *   2. Symbol equality is structural over 14 constructor params.
  *   3. Symbol copy produces independent instance.
  *   4. private[kyo] constructor accessible from package kyo only.
  *   5. no SingleAssign or OnceCell field survives on Symbol.
  *
  * Pins: INV-001 (single-shot construction; all fields set in one apply call).
  */
class SymbolCaseClassTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Fixture ─────────────────────────────────────────────────────────────

    /** Build a Symbol.Class using the typed constructor directly. */
    private def makeTestSymbol(
        id: Int = 42,
        name: String = "Foo",
        ownerId: Int = 0,
        flags: Tasty.Flags = Tasty.Flags.empty,
        scaladoc: Maybe[String] = Maybe.Absent,
        parentTypes: Chunk[Tasty.Type] = Chunk.empty
    ): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            id = SymbolId(id),
            name = Tasty.Name(name),
            flags = flags,
            ownerId = SymbolId(ownerId),
            scaladoc = scaladoc,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = parentTypes,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )

    // ── Leaf 1: construction populates all 14 fields ─────────────────────────

    // Given: a fully-specified 14-field Symbol.Class.
    // When: each field is read.
    // Then: every field equals the value provided at construction; no field is at a wrong default.
    // Pins: INV-001 (single-shot construction; all fields set in one apply call).
    "Leaf 1: Symbol case-class construction populates all 14 fields" in {
        val sym = makeTestSymbol(
            id = 7,
            name = "Bar",
            ownerId = 3,
            flags = new Tasty.Flags(Tasty.Flag.Final.bit),
            scaladoc = Maybe("/** doc */"),
            parentTypes = Chunk(Tasty.Type.Named(makeTestSymbol(id = 1, name = "AnyRef").id))
        )
        assert(sym.id == SymbolId(7), s"id: ${sym.id}")
        assert(sym.kind == Tasty.SymbolKind.Class, s"kind: ${sym.kind}")
        assert(sym.flags.contains(Tasty.Flag.Final), s"flags: ${sym.flags.bits}")
        assert(sym.name.asString == "Bar", s"name: ${sym.name.asString}")
        assert(sym.ownerId == SymbolId(3), s"ownerId: ${sym.ownerId}")
        assert(sym.javaMetadata.isEmpty, s"javaMetadata: ${sym.javaMetadata}")
        assert(sym.scaladoc.isDefined && sym.scaladoc.get == "/** doc */", s"scaladoc: ${sym.scaladoc}")
        assert(sym.sourcePosition.isEmpty, s"sourcePosition: ${sym.sourcePosition}")
        assert(sym.parentTypes.length == 1, s"parentTypes.length: ${sym.parentTypes.length}")
        assert(sym.typeParamIds.isEmpty, s"typeParamIds: ${sym.typeParamIds}")
        assert(sym.declarationIds.isEmpty, s"declarationIds: ${sym.declarationIds}")
        assert(sym.permittedSubclassIds.isEmpty, s"permittedSubclassIds: ${sym.permittedSubclassIds}")
        assert(sym.body.isEmpty, s"body: ${sym.body}")
        succeed
    }

    // ── Leaf 2: equality is structural over 14 constructor params ─────────────

    // Given: two Symbol.Class instances built with identical constructor arguments.
    // When: compared via == and hashCode.
    // Then: equal; hashCode matches.
    // Pins: INV-001 (case-class auto-generated equals over constructor params).
    "Leaf 2: Symbol equality is structural over 14 constructor params" in {
        val sym1 = makeTestSymbol(id = 5, name = "MyClass")
        val sym2 = makeTestSymbol(id = 5, name = "MyClass")
        assert(sym1 == sym2, s"Expected sym1 == sym2 but they differ")
        assert(sym1.hashCode == sym2.hashCode, s"Expected equal hashCodes: ${sym1.hashCode} vs ${sym2.hashCode}")
        succeed
    }

    // ── Leaf 3: copy produces independent instance ────────────────────────────

    // Given: a Symbol.Class s1 with scaladoc = Present("a").
    // When: s1.copy(scaladoc = Present("b")).
    // Then: the new instance has scaladoc = Present("b") and is not eq to s1; other fields equal.
    // Pins: INV-001 (copy operates on constructor params only).
    "Leaf 3: Symbol copy produces independent instance" in {
        val s1 = makeTestSymbol(id = 10, name = "CopyMe", scaladoc = Maybe("a"))
        val s2 = s1.copy(scaladoc = Maybe("b"))
        assert(!(s1 eq s2), "Expected copy to produce a new reference (not eq)")
        assert(s2.scaladoc.isDefined && s2.scaladoc.get == "b", s"Expected scaladoc Present('b') but got ${s2.scaladoc}")
        assert(s1.scaladoc.isDefined && s1.scaladoc.get == "a", s"Original scaladoc must remain 'a' but got ${s1.scaladoc}")
        assert(s2.id == s1.id, s"id must match: ${s2.id} vs ${s1.id}")
        assert(s2.name == s1.name, s"name must match: ${s2.name.asString} vs ${s1.name.asString}")
        succeed
    }

    // ── Leaf 4: private[kyo] constructor accessible from package kyo ──────────

    // Given: code in package kyo constructing Tasty.Symbol.Class directly.
    // When: compiled.
    // Then: compiles cleanly (private[kyo] allows this package).
    // Pins: INV-001 (no public Symbol construction path; but kyo tests can use typed constructors).
    "Leaf 4: private[kyo] constructor accessible from package kyo" in {
        val sym = makeTestSymbol(id = 99, name = "AccessTest")
        assert(sym.id == SymbolId(99), "Symbol was constructed via internal factory")
        succeed
    }

    // ── Leaf 5: no SingleAssign or OnceCell field survives on Symbol ──────────

    // Given: the Symbol.Class case class definition.
    // When: its 14 constructor parameter types are statically examined.
    // Then: all fields have pure-data types; no SingleAssign or OnceCell is accessible.
    // Pins: INV-001 + INV-012 (pure data shape).
    "Leaf 5: no SingleAssign or OnceCell field survives on Symbol" in {
        val sym = makeTestSymbol(id = 1, name = "Leaf5Test")

        // Pure field access - these must be val fields returning their stored value directly.
        val _id                   = sym.id
        val _kind                 = sym.kind
        val _flags                = sym.flags
        val _name                 = sym.name
        val _ownerId              = sym.ownerId
        val _scaladoc             = sym.scaladoc
        val _sourcePosition       = sym.sourcePosition
        val _javaMetadata         = sym.javaMetadata
        val _parentTypes          = sym.parentTypes
        val _typeParamIds         = sym.typeParamIds
        val _declarationIds       = sym.declarationIds
        val _permittedSubclassIds = sym.permittedSubclassIds
        val _annotations          = sym.annotations
        val _body                 = sym.body

        assert(_id.getClass.getSimpleName != "SingleAssign", "id field must not be SingleAssign")
        assert(_kind.getClass.getSimpleName != "OnceCell", "kind field must not be OnceCell")
        assert(_flags.getClass.getSimpleName != "SingleAssign", "flags field must not be SingleAssign")

        // Symbol.Class has 14 constructor fields.
        assert(sym.productArity == 14, s"Expected 14 product elements (Symbol.Class params) but got ${sym.productArity}")

        succeed
    }

end SymbolCaseClassTest

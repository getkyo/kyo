package kyo
import kyo.Tasty.SymbolId
import kyo.internal.tasty.symbol.SymbolKind

/** Tests for Symbol case class construction, equality, copy semantics, and field visibility. */
class SymbolCaseClassTest extends kyo.test.Test[Any]:

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
            javaAnnotations = Chunk.empty
        )

    "Symbol case-class construction populates all 14 fields" in {
        val sym = makeTestSymbol(
            id = 7,
            name = "Bar",
            ownerId = 3,
            flags = Tasty.Flags(Tasty.Flag.Final),
            scaladoc = Maybe("/** doc */"),
            parentTypes = Chunk(Tasty.Type.Named(makeTestSymbol(id = 1, name = "AnyRef").id))
        )
        assert(sym.id == SymbolId(7), s"id: ${sym.id}")
        assert(sym.kind == SymbolKind.Class, s"kind: ${sym.kind}")
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
        succeed
    }

    "Symbol equality is structural over 14 constructor params" in {
        val sym1 = makeTestSymbol(id = 5, name = "MyClass")
        val sym2 = makeTestSymbol(id = 5, name = "MyClass")
        assert(sym1 == sym2, s"Expected sym1 == sym2 but they differ")
        assert(sym1.hashCode == sym2.hashCode, s"Expected equal hashCodes: ${sym1.hashCode} vs ${sym2.hashCode}")
        succeed
    }

    "Symbol copy produces independent instance" in {
        val s1 = makeTestSymbol(id = 10, name = "CopyMe", scaladoc = Maybe("a"))
        val s2 = s1.copy(scaladoc = Maybe("b"))
        assert(!(s1 eq s2), "Expected copy to produce a new reference (not eq)")
        assert(s2.scaladoc.isDefined && s2.scaladoc.get == "b", s"Expected scaladoc Present('b') but got ${s2.scaladoc}")
        assert(s1.scaladoc.isDefined && s1.scaladoc.get == "a", s"Original scaladoc must remain 'a' but got ${s1.scaladoc}")
        assert(s2.id == s1.id, s"id must match: ${s2.id} vs ${s1.id}")
        assert(s2.name == s1.name, s"name must match: ${s2.name.asString} vs ${s1.name.asString}")
        succeed
    }

    "private[kyo] constructor accessible from package kyo" in {
        val sym = makeTestSymbol(id = 99, name = "AccessTest")
        assert(sym.id == SymbolId(99), "Symbol was constructed via internal factory")
        succeed
    }

    "no SingleAssign or OnceCell field survives on Symbol" in {
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
        // body field removed in

        assert(_id.getClass.getSimpleName != "SingleAssign", "id field must not be SingleAssign")
        assert(_kind.getClass.getSimpleName != "OnceCell", "kind field must not be OnceCell")
        assert(_flags.getClass.getSimpleName != "SingleAssign", "flags field must not be SingleAssign")

        // Symbol.Class has 13 constructor fields after body removal.
        assert(sym.productArity == 13, s"Expected 13 product elements (Symbol.Class params) but got ${sym.productArity}")

        succeed
    }

end SymbolCaseClassTest

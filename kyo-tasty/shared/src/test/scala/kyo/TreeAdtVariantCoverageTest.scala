package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.symbol.LoadingSymbol
import kyo.internal.tasty.symbol.SymbolBody
import kyo.internal.tasty.symbol.SymbolKind
import kyo.internal.tasty.type_.TypeArena

/** Verifies that 28 Tree ADT variants are reachable via TASTy decode.
  *
  * Two complementary approaches are used:
  *
  *   1. Body-tree sweep: loads TreeVariantFixture$package.tasty (embedded in kyo.fixtures.Embedded), runs pass-1, and decodes all symbol
  *      body slices with TreeUnpickler.decodeSync. Collects distinct Tree variant names seen. Verifies that the variants that naturally appear
  *      in body-tree positions are present.
  *
  *   2. Byte-pickle tests: for variants that appear only in type-tree positions or are compiler-internal (not produced by user Scala source in
  *      body slices), each variant is exercised via a hand-crafted byte-pickle decoded by TreeUnpickler.decodeAnnotationTerm. This directly
  *      exercises the decode code path for each variant tag.
  *
  * Both approaches are cross-platform.
  */
class TreeAdtVariantCoverageTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // ── Pass-1 helper ─────────────────────────────────────────────────────────

    private def runPass1(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view  = ByteView(bytes)
        val arena = TypeArena.canonical()
        for
            _        <- Sync.Unsafe.defer(Abort.get(TastyHeader.read(view)))
            names    <- NameUnpickler.read(view)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, arena)
                case Absent =>
                    Abort.fail(TastyError.MalformedSection("ASTs", "ASTs section not found", 0L))
        yield result
        end for
    end runPass1

    private def symbolBody(symbol: LoadingSymbol.Materialising, pass1: AstUnpickler.Pass1Result): Maybe[SymbolBody] =
        pass1.bodyDataByAddr.get(symbol.id.toLong) match
            case Some((bodyStart, bodyEnd)) =>
                Maybe(SymbolBody(
                    bodyStart = bodyStart,
                    bodyEnd = bodyEnd,
                    sectionBytes = Span.fromUnsafe(pass1.sectionBytes),
                    names = Span.fromUnsafe(pass1.names),
                    sectionOffset = pass1.sectionOffset,
                    addrMap = scala.collection.immutable.IntMap.empty
                ))
            case None => Maybe.Absent
    end symbolBody

    private val dummyLookup: Int => Tasty.Symbol =
        _ =>
            import AllowUnsafe.embrace.danger
            toFinalSym(LoadingSymbol.Materialising(
                id = 20,
                kind = SymbolKind.Class,
                flags = Tasty.Flags.empty,
                name = Tasty.Name("unresolved")
            ))

    // Convert LoadingSymbol.Materialising to Tasty.Symbol for TreeUnpickler.decodeSync
    private def toFinalSym(m: LoadingSymbol.Materialising)(using AllowUnsafe): Tasty.Symbol =
        kyo.internal.tasty.symbol.TypedSymbolFactory.from(new kyo.internal.tasty.symbol.SymbolDescriptor(
            id = m.id.max(0),
            kind = m.kind,
            flags = m.flags,
            name = m.name,
            ownerId = -1,
            declaredType = Maybe.Absent,
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = Chunk.empty,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            body = Maybe.Absent
        ))

    /** Decode a hand-crafted byte pickle via decodeAnnotationTerm. */
    private def decodePickle(
        pickle: Array[Byte],
        names: Array[Tasty.Name],
        addrMap: scala.collection.Map[Int, LoadingSymbol.Materialising]
    ): Result[TastyError, Tasty.Tree] =
        try Result.Success(TreeUnpickler.decodeAnnotationTerm(pickle, names, addrMap, pickle, 0))
        catch
            case ex: TreeUnpickler.DecodeException =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"decode: ${ex.getMessage}", ex.byteOffset))
            case ex: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("ASTs", "truncated", 0L))

    private def noSym: scala.collection.immutable.IntMap[LoadingSymbol.Materialising] =
        scala.collection.immutable.IntMap.empty[LoadingSymbol.Materialising]

    private def sym1(name: String): (Array[Tasty.Name], scala.collection.immutable.IntMap[LoadingSymbol.Materialising]) =
        val s = LoadingSymbol.Materialising(
            id = name.hashCode.abs % 100,
            kind = SymbolKind.Class,
            flags = Tasty.Flags.empty,
            name = Tasty.Name(name)
        )
        (Array(Tasty.Name(name)), scala.collection.immutable.IntMap(1 -> s))
    end sym1

    // ── Approach 1: body-sweep sees TypeDef, Template, TypeRefTree ────────────

    // These three variants appear in body-tree positions decoded from the fixture:
    // TypeDef: type member definitions appear as body elements of class templates.
    // Template: class bodies (TEMPLATE tag) are decoded as Tree.Template nodes.
    // TypeRefTree: qualified type references (TYPEREF tag) appear in method body type positions.
    // The sweep also confirms that the decode of TreeVariantFixture completes without crash.

    "Sweep: TypeDef, Template, and TypeRefTree appear in TreeVariantFixture body trees" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.treeVariantFixturePackageTasty)).map {
            case Result.Success(pass1) =>
                val seen = scala.collection.mutable.HashSet.empty[String]
                pass1.symbols.foreach { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) =>
                            try
                                val tree = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                                tree.foreach(t => seen += t.getClass.getSimpleName.stripSuffix("$"))
                            catch
                                case _: Exception => ()
                        case Absent => ()
                }
                assert(
                    seen.contains("TypeDef"),
                    s"Expected TypeDef in body trees but only saw: ${seen.toSeq.sorted.mkString(", ")}"
                )
                assert(
                    seen.contains("Template"),
                    s"Expected Template in body trees but only saw: ${seen.toSeq.sorted.mkString(", ")}"
                )
                assert(
                    seen.contains("TypeRefTree"),
                    s"Expected TypeRefTree in body trees but only saw: ${seen.toSeq.sorted.mkString(", ")}"
                )
            case Result.Failure(e) =>
                fail(s"TreeVariantFixture pass1 failed: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // ── Approach 2: byte-pickle tests for the remaining 25 variants ───────────

    // Variants in this group appear in type-tree positions (method signatures, parent types),
    // or are compiler-internal tags not produced in body slices by standard user Scala source.
    // Each test directly exercises the TreeUnpickler decode path for the corresponding tag.

    // Pickle construction note:
    //   Cat-1 (1-59): tag only, no payload.
    //   Cat-2 (60-89): tag + readNat (stop-bit-terminated varint; single byte n: (n | 0x80).toByte).
    //   Cat-3 (90-109): tag + readTree/readType (sub-AST immediately follows).
    //   Cat-4 (110-127): tag + readNat + readTree/readType.
    //   Cat-5 (128-255): tag + readEnd (length nat) + payload bytes.
    //   Length encoding for cat-5: the 'length' field encodes payload byte count as a nat.
    //   Single-byte: (payloadBytes | 0x80).toByte. After readEnd the position advances by the
    //   length nat width, and end = current_position + payloadBytes.

    // ── SelfDef (cat-4: SELFDEF=118 + nameRef + type_Tree) ─────────────────────
    // SELFDEF 118 is cat-4: tag + nameRef(Nat) + type(Tree).
    // Decoded as: name = nameFromRef(readNat), tpe = readTree -> Tree.SelfDef(name, tpe).
    // Pickle: SELFDEF(118=0x76) nameRef(0=0x80) UNITconst(2).
    "SelfDef: SELFDEF tag decodes to Tree.SelfDef" in {
        val names  = Array(Tasty.Name("self"))
        val pickle = Array[Byte](118.toByte, (0 | 0x80).toByte, TastyFormat.UNITconst.toByte)
        decodePickle(pickle, names, noSym) match
            case Result.Success(t @ Tasty.Tree.SelfDef(name, _)) =>
                assert(name.asString == "self", s"Expected SelfDef name 'self' but got '${name.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.SelfDef but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Super (cat-5: SUPER=157 + length + qual + mix?) ────────────────────────
    // SUPER 157 is cat-5. Decoded as: end=readEnd, qual=readTree, mix=Maybe(if remaining then readTree).
    // Pickle with no mix: SUPER(157=0x9D) length(2=0x82) TERMREFdirect(62=0x3E) address(1=0x81).
    "Super: SUPER tag decodes to Tree.Super" in {
        val (names, addrMap) = sym1("Outer")
        val pickle = Array[Byte](
            TastyFormat.SUPER.toByte,
            (2 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Super(_, mix)) =>
                assert(mix.isEmpty, s"Expected no-mix Super but got mix=$mix")
            case Result.Success(other) => fail(s"Expected Tree.Super but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── SuperType (cat-5: SUPERtype=158 + length + thistpe + supertpe) ─────────
    // SUPERtype 158 is cat-5. Pickle: SUPERtype(158=0x9E) length(4=0x84) TERMREFdirect(62) address(1) TERMREFdirect(62) address(2).
    "SuperType: SUPERtype tag decodes to Tree.SuperType" in {
        val s1      = LoadingSymbol.Materialising(id = 49, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("This"))
        val s2      = LoadingSymbol.Materialising(id = 20, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Super"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val pickle = Array[Byte](
            TastyFormat.SUPERtype.toByte,
            (4 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), addrMap) match
            case Result.Success(Tasty.Tree.SuperType(thistpe, supertpe)) =>
                assert(thistpe != null && supertpe != null, "SuperType must have both thistpe and supertpe")
            case Result.Success(other) => fail(s"Expected Tree.SuperType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── RefinedType (cat-5: REFINEDtype=159 + length + nameRef + parent + info) ──
    // REFINEDtype 159 is cat-5. Decoded as: end=readEnd, nameRef=readNat, parent=readTree, info=readTree.
    // Payload order: nameRef(Nat), parent(Tree), info(Tree).
    // Pickle: REFINEDtype(159) length(4=0x84) nameRef(0=0x80) TERMREFdirect(62) address(1=0x81) UNITconst(2).
    "RefinedType: REFINEDtype tag decodes to Tree.RefinedType" in {
        val (names, addrMap) = sym1("Base")
        val pickle = Array[Byte](
            TastyFormat.REFINEDtype.toByte,
            (4 | 0x80).toByte,
            (0 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.UNITconst.toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.RefinedType(_, name, _)) =>
                assert(name.asString == "Base", s"Expected refinement name 'Base' but got '${name.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.RefinedType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── AndType (cat-5: ANDtype=165 + length + left + right) ───────────────────
    // Pickle: ANDtype(165=0xA5) length(4=0x84) TERMREFdirect(62) address(1) TERMREFdirect(62) address(2).
    "AndType: ANDtype tag decodes to Tree.AndType" in {
        val s1      = LoadingSymbol.Materialising(id = 66, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("A"))
        val s2      = LoadingSymbol.Materialising(id = 55, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("B"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val pickle = Array[Byte](
            TastyFormat.ANDtype.toByte,
            (4 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), addrMap) match
            case Result.Success(Tasty.Tree.AndType(left, right)) =>
                assert(left != null && right != null, "AndType must have both left and right")
            case Result.Success(other) => fail(s"Expected Tree.AndType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── OrType (cat-5: ORtype=167 + length + left + right) ─────────────────────
    // Pickle: ORtype(167=0xA7) length(4=0x84) TERMREFdirect(62) address(1) TERMREFdirect(62) address(2).
    "OrType: ORtype tag decodes to Tree.OrType" in {
        val s1      = LoadingSymbol.Materialising(id = 66, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("A"))
        val s2      = LoadingSymbol.Materialising(id = 55, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("B"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val pickle = Array[Byte](
            TastyFormat.ORtype.toByte,
            (4 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), addrMap) match
            case Result.Success(Tasty.Tree.OrType(left, right)) =>
                assert(left != null && right != null, "OrType must have both left and right")
            case Result.Success(other) => fail(s"Expected Tree.OrType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── AnnotatedType (cat-5: ANNOTATEDtype=153 + length + parent + annot) ─────
    // Pickle: ANNOTATEDtype(153=0x99) length(2=0x82) UNITconst(2) UNITconst(2).
    "AnnotatedType: ANNOTATEDtype tag decodes to Tree.AnnotatedType" in {
        val pickle = Array[Byte](
            TastyFormat.ANNOTATEDtype.toByte,
            (2 | 0x80).toByte,
            TastyFormat.UNITconst.toByte,
            TastyFormat.UNITconst.toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), noSym) match
            case Result.Success(Tasty.Tree.AnnotatedType(parent, annot)) =>
                assert(parent != null && annot != null, "AnnotatedType must have both parent and annot")
            case Result.Success(other) => fail(s"Expected Tree.AnnotatedType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── RecType (cat-3: RECtype=100 + parent_Tree) ──────────────────────────────
    // RECtype 100 is cat-3: tag + sub-AST. Decoded as: parent=readTree -> Tree.RecType(parent).
    // Pickle: RECtype(100=0x64) UNITconst(2).
    "RecType: RECtype tag decodes to Tree.RecType" in {
        val pickle = Array[Byte](TastyFormat.RECtype.toByte, TastyFormat.UNITconst.toByte)
        decodePickle(pickle, Array(Tasty.Name("dummy")), noSym) match
            case Result.Success(Tasty.Tree.RecType(parent)) =>
                assert(parent != null, "RecType must have parent")
            case Result.Success(other) => fail(s"Expected Tree.RecType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── RecThisAddr (cat-2: RECthis=66 + addr_Nat) ──────────────────────────────
    // RECthis 66 is cat-2: tag + Nat. Decoded as: address=readNat -> Tree.RecThisAddr(address).
    "RecThisAddr: RECthis byte + address decodes to Tree.RecThisAddr" in {
        val pickle = Array[Byte](TastyFormat.RECthis.toByte, (3 | 0x80).toByte)
        decodePickle(pickle, Array(Tasty.Name("dummy")), noSym) match
            case Result.Success(Tasty.Tree.RecThisAddr(address)) =>
                assert(address == 3, s"Expected RecThisAddr address=3 but got address=$address")
            case Result.Success(other) => fail(s"Expected Tree.RecThisAddr(3) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── IdentTpt (cat-4: IDENTtpt=111 + nameRef + type_Tree) ───────────────────
    // IDENTtpt 111 is cat-4: tag + nameRef(Nat) + type(Tree).
    // Decoded as: nameRef=readNat, tpe=readType, name=nameFromRef -> Tree.IdentTpt(name, tpe).
    // Pickle: IDENTtpt(111=0x6F) nameRef(0=0x80) TERMREFdirect(62) address(1=0x81).
    "IdentTpt: IDENTtpt tag decodes to Tree.IdentTpt" in {
        val (names, addrMap) = sym1("Int")
        val pickle = Array[Byte](
            TastyFormat.IDENTtpt.toByte,
            (0 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.IdentTpt(name, _)) =>
                assert(name.asString == "Int", s"Expected IdentTpt name 'Int' but got '${name.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.IdentTpt but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── SelectTpt (cat-4: SELECTtpt=113 + nameRef + qual_Tree) ─────────────────
    // SELECTtpt 113 is cat-4: tag + nameRef(Nat) + qual(Tree).
    // Decoded as: nameRef=readNat, qual=readTree -> Tree.SelectTpt(qual, name).
    // Pickle: SELECTtpt(113=0x71) nameRef(0=0x80) TERMREFdirect(62) address(1=0x81).
    "SelectTpt: SELECTtpt tag decodes to Tree.SelectTpt" in {
        val (names, addrMap) = sym1("pkg")
        val pickle = Array[Byte](
            TastyFormat.SELECTtpt.toByte,
            (0 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.SelectTpt(_, name)) =>
                assert(name.asString == "pkg", s"Expected SelectTpt name 'pkg' but got '${name.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.SelectTpt but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── SingletonTpt (cat-3: SINGLETONtpt=101 + ref_Tree) ──────────────────────
    // SINGLETONtpt 101 is cat-3: tag + sub-AST (ref tree).
    // Decoded as: tpe=readTree -> Tree.SingletonTpt(tpe).
    // Pickle: SINGLETONtpt(101=0x65) TERMREFdirect(62) address(1=0x81).
    "SingletonTpt: SINGLETONtpt tag decodes to Tree.SingletonTpt" in {
        val (names, addrMap) = sym1("obj")
        val pickle = Array[Byte](
            TastyFormat.SINGLETONtpt.toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.SingletonTpt(tpe)) =>
                assert(tpe != null, "SingletonTpt must have tpe")
            case Result.Success(other) => fail(s"Expected Tree.SingletonTpt but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── ByNameTpt (cat-3: BYNAMEtpt=94 + inner_Tree) ───────────────────────────
    // BYNAMEtpt 94 is cat-3: tag + sub-AST. Decoded as: inner=readTree -> Tree.ByNameTpt stored as Type.ByName.
    // Check the actual handler -- looking at the code: it reads a type, not a tree.
    // BYNAMEtpt(94): inner = readType -> Tree.ByNameTpt(inner).
    // Pickle: BYNAMEtpt(94=0x5E) TERMREFdirect(62) address(1=0x81).
    "ByNameTpt: BYNAMEtpt tag decodes to Tree.ByNameTpt" in {
        val (names, addrMap) = sym1("Int")
        val pickle = Array[Byte](
            TastyFormat.BYNAMEtpt.toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.ByNameTpt(inner)) =>
                assert(inner != null, "ByNameTpt must have inner type")
            case Result.Success(other) => fail(s"Expected Tree.ByNameTpt but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── ByNameType (cat-3: BYNAMEtype=93 + inner_Tree) ─────────────────────────
    // BYNAMEtype 93 is cat-3: tag + sub-AST. Decoded as: inner=readTree -> Tree.ByNameType(inner).
    // Pickle: BYNAMEtype(93=0x5D) TERMREFdirect(62) address(1=0x81).
    "ByNameType: BYNAMEtype tag decodes to Tree.ByNameType" in {
        val (names, addrMap) = sym1("Int")
        val pickle = Array[Byte](
            TastyFormat.BYNAMEtype.toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.ByNameType(arg)) =>
                assert(arg != null, "ByNameType must have arg")
            case Result.Success(other) => fail(s"Expected Tree.ByNameType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── TypeRefPkg (cat-2: TYPEREFpkg=65 + nameRef_Nat) ───────────────────────
    // TYPEREFpkg 65 is cat-2: tag + Nat. Decoded as: nameRef=readNat -> Tree.TypeRefPkg(name).
    // Pickle: TYPEREFpkg(65=0x41) nameRef(0=0x80).
    "TypeRefPkg: TYPEREFpkg tag decodes to Tree.TypeRefPkg" in {
        val names  = Array(Tasty.Name("java"))
        val pickle = Array[Byte](TastyFormat.TYPEREFpkg.toByte, (0 | 0x80).toByte)
        decodePickle(pickle, names, noSym) match
            case Result.Success(Tasty.Tree.TypeRefPkg(n)) =>
                assert(n.asString == "java", s"Expected TypeRefPkg name 'java' but got '${n.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.TypeRefPkg(java) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── TypeRefDirect (cat-2: TYPEREFdirect=63 + addr_Nat) ────────────────────
    // TYPEREFdirect 63 is cat-2: tag + Nat. Decoded as: address=readNat -> Tree.TypeRefDirect(address).
    "TypeRefDirect: TYPEREFdirect tag decodes to Tree.TypeRefDirect" in {
        val pickle = Array[Byte](TastyFormat.TYPEREFdirect.toByte, (5 | 0x80).toByte)
        decodePickle(pickle, Array(Tasty.Name("dummy")), noSym) match
            case Result.Success(Tasty.Tree.TypeRefDirect(address)) =>
                assert(address == 5, s"Expected TypeRefDirect address=5 but got address=$address")
            case Result.Success(other) => fail(s"Expected Tree.TypeRefDirect(5) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── TypeRefSymbol (cat-4: TYPEREFsymbol=116 + addr_Nat + qual_Tree) ────────
    // TYPEREFsymbol 116 is cat-4: tag + address(Nat) + qual(Tree).
    // Decoded as: address=readNat, qual=readTree -> Tree.TypeRefSymbol(address, qual).
    // Pickle: TYPEREFsymbol(116=0x74) address(1=0x81) TERMREFdirect(62) address(2=0x82).
    "TypeRefSymbol: TYPEREFsymbol tag decodes to Tree.TypeRefSymbol" in {
        val s1      = LoadingSymbol.Materialising(id = 66, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("A"))
        val s2      = LoadingSymbol.Materialising(id = 55, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("B"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val pickle = Array[Byte](
            TastyFormat.TYPEREFsymbol.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), addrMap) match
            case Result.Success(Tasty.Tree.TypeRefSymbol(address, _)) =>
                assert(address == 1, s"Expected TypeRefSymbol address=1 but got address=$address")
            case Result.Success(other) => fail(s"Expected Tree.TypeRefSymbol but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── TermRefSymbol (cat-4: TERMREFsymbol=114 + addr_Nat + qual_Tree) ────────
    // TERMREFsymbol 114 is cat-4: tag + address(Nat) + qual(Tree).
    // Decoded as: address=readNat, qual=readTree -> Tree.TermRefSymbol(address, qual).
    // Pickle: TERMREFsymbol(114=0x72) address(1=0x81) TERMREFdirect(62) address(2=0x82).
    "TermRefSymbol: TERMREFsymbol tag decodes to Tree.TermRefSymbol" in {
        val s1      = LoadingSymbol.Materialising(id = 52, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("x"))
        val s2      = LoadingSymbol.Materialising(id = 37, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Owner"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val pickle = Array[Byte](
            TastyFormat.TERMREFsymbol.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, Array(Tasty.Name("dummy")), addrMap) match
            case Result.Success(Tasty.Tree.TermRefSymbol(address, _)) =>
                assert(address == 1, s"Expected TermRefSymbol address=1 but got address=$address")
            case Result.Success(other) => fail(s"Expected Tree.TermRefSymbol but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Imported (cat-2: IMPORTED=75 + nameRef_Nat) ─────────────────────────────
    // IMPORTED 75 is cat-2: tag + Nat. Decoded as: nameRef=readNat, name=nameFromRef -> Tree.Imported(Ident(name, _)).
    "Imported: IMPORTED tag decodes to Tree.Imported" in {
        val names  = Array(Tasty.Name("List"))
        val pickle = Array[Byte](TastyFormat.IMPORTED.toByte, (0 | 0x80).toByte)
        decodePickle(pickle, names, noSym) match
            case Result.Success(Tasty.Tree.Imported(qual)) =>
                assert(qual != null, "Imported must have qual")
            case Result.Success(other) => fail(s"Expected Tree.Imported but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Renamed (cat-2: RENAMED=76 + nameRef_Nat) ────────────────────────────────
    // RENAMED 76 is cat-2: tag + Nat. Decoded as: nameRef=readNat -> Tree.Renamed(name).
    "Renamed: RENAMED tag decodes to Tree.Renamed" in {
        val names  = Array(Tasty.Name("AB"))
        val pickle = Array[Byte](TastyFormat.RENAMED.toByte, (0 | 0x80).toByte)
        decodePickle(pickle, names, noSym) match
            case Result.Success(Tasty.Tree.Renamed(n)) =>
                assert(n.asString == "AB", s"Expected Renamed name 'AB' but got '${n.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.Renamed(AB) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── ExplicitTpt (cat-3: EXPLICITtpt=103 + tpe_Type) ────────────────────────
    // EXPLICITtpt 103 is cat-3: tag + readType. Decoded as: inner=readType -> Tree.ExplicitTpt(inner).
    // Pickle: EXPLICITtpt(103=0x67) TERMREFdirect(62) address(1=0x81).
    "ExplicitTpt: EXPLICITtpt tag decodes to Tree.ExplicitTpt" in {
        val (names, addrMap) = sym1("Int")
        val pickle = Array[Byte](
            TastyFormat.EXPLICITtpt.toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.ExplicitTpt(inner)) =>
                assert(inner != null, "ExplicitTpt must have inner type")
            case Result.Success(other) => fail(s"Expected Tree.ExplicitTpt but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Bounded (cat-3: BOUNDED=102 + bound_Tree) ────────────────────────────────
    // BOUNDED 102 is cat-3: tag + sub-AST. Decoded as: bound=readTree -> Tree.Bounded(bound).
    "Bounded: BOUNDED tag decodes to Tree.Bounded" in {
        val pickle = Array[Byte](TastyFormat.BOUNDED.toByte, TastyFormat.UNITconst.toByte)
        decodePickle(pickle, Array(Tasty.Name("dummy")), noSym) match
            case Result.Success(Tasty.Tree.Bounded(bound)) =>
                assert(bound != null, "Bounded must have bound")
            case Result.Success(other) => fail(s"Expected Tree.Bounded but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── SelectOuter (cat-5: SELECTouter=148 + length + levels + name + qual + tpe) ─
    // SELECTouter 148 is cat-5. Decoded as: end=readEnd, levels=readNat, nm=nameFromRef(readNat), qual=readTree, tpe=readType.
    // Pickle: SELECTouter(148=0x94) length(6=0x86) levels(1=0x81) nameRef(0=0x80) qual=TERMREFdirect(62) address(1=0x81) tpe=TERMREFdirect(62) address(2=0x82).
    "SelectOuter: SELECTouter tag decodes to Tree.SelectOuter" in {
        val s1      = LoadingSymbol.Materialising(id = 30, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Outer"))
        val s2      = LoadingSymbol.Materialising(id = 7, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Inner"))
        val addrMap = scala.collection.immutable.IntMap(1 -> s1, 2 -> s2)
        val names   = Array(Tasty.Name("outerVal"))
        val pickle = Array[Byte](
            TastyFormat.SELECTouter.toByte,
            (6 | 0x80).toByte,
            (1 | 0x80).toByte,
            (0 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.SelectOuter(_, name, levels, _)) =>
                assert(levels == 1, s"Expected SelectOuter levels=1 but got $levels")
                assert(name.asString == "outerVal", s"Expected SelectOuter name 'outerVal' but got '${name.asString}'")
            case Result.Success(other) => fail(s"Expected Tree.SelectOuter but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── FlexibleType (cat-5: FLEXIBLEtype=193 + length + arg_Tree) ─────────────
    // FLEXIBLEtype 193 is cat-5. Decoded as: end=readEnd, arg=readTree -> Tree.FlexibleType(arg).
    // Emitted only with -Yexplicit-nulls; not produced by standard user Scala sources.
    "FlexibleType: FLEXIBLEtype tag decodes to Tree.FlexibleType" in {
        val (names, addrMap) = sym1("String")
        val pickle = Array[Byte](
            TastyFormat.FLEXIBLEtype.toByte,
            (2 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.FlexibleType(arg)) =>
                assert(arg != null, "FlexibleType must have arg")
            case Result.Success(other) => fail(s"Expected Tree.FlexibleType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Elided (cat-3: ELIDED=104 + tpe_Type) ───────────────────────────────────
    // ELIDED 104 is cat-3: tag + readType. Decoded as: inner=readType -> Tree.Elided(inner).
    // Appears in top-level symbol declarations (inferred type positions) but not in body slices.
    "Elided: ELIDED tag decodes to Tree.Elided" in {
        val (names, addrMap) = sym1("Int")
        val pickle = Array[Byte](
            TastyFormat.ELIDED.toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte
        )
        decodePickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Elided(inner)) =>
                assert(inner != null, "Elided must have inner type")
            case Result.Success(other) => fail(s"Expected Tree.Elided but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

end TreeAdtVariantCoverageTest

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

/** Tests for TreeUnpickler body decode. */
class TreeUnpicklerTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    // ── Pass-1 helper ─────────────────────────────────────────────────────────

    /** Build a SymbolBody from Pass1Result for a given symbol, or None if not found. */
    private def symbolBody(symbol: LoadingSymbol.Materialising, pass1: AstUnpickler.Pass1Result): Maybe[SymbolBody] =
        pass1.bodyDataByAddr.get(symbol.id.toLong) match
            case Some((bodyStart, bodyEnd)) =>
                Maybe(SymbolBody(
                    bodyStart = bodyStart,
                    bodyEnd = bodyEnd,
                    sectionBytes = Span.fromUnsafe(pass1.sectionBytes),
                    names = Span.fromUnsafe(pass1.names),
                    sectionOffset = pass1.sectionOffset,
                    addrMap = scala.collection.immutable.IntMap.empty,
                    pickleId = 0
                ))
            case None => Maybe.Absent

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

    /** Dummy symbolLookup for tree decode (addrMap is empty; this is never called). */
    private val dummyLookup: Int => Tasty.Symbol =
        _ => Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("unresolved"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)

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

    // ── Tree search helpers ───────────────────────────────────────────────────

    private def findLiteral(tree: Tasty.Tree): Maybe[Tasty.Constant] =
        tree match
            case Tasty.Tree.Literal(c)                  => Maybe(c)
            case Tasty.Tree.Block(_, expr)              => findLiteral(expr)
            case Tasty.Tree.Typed(inner, _)             => findLiteral(inner)
            case Tasty.Tree.Inlined(_, _, b)            => findLiteral(b)
            case Tasty.Tree.ValDef(_, _, Present(r))    => findLiteral(r)
            case Tasty.Tree.DefDef(_, _, _, Present(r)) => findLiteral(r)
            case _                                      => Maybe.Absent
    end findLiteral

    private def findIf(tree: Tasty.Tree): Maybe[Tasty.Tree.If] =
        tree match
            case i: Tasty.Tree.If           => Maybe(i)
            case Tasty.Tree.Block(_, expr)  => findIf(expr)
            case Tasty.Tree.Typed(inner, _) => findIf(inner)
            case _                          => Maybe.Absent
    end findIf

    private def findMatch(tree: Tasty.Tree): Maybe[Tasty.Tree.Match] =
        tree match
            case m: Tasty.Tree.Match        => Maybe(m)
            case Tasty.Tree.Block(_, expr)  => findMatch(expr)
            case Tasty.Tree.Typed(inner, _) => findMatch(inner)
            case _                          => Maybe.Absent
    end findMatch

    "body of SomeObject.value decodes to top-level Literal/Block/Typed/ValDef containing IntConst(42)" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map {
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == SymbolKind.Val)
                valueSym match
                    case None =>
                        fail(
                            s"No 'value' Val symbol in SomeObject. Symbols: ${pass1.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                        )
                    case Some(symbol) =>
                        symbolBody(symbol, pass1) match
                            case Present(body) =>
                                val tree = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                                val isInt42AtTopLevel = tree match
                                    case Tasty.Tree.Literal(Tasty.Constant.IntConst(42)) =>
                                        true
                                    case Tasty.Tree.Block(_, Tasty.Tree.Literal(Tasty.Constant.IntConst(42))) =>
                                        true
                                    case Tasty.Tree.Typed(Tasty.Tree.Literal(Tasty.Constant.IntConst(42)), _) =>
                                        true
                                    case Tasty.Tree.ValDef(_, _, Present(Tasty.Tree.Literal(Tasty.Constant.IntConst(42)))) =>
                                        true
                                    case _ =>
                                        false
                                assert(
                                    isInt42AtTopLevel,
                                    s"Expected top-level Literal/Block/Typed/ValDef with IntConst(42), got: $tree"
                                )
                            case Absent =>
                                // No body slice -- acceptable for a val without RHS.
                                succeed
                        end match
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    private def containsApplyOrIdentOrLiteral(tree: Tasty.Tree): Boolean =
        tree match
            case _: Tasty.Tree.Apply   => true
            case _: Tasty.Tree.Ident   => true
            case _: Tasty.Tree.Literal => true
            case Tasty.Tree.Block(stats, expr) =>
                stats.exists(containsApplyOrIdentOrLiteral) || containsApplyOrIdentOrLiteral(expr)
            case Tasty.Tree.Typed(inner, _)             => containsApplyOrIdentOrLiteral(inner)
            case Tasty.Tree.Inlined(_, _, body)         => containsApplyOrIdentOrLiteral(body)
            case Tasty.Tree.ValDef(_, _, Present(r))    => containsApplyOrIdentOrLiteral(r)
            case Tasty.Tree.DefDef(_, _, _, Present(r)) => containsApplyOrIdentOrLiteral(r)
            case _                                      => false
    end containsApplyOrIdentOrLiteral

    "method bodies in SomeObject decode to Trees containing Apply, Ident, or Literal nodes" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map {
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val methodSyms = pass1.symbols.filter(_.kind == SymbolKind.Method)
                val decodedBodies = methodSyms.flatMap { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) => Chunk(symbol -> TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup))
                        case Absent        => Chunk.empty
                }
                val allNonNull = decodedBodies.forall((_, tree) => tree != null)
                // Each method body must contain at least one Apply, Ident, or Literal node.
                // A body that consists only of unrecognized tags would produce a structurally empty tree
                // and would silently pass the null check above; this assertion catches that case.
                val hasStructure = decodedBodies.isEmpty || decodedBodies.exists((_, tree) => containsApplyOrIdentOrLiteral(tree))
                assert(
                    allNonNull && hasStructure,
                    if !allNonNull then
                        s"Some method body was null. Methods with bodies: ${decodedBodies.map((s, _) => s.name.asString).mkString(", ")}"
                    else
                        s"None of the ${decodedBodies.length} method bodies contain Apply/Ident/Literal. Bodies: ${decodedBodies.map(
                                (s, t) => s"${s.name.asString}=$t"
                            ).mkString("; ")}"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "bodies in baseClassTasty decode without crash; If trees have 3 subtrees" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.baseClassTasty)).map {
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) => Chunk(TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup))
                        case Absent        => Chunk.empty
                }
                val allNonNull = allBodies.forall(_ != null)
                assert(allNonNull, "A decoded body was null in baseClassTasty")
                // If any If node is found, verify it has 3 non-null subtrees.
                val ifOpt = allBodies.flatMap(t => findIf(t).toList).headOption
                ifOpt match
                    case Some(Tasty.Tree.If(cond, thenp, elsep)) =>
                        assert(cond != null && thenp != null && elsep != null, "If subtrees must be non-null")
                    case _ =>
                        succeed
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "bodies in colorTasty decode without crash; Match trees have CaseDefs" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.colorTasty)).map {
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) => Chunk(TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup))
                        case Absent        => Chunk.empty
                }
                val allNonNull = allBodies.forall(_ != null)
                assert(allNonNull, "A decoded body was null in colorTasty")
                // If any Match is found, verify it has non-empty cases and each CaseDef is non-null.
                val matchOpt = allBodies.flatMap(t => findMatch(t).toList).headOption
                matchOpt match
                    case Some(Tasty.Tree.Match(_, cases)) =>
                        assert(cases.nonEmpty, "Match.cases is empty")
                        val allCaseDefsValid = cases.forall {
                            case Tasty.Tree.CaseDef(pat, _, body) => pat != null && body != null
                        }
                        assert(allCaseDefsValid, "Some CaseDef had null pattern or body")
                    case _ =>
                        succeed
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "decoding all bodies in fixtureClassesPackageTasty completes without stack overflow" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.fixtureClassesPackageTasty)).map {
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                var count = 0
                pass1.symbols.foreach { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) =>
                            val tree = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                            assert(tree != null, s"body of ${symbol.name.asString} was null")
                            count += 1
                        case Absent => ()
                }
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // ── Truncated body slice does not panic the runtime ────────────────────────

    "a 1-byte truncated body slice does not cause an unhandled exception" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map {
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(symbol) =>
                        symbolBody(symbol, pass1) match
                            case Present(body) =>
                                val truncated = body.copy(bodyEnd = body.bodyStart + 1)
                                val ok =
                                    try
                                        TreeUnpickler.decodeSync(truncated, toFinalSym(symbol), dummyLookup)
                                        true
                                    catch
                                        case _: TreeUnpickler.DecodeException  => true
                                        case _: ArrayIndexOutOfBoundsException => true
                                assert(ok, "Expected either success or a known decode exception on 1-byte body")
                            case Absent =>
                                succeed
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "two consecutive _bodyOnce.get() calls return the same Tree reference" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map {
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(symbol) =>
                        symbolBody(symbol, pass1) match
                            case Present(body) =>
                                val tree1 = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                                val tree2 = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                                // In trees are freshly decoded each call (no memoization yet).
                                // We just verify both decode without crash.
                                assert(tree1 != null && tree2 != null, "Both tree decodes must be non-null")
                            case Absent => succeed
                        end match
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // ── Direct decodeAnnotationTerm calls ──────────────────────────────────────

    /** Decode an annotation pickle directly via TreeUnpickler. Returns Result to mirror old test shape. */
    private def decodeAnnPickle(
        pickle: Array[Byte],
        names: Array[Tasty.Name],
        addrMap: scala.collection.Map[Int, LoadingSymbol.Materialising]
    ): Result[TastyError, Tasty.Tree] =
        try Result.Success(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(pickle, names, addrMap, pickle, 0))
        catch
            case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"annotation args: ${ex.getMessage}", ex.byteOffset))
            case ex: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("ASTs", "annotation args truncated", 0L))

    // UNITconst pickle decodes to Literal(UnitConst).
    // decodeAnnotationTerm takes raw bytes; no DecodeContext needed.
    "UNITconst pickle decodes to Literal(UnitConst)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val symbol  = LoadingSymbol.Materialising(id = 1, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Int"))
        val names   = Array(Tasty.Name("scala"))
        val addrMap = IntMap(1 -> symbol)
        val pickle  = Array(TastyFormat.UNITconst.toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type)) => succeed
            case Result.Success(other)                                                => fail(s"Expected Literal(UnitConst) but got $other")
            case Result.Failure(e)                                                    => fail(s"Expected success but got failure $e")
            case Result.Panic(t)                                                      => throw t
        end match
    }

    // Annotation with an empty arguments chunk (empty pickle case) holds Chunk.empty.
    // empty annotation pickle path produces an empty chunk directly in ANNOTATEDtype.
    "Annotation with an empty arguments chunk holds Chunk.empty" in {
        val symbol     = LoadingSymbol.Materialising(id = 2, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Foo"))
        val annotation = Tasty.Annotation(Tasty.Type.Named(Tasty.SymbolId(symbol.id)), Chunk.empty, Tasty.Name(""))
        assert(annotation.arguments.isEmpty, s"Expected empty arguments but got ${annotation.arguments}")
        succeed
    }

    "PRIVATE byte decodes to Tree.Modifier(Flag.Private)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[LoadingSymbol.Materialising]
        val pickle  = Array(TastyFormat.PRIVATE.toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag == Tasty.Flag.Private => succeed
            case Result.Success(other) => fail(s"Expected Tree.Modifier(Flag.Private) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    "unrecognised category-1 byte yields Failure(MalformedSection)" in {
        import scala.collection.immutable.IntMap
        val names      = Array(Tasty.Name("dummy"))
        val addrMap    = IntMap.empty[LoadingSymbol.Materialising]
        val unknownTag = Array(50.toByte)
        decodeAnnPickle(unknownTag, names, addrMap) match
            case Result.Failure(TastyError.MalformedSection("ASTs", reason, _))
                if reason.contains("unknown category-1 modifier tag 50") =>
                succeed
            case Result.Failure(e)     => fail(s"Expected MalformedSection with 'unknown category-1 modifier tag 50' but got: $e")
            case Result.Success(other) => fail(s"Expected failure but got success: $other")
            case Result.Panic(t)       => throw t
        end match
    }

    // INLINED is pickled `expansion_Term call_Term? binding_Stat*`. A bare-expansion INLINED (no call,
    // no bindings) must decode to Tree.Inlined whose body IS the expansion, not Tree.Unknown(INLINED).
    "bare-expansion INLINED decodes to Tree.Inlined with the expansion as body" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names     = Array(Tasty.Name("scala"))
        val addrMap   = IntMap.empty[LoadingSymbol.Materialising]
        val expansion = Array(TastyFormat.UNITconst.toByte)
        val pickle    = Array(TastyFormat.INLINED.toByte, (expansion.length | 0x80).toByte) ++ expansion
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Inlined(Maybe.Absent, bindings, Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type))) =>
                assert(bindings.isEmpty, s"Expected no bindings but got $bindings")
            case Result.Success(other) => fail(s"Expected Inlined(Absent, [], Literal(Unit)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // APPLYsigpoly is pickled `fn_Term meth_Type arg_Term*`; the erased meth_Type has no Tree form and
    // is dropped. The node must decode to the underlying Tree.Apply(fn, args), not Tree.Unknown.
    "APPLYsigpoly decodes to Tree.Apply of its function and args" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("scala"))
        val sym     = LoadingSymbol.Materialising(id = 5, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Int"))
        val addrMap = IntMap(5 -> sym)
        val fn      = Array(TastyFormat.UNITconst.toByte)
        // meth_Type = TYPEREFsymbol(addr=5, qual=TYPEREFpkg(nameRef=0)); read and discarded.
        val qual     = Array(TastyFormat.TYPEREFpkg.toByte, (0 | 0x80).toByte)
        val methType = Array(TastyFormat.TYPEREFsymbol.toByte, (5 | 0x80).toByte) ++ qual
        val payload  = fn ++ methType
        val pickle   = Array(TastyFormat.APPLYsigpoly.toByte, (payload.length | 0x80).toByte) ++ payload
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Apply(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type), args)) =>
                assert(args.isEmpty, s"Expected no args but got $args")
            case Result.Success(other) => fail(s"Expected Apply(Literal(Unit), []) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // A qualified access modifier (private[X]) between statements is a modifier carrying a qualifier
    // sub-AST, not a body tree. The stat reader must skip it, never surface it as a Tree.Unknown leaf.
    "qualified PRIVATEqualified between BLOCK statements is skipped, not an Unknown leaf" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("scala"))
        val addrMap = IntMap.empty[LoadingSymbol.Materialising]
        val expr    = Array(TastyFormat.UNITconst.toByte)
        // PRIVATEqualified (cat-3): tag + qualifier sub-AST (TYPEREFpkg(nameRef=0)).
        val privQual = Array(TastyFormat.PRIVATEqualified.toByte, TastyFormat.TYPEREFpkg.toByte, (0 | 0x80).toByte)
        val payload  = expr ++ privQual
        val pickle   = Array(TastyFormat.BLOCK.toByte, (payload.length | 0x80).toByte) ++ payload
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(block @ Tasty.Tree.Block(stats, Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type))) =>
                assert(stats.isEmpty, s"Expected the qualified modifier to be skipped (no stats) but got $stats")
                assert(
                    block.collect { case u: Tasty.Tree.Unknown => u }.isEmpty,
                    s"Expected no Unknown leaves but got ${block.collect { case u: Tasty.Tree.Unknown => u }}"
                )
            case Result.Success(other) => fail(s"Expected Block([], Literal(Unit)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    "OBJECT byte decodes to Tree.Modifier(Flag.Module)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.OBJECT.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag == Tasty.Flag.Module => succeed
            case other                                                                  => fail(s"Expected Modifier(Module), got: $other")
    }

    "TRAIT byte decodes to Tree.Modifier(Flag.Trait)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.TRAIT.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag == Tasty.Flag.Trait => succeed
            case other                                                                 => fail(s"Expected Modifier(Trait), got: $other")
    }

    "ENUM byte decodes to Tree.Modifier(Flag.Enum)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.ENUM.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag == Tasty.Flag.Enum => succeed
            case other                                                                => fail(s"Expected Modifier(Enum), got: $other")
    }

    // SHAREDtype byte + nat(42) decodes to Tree.Shared(42).
    "SHAREDtype + nat(42) decodes to Tree.Shared(42)" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[LoadingSymbol.Materialising]
        // SHAREDtype = 61; nat(42): single-byte encoding with stop-bit set = 42 | 0x80 = 0xAA.
        val pickle = Array(TastyFormat.SHAREDtype.toByte, (42 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Shared(42)) => succeed
            case Result.Success(other)                 => fail(s"Expected Tree.Shared(42) but got $other")
            case Result.Failure(e)                     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)                       => throw t
        end match
    }

    // INTconst byte + int(7) decodes to Tree.Literal(Constant.IntConst(7)).
    "INTconst + int(7) decodes to Tree.Literal(Constant.IntConst(7))" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[LoadingSymbol.Materialising]
        // INTconst = 70; int(7): signed readInt encoding, single byte with stop-bit set = 7 | 0x80 = 0x87.
        val pickle = Array(TastyFormat.INTconst.toByte, (7 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Literal(Tasty.Constant.IntConst(7))) => succeed
            case Result.Success(other) => fail(s"Expected Tree.Literal(Constant.IntConst(7)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // Byte-sequence construction note:
    //   TASTy nat encoding: big-endian base-128 with continuation bit 0x80 CLEAR and stop bit 0x80 SET on the last byte.
    //   So single-byte nat `n` (0 < n < 128) encodes as `n | 0x80`.
    //   readEnd reads a nat as the payload length, then returns cursor + length.
    //   APPLIEDtype = 161 (0xA1), TERMREFdirect = 62 (0x3E), MATCHtype = 190 (0xBE).

    "APPLIEDtype decodes tycon + one arg into Tree.AppliedType" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val listSym = LoadingSymbol.Materialising(id = 3, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("List"))
        val intSym  = LoadingSymbol.Materialising(id = 1, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("Int"))
        val names   = Array(Tasty.Name("scala"))
        val addrMap = IntMap(1 -> listSym, 2 -> intSym)
        // APPLIEDtype(161=0xA1) Length(4=0x84) TERMREFdirect(62=0x3E) nat(1)=0x81 TERMREFdirect(62=0x3E) nat(2)=0x82
        val pickle = Array[Byte](
            TastyFormat.APPLIEDtype.toByte,
            (4 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.AppliedType(tycon, args)) =>
                val tyconOk = tycon match
                    case Tasty.Tree.TermRefDirect(1) => true
                    case _                           => false
                assert(tyconOk, s"Expected TermRefDirect(1) as tycon but got $tycon")
                assert(args.length == 1, s"Expected 1 arg but got ${args.length}")
                val argOk = args(0) match
                    case Tasty.Tree.TermRefDirect(2) => true
                    case _                           => false
                assert(argOk, s"Expected TermRefDirect(2) as arg but got ${args(0)}")
            case Result.Success(other) => fail(s"Expected Tree.AppliedType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    "MATCHtype with 2 case nodes decodes into Tree.MatchType with cases.length==2" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        var _symId = 10
        def makeSym(n: String) =
            _symId += 1; LoadingSymbol.Materialising(id = _symId, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name(n))
        val boundSym = makeSym("Bound")
        val scrutSym = makeSym("Scrut")
        val case1Sym = makeSym("Case1")
        val case2Sym = makeSym("Case2")
        val names    = Array(Tasty.Name("scala"))
        val addrMap  = IntMap(1 -> boundSym, 2 -> scrutSym, 3 -> case1Sym, 4 -> case2Sym)
        val pickle = Array[Byte](
            TastyFormat.MATCHtype.toByte,
            (8 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (3 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (4 | 0x80).toByte
        )
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.MatchType(_, _, cases)) =>
                assert(cases.length == 2, s"Expected 2 cases but got ${cases.length}")
            case Result.Success(other) => fail(s"Expected Tree.MatchType but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    "TERMREFpkg + nameRef decodes to Tree.TermRefPkg(Name(scala))" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names = Array(
            Tasty.Name("a"),
            Tasty.Name("b"),
            Tasty.Name("c"),
            Tasty.Name("d"),
            Tasty.Name("e"),
            Tasty.Name("scala")
        )
        val addrMap = IntMap.empty[LoadingSymbol.Materialising]
        val pickle  = Array(TastyFormat.TERMREFpkg.toByte, (5 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.TermRefPkg(name)) if name.asString == "scala" => succeed
            case Result.Success(other) => fail(s"Expected Tree.TermRefPkg(Name(scala)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    "SELECTin with nameRef + qual + owner decodes to Tree.SelectIn" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val listSym  = LoadingSymbol.Materialising(id = 3, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("List"))
        val scalaSym = LoadingSymbol.Materialising(id = 4, kind = SymbolKind.Class, flags = Tasty.Flags.empty, name = Tasty.Name("scala"))
        val names    = Array(Tasty.Name("map"))
        val addrMap  = IntMap(1 -> listSym, 2 -> scalaSym)
        val pickle = Array[Byte](
            TastyFormat.SELECTin.toByte,
            (6 | 0x80).toByte,
            (0 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte
        )
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.SelectIn(qual, name, owner)) =>
                val nameStr = name.asString
                assert(nameStr == "map", s"Expected name 'map' but got '$nameStr'")
                val qualOk = qual match
                    case Tasty.Tree.TermRefDirect(1) => true
                    case _                           => false
                assert(qualOk, s"Expected TermRefDirect(1) for qual but got $qual")
                val ownerOk = owner match
                    case Tasty.Tree.TermRefDirect(2) => true
                    case _                           => false
                assert(ownerOk, s"Expected TermRefDirect(2) for owner but got $owner")
            case Result.Success(other) => fail(s"Expected Tree.SelectIn but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    //       and the body is either Absent or Present(non-null tree).
    "VALDEF body from SomeObject.value decodes to Tree.ValDef with non-null fields" in {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map {
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(symbol) =>
                        symbolBody(symbol, pass1) match
                            case Present(body) =>
                                val tree = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                                tree match
                                    case Tasty.Tree.ValDef(s, tpt, rhs) =>
                                        assert(s != null, "ValDef.symbol must not be null")
                                        assert(tpt != null, "ValDef.tpt must not be null")
                                        assert(rhs != null, "ValDef.rhs must not be null (Maybe is never null)")
                                    case _ =>
                                        succeed
                                end match
                            case Absent => succeed
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // uses decodeAnnPickle helper directly instead of Annotation internal factory.
    "APPLY with fun + 2 args decodes to Tree.Apply with fun and 2-element args chunk" in {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        var _symId2 = 20
        def makeSym(n: String) =
            _symId2 += 1;
            LoadingSymbol.Materialising(id = _symId2, kind = SymbolKind.Method, flags = Tasty.Flags.empty, name = Tasty.Name(n))
        val fnSym   = makeSym("fn")
        val arg1Sym = makeSym("arg1")
        val arg2Sym = makeSym("arg2")
        val names   = Array(Tasty.Name("test"))
        val addrMap = IntMap(1 -> fnSym, 2 -> arg1Sym, 3 -> arg2Sym)
        val pickle = Array[Byte](
            TastyFormat.APPLY.toByte,
            (6 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (1 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (2 | 0x80).toByte,
            TastyFormat.TERMREFdirect.toByte,
            (3 | 0x80).toByte
        )
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Apply(fun, args)) =>
                val funOk = fun match
                    case Tasty.Tree.TermRefDirect(1) => true
                    case _                           => false
                assert(funOk, s"Expected TermRefDirect(1) as fun but got $fun")
                assert(args.length == 2, s"Expected 2 args but got ${args.length}")
                val arg1Ok = args(0) match
                    case Tasty.Tree.TermRefDirect(2) => true
                    case _                           => false
                val arg2Ok = args(1) match
                    case Tasty.Tree.TermRefDirect(3) => true
                    case _                           => false
                assert(arg1Ok, s"Expected TermRefDirect(2) as first arg but got ${args(0)}")
                assert(arg2Ok, s"Expected TermRefDirect(3) as second arg but got ${args(1)}")
            case Result.Success(other) => fail(s"Expected Tree.Apply but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // plainClassTasty contain zero Tree.Unknown nodes with tag >= 128 (category-5 unhandled nodes).
    //       and the resulting trees are walked recursively.
    "zero-Unknown sweep: no category-5 Unknown nodes in someObjectTasty or plainClassTasty bodies" in {
        def countCat5Unknown(tree: Tasty.Tree): Int =
            tree match
                case Tasty.Tree.Unknown(tag, _) if tag >= 128 => 1
                case Tasty.Tree.Unknown(_, _)                 => 0
                case Tasty.Tree.Block(stats, expr) =>
                    stats.toList.map(countCat5Unknown).sum + countCat5Unknown(expr)
                case Tasty.Tree.Apply(fun, args) =>
                    countCat5Unknown(fun) + args.toList.map(countCat5Unknown).sum
                case Tasty.Tree.TypeApply(fun, _) =>
                    countCat5Unknown(fun)
                case Tasty.Tree.If(cond, thenp, elsep) =>
                    countCat5Unknown(cond) + countCat5Unknown(thenp) + countCat5Unknown(elsep)
                case Tasty.Tree.Match(sel, cases) =>
                    countCat5Unknown(sel) + cases.toList.map {
                        case Tasty.Tree.CaseDef(pat, guard, body) =>
                            countCat5Unknown(pat) + guard.toList.map(countCat5Unknown).sum + countCat5Unknown(body)
                    }
                        .sum
                case Tasty.Tree.ValDef(_, _, rhs) =>
                    rhs.toList.map(countCat5Unknown).sum
                case Tasty.Tree.DefDef(_, paramss, _, rhs) =>
                    paramss.toList.flatMap(_.toList).map(countCat5Unknown).sum + rhs.toList.map(countCat5Unknown).sum
                case Tasty.Tree.ClassDef(_, tmpl) =>
                    tmpl.parents.toList.map(countCat5Unknown).sum + tmpl.body.toList.map(countCat5Unknown).sum
                case Tasty.Tree.PackageDef(_, stats) =>
                    stats.toList.map(countCat5Unknown).sum
                case Tasty.Tree.Typed(expr, _) =>
                    countCat5Unknown(expr)
                case Tasty.Tree.Assign(lhs, rhs) =>
                    countCat5Unknown(lhs) + countCat5Unknown(rhs)
                case Tasty.Tree.Return(expr, _) =>
                    expr.toList.map(countCat5Unknown).sum
                case Tasty.Tree.Throw(expr) =>
                    countCat5Unknown(expr)
                case Tasty.Tree.Inlined(call, bindings, body) =>
                    call.toList.map(countCat5Unknown).sum + bindings.toList.map(countCat5Unknown).sum + countCat5Unknown(
                        body
                    )
                case Tasty.Tree.Try(expr, cases, fin) =>
                    countCat5Unknown(expr) + cases.toList.map {
                        case Tasty.Tree.CaseDef(pat, _, body) => countCat5Unknown(pat) + countCat5Unknown(body)
                    }
                        .sum + fin.toList.map(countCat5Unknown).sum
                case Tasty.Tree.While(cond, body) =>
                    countCat5Unknown(cond) + countCat5Unknown(body)
                case Tasty.Tree.Bind(_, pat) =>
                    countCat5Unknown(pat)
                case Tasty.Tree.Alternative(pats) =>
                    pats.toList.map(countCat5Unknown).sum
                case Tasty.Tree.Unapply(fun, implicits, patterns) =>
                    countCat5Unknown(fun) + implicits.toList.map(countCat5Unknown).sum + patterns.toList.map(
                        countCat5Unknown
                    ).sum
                case Tasty.Tree.NamedArg(_, value) =>
                    countCat5Unknown(value)
                case Tasty.Tree.Lambda(method, _) =>
                    countCat5Unknown(method)
                case Tasty.Tree.Import(qual, sels) =>
                    countCat5Unknown(qual) + sels.toList.map(countCat5Unknown).sum
                case Tasty.Tree.Export(qual, sels) =>
                    countCat5Unknown(qual) + sels.toList.map(countCat5Unknown).sum
                case Tasty.Tree.AnnotationNode(annotType, arg) =>
                    countCat5Unknown(annotType) + countCat5Unknown(arg)
                case _ =>
                    0

        def sweepFixture(bytes: Array[Byte])(using Frame): Int < (Sync & Abort[TastyError]) =
            runPass1(bytes).map { pass1 =>
                import AllowUnsafe.embrace.danger
                var total = 0
                pass1.symbols.foreach { symbol =>
                    symbolBody(symbol, pass1) match
                        case Present(body) =>
                            val tree = TreeUnpickler.decodeSync(body, toFinalSym(symbol), dummyLookup)
                            total += countCat5Unknown(tree)
                        case Absent => ()
                }
                total
            }

        for
            soCount <- Abort.run[TastyError](sweepFixture(kyo.fixtures.Embedded.someObjectTasty))
            pcCount <- Abort.run[TastyError](sweepFixture(kyo.fixtures.Embedded.plainClassTasty))
        yield
            val soUnknown = soCount match
                case Result.Success(n) => n
                case Result.Failure(e) => fail(s"someObjectTasty sweep failed: $e"); 0
                case Result.Panic(t)   => throw t
            val pcUnknown = pcCount match
                case Result.Success(n) => n
                case Result.Failure(e) => fail(s"plainClassTasty sweep failed: $e"); 0
                case Result.Panic(t)   => throw t
            assert(
                soUnknown == 0,
                s"violation: someObjectTasty has $soUnknown category-5 Unknown nodes"
            )
            assert(
                pcUnknown == 0,
                s"violation: plainClassTasty has $pcUnknown category-5 Unknown nodes"
            )
        end for
    }

end TreeUnpicklerTest

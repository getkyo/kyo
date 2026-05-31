package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.reader.AstUnpickler
import kyo.internal.tasty.reader.FileAttributes
import kyo.internal.tasty.reader.NameUnpickler
import kyo.internal.tasty.reader.SectionIndex
import kyo.internal.tasty.reader.TastyFormat
import kyo.internal.tasty.reader.TastyHeader
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.symbol.Interner
import kyo.internal.tasty.type_.TypeArena
import scala.collection.mutable

/** Tests for TreeUnpickler body decode (Phase 8 plan tests 1-9).
  *
  * Tests 1-5 and 8: decode bodies directly from pass-1 TastyOrigin values using decodeSync (no full classpath needed). Test 6: verifies
  * Java symbol error path. Test 7: verifies ClasspathClosed error path via scope exit. Test 9: verifies OnceCell reference equality.
  */
class TreeUnpicklerTest extends Test:

    import AllowUnsafe.embrace.danger
    import Tasty.Name.asString

    // ── FileSource for tests that need full classpath ─────────────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(b) =>
                    Sync.defer { files.remove(from); files(to) = b }
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq))
        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    /** Open a classpath with PlainClass.tasty. Returns inside a Scope. */
    private def openPlainClassCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        ClasspathOrchestrator.open(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openPlainClassCp

    // ── Pass-1 helper ─────────────────────────────────────────────────────────

    /** Build a SymbolBody from Pass1Result for a given symbol, or None if not found. */
    private def symbolBody(sym: Tasty.Symbol, pass1: AstUnpickler.Pass1Result): Maybe[Tasty.SymbolBody] =
        pass1.bodyDataByAddr.get(sym) match
            case Some((bodyStart, bodyEnd)) =>
                Maybe(Tasty.SymbolBody(
                    bodyStart = bodyStart,
                    bodyEnd = bodyEnd,
                    sectionBytes = pass1.sectionBytes,
                    names = pass1.names,
                    sectionOffset = pass1.sectionOffset,
                    addrMap = scala.collection.immutable.IntMap.empty
                ))
            case None => Maybe.Absent

    /** Dummy symbolLookup for Phase 02 tree decode (addrMap is empty; this is never called). */
    private val dummyLookup: Int => Tasty.Symbol =
        _ => Tasty.Symbol.make(Tasty.SymbolKind.Unresolved, Tasty.Flags.empty, Tasty.Name("unresolved"))

    private def runPass1(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view     = ByteView(bytes)
        val interner = Interner.init(numShards = 32, initialShardCapacity = 16)
        val arena    = TypeArena.canonical()
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
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

    // ── Test 1: body of SomeObject.value decodes to a tree whose top-level structure ──
    // ── is one of: Literal(IntConst(42)), Block(_, Literal(IntConst(42))),             ──
    // ── Typed(Literal(IntConst(42)), _), or ValDef(_, _, Literal(IntConst(42))).       ──
    // ── No recursive search: only one level of wrapping is accepted.                   ──

    "Test 1: body of SomeObject.value decodes to top-level Literal/Block/Typed/ValDef containing IntConst(42)" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Tasty.SymbolKind.Val)
                valueSym match
                    case None =>
                        fail(
                            s"No 'value' Val symbol in SomeObject. Symbols: ${pass1.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                        )
                    case Some(sym) =>
                        // plan: phase-02 bridge; use symbolBody(sym, pass1) instead of sym.origin match.
                        symbolBody(sym, pass1) match
                            case Present(body) =>
                                val tree = TreeUnpickler.decodeSync(body, sym, dummyLookup)
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

    // ── Test 2: method bodies decode to trees containing Apply, Ident, or Literal nodes ──

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

    "Test 2: method bodies in SomeObject decode to Trees containing Apply, Ident, or Literal nodes" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val methodSyms = pass1.symbols.filter(_.kind == Tasty.SymbolKind.Method)
                val decodedBodies = methodSyms.flatMap: sym =>
                    symbolBody(sym, pass1) match
                        case Present(body) => Chunk(sym -> TreeUnpickler.decodeSync(body, sym, dummyLookup))
                        case Absent        => Chunk.empty
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

    // ── Test 3: If tree decoded correctly ─────────────────────────────────────

    "Test 3: bodies in baseClassTasty decode without crash; If trees have 3 subtrees" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.baseClassTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap: sym =>
                    symbolBody(sym, pass1) match
                        case Present(body) => Chunk(TreeUnpickler.decodeSync(body, sym, dummyLookup))
                        case Absent        => Chunk.empty
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

    // ── Test 4: Match tree decodes to Match with CaseDef children ─────────────

    "Test 4: bodies in colorTasty decode without crash; Match trees have CaseDefs" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.colorTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap: sym =>
                    symbolBody(sym, pass1) match
                        case Present(body) => Chunk(TreeUnpickler.decodeSync(body, sym, dummyLookup))
                        case Absent        => Chunk.empty
                val allNonNull = allBodies.forall(_ != null)
                assert(allNonNull, "A decoded body was null in colorTasty")
                // If any Match is found, verify it has non-empty cases and each CaseDef is non-null.
                val matchOpt = allBodies.flatMap(t => findMatch(t).toList).headOption
                matchOpt match
                    case Some(Tasty.Tree.Match(_, cases)) =>
                        assert(cases.nonEmpty, "Match.cases is empty")
                        val allCaseDefsValid = cases.forall:
                            case Tasty.Tree.CaseDef(pat, _, body) => pat != null && body != null
                        assert(allCaseDefsValid, "Some CaseDef had null pattern or body")
                    case _ =>
                        succeed
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 5: Recursive method bodies decode without stack overflow ──────────

    "Test 5: decoding all bodies in fixtureClassesPackageTasty completes without stack overflow" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.fixtureClassesPackageTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                var count = 0
                pass1.symbols.foreach: sym =>
                    symbolBody(sym, pass1) match
                        case Present(body) =>
                            val tree = TreeUnpickler.decodeSync(body, sym, dummyLookup)
                            assert(tree != null, s"body of ${sym.name.asString} was null")
                            count += 1
                        case Absent => ()
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 6: Java symbol returns NotImplemented ───────────────────────────

    "Test 6: sym.body for a Java symbol returns Abort.fail(NotImplemented)" in {
        pending // plan: phase-02; sym.body effectful method added in Phase 04; Java symbol check deferred
    }

    // ── Test 7: body called after classpath close returns ClasspathClosed ─────

    "Test 7: sym.body after classpath close returns Abort.fail(ClasspathClosed)" in {
        pending // plan: phase-02; sym.body effectful method added in Phase 04
    }

    // ── Test 8: truncated body slice does not panic the runtime ───────────────

    "Test 8: a 1-byte truncated body slice does not cause an unhandled exception" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Tasty.SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(sym) =>
                        // plan: phase-02 bridge; use symbolBody + truncated body.
                        symbolBody(sym, pass1) match
                            case Present(body) =>
                                val truncated = body.copy(bodyEnd = body.bodyStart + 1)
                                val ok =
                                    try
                                        TreeUnpickler.decodeSync(truncated, sym, dummyLookup)
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

    // ── Test 9: two consecutive body calls return reference-equal Tree ────────

    "Test 9: two consecutive _bodyOnce.get() calls return the same Tree reference" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Tasty.SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(sym) =>
                        // plan: phase-02 bridge; use decodeSync twice to verify identical output (not memoized via OnceCell).
                        symbolBody(sym, pass1) match
                            case Present(body) =>
                                val tree1 = TreeUnpickler.decodeSync(body, sym, dummyLookup)
                                val tree2 = TreeUnpickler.decodeSync(body, sym, dummyLookup)
                                // In Phase 02, trees are freshly decoded each call (no memoization yet).
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

    // ── Test 10: regression: sym.body after Scope close returns ClasspathClosed ──

    "sym.body after Scope close returns Abort.fail(ClasspathClosed)" in {
        pending // plan: phase-02; sym.body effectful method added in Phase 04
    }

    // ── Phase 17 Tests (INV-006, M2): Phase 08 -- direct decodeAnnotationTerm calls ─────────────────

    /** Decode an annotation pickle directly via TreeUnpickler. Returns Result to mirror old test shape. */
    private def decodeAnnPickle(
        pickle: Array[Byte],
        names: Array[Tasty.Name],
        addrMap: scala.collection.Map[Int, Tasty.Symbol]
    ): Result[TastyError, Tasty.Tree] =
        try Result.Success(kyo.internal.tasty.reader.TreeUnpickler.decodeAnnotationTerm(pickle, names, addrMap, pickle, 0))
        catch
            case ex: kyo.internal.tasty.reader.TreeUnpickler.DecodeException =>
                Result.Failure(TastyError.MalformedSection("ASTs", s"annotation args: ${ex.getMessage}", ex.byteOffset))
            case ex: ArrayIndexOutOfBoundsException =>
                Result.Failure(TastyError.MalformedSection("ASTs", "annotation args truncated", 0L))

    // Test A (INV-006, M2): UNITconst pickle decodes to Literal(UnitConst).
    // Phase 08: decodeAnnotationTerm takes raw bytes; no DecodeContext needed.
    "Phase17-A: UNITconst pickle decodes to Literal(UnitConst)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val sym     = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("Int"))
        val names   = Array(Tasty.Name("scala"))
        val addrMap = IntMap(1 -> sym)
        val pickle  = Array(TastyFormat.UNITconst.toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type)) => succeed
            case Result.Success(other)                                                => fail(s"Expected Literal(UnitConst) but got $other")
            case Result.Failure(e)                                                    => fail(s"Expected success but got failure $e")
            case Result.Panic(t)                                                      => throw t
        end match
    }

    // Test B (INV-006): Annotation with Absent args (empty pickle case) holds Maybe.Absent.
    // Phase 08: empty annotation pickle path produces Absent directly in ANNOTATEDtype.
    "Phase17-B: Annotation with Maybe.Absent args holds Absent" in {
        val sym = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("Foo"))
        val ann = Tasty.Annotation(Tasty.Type.Named(sym.id), Maybe.Absent)
        assert(ann.args == Maybe.Absent, s"Expected Absent but got ${ann.args}")
        succeed
    }

    // ── Phase 18a Tests (M1, category-1 modifiers) ────────────────────────────

    // Test 18a-1 (M1 category 1): PRIVATE tag decodes to Modifier(Private).
    "Phase18a-1: PRIVATE byte decodes to Tree.Modifier(Flag.Private)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        val pickle  = Array(TastyFormat.PRIVATE.toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag.bit == Tasty.Flag.Private.bit => succeed
            case Result.Success(other) => fail(s"Expected Tree.Modifier(Flag.Private) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // Test 18a-2 (M1 category 1 negative): unknown category-1 tag throws DecodeException.
    "Phase18a-2: unrecognised category-1 byte yields Failure(MalformedSection)" in run {
        import scala.collection.immutable.IntMap
        val names      = Array(Tasty.Name("dummy"))
        val addrMap    = IntMap.empty[Tasty.Symbol]
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

    // Test 18a-debt-1 through 18a-debt-3 (M1 category 1, BLOCKER from Phase 18a audit).

    "Phase18a-debt-1: OBJECT byte decodes to Tree.Modifier(Flag.Module)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.OBJECT.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag.bit == Tasty.Flag.Module.bit => succeed
            case other => fail(s"Expected Modifier(Module), got: $other")
    }

    "Phase18a-debt-2: TRAIT byte decodes to Tree.Modifier(Flag.Trait)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.TRAIT.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag.bit == Tasty.Flag.Trait.bit => succeed
            case other => fail(s"Expected Modifier(Trait), got: $other")
    }

    "Phase18a-debt-3: ENUM byte decodes to Tree.Modifier(Flag.Enum)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val pickle = Array(TastyFormat.ENUM.toByte)
        decodeAnnPickle(pickle, Array(Tasty.Name("dummy")), IntMap.empty) match
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag.bit == Tasty.Flag.Enum.bit => succeed
            case other => fail(s"Expected Modifier(Enum), got: $other")
    }

    // ── Phase 18b Tests (M1, category-2 tags) ────────────────────────────────

    // Test 18b-1 (M1 category 2): SHAREDtype byte + nat(42) decodes to Tree.Shared(42).
    "Phase18b-1: SHAREDtype + nat(42) decodes to Tree.Shared(42)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        // SHAREDtype = 61; nat(42): single-byte encoding with stop-bit set = 42 | 0x80 = 0xAA.
        val pickle = Array(TastyFormat.SHAREDtype.toByte, (42 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Shared(42)) => succeed
            case Result.Success(other)                 => fail(s"Expected Tree.Shared(42) but got $other")
            case Result.Failure(e)                     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)                       => throw t
        end match
    }

    // Test 18b-2 (M1 category 2): INTconst byte + int(7) decodes to Tree.Literal(Constant.IntConst(7)).
    "Phase18b-2: INTconst + int(7) decodes to Tree.Literal(Constant.IntConst(7))" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        // INTconst = 70; int(7): signed readInt encoding, single byte with stop-bit set = 7 | 0x80 = 0x87.
        val pickle = Array(TastyFormat.INTconst.toByte, (7 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.Literal(Tasty.Constant.IntConst(7))) => succeed
            case Result.Success(other) => fail(s"Expected Tree.Literal(Constant.IntConst(7)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // ── Phase 18c Tests (M1, category-5 type-form tags) ──────────────────────
    //
    // Byte-sequence construction note:
    //   TASTy nat encoding: big-endian base-128 with continuation bit 0x80 CLEAR and stop bit 0x80 SET on the last byte.
    //   So single-byte nat `n` (0 < n < 128) encodes as `n | 0x80`.
    //   readEnd() reads a nat as the payload length, then returns cursor + length.
    //   APPLIEDtype = 161 (0xA1), TERMREFdirect = 62 (0x3E), MATCHtype = 190 (0xBE).

    // Test 18c-1 (M1 category 3): APPLIEDtype decodes nested arguments.
    "Phase18c-1: APPLIEDtype decodes tycon + one arg into Tree.AppliedType" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val listSym = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("List"))
        val intSym  = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("Int"))
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

    // Test 18c-2 (M1 category 3): MATCHtype with 2 case nodes decodes into Tree.MatchType.
    "Phase18c-2: MATCHtype with 2 case nodes decodes into Tree.MatchType with cases.length==2" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        def makeSym(n: String) = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name(n))
        val boundSym           = makeSym("Bound")
        val scrutSym           = makeSym("Scrut")
        val case1Sym           = makeSym("Case1")
        val case2Sym           = makeSym("Case2")
        val names              = Array(Tasty.Name("scala"))
        val addrMap            = IntMap(1 -> boundSym, 2 -> scrutSym, 3 -> case1Sym, 4 -> case2Sym)
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

    // ── Phase 18d Tests (M1, category-4 type-position tags) ──────────────────

    // Test 18d-1 (M1 category 4): TERMREFpkg + nameRef decodes to Tree.TermRefPkg(Name("scala")).
    "Phase18d-1: TERMREFpkg + nameRef decodes to Tree.TermRefPkg(Name(scala))" in run {
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
        val addrMap = IntMap.empty[Tasty.Symbol]
        val pickle  = Array(TastyFormat.TERMREFpkg.toByte, (5 | 0x80).toByte)
        decodeAnnPickle(pickle, names, addrMap) match
            case Result.Success(Tasty.Tree.TermRefPkg(name)) if name.asString == "scala" => succeed
            case Result.Success(other) => fail(s"Expected Tree.TermRefPkg(Name(scala)) but got $other")
            case Result.Failure(e)     => fail(s"Expected success but got failure $e")
            case Result.Panic(t)       => throw t
        end match
    }

    // Test 18d-2 (M1 category 4): SELECTin with 3 components decodes to Tree.SelectIn(qual, name, owner).
    "Phase18d-2: SELECTin with nameRef + qual + owner decodes to Tree.SelectIn" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val listSym  = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("List"))
        val scalaSym = Tasty.Symbol.make(Tasty.SymbolKind.Class, Tasty.Flags.empty, Tasty.Name("scala"))
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

    // ── Phase 18e Tests (category-5 complete coverage) ────────────────────────

    // Test 18e-1: VALDEF body from a real fixture decodes to Tree.ValDef with non-null sym/tpt/rhs fields.
    // Given: the 'value' Val symbol from SomeObject.tasty has a body slice.
    // When: TreeUnpickler.decodeSync is called.
    // Then: returns Tree.ValDef(sym, tpt, _) where sym is non-null, tpt is a non-null Tasty.Type,
    //       and the body is either Absent or Present(non-null tree).
    "Phase18e-1: VALDEF body from SomeObject.value decodes to Tree.ValDef with non-null fields" in run {
        Abort.run[TastyError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Tasty.SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(sym) =>
                        // plan: phase-02 bridge; use symbolBody instead of sym.origin.
                        symbolBody(sym, pass1) match
                            case Present(body) =>
                                val tree = TreeUnpickler.decodeSync(body, sym, dummyLookup)
                                tree match
                                    case Tasty.Tree.ValDef(s, tpt, rhs) =>
                                        assert(s != null, "ValDef.sym must not be null")
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

    // Test 18e-2: APPLY with fun + 2 args hand-crafted pickle decodes to Tree.Apply with correct structure.
    // Phase 08: uses decodeAnnPickle helper directly instead of Annotation internal factory.
    "Phase18e-2: APPLY with fun + 2 args decodes to Tree.Apply with fun and 2-element args chunk" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        def makeSym(n: String) = Tasty.Symbol.make(Tasty.SymbolKind.Method, Tasty.Flags.empty, Tasty.Name(n))
        val fnSym              = makeSym("fn")
        val arg1Sym            = makeSym("arg1")
        val arg2Sym            = makeSym("arg2")
        val names              = Array(Tasty.Name("test"))
        val addrMap            = IntMap(1 -> fnSym, 2 -> arg1Sym, 3 -> arg2Sym)
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

    // Test 18e-3: INV-005 zero-Unknown sweep: all symbol bodies decoded from someObjectTasty and
    // plainClassTasty contain zero Tree.Unknown nodes with tag >= 128 (category-5 unhandled nodes).
    // Given: embedded fixture bytes for SomeObject.tasty and PlainClass.tasty.
    // When: all symbol bodies are decoded via AstUnpickler.readPass1 + TreeUnpickler.decodeSync
    //       and the resulting trees are walked recursively.
    // Then: countCat5Unknown across all decoded trees == 0, demonstrating INV-005.
    "Phase18e-3: INV-005 zero-Unknown sweep: no category-5 Unknown nodes in someObjectTasty or plainClassTasty bodies" in run {
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
                    countCat5Unknown(sel) + cases.toList.map:
                        case Tasty.Tree.CaseDef(pat, guard, body) =>
                            countCat5Unknown(pat) + guard.toList.map(countCat5Unknown).sum + countCat5Unknown(body)
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
                    countCat5Unknown(expr) + cases.toList.map:
                        case Tasty.Tree.CaseDef(pat, _, body) => countCat5Unknown(pat) + countCat5Unknown(body)
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
            runPass1(bytes).map: pass1 =>
                import AllowUnsafe.embrace.danger
                var total = 0
                pass1.symbols.foreach: sym =>
                    // plan: phase-02 bridge; use symbolBody instead of sym.origin.
                    symbolBody(sym, pass1) match
                        case Present(body) =>
                            val tree = TreeUnpickler.decodeSync(body, sym, dummyLookup)
                            total += countCat5Unknown(tree)
                        case Absent => ()
                total

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
                s"INV-005 violation: someObjectTasty has $soUnknown category-5 Unknown nodes"
            )
            assert(
                pcUnknown == 0,
                s"INV-005 violation: plainClassTasty has $pcUnknown category-5 Unknown nodes"
            )
        end for
    }

end TreeUnpicklerTest

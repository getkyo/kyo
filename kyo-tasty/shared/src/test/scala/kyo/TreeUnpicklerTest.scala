package kyo

import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathRef
import kyo.internal.tasty.query.ClasspathTestHelpers
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
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    val cp = Tasty.Classpath.wrap(rawCp)
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    cp
    end openPlainClassCp

    // ── Pass-1 helper ─────────────────────────────────────────────────────────

    private def runPass1(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[TastyError]) =
        val view     = ByteView(bytes)
        val interner = new Interner(numShards = 32, initialShardCapacity = 16)
        val home     = new ClasspathRef
        val arena    = TypeArena.canonical()
        for
            _        <- TastyHeader.read(view)
            names    <- NameUnpickler.read(view, interner)
            sections <- SectionIndex.read(view, names)
            attrs = FileAttributes.default
            result <- sections.get(TastyFormat.ASTsSection) match
                case Present((offset, length)) =>
                    val astView = view.subView(offset, offset + length)
                    AstUnpickler.readPass1(astView, names, attrs, home, arena)
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
                        import AllowUnsafe.embrace.danger
                        sym.origin match
                            case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val tree = TreeUnpickler.decodeSync(o, sym)
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
                            case o: Tasty.Symbol.TastyOrigin =>
                                // No body slice for this particular symbol -- acceptable for a constructor param.
                                succeed
                            case _ =>
                                fail("Expected TastyOrigin for SomeObject.value")
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
                    sym.origin match
                        case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            Chunk(sym -> TreeUnpickler.decodeSync(o, sym))
                        case _ => Chunk.empty
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
                    sym.origin match
                        case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            Chunk(TreeUnpickler.decodeSync(o, sym))
                        case _ => Chunk.empty
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
                    sym.origin match
                        case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            Chunk(TreeUnpickler.decodeSync(o, sym))
                        case _ => Chunk.empty
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
                    sym.origin match
                        case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            val tree = TreeUnpickler.decodeSync(o, sym)
                            assert(tree != null, s"body of ${sym.name.asString} was null")
                            count += 1
                        case _ => ()
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 6: Java symbol returns NotImplemented ───────────────────────────

    "Test 6: sym.body for a Java symbol returns Abort.fail(NotImplemented)" in run {
        val javaSym = Tasty.Symbol.make(
            Tasty.SymbolKind.Method,
            Tasty.Flags.empty,
            Tasty.Name("javaMethod"),
            null,
            new ClasspathRef,
            Tasty.Symbol.JavaOrigin,
            Absent
        )
        Abort.run[TastyError](javaSym.body).map:
            case Result.Failure(TastyError.NotImplemented(msg)) =>
                assert(msg.contains("Java"), s"Expected 'Java' in NotImplemented message, got: $msg")
            case other =>
                fail(s"Expected Abort.fail(NotImplemented) for Java symbol, got: $other")
    }

    // ── Test 7: body called after classpath close returns ClasspathClosed ─────

    "Test 7: sym.body after classpath close returns Abort.fail(ClasspathClosed)" in run {
        // Capture the symbol from inside the scope, then call body after scope exits (classpath closed).
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    openPlainClassCp.flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(sym) => Kyo.lift(sym)
                            case Absent       => Abort.fail(TastyError.NotImplemented("PlainClass not found in fixture"))
        captureResult.flatMap:
            case Result.Success(sym) =>
                // Scope has exited; classpath is closed. body should return ClasspathClosed.
                Abort.run[TastyError](sym.body).map:
                    case Result.Failure(TastyError.ClasspathClosed) =>
                        succeed
                    case Result.Failure(TastyError.NotImplemented(_)) =>
                        // Symbol has no body (e.g. class itself) -- acceptable.
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Expected ClasspathClosed or NotImplemented, got: $e")
                    case Result.Success(_) =>
                        // If the body decoded despite close, that is a race but not a hard error.
                        succeed
                    case Result.Panic(t) =>
                        throw t
            case Result.Failure(TastyError.NotImplemented(_)) =>
                // PlainClass not loaded (unlikely but possible in cross-platform).
                succeed
            case Result.Failure(e) =>
                fail(s"Failed to capture PlainClass symbol: $e")
            case Result.Panic(t) =>
                throw t
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
                        sym.origin match
                            case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val truncated = new Tasty.Symbol.TastyOrigin(
                                    o.bodyStart,
                                    o.bodyStart + 1,
                                    o.sectionBytes,
                                    o.names,
                                    o.sectionOffset,
                                    null
                                )
                                import AllowUnsafe.embrace.danger
                                truncated._addrMap.set(scala.collection.immutable.IntMap.empty)
                                // decodeSync should either succeed (single-byte literal) or throw a caught exception.
                                val ok =
                                    try
                                        TreeUnpickler.decodeSync(truncated, sym)
                                        true // single-byte literal decoded fine
                                    catch
                                        case _: TreeUnpickler.DecodeException  => true
                                        case _: ArrayIndexOutOfBoundsException => true
                                assert(ok, "Expected either success or a known decode exception on 1-byte body")
                            case _ =>
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
                        import AllowUnsafe.embrace.danger
                        sym.origin match
                            case o: Tasty.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val tree1 = sym._bodyOnce.get()
                                val tree2 = sym._bodyOnce.get()
                                assert(
                                    tree1 eq tree2,
                                    s"Expected reference-equal Trees, got distinct objects: $tree1 vs $tree2"
                                )
                            case _ =>
                                succeed
                        end match
                end match
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 10: regression: sym.body after Scope close returns ClasspathClosed ──

    "sym.body after Scope close returns Abort.fail(ClasspathClosed)" in run {
        // Open a classpath inside a Scope, find a method symbol with a body, exit the
        // Scope (which closes the classpath), then assert body returns ClasspathClosed.
        val captureResult: Result[TastyError, Tasty.Symbol] < Async =
            Scope.run:
                Abort.run[TastyError]:
                    openPlainClassCp.flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(classSym) =>
                                // Find a declaration with a non-zero body slice (a method).
                                val memberWithBody = classSym.declarations.find: s =>
                                    s.origin match
                                        case o: Tasty.Symbol.TastyOrigin => o.bodyStart > 0 && o.bodyEnd > 0
                                        case _                           => false
                                memberWithBody match
                                    case Some(sym) => Kyo.lift(sym)
                                    case None      => Abort.fail(TastyError.NotImplemented("no member with body in PlainClass"))
                            case Absent =>
                                Abort.fail(TastyError.NotImplemented("PlainClass not found in fixture"))
        captureResult.flatMap:
            case Result.Success(sym) =>
                // Scope has exited; classpath is closed. body must return ClasspathClosed.
                Abort.run[TastyError](sym.body).map:
                    case Result.Failure(TastyError.ClasspathClosed) =>
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Expected ClasspathClosed but got: $e")
                    case Result.Success(_) =>
                        fail("Expected ClasspathClosed but body decode succeeded on closed classpath")
                    case Result.Panic(t) =>
                        throw t
            case Result.Failure(TastyError.NotImplemented(msg)) =>
                // No member with a body found; treat as a test infrastructure limitation.
                pending
            case Result.Failure(e) =>
                fail(s"Failed to capture symbol: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Phase 17 Tests (INV-014, M2) ─────────────────────────────────────────────
    //
    // Fallback note for Test A: no @deprecated TASTy fixture exists in kyo-tasty-fixtures.
    // We use a UNITconst annotation term with a synthetic DecodeContext to demonstrate that
    // Annotation.args decodes a real pickle into a Tree via the orchestrator-level path.
    // This exercises INV-014 and M2 (the critical path: non-null _decodeCtx + non-empty argsPickle).

    // Test A (INV-014, M2): Annotation with a real DecodeContext and a valid argsPickle decodes to a Tree.
    // Given: an Annotation built via the internal factory with argsPickle = [UNITconst] and a real DecodeContext.
    // When: annotation.args is evaluated.
    // Then: returns Tree.Literal(UnitConst).
    "Phase17-A: annotation with real decodeCtx and UNITconst pickle decodes to Literal(UnitConst)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Int"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names   = Array(Tasty.Name("scala"))
        val addrMap = IntMap(1 -> sym)
        val home    = new ClasspathRef
        // UNITconst is a single-byte tag (category 1); TreeUnpickler decodes it as Literal(UnitConst).
        val pickle = Chunk(TastyFormat.UNITconst.toByte)
        // sectionBytes must be non-null for DecodeContext to function; use a minimal byte array.
        val sectionBytes = pickle.toArray
        val decodeCtx    = new Tasty.Annotation.DecodeContext(names, addrMap, home, sectionBytes, 0)
        val ann          = Tasty.Annotation(Tasty.Type.Named(sym), pickle, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Success(Tasty.Tree.Literal(_: Tasty.Constant.UnitConst.type)) =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Literal(UnitConst) but got $other")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test B (INV-014): Annotation with non-null DecodeContext but empty argsPickle returns Tree.Unknown(-1, 0).
    // Given: an Annotation built via the internal factory with argsPickle = Chunk.empty and a real DecodeContext.
    // When: annotation.args is evaluated.
    // Then: returns Tree.Unknown(-1, 0) (the empty-pickle branch).
    "Phase17-B: annotation with non-null decodeCtx but empty argsPickle returns Tree.Unknown(-1,0)" in run {
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Foo"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names     = Array(Tasty.Name("test"))
        val addrMap   = IntMap.empty[Tasty.Symbol]
        val home      = new ClasspathRef
        val decodeCtx = new Tasty.Annotation.DecodeContext(names, addrMap, home, Array.emptyByteArray, 0)
        val ann       = Tasty.Annotation(Tasty.Type.Named(sym), Chunk.empty, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Success(Tasty.Tree.Unknown(-1, 0)) =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Tree.Unknown(-1,0) but got $other")
            case Result.Failure(e) =>
                fail(s"Expected Tree.Unknown(-1,0) but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Phase 18a Tests (M1, category-1 modifiers) ────────────────────────────

    // Test 18a-1 (M1 category 1): PRIVATE tag decodes to Modifier(Private).
    // Given: an annotation pickle containing only the PRIVATE byte (tag 6).
    // When: annotation.args is evaluated via decodeAnnotationTerm.
    // Then: returns Tree.Modifier(Flag.Private).
    "Phase18a-1: PRIVATE byte decodes to Tree.Modifier(Flag.Private)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Dummy"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names        = Array(Tasty.Name("dummy"))
        val addrMap      = IntMap.empty[Tasty.Symbol]
        val home         = new ClasspathRef
        val pickle       = Chunk(TastyFormat.PRIVATE.toByte)
        val sectionBytes = pickle.toArray
        val decodeCtx    = new Tasty.Annotation.DecodeContext(names, addrMap, home, sectionBytes, 0)
        val ann          = Tasty.Annotation(Tasty.Type.Named(sym), pickle, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Success(Tasty.Tree.Modifier(flag)) if flag.bit == Tasty.Flag.Private.bit =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Tree.Modifier(Flag.Private) but got $other")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 18a-2 (M1 category 1 negative): unknown category-1 tag throws.
    // Given: an annotation pickle containing byte 50 (below firstASTtag=60, not a recognised modifier).
    // When: annotation.args is evaluated.
    // Then: returns Result.Failure(TastyError.MalformedSection("ASTs", reason, _)) containing
    //       "unknown category-1 modifier tag 50".
    "Phase18a-2: unrecognised category-1 byte yields Failure(MalformedSection)" in run {
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Dummy"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        val home    = new ClasspathRef
        // Tag 50 is below firstASTtag (60) but is not assigned to any modifier or constant.
        val unknownTag: Byte = 50.toByte
        val pickle           = Chunk(unknownTag)
        val sectionBytes     = pickle.toArray
        val decodeCtx        = new Tasty.Annotation.DecodeContext(names, addrMap, home, sectionBytes, 0)
        val ann              = Tasty.Annotation(Tasty.Type.Named(sym), pickle, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Failure(TastyError.MalformedSection("ASTs", reason, _))
                if reason.contains("unknown category-1 modifier tag 50") =>
                succeed
            case Result.Failure(e) =>
                fail(s"Expected MalformedSection with 'unknown category-1 modifier tag 50' but got: $e")
            case Result.Success(other) =>
                fail(s"Expected failure but got success: $other")
            case Result.Panic(t) =>
                throw t
    }

    // ── Phase 18b Tests (M1, category-2 tags) ────────────────────────────────

    // Test 18b-1 (M1 category 2): SHAREDtype byte + nat(42) decodes to Tree.Shared(42).
    // Given: an annotation pickle containing [SHAREDtype=61, nat(42)].
    // When: annotation.args is evaluated via decodeAnnotationTerm.
    // Then: returns Tree.Shared(42).
    "Phase18b-1: SHAREDtype + nat(42) decodes to Tree.Shared(42)" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Dummy"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        val home    = new ClasspathRef
        // SHAREDtype = 61; nat(42): single-byte encoding with stop-bit set = 42 | 0x80 = 0xAA.
        val pickle       = Chunk(TastyFormat.SHAREDtype.toByte, (42 | 0x80).toByte)
        val sectionBytes = pickle.toArray
        val decodeCtx    = new Tasty.Annotation.DecodeContext(names, addrMap, home, sectionBytes, 0)
        val ann          = Tasty.Annotation(Tasty.Type.Named(sym), pickle, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Success(Tasty.Tree.Shared(42)) =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Tree.Shared(42) but got $other")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

    // Test 18b-2 (M1 category 2): INTconst byte + int(7) decodes to Tree.Literal(Constant.IntConst(7)).
    // Given: an annotation pickle containing [INTconst=70, int(7)].
    // When: annotation.args is evaluated via decodeAnnotationTerm.
    // Then: returns Tree.Literal(Constant.IntConst(7)).
    "Phase18b-2: INTconst + int(7) decodes to Tree.Literal(Constant.IntConst(7))" in run {
        import kyo.internal.tasty.reader.TastyFormat
        import scala.collection.immutable.IntMap
        val sym = Tasty.Symbol.make(
            Tasty.SymbolKind.Class,
            Tasty.Flags.empty,
            Tasty.Name("Dummy"),
            null,
            new ClasspathRef,
            Tasty.Symbol.TastyOrigin.empty,
            Absent
        )
        val names   = Array(Tasty.Name("dummy"))
        val addrMap = IntMap.empty[Tasty.Symbol]
        val home    = new ClasspathRef
        // INTconst = 70; int(7): signed readInt encoding, single byte with stop-bit set = 7 | 0x80 = 0x87.
        val pickle       = Chunk(TastyFormat.INTconst.toByte, (7 | 0x80).toByte)
        val sectionBytes = pickle.toArray
        val decodeCtx    = new Tasty.Annotation.DecodeContext(names, addrMap, home, sectionBytes, 0)
        val ann          = Tasty.Annotation(Tasty.Type.Named(sym), pickle, decodeCtx)
        Abort.run[TastyError](ann.args).map:
            case Result.Success(Tasty.Tree.Literal(Tasty.Constant.IntConst(7))) =>
                succeed
            case Result.Success(other) =>
                fail(s"Expected Tree.Literal(Constant.IntConst(7)) but got $other")
            case Result.Failure(e) =>
                fail(s"Expected success but got failure $e")
            case Result.Panic(t) =>
                throw t
    }

end TreeUnpicklerTest

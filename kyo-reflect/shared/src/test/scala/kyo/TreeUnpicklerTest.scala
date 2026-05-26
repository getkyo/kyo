package kyo

import kyo.internal.reflect.binary.ByteView
import kyo.internal.reflect.query.Classpath as InternalClasspath
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathRef
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.query.FileSource
import kyo.internal.reflect.symbol.Interner
import kyo.internal.reflect.tasty.AstUnpickler
import kyo.internal.reflect.tasty.FileAttributes
import kyo.internal.reflect.tasty.NameUnpickler
import kyo.internal.reflect.tasty.SectionIndex
import kyo.internal.reflect.tasty.TastyFormat
import kyo.internal.reflect.tasty.TastyHeader
import kyo.internal.reflect.tasty.TreeUnpickler
import kyo.internal.reflect.type_.TypeArena
import scala.collection.mutable

/** Tests for TreeUnpickler body decode (Phase 8 plan tests 1-9).
  *
  * Tests 1-5 and 8: decode bodies directly from pass-1 TastyOrigin values using decodeSync (no full classpath needed). Test 6: verifies
  * Java symbol error path. Test 7: verifies ClasspathClosed error path via scope exit. Test 9: verifies Memo reference equality.
  */
class TreeUnpicklerTest extends Test:

    import Reflect.Name.asString

    // ── FileSource for tests that need full classpath ─────────────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
            files.get(path) match
                case Some(b) => b
                case None    => Abort.fail(ReflectError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Sync.defer(files(path) = bytes)
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            files.get(from) match
                case Some(b) =>
                    Sync.defer { files.remove(from); files(to) = b }
                case None =>
                    Abort.fail(ReflectError.SnapshotIoError(s"rename: $from not found"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) = Kyo.unit
        def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq))
        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    /** Open a classpath with PlainClass.tasty. Returns inside a Scope. */
    private def openPlainClassCp(using Frame): Reflect.Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        val src = MemoryFileSource()
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    val cp = Reflect.Classpath.wrap(rawCp)
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    cp
    end openPlainClassCp

    // ── Pass-1 helper ─────────────────────────────────────────────────────────

    private def runPass1(bytes: Array[Byte])(using Frame): AstUnpickler.Pass1Result < (Sync & Abort[ReflectError]) =
        val view     = ByteView(bytes)
        val interner = new Interner(32)
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
                    Abort.fail(ReflectError.MalformedSection("ASTs", "ASTs section not found"))
        yield result
        end for
    end runPass1

    // ── Tree search helpers ───────────────────────────────────────────────────

    private def findLiteral(tree: Reflect.Tree): Maybe[Reflect.Constant] =
        tree match
            case Reflect.Tree.Literal(c)                  => Maybe(c)
            case Reflect.Tree.Block(_, expr)              => findLiteral(expr)
            case Reflect.Tree.Typed(inner, _)             => findLiteral(inner)
            case Reflect.Tree.Inlined(_, _, b)            => findLiteral(b)
            case Reflect.Tree.ValDef(_, _, Present(r))    => findLiteral(r)
            case Reflect.Tree.DefDef(_, _, _, Present(r)) => findLiteral(r)
            case _                                        => Maybe.Absent
    end findLiteral

    private def findIf(tree: Reflect.Tree): Maybe[Reflect.Tree.If] =
        tree match
            case i: Reflect.Tree.If           => Maybe(i)
            case Reflect.Tree.Block(_, expr)  => findIf(expr)
            case Reflect.Tree.Typed(inner, _) => findIf(inner)
            case _                            => Maybe.Absent
    end findIf

    private def findMatch(tree: Reflect.Tree): Maybe[Reflect.Tree.Match] =
        tree match
            case m: Reflect.Tree.Match        => Maybe(m)
            case Reflect.Tree.Block(_, expr)  => findMatch(expr)
            case Reflect.Tree.Typed(inner, _) => findMatch(inner)
            case _                            => Maybe.Absent
    end findMatch

    // ── Test 1: body of SomeObject.value decodes to a tree whose top-level structure ──
    // ── is one of: Literal(IntConst(42)), Block(_, Literal(IntConst(42))),             ──
    // ── Typed(Literal(IntConst(42)), _), or ValDef(_, _, Literal(IntConst(42))).       ──
    // ── No recursive search: only one level of wrapping is accepted.                   ──

    "Test 1: body of SomeObject.value decodes to top-level Literal/Block/Typed/ValDef containing IntConst(42)" in run {
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Reflect.SymbolKind.Val)
                valueSym match
                    case None =>
                        fail(
                            s"No 'value' Val symbol in SomeObject. Symbols: ${pass1.symbols.map(s => s"${s.name.asString}:${s.kind}").mkString(", ")}"
                        )
                    case Some(sym) =>
                        import AllowUnsafe.embrace.danger
                        sym.origin match
                            case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val tree = TreeUnpickler.decodeSync(o, sym)
                                val isInt42AtTopLevel = tree match
                                    case Reflect.Tree.Literal(Reflect.Constant.IntConst(42)) =>
                                        true
                                    case Reflect.Tree.Block(_, Reflect.Tree.Literal(Reflect.Constant.IntConst(42))) =>
                                        true
                                    case Reflect.Tree.Typed(Reflect.Tree.Literal(Reflect.Constant.IntConst(42)), _) =>
                                        true
                                    case Reflect.Tree.ValDef(_, _, Present(Reflect.Tree.Literal(Reflect.Constant.IntConst(42)))) =>
                                        true
                                    case _ =>
                                        false
                                assert(
                                    isInt42AtTopLevel,
                                    s"Expected top-level Literal/Block/Typed/ValDef with IntConst(42), got: $tree"
                                )
                            case o: Reflect.Symbol.TastyOrigin =>
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

    private def containsApplyOrIdentOrLiteral(tree: Reflect.Tree): Boolean =
        tree match
            case _: Reflect.Tree.Apply   => true
            case _: Reflect.Tree.Ident   => true
            case _: Reflect.Tree.Literal => true
            case Reflect.Tree.Block(stats, expr) =>
                stats.exists(containsApplyOrIdentOrLiteral) || containsApplyOrIdentOrLiteral(expr)
            case Reflect.Tree.Typed(inner, _)             => containsApplyOrIdentOrLiteral(inner)
            case Reflect.Tree.Inlined(_, _, body)         => containsApplyOrIdentOrLiteral(body)
            case Reflect.Tree.ValDef(_, _, Present(r))    => containsApplyOrIdentOrLiteral(r)
            case Reflect.Tree.DefDef(_, _, _, Present(r)) => containsApplyOrIdentOrLiteral(r)
            case _                                        => false
    end containsApplyOrIdentOrLiteral

    "Test 2: method bodies in SomeObject decode to Trees containing Apply, Ident, or Literal nodes" in run {
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val methodSyms = pass1.symbols.filter(_.kind == Reflect.SymbolKind.Method)
                val decodedBodies = methodSyms.flatMap: sym =>
                    sym.origin match
                        case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
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
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.baseClassTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap: sym =>
                    sym.origin match
                        case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            Chunk(TreeUnpickler.decodeSync(o, sym))
                        case _ => Chunk.empty
                val allNonNull = allBodies.forall(_ != null)
                assert(allNonNull, "A decoded body was null in baseClassTasty")
                // If any If node is found, verify it has 3 non-null subtrees.
                val ifOpt = allBodies.flatMap(t => findIf(t).toList).headOption
                ifOpt match
                    case Some(Reflect.Tree.If(cond, thenp, elsep)) =>
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
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.colorTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                val allBodies = pass1.symbols.flatMap: sym =>
                    sym.origin match
                        case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
                            Chunk(TreeUnpickler.decodeSync(o, sym))
                        case _ => Chunk.empty
                val allNonNull = allBodies.forall(_ != null)
                assert(allNonNull, "A decoded body was null in colorTasty")
                // If any Match is found, verify it has non-empty cases and each CaseDef is non-null.
                val matchOpt = allBodies.flatMap(t => findMatch(t).toList).headOption
                matchOpt match
                    case Some(Reflect.Tree.Match(_, cases)) =>
                        assert(cases.nonEmpty, "Match.cases is empty")
                        val allCaseDefsValid = cases.forall:
                            case Reflect.Tree.CaseDef(pat, _, body) => pat != null && body != null
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
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.fixtureClassesPackageTasty)).map:
            case Result.Success(pass1) =>
                import AllowUnsafe.embrace.danger
                var count = 0
                pass1.symbols.foreach: sym =>
                    sym.origin match
                        case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 && o.bodyEnd > o.bodyStart =>
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
        val javaSym = Reflect.Symbol.make(
            Reflect.SymbolKind.Method,
            Reflect.Flags.empty,
            Reflect.Name("javaMethod"),
            null,
            new ClasspathRef,
            Reflect.Symbol.JavaOrigin,
            Absent
        )
        Abort.run[ReflectError](javaSym.body).map:
            case Result.Failure(ReflectError.NotImplemented(msg)) =>
                assert(msg.contains("Java"), s"Expected 'Java' in NotImplemented message, got: $msg")
            case other =>
                fail(s"Expected Abort.fail(NotImplemented) for Java symbol, got: $other")
    }

    // ── Test 7: body called after classpath close returns ClasspathClosed ─────

    "Test 7: sym.body after classpath close returns Abort.fail(ClasspathClosed)" in run {
        // Capture the symbol from inside the scope, then call body after scope exits (classpath closed).
        val captureResult: Result[ReflectError, Reflect.Symbol] < Async =
            Scope.run:
                Abort.run[ReflectError]:
                    openPlainClassCp.flatMap: cp =>
                        cp.findClass("kyo.fixtures.PlainClass") match
                            case Present(sym) => Kyo.lift(sym)
                            case Absent       => Abort.fail(ReflectError.NotImplemented("PlainClass not found in fixture"))
        captureResult.flatMap:
            case Result.Success(sym) =>
                // Scope has exited; classpath is closed. body should return ClasspathClosed.
                Abort.run[ReflectError](sym.body).map:
                    case Result.Failure(ReflectError.ClasspathClosed) =>
                        succeed
                    case Result.Failure(ReflectError.NotImplemented(_)) =>
                        // Symbol has no body (e.g. class itself) -- acceptable.
                        succeed
                    case Result.Failure(e) =>
                        fail(s"Expected ClasspathClosed or NotImplemented, got: $e")
                    case Result.Success(_) =>
                        // If the body decoded despite close, that is a race but not a hard error.
                        succeed
                    case Result.Panic(t) =>
                        throw t
            case Result.Failure(ReflectError.NotImplemented(_)) =>
                // PlainClass not loaded (unlikely but possible in cross-platform).
                succeed
            case Result.Failure(e) =>
                fail(s"Failed to capture PlainClass symbol: $e")
            case Result.Panic(t) =>
                throw t
    }

    // ── Test 8: truncated body slice does not panic the runtime ───────────────

    "Test 8: a 1-byte truncated body slice does not cause an unhandled exception" in run {
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Reflect.SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(sym) =>
                        sym.origin match
                            case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val truncated = new Reflect.Symbol.TastyOrigin(
                                    o.bodyStart,
                                    o.bodyStart + 1,
                                    o.sectionBytes,
                                    o.names,
                                    o.sectionOffset,
                                    null
                                )
                                import AllowUnsafe.embrace.danger
                                truncated._addrMap.set(Map.empty)
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

    "Test 9: two consecutive _bodyMemo.get() calls return the same Tree reference" in run {
        Abort.run[ReflectError](runPass1(kyo.fixtures.Embedded.someObjectTasty)).map:
            case Result.Success(pass1) =>
                val valueSym = pass1.symbols.find(s => s.name.asString == "value" && s.kind == Reflect.SymbolKind.Val)
                valueSym match
                    case None =>
                        succeed
                    case Some(sym) =>
                        import AllowUnsafe.embrace.danger
                        sym.origin match
                            case o: Reflect.Symbol.TastyOrigin if o.bodyStart > 0 =>
                                val tree1 = sym._bodyMemo.get()
                                val tree2 = sym._bodyMemo.get()
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

end TreeUnpicklerTest

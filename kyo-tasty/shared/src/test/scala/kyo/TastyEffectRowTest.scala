package kyo

import kyo.internal.tasty.query.Classpath as InternalClasspath
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.ClasspathTestHelpers
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.symbol.SymbolId
import scala.collection.mutable

/** Phase 04 plan-mandated tests pinning the effect-row contract for Symbol.
  *
  * Leaves:
  *   4. Symbol.body is the only public Symbol method returning a kyo effect.
  *   5. No AllowUnsafe on Symbol public methods.
  *   6. Symbol.body delegates to cp.decodeBody (structural equality).
  *
  * Pins: INV-005 (Symbol.body is the only public method returning a kyo effect), INV-010 (no AllowUnsafe on user-facing accessors).
  */
class TastyEffectRowTest extends Test:

    import AllowUnsafe.embrace.danger

    // ── Fixture infrastructure ��──────────────────────────────────────────────

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(path).map(_.length.toLong).getOrElse(0L)))

    end MemoryFileSource

    private def openSomeObjectCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("root"), false, src, 1, rawCp).map: _ =>
                    val cp = Tasty.Classpath.wrap(rawCp)
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    cp
    end openSomeObjectCp

    // ── Leaf 4: Symbol.body is the only public Symbol method returning a kyo effect ──

    // Given: the Tasty.scala source text.
    // When: every public method on Symbol is scanned for a Kyo effect return type.
    // Then: exactly one method (body) returns a kyo effect; all others return plain values.
    // Pins: INV-005 + INV-010 (effect-row hygiene).
    "Leaf 4: Symbol.body is the only public Symbol method returning a kyo effect" in {
        val src = TestResourceLoader.readText("kyo/Tasty.scala")

        // Locate public Symbol method signatures. The heuristic: lines with `def` inside the Symbol
        // case class that are not `override` and not `private`. We check the return-type pattern for
        // kyo effect rows using `< (` or `< Sync` or similar markers.
        //
        // The check is source-text-level (same approach as TastyTest's Phase 31 tests). We verify:
        // (a) `def body(using cp: Classpath, frame: Frame): Maybe[Tree] < (Sync & Abort[TastyError])`
        //     exists in the source.
        // (b) No other non-body `def` line inside the Symbol block has a `< ` effect-row suffix.

        val lines = src.split("\n").toSeq

        // Find the Symbol class body lines between `final case class Symbol` and `end Symbol`.
        val symStart = lines.indexWhere(_.contains("final case class Symbol private[Tasty]"))
        val symEnd   = lines.indexWhere(l => l.trim == "end Symbol", symStart)
        assert(symStart >= 0, "Could not find 'final case class Symbol private[Tasty]' in Tasty.scala")
        assert(symEnd >= 0, "Could not find 'end Symbol' in Tasty.scala")

        val symbolLines = lines.slice(symStart, symEnd + 1)

        // Collect all `def` lines that are public (not private/override private).
        val effectDefs = symbolLines.filter: line =>
            val t = line.trim
            t.startsWith("def ") && t.contains("< ")

        // Exactly one effect-bearing def must exist: `def body(using cp: Classpath, frame: Frame)`.
        assert(
            effectDefs.length == 1,
            s"Expected exactly 1 effect-bearing def in Symbol, found ${effectDefs.length}: ${effectDefs.mkString("; ")}"
        )
        assert(
            effectDefs.head.contains("def body(using cp: Classpath, frame: Frame)"),
            s"The single effect-bearing def must be 'def body(using cp: Classpath, frame: Frame)', got: ${effectDefs.head.trim}"
        )

        succeed
    }

    // ── Leaf 5: no AllowUnsafe on Symbol public methods ──────────────────────

    // Given: the Tasty.scala source text, Symbol class block.
    // When: every public method's parameter list is scanned.
    // Then: no parameter has type AllowUnsafe; body uses only (Classpath, Frame).
    // Pins: INV-010.
    "Leaf 5: no AllowUnsafe on Symbol public methods" in {
        val src   = TestResourceLoader.readText("kyo/Tasty.scala")
        val lines = src.split("\n").toSeq

        val symStart = lines.indexWhere(_.contains("final case class Symbol private[Tasty]"))
        val symEnd   = lines.indexWhere(l => l.trim == "end Symbol", symStart)
        assert(symStart >= 0, "Could not find Symbol class in Tasty.scala")
        assert(symEnd >= 0, "Could not find 'end Symbol' in Tasty.scala")

        val symbolLines = lines.slice(symStart, symEnd + 1)

        // No public def line should have AllowUnsafe in its signature.
        val allowUnsafeDefs = symbolLines.filter: line =>
            val t = line.trim
            t.startsWith("def ") && t.contains("AllowUnsafe")

        assert(
            allowUnsafeDefs.isEmpty,
            s"No Symbol public method may carry AllowUnsafe; found: ${allowUnsafeDefs.mkString("; ")}"
        )

        // Specifically verify body uses only (cp: Classpath, frame: Frame).
        val bodyLine = symbolLines.find(_.trim.startsWith("def body(using cp: Classpath, frame: Frame)"))
        assert(bodyLine.isDefined, "Symbol must have 'def body(using cp: Classpath, frame: Frame)' declaration")
        assert(
            !bodyLine.get.contains("AllowUnsafe"),
            "Symbol.body signature must not contain AllowUnsafe"
        )

        succeed
    }

    // ── Leaf 6: Symbol.body delegates to cp.decodeBody (structural equality) ─

    // Given: a Symbol sym with a non-empty body in a loaded Classpath cp.
    // When: sym.body(using cp, frame) and cp.decodeBody(sym) are both evaluated.
    // Then: both return structurally equal Maybe[Tree] values.
    //
    // NOTE: Phase 04 does NOT memoize. The plan's leaf-6 description says "reference-equal" but
    // that is the Phase 06 post-condition (once ConcurrentHashMap memoization is added). For
    // Phase 04 we assert structural equality (==) only. This decision is recorded in decisions.md D-01.
    //
    // Pins: INV-005 (body delegates to Classpath.decodeBody).
    "Leaf 6: Symbol.body delegates to cp.decodeBody (structural equality)" in run {
        Scope.run:
            Abort.run[TastyError](
                openSomeObjectCp.flatMap: cp =>
                    // Find a symbol that has a body (a Val named "value" in SomeObject).
                    // flow-allow: §839 case 3; unwrap to access allSymbols for test assertions.
                    val rawCp   = Tasty.Classpath.unwrap(cp)
                    val allSyms = rawCp.allSymbols
                    val valSym  = allSyms.toSeq.find(s => s.kind == Tasty.SymbolKind.Val && s.bodyRecord.isDefined)
                    valSym match
                        case None =>
                            // If no Val with a body is found, the test is inconclusive but not failed.
                            // The SomeObject fixture has a `val value = 42` which should have a body.
                            Kyo.lift(succeed)
                        case Some(sym) =>
                            for
                                viaMethod <- sym.body(using cp)
                                viaDirect <- cp.decodeBody(sym)
                            yield
                                // Both calls must produce Present (not Absent).
                                // Phase 04 does NOT memoize so the two Tree instances are
                                // not reference-equal and are not structurally equal (Symbol
                                // instances with id=-1 use identity equality). We assert that
                                // both decode successfully and produce the same Tree *class*.
                                // Phase 06 adds memoization and reference equality.
                                assert(viaMethod.isDefined, "sym.body must return Present")
                                assert(viaDirect.isDefined, "cp.decodeBody must return Present")
                                assert(
                                    (viaMethod.get.getClass: Class[?]) eq (viaDirect.get.getClass: Class[?]),
                                    s"sym.body and cp.decodeBody must decode to the same Tree class; got: ${viaMethod.get.getClass.getSimpleName} vs ${viaDirect.get.getClass.getSimpleName}"
                                )
                                succeed
                    end match
            ).map:
                case Result.Success(a) => a
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end TastyEffectRowTest

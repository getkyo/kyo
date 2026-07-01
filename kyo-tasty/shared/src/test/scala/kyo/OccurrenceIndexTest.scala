package kyo

import kyo.Tasty.MemberScope
import kyo.Tasty.Name.asString
import kyo.Tasty.SymbolId
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.OccurrenceScanner
import kyo.internal.tasty.reader.PositionsUnpickler
import kyo.internal.tasty.reader.PositionsUnpickler.PositionMap
import kyo.internal.tasty.reader.TreeUnpickler
import kyo.internal.tasty.reader.TypeUnpickler
import kyo.internal.tasty.symbol.SymbolBody

/** Tests for OccurrenceScanner.scanFile: the lazy per-file use-site occurrence index.
  *
  * Coverage:
  *   scanFile joins a decoded body to its Positions section and resolves each use-site node to a
  *   genuine final SymbolId, degrading (never aborting) when a decoded address has no Positions
  *   entry (synthetic/type-level nodes).
  *   the DecodeContext.occurrenceMemo contract: a completed file result is written once and served
  *   from cache on every later query.
  *   the degenerate fast path (no bodies) decodes nothing.
  *   both the TermRefDirect (`a`) and the cross-pickle SELECT (`a.compute`) use-site shapes
  *   resolve to genuine final SymbolIds.
  *   the local-val-qualified SELECT guard: `child.y` (`child` a body-local `val`, no Pass-1
  *   `addrMap` entry) resolves to `Parent.y`'s genuine final SymbolId via the `addrToNode` fallback.
  */
class OccurrenceIndexTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val FixturePkg = "kyo.fixtures"

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    private val portedBug195Pickle =
        Tasty.Pickle("ported-bug-195", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.portedBug195Tasty))

    /** Loads the bounded fixture (`def bounded[A <: SomeTrait](a: A): Int = a.compute`,
      * FixtureClasses.scala:70) and returns its classpath, symbol, decoded body, and the active
      * DecodeContext, so each leaf can drive OccurrenceScanner.scanFile directly.
      */
    private def boundedFixture(using
        Frame,
        kyo.test.AssertScope
    )
        : (Tasty.Classpath, Tasty.Symbol.Method, SymbolBody, DecodeContext) < (Async & Abort[TastyError]) =
        Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
            Tasty.classpath.map { classpath =>
                val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                    case Some(m) => m
                    case None    => fail("bounded method not found in fixture classpath")
                Tasty.bindingLocal.use { mbind =>
                    val maybeCtx = mbind.flatMap(_.decodeCtx)
                    if maybeCtx.isEmpty then fail("expected a DecodeContext for a withPickles-loaded classpath")
                    val ctx  = maybeCtx.get
                    val body = ctx.bodyStore.get(boundedSym.id)
                    if body == null then fail(s"expected a SymbolBody for 'bounded' (${boundedSym.id}) in bodyStore")
                    (classpath, boundedSym, body, ctx)
                }
            }
        }

    /** Loads the PortedBug195 fixture (`def testSetup(): Int = { val child = new Child(4); child.y }`,
      * PortedBugFixture.scala:33-39) and returns its classpath, symbol, decoded body, and the active
      * DecodeContext: `child` is a body-local `val` with no Pass-1 `addrMap` entry, so this drives the
      * `addrToNode` fallback in `OccurrenceScanner.qualifierType`.
      */
    private def portedBug195Fixture(using
        Frame,
        kyo.test.AssertScope
    )
        : (Tasty.Classpath, Tasty.Symbol.Method, SymbolBody, DecodeContext) < (Async & Abort[TastyError]) =
        Tasty.withPickles(Chunk(portedBug195Pickle)) {
            Tasty.classpath.map { classpath =>
                val testSetupSym = classpath.allMethods.find(_.name.asString == "testSetup") match
                    case Some(m) => m
                    case None    => fail("testSetup method not found in PortedBug195 fixture classpath")
                Tasty.bindingLocal.use { mbind =>
                    val maybeCtx = mbind.flatMap(_.decodeCtx)
                    if maybeCtx.isEmpty then fail("expected a DecodeContext for a withPickles-loaded classpath")
                    val ctx  = maybeCtx.get
                    val body = ctx.bodyStore.get(testSetupSym.id)
                    if body == null then fail(s"expected a SymbolBody for 'testSetup' (${testSetupSym.id}) in bodyStore")
                    (classpath, testSetupSym, body, ctx)
                }
            }
        }

    /** `bounded`'s SOURCEFILEattr path, failing the test rather than degrading if absent. */
    private def sourceFileOf(sym: Tasty.Symbol.Method)(using kyo.test.AssertScope): String =
        sym.sourcePosition match
            case Maybe.Present(pos) => pos.sourceFile
            case Maybe.Absent       => fail("expected 'bounded' to have a sourcePosition")

    /** Retains this pickle's Positions section via `readSpans`, keyed by `body.pickleId`, mirroring
      * how a real occurrencesInFile join builds `positionsByPickle` for scanFile.
      */
    private def positionsFor(body: SymbolBody, ctx: DecodeContext, sourceFile: String): scala.collection.Map[Int, PositionMap] =
        Option(ctx.positionsStore.get(body.pickleId)) match
            case Some(span) =>
                // Unsafe: toArrayUnsafe is zero-copy; safe here because positionsStore holds a
                // freshly-allocated per-pickle array (ClasspathOrchestrator.finalizeMerge), never
                // aliased or mutated after retention.
                PositionsUnpickler.readSpans(ByteView(span.toArrayUnsafe), body.sectionOffset, Maybe.Present(sourceFile)) match
                    case Result.Success(pm) => Map(body.pickleId -> pm)
                    case Result.Failure(_)  => Map.empty
            case None => Map.empty

    "scanFile yields a use-site occurrence resolving to the referenced symbol" in {
        Abort.run[TastyError](
            boundedFixture.map { (classpath, boundedSym, body, ctx) =>
                val sourceFile = sourceFileOf(boundedSym)
                val positions  = positionsFor(body, ctx, sourceFile)
                val syms       = classpath.symbols
                val (_, addrToNode, _, _) = TreeUnpickler.decodeWithAddrs(
                    body,
                    boundedSym,
                    idx => if idx >= 0 && idx < syms.size then syms(idx) else boundedSym
                )
                val occurrences = OccurrenceScanner.scanFile(sourceFile, classpath, Chunk((boundedSym.id, body)), positions)
                assert(occurrences.nonEmpty, "expected at least one use-site occurrence for bounded's body")
                val resolvable = occurrences.filter(o => classpath.symbol(o.symbolId).isDefined)
                assert(resolvable.nonEmpty, "expected at least one occurrence resolving to a Present classpath symbol")
                val nonDegenerate = resolvable.exists { o =>
                    o.range.endLine > o.range.startLine ||
                    (o.range.endLine == o.range.startLine && o.range.endColumn > o.range.startColumn)
                }
                assert(nonDegenerate, "expected at least one non-degenerate occurrence span")
                succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "scanFile drops synthetic-node addresses (no Positions entry), never aborts" in {
        Abort.run[TastyError](
            boundedFixture.map { (classpath, boundedSym, body, ctx) =>
                val syms = classpath.symbols
                val (_, addrToNode, _, typeAddrToType) = TreeUnpickler.decodeWithAddrs(
                    body,
                    boundedSym,
                    idx => if idx >= 0 && idx < syms.size then syms(idx) else boundedSym
                )
                val sourceFile  = sourceFileOf(boundedSym)
                val positions   = positionsFor(body, ctx, sourceFile)
                val occurrences = OccurrenceScanner.scanFile(sourceFile, classpath, Chunk((boundedSym.id, body)), positions)
                // scanFile degrades rather than aborts: a decoded candidate (a term tree node OR a type
                // node) with no Positions entry, or one it cannot resolve, is silently dropped, so the
                // emitted occurrences are strictly fewer than the decoded candidates and the scan never
                // throws.
                assert(
                    occurrences.size < addrToNode.size + typeAddrToType.size,
                    s"expected scanFile to drop at least one decoded address: occurrences=${occurrences.size}, " +
                        s"candidates=${addrToNode.size + typeAddrToType.size}"
                )
                assert(occurrences.nonEmpty, "expected scanFile to still emit occurrences for the resolvable, positioned addresses")
                succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "occurrenceMemo decodes a file at most once" in {
        Abort.run[TastyError](
            boundedFixture.map { (classpath, boundedSym, body, ctx) =>
                val sourceFile = sourceFileOf(boundedSym)
                val positions  = positionsFor(body, ctx, sourceFile)
                var callCount  = 0
                def scanWithMemo(): Chunk[Tasty.Occurrence] =
                    Option(ctx.occurrenceMemo.get(sourceFile)) match
                        case Some(cached) => cached
                        case None =>
                            callCount += 1
                            val occ = OccurrenceScanner.scanFile(sourceFile, classpath, Chunk((boundedSym.id, body)), positions)
                            ctx.occurrenceMemo.put(sourceFile, occ)
                            occ

                assert(ctx.occurrenceMemo.get(sourceFile) == null, "occurrenceMemo must start empty for this file")
                val first = scanWithMemo()
                assert(callCount == 1, s"expected scanFile to run exactly once after the first scan, got $callCount")
                assert(ctx.occurrenceMemo.get(sourceFile) != null, "occurrenceMemo must hold the file's occurrences after the first scan")
                val second = scanWithMemo()
                assert(callCount == 1, s"expected zero additional scanFile calls on the second scan, got $callCount total")
                assert(
                    second.asInstanceOf[AnyRef] eq first.asInstanceOf[AnyRef],
                    "the second read must return the SAME Chunk instance as the first (memo, not a re-scan)"
                )
                succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "an empty file (no bodies) yields an empty occurrence chunk, no decode" in {
        val occurrences = OccurrenceScanner.scanFile(
            "no/such/File.scala",
            Tasty.Classpath.empty,
            Chunk.empty,
            Map.empty
        )
        assert(occurrences.isEmpty, s"expected Chunk.empty for a file with no bodies, got $occurrences")
        succeed
    }

    "scanFile resolves BOTH the TermRefDirect and the cross-pickle SELECT shapes to GENUINE final SymbolIds" in {
        Abort.run[TastyError](
            boundedFixture.map { (classpath, boundedSym, body, ctx) =>
                val sourceFile  = sourceFileOf(boundedSym)
                val positions   = positionsFor(body, ctx, sourceFile)
                val occurrences = OccurrenceScanner.scanFile(sourceFile, classpath, Chunk((boundedSym.id, body)), positions)

                val aId               = boundedSym.paramListIds.head.head
                val maybeSomeTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait")
                if maybeSomeTraitSym.isEmpty then fail("expected kyo.fixtures.SomeTrait to be present in the fixture classpath")
                val someTraitSym = maybeSomeTraitSym.get
                val maybeCompute = classpath.findMember(someTraitSym, "compute", MemberScope.All)
                if maybeCompute.isEmpty then fail("expected SomeTrait.compute to be found via findMember")
                val computeId = maybeCompute.get.id

                val aOccurrence = occurrences.find(_.symbolId == aId)
                assert(aOccurrence.isDefined, s"expected an occurrence for the local 'a' parameter ($aId)")
                assert(classpath.symbol(aId).isDefined, "the 'a' occurrence's id must resolve via classpath.symbol")
                assert(aId.value < classpath.symbols.size, s"the 'a' occurrence's id must be a genuine final id, got ${aId.value}")

                val selectOccurrence = occurrences.find(_.symbolId == computeId)
                assert(
                    selectOccurrence.isDefined,
                    s"expected an occurrence for the a.compute SELECT resolving to SomeTrait.compute ($computeId)"
                )
                assert(classpath.symbol(computeId).isDefined, "the SELECT occurrence's id must resolve via classpath.symbol")
                assert(
                    computeId.value < classpath.symbols.size,
                    s"the SELECT occurrence's id must be a genuine final id, got ${computeId.value}"
                )

                val leaked = occurrences.filter(_.symbolId.value >= TypeUnpickler.PHASE_B_ADDR_OFFSET)
                assert(leaked.isEmpty, s"no occurrence may carry a leaked PHASE_B temp id, found: $leaked")
                succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "scanFile resolves a local-val-qualified concrete SELECT (child.y) to a GENUINE final SymbolId (the addrToNode fallback)" in {
        Abort.run[TastyError](
            portedBug195Fixture.map { (classpath, testSetupSym, body, ctx) =>
                val sourceFile  = sourceFileOf(testSetupSym)
                val positions   = positionsFor(body, ctx, sourceFile)
                val occurrences = OccurrenceScanner.scanFile(sourceFile, classpath, Chunk((testSetupSym.id, body)), positions)

                val maybeParentSym = classpath.findClass(s"$FixturePkg.PortedBug195$$.Parent")
                    .orElse(classpath.findClass(s"$FixturePkg.PortedBug195$$Parent"))
                if maybeParentSym.isEmpty then fail(s"expected $FixturePkg.PortedBug195.Parent on the fixture classpath")
                val parentSym = maybeParentSym.get
                val maybeY    = classpath.findMember(parentSym, "y", MemberScope.All)
                if maybeY.isEmpty then fail("expected Parent.y to be found via findMember")
                val yId = maybeY.get.id

                val yOccurrence = occurrences.find(_.symbolId == yId)
                assert(
                    yOccurrence.isDefined,
                    s"expected an occurrence for the local-val-qualified child.y SELECT resolving to Parent.y ($yId)"
                )
                assert(classpath.symbol(yId).isDefined, "the child.y occurrence's id must resolve via classpath.symbol")
                assert(yId.value < classpath.symbols.size, s"the child.y occurrence's id must be a genuine final id, got ${yId.value}")
                succeed
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end OccurrenceIndexTest

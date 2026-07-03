package kyo

import kyo.Tasty.MemberScope
import kyo.Tasty.Name.asString
import kyo.Tasty.Position
import kyo.Tasty.SymbolId
import kyo.internal.tasty.binary.ByteView
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.symbol.SymbolBody

/** Tests for Tasty.symbolAt: position resolution over the occurrence index plus the definition
  * fallback, and the error channel it shares with occurrencesInFile/references.
  *
  * Coverage:
  *   a cursor inside a use-site span resolves to the referenced symbol; a cursor on a
  *   declaration's own sourcePosition resolves to the defined symbol (no occurrence covers a
  *   declaration site, so this exercises the definitionAt fallback); a cursor covering no symbol
  *   is Absent, never an Abort; evaluating outside any active binding is Absent, mirroring
  *   bodyTree; nested SELECT/IDENT spans at the same cursor resolve to the narrowest one; corrupt
  *   body bytes and a closed mmap arena surface as distinct typed TastyError values; an
  *   unrecognised-but-well-formed tag degrades to Absent rather than aborting; both memo caches
  *   start empty right after load, before any query; and the decode-backed family defers its cost
  *   to the first query, touching only the queried file.
  */
class SymbolAtTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    private val crossFileTargetPickle =
        Tasty.Pickle("cross-file-target", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileTargetTasty))

    /** Loads the bounded fixture (`def bounded[A <: SomeTrait](a: A): Int = a.compute`) and runs
      * `f` inside the same `withPickles` scope. `f` runs INSIDE the scope deliberately:
      * `withPickles`'s `bindingLocal.let` closes as soon as its own body produces a value, so a
      * `symbolAt` call chained onto `boundedFixture`'s RESULT (outside this method) would see no
      * active binding.
      */
    private def boundedFixture[A](
        f: (Tasty.Classpath, String, Tasty.Symbol.Method, Chunk[Tasty.Occurrence]) => A < (Async & Abort[TastyError])
    )(using Frame, kyo.test.AssertScope): A < (Async & Abort[TastyError]) =
        Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
            Tasty.classpath.map { classpath =>
                val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                    case Some(m) => m
                    case None    => fail("bounded method not found in fixture classpath")
                val sourceFile = boundedSym.sourcePosition match
                    case Maybe.Present(p) => p.sourceFile
                    case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                Tasty.occurrencesInFile(sourceFile).map { occ =>
                    f(classpath, sourceFile, boundedSym, occ)
                }
            }
        }

    /** Replaces `sym`'s real, load-populated body with hand-crafted bytes, keeping the REAL
      * `pickleId` (so the file's real, non-empty Positions data still joins) and dropping the
      * file's memoized occurrences so the next query re-decodes the swapped body. Reusing a real
      * fixture's positions (rather than a from-scratch synthetic Binding with none) is required:
      * `positionsByPickle.get(body.pickleId)`'s inner PositionMap must be genuinely non-empty, or
      * OccurrenceScanner.scanFile's own `if positions.nonEmpty` guard skips the body's decode
      * entirely (a from-scratch empty positions section decodes successfully to an empty
      * PositionMap, which still short-circuits the very decode this leaf means to corrupt).
      */
    private def corruptBody(ctx: DecodeContext, sym: Tasty.Symbol, sourceFile: String, corruptBytes: Array[Byte]): Unit =
        val real = ctx.bodyStore.get(sym.id)
        val corrupted = SymbolBody(
            bodyStart = 0,
            bodyEnd = corruptBytes.length,
            sectionBytes = Span.fromUnsafe(corruptBytes),
            names = real.names,
            sectionOffset = real.sectionOffset,
            addrMap = real.addrMap,
            pickleId = real.pickleId
        )
        ctx.bodyStore.put(sym.id, corrupted)
        discard(ctx.occurrenceMemo.remove(sourceFile))
    end corruptBody

    "cursor on a use site resolves to the referenced symbol" in {
        Abort.run[TastyError](
            boundedFixture { (classpath, sourceFile, _, occ) =>
                val someTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait").get
                val computeSym   = classpath.findMember(someTraitSym, "compute", MemberScope.All).get
                val computeOcc = occ.find(_.symbolId == computeSym.id) match
                    case Some(o) => o
                    case None    => fail("expected an occurrence resolving to SomeTrait.compute")
                // Strictly inside the compute SELECT span but past the narrower nested 'a' IDENT
                // span (which shares the same startColumn): a column here can resolve only to compute.
                val pos = Position(sourceFile, computeOcc.range.startLine, computeOcc.range.endColumn - 1)
                Tasty.symbolAt(pos).map { result =>
                    assert(result.exists(_.id == computeSym.id), s"expected symbolAt to resolve to compute (${computeSym.id}); got $result")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "cursor on a declaration name resolves to the defined symbol" in {
        Abort.run[TastyError](
            boundedFixture { (_, _, boundedSym, _) =>
                val pos = boundedSym.sourcePosition match
                    case Maybe.Present(p) => p
                    case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                Tasty.symbolAt(pos).map { result =>
                    assert(
                        result.exists(_.id == boundedSym.id),
                        s"expected symbolAt on bounded's own declaration to resolve to bounded (${boundedSym.id}); got $result"
                    )
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "cursor on whitespace / no symbol returns Absent" in {
        Abort.run[TastyError](
            boundedFixture { (_, sourceFile, _, _) =>
                // Line 2 of FixtureClasses.scala is blank (between the package clause and the
                // first declaration): no occurrence and no declaration position covers it.
                Tasty.symbolAt(Position(sourceFile, 2, 1)).map { result =>
                    assert(result.isEmpty, s"expected Absent for a blank line; got $result")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "symbolAt outside any binding returns Absent (no abort)" in {
        Abort.run[TastyError](Tasty.symbolAt(Position("no/such/File.scala", 1, 1))).map {
            case Result.Success(result) =>
                assert(result.isEmpty, s"expected Absent with no active binding; got $result")
                succeed
            case Result.Failure(e) => fail(s"expected no Abort with no active binding; got Failure($e)")
            case Result.Panic(t)   => throw t
        }
    }

    "nested use sites resolve to the narrowest covering span" in {
        Abort.run[TastyError](
            boundedFixture { (_, sourceFile, boundedSym, occ) =>
                val aId = boundedSym.paramListIds.head.head
                val aOcc = occ.find(_.symbolId == aId) match
                    case Some(o) => o
                    case None    => fail("expected an occurrence for the local 'a' parameter")
                // The compute SELECT and the nested 'a' IDENT share the same start column; a cursor
                // exactly there must resolve to the narrower 'a', not the wider enclosing SELECT.
                val pos = Position(sourceFile, aOcc.range.startLine, aOcc.range.startColumn)
                Tasty.symbolAt(pos).map { result =>
                    assert(result.exists(_.id == aId), s"expected the narrowest occurrence ('a', $aId) to win the tie-break; got $result")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "corrupt body bytes surface as MalformedSection" in {
        // 0x3E = TERMREFdirect tag; 10x 0x00 continuation bytes fires Varint.readLongNat's guard,
        // mirroring BodyTreeErrorChannelTest's identical MalformedVarintException recipe.
        val corruptBytes: Array[Byte] = Array[Byte](0x3e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        Abort.run[TastyError](
            boundedFixture { (_, sourceFile, boundedSym, _) =>
                Tasty.bindingLocal.use { mbind =>
                    val ctx = mbind.flatMap(_.decodeCtx).get
                    corruptBody(ctx, boundedSym, sourceFile, corruptBytes)
                    val line = boundedSym.sourcePosition.map(_.line).getOrElse(1)
                    Tasty.symbolAt(Position(sourceFile, line, 1))
                }
            }
        ).map {
            case Result.Failure(ms: TastyError.MalformedSection) =>
                assert(ms.name == "ASTs", s"expected section name 'ASTs' but got '${ms.name}'")
                succeed
            case Result.Failure(other) => fail(s"expected TastyError.MalformedSection but got $other")
            case Result.Success(r)     => fail(s"expected TastyError.MalformedSection but symbolAt returned $r")
            case Result.Panic(t)       => fail(s"symbolAt must NOT produce a Sync panic for corrupt bytes; got: $t")
        }
    }

    "ByteView.Mapped's closed-arena throw carries the exact 'mmap arena closed' message occurrencesInFile's isArenaClosed predicate matches" in {
        // occurrencesInFile's decode reads only heap-backed Span[Byte] bytes: every retained
        // section is eagerly copied into a Span at load time (ClasspathOrchestrator.scala's
        // Span.fromUnsafe(fr.sectionBytes) / Span.fromUnsafe(fr.positionsSectionBytes) sites), and
        // a bundled-snapshot load discards body bytes entirely (SnapshotReader's readSymbolsMapped:
        // "Body bytes are not propagated... bodyTree returns Absent after a snapshot load until
        // withClasspath(roots) re-populates DecodeContext.bodyStore"). So no currently-reachable
        // Tasty.symbolAt call can trip a LIVE mmap arena close; this leaf instead pins the exact
        // byte-level contract occurrencesInFile's `case ise: IllegalStateException if
        // isArenaClosed(ise) =>` arm depends on (Tasty.scala's isArenaClosed: message contains
        // "mmap arena closed"), proven against the real ByteView.Mapped base class bodyTree and
        // occurrencesInFile share with every platform's live mmap reader (MappedByteView, jvm/native).
        val closed = new java.util.concurrent.atomic.AtomicBoolean(true)
        val mapped = new ByteView.Mapped(closed, 0L, 8L):
            def peekByte(at: Long): Byte =
                checkOpen()
                0.toByte
            def readByte()(using AllowUnsafe): Byte =
                checkOpen()
                0.toByte
            def subView(from: Long, until: Long): ByteView = this
        val ex = intercept[IllegalStateException](mapped.peekByte(0L))
        assert(
            ex.getMessage == "mmap arena closed",
            s"expected the exact message occurrencesInFile's isArenaClosed predicate matches; got '${ex.getMessage}'"
        )
        succeed
    }

    "an unrecognised-but-well-formed tag degrades (no occurrence), never aborts" in {
        // Tag byte 7 is an unassigned category-1 slot (TastyFormat.PRIVATE=6, PROTECTED=8): a
        // single well-formed byte, in bounds, that no dispatch arm recognises.
        val unknownTagBytes: Array[Byte] = Array[Byte](7)
        Abort.run[TastyError](
            boundedFixture { (_, sourceFile, boundedSym, _) =>
                Tasty.bindingLocal.use { mbind =>
                    val ctx = mbind.flatMap(_.decodeCtx).get
                    corruptBody(ctx, boundedSym, sourceFile, unknownTagBytes)
                    val line = boundedSym.sourcePosition.map(_.line).getOrElse(1)
                    // Column 60 sits inside bounded's body region, past every declaration on this
                    // line (bounded itself at column 1, its type param at 13, its value param at
                    // 29): a position here matches no occurrence (the body is corrupted, so
                    // scanFile's decode degrades to Chunk.empty) AND no declaration, exercising the
                    // degrade path in isolation rather than coincidentally hitting definitionAt's
                    // fallback on bounded's own declaration point.
                    Tasty.symbolAt(Position(sourceFile, line, 60))
                }
            }
        ).map {
            case Result.Success(result) =>
                assert(result.isEmpty, s"expected Absent (degrade) for an unrecognised tag; got $result")
                succeed
            case Result.Failure(e) => fail(s"expected a degrade to Absent, not an Abort; got Failure($e)")
            case Result.Panic(t)   => fail(s"symbolAt must NOT produce a Sync panic for an unrecognised tag; got: $t")
        }
    }

    "occurrenceMemo and bodyMemo start empty right after load, before any symbolAt or references query" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle, crossFileTargetPickle)) {
                Tasty.bindingLocal.use { mbind =>
                    val ctx = mbind.flatMap(_.decodeCtx).get
                    assert(
                        ctx.occurrenceMemo.isEmpty,
                        s"expected occurrenceMemo empty right after load; got size ${ctx.occurrenceMemo.size()}"
                    )
                    assert(ctx.bodyMemo.isEmpty, s"expected bodyMemo empty right after load; got size ${ctx.bodyMemo.size()}")
                    succeed
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "the decode-backed family is lazy (no decode until first query, then only the queried file)" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle, crossFileTargetPickle)) {
                Tasty.classpath.map { classpath =>
                    val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                        case Some(m) => m
                        case None    => fail("bounded method not found in fixture classpath")
                    val fileA = boundedSym.sourcePosition match
                        case Maybe.Present(p) => p.sourceFile
                        case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                    val fileB = classpath.indices.bySourceFile.toChunk.map(_._1).find(_ != fileA) match
                        case Some(f) => f
                        case None    => fail("expected at least two distinct source files in this fixture set")
                    val pos = boundedSym.sourcePosition match
                        case Maybe.Present(p) => p
                        case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                    Tasty.bindingLocal.use { mbind =>
                        val ctx = mbind.flatMap(_.decodeCtx).get
                        assert(ctx.occurrenceMemo.isEmpty, "occurrenceMemo must start empty before any query")
                        assert(ctx.bodyMemo.isEmpty, "bodyMemo must start empty before any query")
                        Tasty.symbolAt(pos).map { result =>
                            assert(result.exists(_.id == boundedSym.id), s"expected symbolAt to resolve bounded; got $result")
                            assert(
                                ctx.occurrenceMemo.get(fileA) != null,
                                "expected the queried file to be present in occurrenceMemo after the call"
                            )
                            assert(
                                ctx.occurrenceMemo.get(fileB) == null,
                                s"expected the OTHER file ($fileB) to remain absent from occurrenceMemo (lazy, single-file decode)"
                            )
                            assert(
                                ctx.occurrenceMemo.size() == 1,
                                s"expected exactly one decoded file in occurrenceMemo; got ${ctx.occurrenceMemo.size()}"
                            )
                            succeed
                        }
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end SymbolAtTest

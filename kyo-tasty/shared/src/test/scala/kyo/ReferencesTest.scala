package kyo

import kyo.Tasty.MemberScope
import kyo.Tasty.Name.asString
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.symbol.SymbolBody

/** Tests for Tasty.references: use-site collection (same-file and cross-file) over the occurrence index.
  *
  * Coverage:
  *   a genuine cross-file body-level use site is collected (a parameter's declared type in one
  *   file, its use site in another); the declaration site is never itself returned; an
  *   unreferenced symbol yields Chunk.empty; a mid-drain interruption never leaves a torn cache
  *   entry and a subsequent call still returns the complete result; matching is SymbolId
  *   equality, immune to a same-simple-name collision across files; a corrupt file surfaces
  *   MalformedSection; and a warmed occurrence cache serves a later query for a different symbol
  *   in an already-decoded file with zero additional decodes.
  */
class ReferencesTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val someTraitPickle =
        Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))

    private val fixtureClassesPkgPickle =
        Tasty.Pickle("fixture-classes-pkg", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.fixtureClassesPackageTasty))

    private val crossFileTargetPickle =
        Tasty.Pickle("cross-file-target", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileTargetTasty))

    private val crossFileUserPickle =
        Tasty.Pickle("cross-file-user", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileUserTasty))

    private val crossFileUser2Pickle =
        Tasty.Pickle("cross-file-user-2", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileUser2Tasty))

    private val crossFileTarget2Pickle =
        Tasty.Pickle("cross-file-target-2", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileTarget2Tasty))

    private val crossFileUser3Pickle =
        Tasty.Pickle("cross-file-user-3", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileUser3Tasty))

    private val crossFileModulePickle =
        Tasty.Pickle("cross-file-module", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileModuleTasty))

    private val crossFileModuleUserPickle =
        Tasty.Pickle("cross-file-module-user", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.crossFileModuleUserTasty))

    private val baseClassPickle =
        Tasty.Pickle("base-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.baseClassTasty))

    private val childClassPickle =
        Tasty.Pickle("child-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.childClassTasty))

    /** Replaces `sym`'s real, load-populated body with hand-crafted bytes, keeping the REAL
      * `pickleId` (so the file's real, non-empty Positions data still joins) and dropping the
      * file's memoized occurrences so the next query re-decodes the swapped body. Mirrors
      * SymbolAtTest.corruptBody: a from-scratch synthetic Binding with no positions data makes
      * OccurrenceScanner.scanFile's own `if positions.nonEmpty` guard skip the decode untried.
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

    "references collects cross-file use sites of a symbol" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(crossFileTargetPickle, crossFileUserPickle)) {
                Tasty.classpath.map { classpath =>
                    val valueSym = classpath.symbols.toSeq.collectFirst {
                        case v: Tasty.Symbol.Val if v.simpleName == "value" => v
                    } match
                        case Some(v) => v
                        case None    => fail("expected CrossFileTarget.value in the fixture classpath")
                    val valueFile = valueSym.sourcePosition match
                        case Maybe.Present(p) => p.sourceFile
                        case Maybe.Absent     => fail("expected CrossFileTarget.value to have a sourcePosition")
                    Tasty.references(valueSym).map { refs =>
                        assert(refs.nonEmpty, "expected at least one cross-file use site for CrossFileTarget.value")
                        assert(
                            refs.forall(_.sourceFile != valueFile),
                            s"expected every use site to live OUTSIDE the declaring file ($valueFile); got $refs"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references collects a same-file use site (a use in the symbol's own declaring file)" in {
        // SomeTrait.compute is declared in FixtureClasses.scala and used by `bounded` (a.compute) in
        // that SAME source file (the two top-level decls compile to separate pickles sharing one
        // SOURCEFILE, Concern 2). references drains every indexed file including the declaring one, so
        // the use is returned and its span lives in compute's own declaring file, not only cross-file.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val someTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait").get
                    val computeSym   = classpath.findMember(someTraitSym, "compute", MemberScope.All).get
                    val computeFile = computeSym.sourcePosition match
                        case Maybe.Present(p) => p.sourceFile
                        case Maybe.Absent     => fail("expected SomeTrait.compute to have a sourcePosition")
                    Tasty.references(computeSym).map { refs =>
                        assert(refs.nonEmpty, "expected at least one use site for SomeTrait.compute")
                        assert(
                            refs.exists(_.sourceFile == computeFile),
                            s"expected a use site in compute's own declaring file ($computeFile); got $refs"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references collects a cross-file use site through a bare module qualifier" in {
        // CrossFileModuleUser.useIt reads CrossFileModule.value through a bare module selection
        // (no locally-typed parameter or value in between), a shape distinct from the parameter-typed
        // CrossFileTarget/CrossFileUser pair above: the qualifier CrossFileModule decodes as a
        // package-owned TERMREFpkg reference, resolved by its fully-qualified name through the
        // classpath rather than through a declared-type lookup.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(crossFileModulePickle, crossFileModuleUserPickle)) {
                Tasty.classpath.map { classpath =>
                    val valueSym = classpath.symbols.toSeq.collectFirst {
                        case m: Tasty.Symbol.Method if m.simpleName == "value" => m
                    } match
                        case Some(m) => m
                        case None    => fail("expected CrossFileModule.value in the fixture classpath")
                    Tasty.references(valueSym).map { refs =>
                        assert(
                            refs == Chunk(Tasty.SourceRange("CrossFileModuleUser.scala", 8, 22, 8, 43)),
                            s"expected exactly the CrossFileModule.value use site in CrossFileModuleUser.useIt; got $refs"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references collects an extends parent-clause use site" in {
        // ChildClass.scala is `class ChildClass extends BaseClass` (BaseClass declared in a separate
        // file). The parent clause is a use of BaseClass at the extends position; the occurrence index
        // captures it from the eager parent decode, so references(BaseClass) returns the extends span,
        // a superclass relationship that was previously reachable only by symbol via implementationsOf.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(childClassPickle, baseClassPickle)) {
                Tasty.classpath.map { classpath =>
                    val base = classpath.findClass("kyo.fixtures.BaseClass").get
                    Tasty.references(base).map { refs =>
                        assert(refs.nonEmpty, s"expected references(BaseClass) to include the extends use site; got $refs")
                        val extendsUse = refs.find(_.sourceFile.endsWith("ChildClass.scala")) match
                            case Some(r) => r
                            case None    => fail(s"expected an extends use in ChildClass.scala; got $refs")
                        // The span covers the full `BaseClass` name in `class ChildClass extends BaseClass`.
                        assert(
                            extendsUse.startLine == extendsUse.endLine &&
                                extendsUse.endColumn - extendsUse.startColumn == "BaseClass".length,
                            s"expected the extends span to cover the full 'BaseClass' name (${"BaseClass".length} cols); got $extendsUse"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references collects a type-position use site (a symbol used as a type, not only as a term)" in {
        // CrossFileUser.useIt takes a parameter `target: CrossFileTarget`: a use of the CLASS
        // CrossFileTarget in TYPE position (a method signature), in a different file from the class's
        // declaration. The occurrence index resolves the decoded type node, so references collects a
        // class's signature-position uses, not only its term (body) use sites.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(crossFileTargetPickle, crossFileUserPickle)) {
                Tasty.classpath.map { classpath =>
                    val targetSym = classpath.findClass("kyo.fixtures.CrossFileTarget").get
                    val targetFile = targetSym.sourcePosition match
                        case Maybe.Present(p) => p.sourceFile
                        case Maybe.Absent     => fail("expected CrossFileTarget to have a sourcePosition")
                    Tasty.references(targetSym).map { refs =>
                        assert(
                            refs.nonEmpty,
                            "expected references(CrossFileTarget) to find its type-position use in CrossFileUser.useIt's parameter"
                        )
                        val crossFileUse = refs.find(_.sourceFile != targetFile) match
                            case Some(r) => r
                            case None =>
                                fail(s"expected a cross-file type-position use outside the declaring file ($targetFile); got $refs")
                        // The span covers the full `CrossFileTarget` name, not the zero-width point TASTy
                        // records for a type-level node, so a find-references highlight and a rename edit
                        // replace the identifier rather than inserting at its start.
                        assert(
                            crossFileUse.startLine == crossFileUse.endLine &&
                                crossFileUse.endColumn - crossFileUse.startColumn == "CrossFileTarget".length,
                            s"expected the type use span to cover the full 'CrossFileTarget' name (${"CrossFileTarget".length} cols), not a point; got $crossFileUse"
                        )
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references excludes the declaration site" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val someTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait").get
                    val computeSym   = classpath.findMember(someTraitSym, "compute", MemberScope.All).get
                    val declPos = computeSym.sourcePosition match
                        case Maybe.Present(p) => p
                        case Maybe.Absent     => fail("expected SomeTrait.compute to have a sourcePosition")
                    Tasty.references(computeSym).map { refs =>
                        assert(refs.nonEmpty, "expected at least one use site for SomeTrait.compute")
                        val declSpanIncluded = refs.exists { r =>
                            r.sourceFile == declPos.sourceFile && r.startLine == declPos.line && r.startColumn == declPos.column
                        }
                        assert(!declSpanIncluded, s"expected the declaration site ($declPos) to be excluded; got $refs")
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "a symbol with no references returns empty" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                        case Some(m) => m
                        case None    => fail("bounded method not found in fixture classpath")
                    Tasty.references(boundedSym).map { refs =>
                        assert(refs == Chunk.empty, s"expected Chunk.empty for an unreferenced symbol ('bounded'); got $refs")
                        succeed
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references is cancellable between files and leaves a consistent partial cache" in {
        // Racing a real Fiber.interrupt against a real references(...) drain over a small fixture
        // set is not a reliable way to LAND mid-drain (a fast multi-file decode plus fiber-
        // scheduling overhead consistently resolves before or after the whole drain, never inside
        // it, across repeated empirical trials), and a sleep-based retry would only mask that
        // unreliability rather than pin the invariant. The "no torn cache entry" invariant is
        // instead proven WITHOUT racing: occurrencesInFile's
        // occurrenceMemo.put is the LAST statement of a file's decode (Tasty.scala), so driving two
        // files' decodes sequentially through the SAME public occurrencesInFile primitive
        // `references` itself calls per file, and observing occurrenceMemo between the two calls,
        // deterministically reproduces the exact partial state a genuine mid-drain interruption
        // would leave (file 1 present and complete, file 2 absent), no timing required. A SEPARATE
        // real Fiber.interrupt against a live references(...) call then proves interruption itself
        // never corrupts state and a subsequent call still returns the complete, correct result,
        // regardless of exactly where that interrupt happens to land.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle, crossFileTargetPickle, crossFileUserPickle)) {
                Tasty.classpath.map { classpath =>
                    val someTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait").get
                    val computeSym   = classpath.findMember(someTraitSym, "compute", MemberScope.All).get
                    val allFiles     = classpath.indices.bySourceFile.toChunk.map(_._1)
                    if allFiles.size < 2 then fail(s"expected at least 2 distinct files in this fixture set; got $allFiles")
                    val file1 = allFiles(0)
                    val file2 = allFiles(1)

                    Tasty.references(computeSym).map { expected =>
                        val expectedSet = expected.toSeq.toSet

                        // A FRESH, separate withPickles load: the OUTER binding's occurrenceMemo is
                        // already fully warmed by the `expected` computation above, so the partial-
                        // decode check needs its own DecodeContext starting empty.
                        Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle, crossFileTargetPickle, crossFileUserPickle)) {
                            Tasty.bindingLocal.use { mbind =>
                                val ctx = mbind.flatMap(_.decodeCtx).get
                                for
                                    _ <- Tasty.occurrencesInFile(file1)
                                    partialFiles = allFiles.filter(f => ctx.occurrenceMemo.get(f) != null)
                                    _ <- Tasty.occurrencesInFile(file2)
                                yield assert(
                                    partialFiles.contains(file1) && !partialFiles.contains(file2),
                                    s"expected occurrenceMemo to hold exactly file1 ($file1) after decoding only file1, " +
                                        s"never a torn/partial entry for file2 ($file2); got $partialFiles"
                                )
                                end for
                            }
                        }.map { _ =>
                            // Fresh cold binding: the interrupt hits an unwarmed drain and the resumed
                            // call must actually re-decode, not serve the outer binding's warm cache.
                            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle, crossFileTargetPickle, crossFileUserPickle)) {
                                for
                                    fiber   <- Fiber.initUnscoped(Tasty.references(computeSym))
                                    _       <- fiber.interrupt
                                    _       <- Abort.run[Any](fiber.get)
                                    resumed <- Tasty.references(computeSym)
                                yield
                                    assert(
                                        resumed.toSeq.toSet == expectedSet,
                                        s"expected the resumed references(computeSym) call after a real interrupt to equal " +
                                            s"the uninterrupted result; got $resumed"
                                    )
                                    succeed
                            }
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

    "references matches by SymbolId equality, not name (no false positives across same-named symbols)" in {
        // CrossFileTarget.value and CrossFileTarget2.value are two REAL, distinct symbols sharing
        // the simple name "value": CrossFileTarget.value has two real cross-file use sites
        // (CrossFileUser.useIt, CrossFileUser2.useItToo); CrossFileTarget2.value has one, in a
        // disjoint file (CrossFileUser3.useItThree). No fabrication: both sides of the adversarial
        // same-simple-name shape are genuine decoded data.
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(
                crossFileTargetPickle,
                crossFileUserPickle,
                crossFileUser2Pickle,
                crossFileTarget2Pickle,
                crossFileUser3Pickle
            )) {
                Tasty.classpath.map { classpath =>
                    val valueSymbols = classpath.symbols.toSeq.collect {
                        case v: Tasty.Symbol.Val if v.simpleName == "value" => v
                    }
                    assert(
                        valueSymbols.size == 2,
                        s"expected exactly 2 distinct 'value' symbols (CrossFileTarget.value, CrossFileTarget2.value); got $valueSymbols"
                    )
                    val targetValue = valueSymbols.find { v =>
                        v.sourcePosition.exists(_.sourceFile == "CrossFileTarget.scala")
                    }.get
                    val target2Value = valueSymbols.find(_.id != targetValue.id).get
                    assert(
                        target2Value.id != targetValue.id,
                        "expected the two 'value' symbols to have distinct SymbolIds"
                    )

                    for
                        refsTarget  <- Tasty.references(targetValue)
                        refsTarget2 <- Tasty.references(target2Value)
                    yield
                        assert(
                            refsTarget.map(_.sourceFile).toSet == Set("CrossFileUser.scala", "CrossFileUser2.scala"),
                            s"expected CrossFileTarget.value's use sites in exactly CrossFileUser.scala and " +
                                s"CrossFileUser2.scala; got ${refsTarget.map(_.sourceFile)}"
                        )
                        assert(
                            refsTarget2.map(_.sourceFile).toSet == Set("CrossFileUser3.scala"),
                            s"expected CrossFileTarget2.value's use site in exactly CrossFileUser3.scala; got ${refsTarget2.map(_.sourceFile)}"
                        )
                        succeed
                    end for
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    "references surfaces MalformedSection on a corrupt file" in {
        // 0x3E = TERMREFdirect tag; 10x 0x00 continuation bytes fires Varint.readLongNat's guard,
        // mirroring BodyTreeErrorChannelTest's identical MalformedVarintException recipe.
        val corruptBytes: Array[Byte] = Array[Byte](0x3e, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val boundedSym = classpath.allMethods.find(_.name.asString == "bounded") match
                        case Some(m) => m
                        case None    => fail("bounded method not found in fixture classpath")
                    val sourceFile = boundedSym.sourcePosition match
                        case Maybe.Present(p) => p.sourceFile
                        case Maybe.Absent     => fail("expected 'bounded' to have a sourcePosition")
                    Tasty.bindingLocal.use { mbind =>
                        val ctx = mbind.flatMap(_.decodeCtx).get
                        corruptBody(ctx, boundedSym, sourceFile, corruptBytes)
                        Tasty.references(boundedSym)
                    }
                }
            }
        ).map {
            case Result.Failure(ms: TastyError.MalformedSection) =>
                assert(ms.name == "ASTs", s"expected section name 'ASTs' but got '${ms.name}'")
                succeed
            case Result.Failure(other) => fail(s"expected TastyError.MalformedSection but got $other")
            case Result.Success(r)     => fail(s"expected TastyError.MalformedSection but references returned $r")
            case Result.Panic(t)       => fail(s"references must NOT produce a Sync panic for corrupt bytes; got: $t")
        }
    }

    "a warmed occurrence cache makes a repeat references call decode nothing" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle, fixtureClassesPkgPickle)) {
                Tasty.classpath.map { classpath =>
                    val someTraitSym = classpath.findTrait("kyo.fixtures.SomeTrait").get
                    val computeSym   = classpath.findMember(someTraitSym, "compute", MemberScope.All).get
                    val boundedSym   = classpath.allMethods.find(_.name.asString == "bounded").get
                    val aId          = boundedSym.paramListIds.head.head
                    val allFiles     = classpath.indices.bySourceFile.toChunk.map(_._1)
                    val aSym         = classpath.symbol(aId).getOrElse(fail("expected the 'a' parameter to resolve"))

                    Tasty.bindingLocal.use { mbind =>
                        val ctx = mbind.flatMap(_.decodeCtx).get
                        for
                            _ <- Tasty.references(computeSym)
                            warmed = allFiles.flatMap(f => Maybe.fromOption(Option(ctx.occurrenceMemo.get(f))).map(c => (f, c)).toChunk)
                            _ <- Tasty.references(aSym)
                        yield
                            assert(warmed.nonEmpty, "expected references(computeSym) to have warmed at least one file")
                            warmed.foreach { (file, chunk) =>
                                val stillCached = ctx.occurrenceMemo.get(file)
                                assert(
                                    stillCached.asInstanceOf[AnyRef] eq chunk.asInstanceOf[AnyRef],
                                    s"expected file $file's occurrenceMemo entry to be the SAME instance after querying a different symbol (no re-decode)"
                                )
                            }
                            succeed
                        end for
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end ReferencesTest

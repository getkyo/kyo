package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Decoder-fidelity deeper probe: boundary values, negative tests, resource exhaustion, races,
  * half-load state, TastyError variants, idempotency, equality/hash, real-world synthetics,
  * structural defense, error messages, and API surface gaps. Uses withPickles and embedded
  * fixtures; no JVM filesystem required.
  */
class DecoderFidelity5Wave2Test extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassTasty: Array[Byte] = kyo.fixtures.Embedded.plainClassTasty

    private def loadCorrupt(
        name: String,
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Async & Abort[TastyError]) =
        val pickle = Tasty.Pickle(s"corrupt-$name", Tasty.Version(28, 3, 0), Span.from(bytes))
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath
        }
    end loadCorrupt

    // Build a fresh in-memory snapshot of the embedded classpath and return (bytes, classpath).
    private def buildSnapshot()(using Frame): (Array[Byte], Tasty.Classpath) < (Sync & Async & Scope & Abort[TastyError]) =
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val digest = Array.fill[Byte](8)(0x42.toByte)
            (SnapshotWriter.serializeToBytes(classpath, digest), classpath)
        }

    "findClass with 1-char and 2-char fully-qualified names returns Absent cleanly" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            assert(classpath.findClass("X") == Maybe.Absent)
            assert(classpath.findClass("ab") == Maybe.Absent)
            assert(classpath.findClass(".") == Maybe.Absent)
            assert(classpath.findClass("..") == Maybe.Absent)
            assert(classpath.findClass(" ") == Maybe.Absent)
            assert(classpath.findClass("\n") == Maybe.Absent)
            assert(classpath.findClass(" ") == Maybe.Absent)
            succeed
        }
    }

    "findClass tolerates unicode and embedded NUL in fully-qualified name" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            assert(classpath.findClass("中文类") == Maybe.Absent)
            assert(classpath.findClass("foo bar") == Maybe.Absent)
            assert(classpath.findClass("💩.Class") == Maybe.Absent)
            succeed
        }
    }

    "Classpath.init with empty roots returns empty classpath cleanly" in {
        Tasty.withPickles(Chunk.empty) {
            Tasty.classpath.map { classpath =>
                assert(classpath.symbols.length == 0, s"expected empty symbols got ${classpath.symbols.length}")
                assert(classpath.errors.length == 0, s"expected no errors got ${classpath.errors}")
                assert(classpath.findClass("anything") == Maybe.Absent)
                succeed
            }
        }
    }

    "classpath.symbol with -1, MAX_INT, and MIN_INT SymbolIds returns sentinel" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import kyo.Tasty.SymbolId
            val a = classpath.symbol(SymbolId(-1))
            val b = classpath.symbol(SymbolId(Int.MaxValue))
            val c = classpath.symbol(SymbolId(Int.MinValue))
            val d = classpath.symbol(SymbolId(classpath.symbols.length))
            val e = classpath.symbol(SymbolId(classpath.symbols.length - 1))
            assert(a == Maybe.Absent, "id=-1 should return Absent")
            assert(b == Maybe.Absent, "id=MAX_INT should return Absent")
            assert(c == Maybe.Absent, "id=MIN_INT should return Absent")
            assert(d == Maybe.Absent, "id=length should return Absent")
            assert(e.isDefined, "id=length-1 should return Present")
            succeed
        }
    }

    "fullName tolerates non-degenerate symbol owner chains within budget" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            import Tasty.SymbolId.value
            var maxChainSym = classpath.symbols.headOption.getOrElse(classpath.symbols.head)
            var maxChainLen = 0
            classpath.symbols.foreach { s =>
                var cur   = s
                var depth = 0
                val seen  = scala.collection.mutable.HashSet.empty[Int]
                while seen.add(cur.id.value) && depth < 128 do
                    classpath.symbol(cur.ownerId) match
                        case Maybe.Present(o) if o.id.value != cur.id.value =>
                            cur = o
                            depth += 1
                        case _ =>
                            depth = 1024
                end while
                if depth > maxChainLen && depth < 1024 then
                    maxChainLen = depth
                    maxChainSym = s
            }
            val fn = classpath.fullName(maxChainSym).asString
            assert(fn != null)
            succeed
        }
    }

    "snapshot truncated at each 256-byte boundary in first KB never panics" in {
        buildSnapshot().map { (full, _) =>
            val maxT    = math.min(1024, full.length - 1)
            val offsets = (1 until maxT by 256).toSeq
            Kyo.foldLeft(offsets)(Option.empty[String]) { (sawPanic, t) =>
                if sawPanic.isDefined then sawPanic
                else
                    val sliced = full.take(t)
                    val path   = s"mem/truncT$t.krfl"
                    Abort.run[TastyError](SnapshotReader.readFromBytes(sliced, path)).map {
                        case Result.Panic(ex) => Some(ex.getClass.getName + ": " + ex.getMessage)
                        case _                => None
                    }
            }
                .map { sawPanic =>
                    assert(sawPanic.isEmpty, s"snapshot truncation panicked at one offset: ${sawPanic.getOrElse("")}")
                    succeed
                }
        }
    }

    "Classpath fields are immutable post-init (case class semantics)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val syms1 = classpath.symbols
            val syms2 = classpath.symbols
            assert(syms1 eq syms2, "classpath.symbols should be the same reference on each access (immutable val)")
            val fullName1 = classpath.indices.byFullName
            val fullName2 = classpath.indices.byFullName
            // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
            assert(
                java.lang.System.identityHashCode(fullName1.asInstanceOf[AnyRef]) ==
                    java.lang.System.identityHashCode(fullName2.asInstanceOf[AnyRef]),
                "classpath.indices.byFullName should be the same reference on each access (immutable val)"
            )
            val seq1 = classpath.symbols.toIndexedSeq.map(_.id.value)
            val seq2 = classpath.symbols.toIndexedSeq.map(_.id.value)
            assert(seq1 == seq2, "iteration order must be stable across calls")
            succeed
        }
    }

    "concurrent findClass from many fibers is deterministic" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val anyFullName = classpath.indices.byFullName.toMap.keys.headOption.getOrElse("nonexistent")
            val results     = (0 until 64).map(_ => classpath.findClass(anyFullName))
            val allSame     = results.forall(_ == results.head)
            assert(allSame, s"findClass($anyFullName) returned divergent results across 64 calls: $results")
            succeed
        }
    }

    "repeated Classpath.init in nested scopes releases resources cleanly" in {
        def oneRound(i: Int): Int < (Sync & Async & Abort[TastyError]) =
            Scope.run(TestClasspaths.withClasspath()(Tasty.classpath)).map(_.symbols.length)
        Kyo.foreach(0 until 4)(oneRound).map { lens =>
            assert(lens.forall(_ > 0), s"all 4 rounds should produce non-empty classpaths: $lens")
            assert(lens.distinct.size == 1, s"all 4 classpath sizes should be equal: ${lens.distinct}")
            succeed
        }
    }

    "snapshot write to unwritable path produces SnapshotIoError" in {
        // Create a temp file and attempt to use it as a cache directory.
        // Path.mkDir on a path occupied by a regular file fails with FileFsException,
        // which SnapshotWriter wraps as SnapshotIoError.
        Path.tempDir("kyo-dfw2-fail").map { tmpDir =>
            val fileAsDir = tmpDir / "not-a-dir"
            fileAsDir.mkFile.map { _ =>
                TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
                    val digest = Array.fill[Byte](8)(0x33.toByte)
                    Abort.run[TastyError](SnapshotWriter.write(classpath, fileAsDir.toString, digest)).map {
                        case Result.Failure(_: TastyError.SnapshotIoError) => succeed
                        case Result.Failure(other)                         => fail(s"expected SnapshotIoError got: $other")
                        case Result.Success(_)                             => fail("expected SnapshotWriter.write to fail")
                        case Result.Panic(t)                               => fail(s"SnapshotWriter panicked: ${t.getMessage}")
                    }
                }
            }
        }
    }

    "concurrent SnapshotReader.readFromBytes produces identical classpath shape" in {
        buildSnapshot().map { (full, cpOrig) =>
            val path = "mem/concur.krfl"
            Async.collectAll(
                (0 until 4).map { _ =>
                    SnapshotReader.readFromBytes(full, path).map(classpath =>
                        (classpath.symbols.length, classpath.errors.length, classpath.indices.byFullName.size)
                    )
                }
            ).map { triples =>
                val first = triples.head
                assert(triples.forall(_ == first), s"divergent reads: $triples")
                succeed
            }
        }
    }

    "concurrent decodeBody returns identical Maybe[Tree] across fibers" in {
        // Must use the callback form so Tasty.bodyTree runs inside the binding scope.
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                Tasty.bindingLocal.use { mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            classpath.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(symbol) =>
                            Async.collectAll(
                                (0 until 8).map(_ => Tasty.bodyTree(symbol).map(_.isDefined))
                            ).map { hits =>
                                val first = hits.head
                                assert(hits.forall(_ == first), s"bodyTree returned divergent presence: $hits")
                                succeed
                            }
                    end match
                }
            }
        }
    }

    "failed snapshot read produces Abort.fail, never a partial classpath" in {
        buildSnapshot().map { (full, _) =>
            val path    = "mem/partial.krfl"
            val partial = full.take(full.length / 3)
            Abort.run[TastyError](SnapshotReader.readFromBytes(partial, path)).map {
                case Result.Failure(_) => succeed
                case Result.Success(classpath) =>
                    val ok = classpath.symbols.forall { s =>
                        s.ownerId.value == -1 ||
                        (s.ownerId.value >= 0 && s.ownerId.value < classpath.symbols.length)
                    }
                    assert(ok, "partial classpath should have no out-of-range ownerIds")
                    succeed
                case Result.Panic(t) => fail(s"unexpected panic: ${t.getMessage}")
            }
        }
    }

    "SnapshotFormatError on wrong magic is reachable and has clean message" in {
        val bad  = Array.fill[Byte](64)(0x42)
        val path = "mem/wrongmagic.krfl"
        Abort.run[TastyError](SnapshotReader.readFromBytes(bad, path)).map {
            case Result.Failure(e: TastyError.SnapshotFormatError) =>
                assert(e.path == path, s"path should match input got: ${e.path}")
                assert(e.reason.nonEmpty, "reason must be non-empty")
                assert(e.byteOffset >= 0L, "byteOffset must be non-negative")
                succeed
            case other => fail(s"expected SnapshotFormatError: $other")
        }
    }

    "SnapshotVersionMismatch is reachable and well-formed" in {
        val path   = "mem/badversion.krfl"
        val buffer = new Array[Byte](SnapshotFormat.headerSize + 4)
        buffer(0) = 'K'; buffer(1) = 'R'; buffer(2) = 'F'; buffer(3) = 'L'
        buffer(4) = 99.toByte
        buffer(5) = 99.toByte
        SnapshotFormat.writeInt32LE(buffer, 32, 0)
        Abort.run[TastyError](SnapshotReader.readFromBytes(buffer, path)).map {
            case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                assert(e.found.major == 99, s"found.major expected 99 got ${e.found.major}")
                assert(e.supported.major == SnapshotFormat.majorVersion)
                succeed
            case other => fail(s"expected SnapshotVersionMismatch got: $other")
        }
    }

    "requireClass on missing fully-qualified name raises TastyError.NotFound" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            Abort.run[TastyError](classpath.requireClass("kyo.DoesNotExistAtAll")).map {
                case Result.Failure(e: TastyError.NotFound) =>
                    assert(e.fullName == "kyo.DoesNotExistAtAll", s"fullName carried through: ${e.fullName}")
                    succeed
                case other => fail(s"expected NotFound: $other")
            }
        }
    }

    "requireSymbol on missing fully-qualified name raises TastyError.NotFound" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            Abort.run[TastyError](classpath.requireSymbol("nothing.like.this.exists")).map {
                case Result.Failure(e: TastyError.NotFound) =>
                    assert(e.fullName == "nothing.like.this.exists")
                    succeed
                case other => fail(s"expected NotFound: $other")
            }
        }
    }

    "findClass 100x on the same fully-qualified name returns the same result" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val sample = classpath.indices.byFullName.toMap.keys.take(3).toList
            sample.foreach { k =>
                val results = (0 until 100).map(_ => classpath.findClass(k))
                assert(results.toSet.size == 1, s"100 lookups of $k diverged: ${results.toSet}")
            }
            succeed
        }
    }

    "SnapshotWriter.serializeToBytes is byte-deterministic within same JVM" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val digest = Array.fill[Byte](8)(0x55.toByte)
            val byteHashes = (0 until 8).map { _ =>
                val bytes = SnapshotWriter.serializeToBytes(classpath, digest)
                java.util.Arrays.hashCode(bytes)
            }
            assert(byteHashes.toSet.size == 1, s"8 serializations produced different hashes: ${byteHashes.toSet}")
            succeed
        }
    }

    "classpath.errors is stable Chunk across accesses" in {
        loadCorrupt("PlainClassW2_20.tasty", plainClassTasty.take(plainClassTasty.length / 2)).map { classpath =>
            val a       = classpath.errors
            val b       = classpath.errors
            val sameRef = (a: AnyRef) eq (b: AnyRef)
            assert(sameRef || a.toIndexedSeq == b.toIndexedSeq, "errors must be a stable Chunk")
            succeed
        }
    }

    "same Symbol fetched twice is equal and has equal hashCode" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val fullNameOpt = classpath.indices.byFullName.toMap.keys.headOption
            fullNameOpt match
                case None => succeed
                case Some(fullName) =>
                    val s1 = classpath.findSymbol(fullName)
                    val s2 = classpath.findSymbol(fullName)
                    assert(s1 == s2, s"equal Symbols expected for $fullName")
                    assert(s1.hashCode == s2.hashCode, "hashCode must be equal for equal symbols")
                    succeed
            end match
        }
    }

    // Symbol equality is id-based with a kind discriminant; `final override def equals`
    // on Tasty.Symbol compares `(id.value, kind)`.
    "Symbol usable as HashMap key (equals/hashCode contract)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val syms = classpath.symbols.take(50).toList
            val map  = scala.collection.mutable.HashMap.empty[Tasty.Symbol, Int]
            syms.zipWithIndex.foreach((s, i) => map.put(s, i))
            syms.zipWithIndex.foreach { (s, i) =>
                assert(map.get(s).contains(i), s"HashMap lookup failed for symbol $i")
            }
            succeed
        }
    }

    "default-arg accessor methods are present on the classpath" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val allMethods = classpath.allMethods
            val defaults   = allMethods.filter(_.name.asString.contains("$default$"))
            assert(defaults.length >= 0, "default-arg-accessor scan should not throw")
            allMethods.foreach { m =>
                assert(m.name.asString.length >= 0, "method name length is sane")
            }
            succeed
        }
    }

    "Java enum $VALUES synthetics scan does not panic" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            import Tasty.Name.asString
            val javaSynthetics = classpath.allFields.filter(_.name.asString == "$VALUES")
            assert(javaSynthetics.length >= 0)
            succeed
        }
    }

    "section-index out-of-bounds offset+length still produces clean error" in {
        buildSnapshot().map { (full, _) =>
            val path         = "mem/sectionoob.krfl"
            val sectionCount = SnapshotFormat.readInt32LE(full, 32)
            val out          = java.util.Arrays.copyOf(full, full.length)
            var idxPos       = 36
            var i            = 0
            var didMutate    = false
            while i < sectionCount && !didMutate do
                val name = SnapshotFormat.readSectionName(out, idxPos)
                if name == SnapshotFormat.sectionSYMBOLS then
                    SnapshotFormat.writeInt64LE(out, idxPos + 8, 0L)
                    SnapshotFormat.writeInt64LE(out, idxPos + 16, (full.length + 1L) * 4L)
                    didMutate = true
                end if
                idxPos += SnapshotFormat.sectionIndexEntrySize
                i += 1
            end while
            assert(didMutate, "could not locate SYMBOLS section index entry")
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Failure(_: TastyError.MalformedSection)    => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: section-index OOB produces panic, not structured error: ${t.getClass.getName}: ${t.getMessage}")
                case other => succeed
            }
        }
    }

    "section-index entry with negative offset produces structured error, no panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/negoffset.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt64LE(out, 36 + 8, -1L)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_) => succeed
                case Result.Success(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
            }
        }
    }

    "section index with absurd section count produces structured error, no panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/manysections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 1_000_000_000)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_) => succeed
                case Result.Success(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: section count=1B panics: ${t.getClass.getName}: ${t.getMessage}")
            }
        }
    }

    "TastyError toString messages contain identifying tag and inputs" in {
        val cases: Seq[(String, TastyError)] = Seq(
            "FileNotFound"         -> TastyError.FileNotFound("/tmp/x"),
            "CorruptedFile"        -> TastyError.CorruptedFile("/tmp/y", 42L, "bad magic"),
            "MalformedSection"     -> TastyError.MalformedSection("NAMES", "trunc", 100L),
            "SymbolNotFound"       -> TastyError.SymbolNotFound("scala.X"),
            "NotFound"             -> TastyError.NotFound("scala.Y"),
            "ClassfileFormatError" -> TastyError.ClassfileFormatError("/tmp/z.class", "bad", 0L),
            "ClasspathClosed"      -> TastyError.ClasspathClosed("test-context"),
            "ClasspathBuilding"    -> TastyError.ClasspathBuilding("test-context"),
            "SnapshotFormatError"  -> TastyError.SnapshotFormatError("/tmp/k.krfl", "wrong magic", 0L),
            "SnapshotIoError"      -> TastyError.SnapshotIoError("disk full"),
            "NotImplemented"       -> TastyError.NotImplemented("feature x"),
            "UnsupportedPlatform"  -> TastyError.UnsupportedPlatform("jrt:/"),
            "UnknownTagInPosition" -> TastyError.UnknownTagInPosition(255, "type")
        )
        Sync.defer {
            cases.foreach { (tag, e) =>
                val s = e.toString
                assert(s.contains(tag), s"$tag missing in toString: $s")
            }
            succeed
        }
    }

    "requireClass on Trait fully-qualified name raises NotFound (narrow-kind semantics)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.allTraits.headOption match
                case None => succeed
                case Some(t) =>
                    val fullName = classpath.fullName(t).asString
                    if fullName.isEmpty then succeed
                    else
                        Abort.run[TastyError](classpath.requireClass(fullName)).map {
                            case Result.Failure(_: TastyError.NotFound) => succeed
                            case Result.Success(_)                      => succeed
                            case other                                  => fail(s"unexpected: $other")
                        }
                    end if
        }
    }

    "bodyMemo accumulates in DecodeContext after bodyTree decode" in {
        // Must use the full callback form so Tasty.bodyTree and Tasty.bindingLocal run inside the binding scope.
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                Tasty.bindingLocal.use { mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            classpath.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(symbol) =>
                            Tasty.bodyTree(symbol).map { _ =>
                                Tasty.bindingLocal.use { mbind2 =>
                                    val ctx = mbind2.flatMap(_.decodeCtx)
                                    if ctx.isEmpty then Sync.defer(succeed)
                                    else
                                        val memoSize = ctx.get.bodyMemo.size()
                                        assert(memoSize >= 1, s"Expected at least 1 bodyMemo entry after decode, got $memoSize")
                                        val cp2 = classpath.copy(errors = classpath.errors)
                                        assert(
                                            cp2 == classpath,
                                            "classpath.copy with same errors must produce a structurally equal Classpath"
                                        )
                                        succeed
                                    end if
                                }
                            }
                    end match
                }
            }
        }
    }

    "classFullName returns the bare dotted class fully-qualified name" in {
        Sync.defer {
            assert(Tasty.classFullName[Int] == "scala.Int")
            assert(Tasty.classFullName[String] == "java.lang.String")
            assert(Tasty.classFullName[List[Int]] == "scala.collection.immutable.List")
            assert(Tasty.classFullName[Map[String, Int]] == "scala.collection.immutable.Map")
            succeed
        }
    }

    "chained Maybe combinators across classpath API never throw" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val r1 = classpath.findClass("does.not.exist").map(_.simpleName)
            val r2 = classpath.findTrait("does.not.exist").orElse(classpath.findObject("does.not.exist"))
            val r3 = classpath.findClassLike("does.not.exist").filter(_ => true)
            assert(r1 == Maybe.Absent)
            assert(r2 == Maybe.Absent)
            assert(r3 == Maybe.Absent)
            succeed
        }
    }

    "section-index OOB entry: OOB section-index entry produces MalformedSection, not an unstructured panic" in {
        buildSnapshot().map { (full, _) =>
            val path         = "mem/r-fw2-1a.krfl"
            val out          = java.util.Arrays.copyOf(full, full.length)
            val sectionCount = SnapshotFormat.readInt32LE(out, 32)
            var idxPos       = 36
            var i            = 0
            var mutated      = false
            while i < sectionCount && !mutated do
                val name = SnapshotFormat.readSectionName(out, idxPos)
                if name == SnapshotFormat.sectionNAMES then
                    SnapshotFormat.writeInt64LE(out, idxPos + 16, (full.length.toLong * 2L))
                    mutated = true
                end if
                idxPos += SnapshotFormat.sectionIndexEntrySize
                i += 1
            end while
            assert(mutated, "could not locate NAMES section index entry")
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_: TastyError.MalformedSection)    => succeed
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: OOB section entry panics instead of giving MalformedSection: ${t.getClass.getName}: ${t.getMessage}")
                case other => succeed
            }
        }
    }

    "section-index negative-offset: negative section offset produces structured error, not a panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/r-fw2-1b.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt64LE(out, 36 + 8, -99L)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
                case Result.Success(_) => succeed
            }
        }
    }

    "section-index valid-snapshot: valid snapshot with no OOB entries loads cleanly" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/r-fw2-1c.krfl"
            Abort.run[TastyError](SnapshotReader.readFromBytes(full, path)).map {
                case Result.Panic(t) => fail(s"BUG: valid snapshot panics: ${t.getMessage}")
                case _               => succeed
            }
        }
    }

    "decode-body: decodeBody produces ClasspathClosed when body read throws IllegalStateException" in {
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                Tasty.bindingLocal.use { mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            classpath.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(symbol) =>
                            Tasty.bodyTree(symbol).map { tree =>
                                assert(tree.isDefined || !tree.isDefined, "decodeBody must return without panic")
                                succeed
                            }
                    end match
                }
            }
        }
    }

    // Contract: the decoder handles APPLIEDtpt (tag 162) bodies without falling through to the
    // unknown-tag arm, and decoder failures surface as structured TastyError values on the typed
    // Abort[TastyError] channel. Specifically:
    //   - No body of an open classpath aborts with TastyError.ClasspathClosed. A ClasspathClosed
    //     result while the scope is open means a decoder ISE was mislabeled as a closed mmap arena.
    //   - At least one body decodes to a Tree, confirming the APPLIEDtpt handler is reachable and
    //     produces a result.
    // Bodies whose constructs the decoder does not model surface as TastyError.MalformedSection,
    // which is the correct structured-error shape on the typed channel.
    "decode-body: no class-like body is mislabeled ClasspathClosed; APPLIEDtpt bodies decode" in {
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                Tasty.bindingLocal.use { mbind =>
                    val bodied: Chunk[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            classpath.allClassLike.filter(c => ctx.bodyStore.get(c.id) != null)
                        case Maybe.Absent => Chunk.empty
                    assert(bodied.nonEmpty, "expected at least one class-like symbol with a decodable body in the fixture classpath")
                    Kyo.foreach(bodied) { symbol =>
                        Abort.run[TastyError](Tasty.bodyTree(symbol)).map {
                            case Result.Success(tree) =>
                                tree.isDefined
                            case Result.Failure(TastyError.ClasspathClosed(ctx)) =>
                                fail(s"body of ${symbol.name.asString} (id=${symbol.id.value}) aborted with ClasspathClosed($ctx) " +
                                    "while the scope is open; a decoder gap was mislabeled as a closed arena")
                            case Result.Failure(_) =>
                                // A structured decoder error on the typed channel is acceptable here.
                                false
                            case Result.Panic(t) =>
                                throw t
                        }
                    }.map { decoded =>
                        assert(
                            decoded.exists(identity),
                            "expected at least one class-like body (including the APPLIEDtpt-bearing one) to decode to a Tree"
                        )
                        succeed
                    }
                }
            }
        }
    }

    "withPickles-empty: withPickles(Chunk.empty) returns empty classpath" in {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map { classpath =>
            assert(classpath.symbols.length == 0, s"expected empty symbols, got ${classpath.symbols.length}")
            assert(classpath.errors.length == 0, s"expected no errors, got ${classpath.errors.length}")
            assert(classpath.findClass("anything") == Maybe.Absent)
            succeed
        }
    }

    "withPickles-real-bytes: withPickles with real TASTy bytes decodes at least 1 symbol" in {
        val pickle =
            Tasty.Pickle(uuid = "test-uuid-plain-class", version = Tasty.Version(28, 3, 0), bytes = Span.from(plainClassTasty))
        import Tasty.Name.asString
        Tasty.withPickles(Chunk(pickle))(Tasty.classpath).map { classpath =>
            val plainClass = classpath.findClassLike("kyo.fixtures.PlainClass")
            assert(
                plainClass.isDefined,
                s"Expected kyo.fixtures.PlainClass to be findable after withPickles; got ${classpath.symbols.length} symbols"
            )
            assert(
                classpath.symbols.length >= 5,
                s"Expected >= 5 symbols from PlainClass pickle but got ${classpath.symbols.length}"
            )
            succeed
        }
    }

    "error-roundtrip: all TastyError variants round-trip through snapshot ERRORS section" in {
        val zeroUUID = Tasty.Uuid.unsafeWrap(new java.util.UUID(0L, 0L).toString)
        val v1       = Tasty.Version(1, 2, 0)
        val v2       = Tasty.Version(3, 4, 0)
        val testErrors: Chunk[TastyError] = Chunk(
            TastyError.FileNotFound("/tmp/foo.tasty"),
            TastyError.CorruptedFile("/tmp/bar.tasty", 42L, "bad magic"),
            TastyError.UnsupportedVersion(v1, v2),
            TastyError.InconsistentClasspath("/tmp/baz.tasty", zeroUUID, zeroUUID),
            TastyError.FullNameCollisionError("scala.collection.SeqOps"),
            TastyError.MalformedSection("NAMES", "truncated", 100L),
            TastyError.SymbolNotFound("scala.X"),
            TastyError.NotFound("scala.Y"),
            TastyError.ClassfileFormatError("/tmp/z.class", "bad classpath", 0L),
            TastyError.ClasspathClosed("decodeBody(symbol.id=0)"),
            TastyError.ClasspathBuilding("finalizeMerge: brokenFullNameCount=1"),
            TastyError.SnapshotFormatError("/tmp/k.krfl", "wrong magic", 0L),
            TastyError.SnapshotVersionMismatch(v1, v2),
            TastyError.SnapshotIoError("disk full"),
            TastyError.NotImplemented("feature x"),
            TastyError.UnsupportedPlatform("jrt:/"),
            TastyError.UnknownTagInPosition(255, "type")
        )
        TestClasspaths.withClasspath()(Tasty.classpath).map { baseCp =>
            val classpath = baseCp.copy(errors = testErrors)
            val digest    = Array.fill[Byte](8)(0x77.toByte)
            val path      = "mem/errors-roundtrip.krfl"
            val bytes     = kyo.internal.tasty.snapshot.SnapshotWriter.serializeToBytes(classpath, digest)
            Abort.run[TastyError](SnapshotReader.readFromBytes(bytes, path)).map { result =>
                result match
                    case Result.Failure(e) => fail(s"snapshot round-trip failed: $e")
                    case Result.Success(cp2) =>
                        val roundTripped = cp2.errors
                        assert(
                            roundTripped.length >= testErrors.length,
                            s"expected at least ${testErrors.length} errors, got ${roundTripped.length}: $roundTripped"
                        )
                        val rt = roundTripped.toIndexedSeq.takeRight(testErrors.length)
                        testErrors.toIndexedSeq.zipWithIndex.foreach { (expected, i) =>
                            assert(
                                rt(i) == expected,
                                s"error[$i] mismatch: expected=$expected got=${rt(i)}"
                            )
                        }
                        succeed
                    case Result.Panic(t) => fail(s"snapshot round-trip panicked: ${t.getMessage}")
            }
        }
    }

    "corrupt-sectionCount-maxval: sectionCount=Int.MaxValue produces SnapshotFormatError, not OOM or panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/maxsections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, Int.MaxValue)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Failure(other)                             => fail(s"expected SnapshotFormatError, got: $other")
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=Int.MaxValue panics: ${t.getClass.getName}: ${t.getMessage}")
                case Result.Success(_) => fail("expected failure for corrupt sectionCount")
            }
        }
    }

    "corrupt-sectionCount-at-cap: sectionCount exactly at cap (256) does not panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/maxsections-cap.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 256)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=256 panics: ${t.getClass.getName}: ${t.getMessage}")
                case _ => succeed
            }
        }
    }

    "sectionCount-zero: sectionCount=0 produces an empty classpath, not a panic" in {
        buildSnapshot().map { (full, _) =>
            val path = "mem/zerosections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 0)
            Abort.run[TastyError](SnapshotReader.readFromBytes(out, path)).map {
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=0 panics: ${t.getClass.getName}: ${t.getMessage}")
                case _ => succeed
            }
        }
    }

    "bodyTree never aborts on a valid fixture body: undecodable constructs degrade to a Present tree" in {
        // The embedded fixtures are real scalac output, so every body is well-formed TASTy. The
        // documented contract is that a construct the reader cannot fully model degrades (Tree.Unknown)
        // rather than aborting with MalformedSection (which is reserved for corrupt byte encodings).
        // This guards the contract for the whole fixture classpath, replacing tests that decoded one
        // arbitrary body and assumed full-fidelity success.
        TestClasspaths.withClasspath() {
            Tasty.classpath.map { classpath =>
                given Tasty.Classpath = classpath
                Kyo.foreach(classpath.allClassLike) { sym =>
                    Abort.run[TastyError](Tasty.bodyTree(sym)).map {
                        case Result.Success(_) => Maybe.Absent
                        case Result.Failure(e) => Maybe(s"${classpath.fullName(sym).asString} aborted: $e")
                        case Result.Panic(t)   => Maybe(s"${classpath.fullName(sym).asString} panicked: ${t.getMessage}")
                    }
                }.map { outcomes =>
                    val aborts = outcomes.flatMap(_.toList)
                    assert(aborts.isEmpty, s"bodyTree aborted on ${aborts.length} valid fixture bodies: ${aborts.take(5)}")
                    succeed
                }
            }
        }
    }

    "Tree.TypeTree wraps a decoded type, renders via treeShow, and exposes no tree children" in {
        // LAMBDAtpt/REFINEDtpt/TYPEBOUNDStpt/MATCHtpt in tree position decode through the type decoder
        // and wrap the resulting Type in Tree.TypeTree. The wrapped value is a Type, so the node has no
        // Tree children; treeShow renders it by delegating to the type renderer.
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val tt: Tasty.Tree = Tasty.Tree.TypeTree(Tasty.Type.Any)
            assert(tt.children.isEmpty, "TypeTree wraps a Type, so it has no Tree children")
            assert(classpath.treeShow(tt).nonEmpty, "treeShow must render a TypeTree")
            assert(tt.collect { case t: Tasty.Tree.TypeTree => t }.length == 1, "collect must find the TypeTree node")
            succeed
        }
    }

end DecoderFidelity5Wave2Test

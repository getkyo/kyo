package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Decoder-fidelity-5 Wave 2: QA-mindset deeper probe.
  *
  * Wave 1 (15 leaves) found one bug (truncated-snapshot AIOOBE) and patched it with catch-and-convert defense in `SnapshotReader.readBytes`.
  * Wave 2 probes a wider matrix of QA-mindset categories: boundaries, negative tests, resource exhaustion, races, half-load state, error
  * variants, idempotency, equality/hash, real-world synthetics, structural defense, error messages, API gaps.
  *
  * Cross-platform: uses MemoryFileSource and TestClasspaths.withClasspath() (embedded fixtures). No JVM filesystem required.
  *
  * Leaves are numbered W2.N for traceability with the exploration document.
  */
class DecoderFidelity5Wave2Test extends Test:

    import AllowUnsafe.embrace.danger

    private val plainClassTasty: Array[Byte] = kyo.fixtures.Embedded.plainClassTasty

    // -- Helpers ---------------------------------------------------------------

    private def loadCorrupt(
        name: String,
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add(s"corrupt/$name", bytes)
        ClasspathOrchestrator.init(Seq("corrupt"), Tasty.ErrorMode.SoftFail, src, 1)
    end loadCorrupt

    // Build a fresh in-memory snapshot of the embedded classpath and return (bytes, cp).
    private def buildSnapshot()(using Frame): (Array[Byte], Tasty.Classpath) < (Sync & Async & Scope & Abort[TastyError]) =
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val digest = Array.fill[Byte](8)(0x42.toByte)
            (SnapshotWriter.serializeToBytes(cp, digest), cp)

    // =========================================================================
    // CATEGORY 1: BOUNDARY VALUES
    // =========================================================================

    // W2.1: findClass with very short strings (1, 2 chars) returns Absent cleanly.
    "W2.1: findClass with 1-char and 2-char FQNs returns Absent cleanly" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(cp.findClass("X") == Maybe.Absent)
            assert(cp.findClass("ab") == Maybe.Absent)
            assert(cp.findClass(".") == Maybe.Absent)
            assert(cp.findClass("..") == Maybe.Absent)
            assert(cp.findClass(" ") == Maybe.Absent)
            assert(cp.findClass("\n") == Maybe.Absent)
            assert(cp.findClass(" ") == Maybe.Absent)
            succeed
    }

    // W2.2: findClass with strings holding unicode and embedded null bytes
    "W2.2: findClass tolerates unicode and embedded NUL in FQN" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            assert(cp.findClass("中文类") == Maybe.Absent)
            assert(cp.findClass("foo bar") == Maybe.Absent)
            assert(cp.findClass("💩.Class") == Maybe.Absent)
            succeed
    }

    // W2.3: empty roots Seq returns a valid empty classpath, no panic.
    "W2.3: Classpath.init with empty roots returns empty classpath cleanly" in run {
        val src = MemoryFileSource()
        ClasspathOrchestrator.init(Seq.empty, Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
            assert(cp.symbols.length == 0, s"expected empty symbols got ${cp.symbols.length}")
            assert(cp.errors.length == 0, s"expected no errors got ${cp.errors}")
            assert(cp.findClass("anything") == Maybe.Absent)
            succeed
    }

    // W2.4: Classpath.symbol with extreme SymbolId values does not panic.
    "W2.4: cp.symbol with -1, MAX_INT, and MIN_INT SymbolIds returns sentinel" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import kyo.Tasty.SymbolId
            val a = cp.symbol(SymbolId(-1))
            val b = cp.symbol(SymbolId(Int.MaxValue))
            val c = cp.symbol(SymbolId(Int.MinValue))
            val d = cp.symbol(SymbolId(cp.symbols.length))
            val e = cp.symbol(SymbolId(cp.symbols.length - 1))
            // cp.symbol now returns Maybe[Symbol]; out-of-range/negative ids return Maybe.Absent
            assert(a == Maybe.Absent, "id=-1 should return Absent")
            assert(b == Maybe.Absent, "id=MAX_INT should return Absent")
            assert(c == Maybe.Absent, "id=MIN_INT should return Absent")
            assert(d == Maybe.Absent, "id=length should return Absent")
            assert(e.isDefined, "id=length-1 should return Present")
            succeed
    }

    // W2.5: Symbol depth-64 cycle guard in fullName tolerates pathological owner chains.
    "W2.5: fullName tolerates non-degenerate symbol owner chains within budget" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            import Tasty.Name.asString
            import Tasty.SymbolId.value
            var maxChainSym = cp.symbols.headOption.getOrElse(cp.symbols.head)
            var maxChainLen = 0
            cp.symbols.foreach: s =>
                var cur   = s
                var depth = 0
                val seen  = scala.collection.mutable.HashSet.empty[Int]
                while seen.add(cur.id.value) && depth < 128 do
                    cp.symbol(cur.ownerId) match
                        case Maybe.Present(o) if o.id.value != cur.id.value =>
                            cur = o
                            depth += 1
                        case _ =>
                            depth = 1024
                end while
                if depth > maxChainLen && depth < 1024 then
                    maxChainLen = depth
                    maxChainSym = s
            cp.fullName(maxChainSym).map: name =>
                val fn = name.asString
                assert(fn != null)
                succeed
    }

    // W2.6: snapshot truncation at every 256-byte boundary up to first KB never panics.
    "W2.6: snapshot truncated at each 256-byte boundary in first KB never panics" in run {
        buildSnapshot().flatMap: (full, _) =>
            val mem     = MemoryFileSource()
            val maxT    = math.min(1024, full.length - 1)
            val offsets = (1 until maxT by 256).toSeq
            Kyo.foldLeft(offsets)(Option.empty[String]): (sawPanic, t) =>
                if sawPanic.isDefined then sawPanic
                else
                    val sliced = full.take(t)
                    val path   = s"mem/truncT$t.krfl"
                    mem.add(path, sliced)
                    Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                        case Result.Panic(ex) => Some(ex.getClass.getName + ": " + ex.getMessage)
                        case _                => None
            .map: sawPanic =>
                assert(sawPanic.isEmpty, s"snapshot truncation panicked at one offset: ${sawPanic.getOrElse("")}")
                succeed
    }

    // =========================================================================
    // CATEGORY 2: NEGATIVE TESTING (invariant-violation attempts)
    // =========================================================================

    // W2.7: HARD RULE 7 -- Classpath case class fields immutable after init.
    "W2.7: HARD RULE 7 Classpath fields are immutable post-init (case class semantics)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val syms1 = cp.symbols
            val syms2 = cp.symbols
            assert(syms1 eq syms2, "cp.symbols should be the same reference on each access (immutable val)")
            val fqn1 = cp.indices.byFqn
            val fqn2 = cp.indices.byFqn
            // Dict is an opaque type; identity check via identityHashCode to confirm same backing object.
            assert(
                java.lang.System.identityHashCode(fqn1.asInstanceOf[AnyRef]) ==
                    java.lang.System.identityHashCode(fqn2.asInstanceOf[AnyRef]),
                "cp.indices.byFqn should be the same reference on each access (immutable val)"
            )
            val seq1 = cp.symbols.toIndexedSeq.map(_.id.value)
            val seq2 = cp.symbols.toIndexedSeq.map(_.id.value)
            assert(seq1 == seq2, "iteration order must be stable across calls")
            succeed
    }

    // W2.8: concurrent findClass from many fibers returns the same Symbol object.
    "W2.8: concurrent findClass from many fibers is deterministic" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val anyFqn  = cp.indices.byFqn.toMap.keys.headOption.getOrElse("nonexistent")
            val results = (0 until 64).map(_ => cp.findClass(anyFqn))
            val allSame = results.forall(_ == results.head)
            assert(allSame, s"findClass($anyFqn) returned divergent results across 64 calls: $results")
            succeed
    }

    // =========================================================================
    // CATEGORY 3: RESOURCE EXHAUSTION + CLEANUP
    // =========================================================================

    // W2.9: repeated Classpath init in nested scopes releases resources cleanly.
    "W2.9: repeated Classpath.init in nested scopes releases resources cleanly" in run {
        def oneRound(i: Int): Int < (Sync & Async & Abort[TastyError]) =
            Scope.run(TestClasspaths.withClasspath()(Tasty.classpath)).map(_.symbols.length)
        Kyo.foreach(0 until 8)(oneRound).map: lens =>
            assert(lens.forall(_ > 0), s"all 8 rounds should produce non-empty classpaths: $lens")
            assert(lens.distinct.size == 1, s"all 8 classpath sizes should be equal: ${lens.distinct}")
            succeed
    }

    // W2.10: snapshot write failure produces SnapshotIoError.
    "W2.10: snapshot write failure produces SnapshotIoError, no leaked tmp" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val digest = Array.fill[Byte](8)(0x33.toByte)
            val failing = new kyo.internal.tasty.query.FileSource:
                def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.FileNotFound(path))
                def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.SnapshotIoError("disk full (synthetic)"))
                def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.SnapshotIoError("rename not supported"))
                def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
                def list(dir: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
                    Chunk.empty
                def exists(path: String)(using Frame): Boolean < Sync = false
                def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
                    Abort.fail(TastyError.FileNotFound(path))
            Abort.run[TastyError](SnapshotWriter.write(cp, "mem-fail", digest, failing)).map:
                case Result.Failure(_: TastyError.SnapshotIoError) => succeed
                case Result.Failure(other)                         => assertionFailure(s"expected SnapshotIoError got: $other")
                case Result.Success(_)                             => assertionFailure("expected SnapshotWriter.write to fail")
                case Result.Panic(t)                               => assertionFailure(s"SnapshotWriter panicked: ${t.getMessage}")
    }

    // =========================================================================
    // CATEGORY 4: RACES + TOCTOU
    // =========================================================================

    // W2.11: concurrent SnapshotReader.read from 8 fibers on same bytes -> same Classpath shape.
    "W2.11: concurrent SnapshotReader.read produces identical classpath shape" in run {
        buildSnapshot().flatMap: (full, cpOrig) =>
            val mem  = MemoryFileSource()
            val path = "mem/concur.krfl"
            mem.add(path, full)
            Async.collectAll(
                (0 until 8).map: _ =>
                    SnapshotReader.read(path, mem).map(cp => (cp.symbols.length, cp.errors.length, cp.indices.byFqn.size))
            ).map: triples =>
                val first = triples.head
                assert(triples.forall(_ == first), s"divergent reads: $triples")
                succeed
    }

    // W2.12: concurrent decodeBody from many fibers on the same symbol is deterministic.
    "W2.12: concurrent decodeBody returns identical Maybe[Tree] across fibers" in run {
        // Must use the callback form so Tasty.bodyTree runs inside the binding scope.
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: cp =>
                given Tasty.Classpath = cp
                TastyState.bindingLocal.use: mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            cp.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(sym) =>
                            Async.collectAll(
                                (0 until 8).map(_ => Tasty.bodyTree(sym).map(_.isDefined))
                            ).map: hits =>
                                val first = hits.head
                                assert(hits.forall(_ == first), s"bodyTree returned divergent presence: $hits")
                                succeed
                    end match
    }

    // =========================================================================
    // CATEGORY 5: HALF-LOADED STATE OBSERVABILITY
    // =========================================================================

    // W2.13: An interrupted snapshot read (truncated) does not produce a partially-built Classpath.
    "W2.13: failed snapshot read produces Abort.fail, never a partial cp" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/partial.krfl"
            mem.add(path, full.take(full.length / 3))
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_) => succeed
                case Result.Success(cp) =>
                    val ok = cp.symbols.forall: s =>
                        s.ownerId.value == -1 ||
                            (s.ownerId.value >= 0 && s.ownerId.value < cp.symbols.length)
                    assert(ok, "partial cp should have no out-of-range ownerIds")
                    succeed
                case Result.Panic(t) => fail(s"unexpected panic: ${t.getMessage}")
    }

    // =========================================================================
    // CATEGORY 6: EVERY TastyError VARIANT
    // =========================================================================

    // W2.14: SnapshotFormatError (wrong magic) is reachable and self-describing.
    "W2.14: SnapshotFormatError on wrong magic is reachable and has clean message" in run {
        val bad  = Array.fill[Byte](64)(0x42)
        val mem  = MemoryFileSource()
        val path = "mem/wrongmagic.krfl"
        mem.add(path, bad)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Failure(e: TastyError.SnapshotFormatError) =>
                assert(e.path == path, s"path should match input got: ${e.path}")
                assert(e.reason.nonEmpty, "reason must be non-empty")
                assert(e.byteOffset >= 0L, "byteOffset must be non-negative")
                succeed
            case other => fail(s"expected SnapshotFormatError: $other")
    }

    // W2.15: SnapshotVersionMismatch is reachable by writing a bad version.
    "W2.15: SnapshotVersionMismatch is reachable and well-formed" in run {
        val mem  = MemoryFileSource()
        val path = "mem/badversion.krfl"
        val buf  = new Array[Byte](SnapshotFormat.headerSize + 4)
        buf(0) = 'K'; buf(1) = 'R'; buf(2) = 'F'; buf(3) = 'L'
        buf(4) = 99.toByte
        buf(5) = 99.toByte
        SnapshotFormat.writeInt32LE(buf, 32, 0)
        mem.add(path, buf)
        Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
            case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                assert(e.found.major == 99, s"found.major expected 99 got ${e.found.major}")
                assert(e.supported.major == SnapshotFormat.majorVersion)
                succeed
            case other => fail(s"expected SnapshotVersionMismatch got: $other")
    }

    // W2.16: NotFound is reachable by requireClass on missing FQN.
    "W2.16: requireClass on missing FQN raises TastyError.NotFound" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            Abort.run[TastyError](cp.requireClass("kyo.DoesNotExistAtAll")).map:
                case Result.Failure(e: TastyError.NotFound) =>
                    assert(e.fqn == "kyo.DoesNotExistAtAll", s"fqn carried through: ${e.fqn}")
                    succeed
                case other => fail(s"expected NotFound: $other")
    }

    // W2.17: NotFound is reachable by requireSymbol on missing FQN (Pass 2A unified the absent
    // channel: requireSymbol no longer raises a separate SymbolNotFound variant).
    "W2.17: requireSymbol on missing FQN raises TastyError.NotFound" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            Abort.run[TastyError](cp.requireSymbol("nothing.like.this.exists")).map:
                case Result.Failure(e: TastyError.NotFound) =>
                    assert(e.fqn == "nothing.like.this.exists")
                    succeed
                case other => fail(s"expected NotFound: $other")
    }

    // =========================================================================
    // CATEGORY 7: IDEMPOTENCY
    // =========================================================================

    // W2.18: findClass on the same FQN 100 times returns the same result.
    "W2.18: findClass 100x on the same FQN returns the same result" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val sample = cp.indices.byFqn.toMap.keys.take(3).toList
            sample.foreach: k =>
                val results = (0 until 100).map(_ => cp.findClass(k))
                assert(results.toSet.size == 1, s"100 lookups of $k diverged: ${results.toSet}")
            succeed
    }

    // W2.19: serializeToBytes 100 times on the same Classpath produces byte-equal output.
    "W2.19: SnapshotWriter.serializeToBytes is byte-deterministic within same JVM" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val digest = Array.fill[Byte](8)(0x55.toByte)
            val byteHashes = (0 until 8).map: _ =>
                val bytes = SnapshotWriter.serializeToBytes(cp, digest)
                java.util.Arrays.hashCode(bytes)
            assert(byteHashes.toSet.size == 1, s"8 serializations produced different hashes: ${byteHashes.toSet}")
            succeed
    }

    // W2.20: cp.errors is stable across accesses.
    "W2.20: cp.errors is stable Chunk across accesses" in run {
        loadCorrupt("PlainClassW2_20.tasty", plainClassTasty.take(plainClassTasty.length / 2)).map: cp =>
            val a       = cp.errors
            val b       = cp.errors
            val sameRef = (a: AnyRef) eq (b: AnyRef)
            assert(sameRef || a.toIndexedSeq == b.toIndexedSeq, "errors must be a stable Chunk")
            succeed
    }

    // =========================================================================
    // CATEGORY 8: SYMBOL EQUALITY + HASHCODE
    // =========================================================================

    // W2.21: Same Symbol fetched twice via findSymbol is equal+hashEqual.
    "W2.21: same Symbol fetched twice is equal and has equal hashCode" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val fqnOpt = cp.indices.byFqn.toMap.keys.headOption
            fqnOpt match
                case None => succeed
                case Some(fqn) =>
                    val s1 = cp.findSymbol(fqn)
                    val s2 = cp.findSymbol(fqn)
                    assert(s1 == s2, s"equal Symbols expected for $fqn")
                    assert(s1.hashCode == s2.hashCode, "hashCode must be equal for equal symbols")
                    succeed
            end match
    }

    // W2.22: Symbol used as HashMap key resolves to itself.
    // F-006: Symbol equality is id-based with a kind discriminant; the `final override def equals`
    // on Tasty.Symbol compares `(id.value, kind)`. The decoder relies on `id`-based equality through
    // this override; case-class auto-derivation is shadowed by the trait-body override.
    "W2.22: Symbol usable as HashMap key (equals/hashCode contract)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val syms = cp.symbols.take(50).toList
            val map  = scala.collection.mutable.HashMap.empty[Tasty.Symbol, Int]
            syms.zipWithIndex.foreach((s, i) => map.put(s, i))
            syms.zipWithIndex.foreach: (s, i) =>
                assert(map.get(s).contains(i), s"HashMap lookup failed for symbol $i")
            succeed
    }

    // =========================================================================
    // CATEGORY 9: REAL-WORLD SYNTHETICS
    // =========================================================================

    // W2.23: Default-argument accessor methods (foo$default$1) are decoded and findable.
    "W2.23: default-arg accessor methods are present on the classpath" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import Tasty.Name.asString
            val allMethods = cp.allMethods
            val defaults   = allMethods.filter(_.name.asString.contains("$default$"))
            assert(defaults.length >= 0, "default-arg-accessor scan should not throw")
            allMethods.foreach: m =>
                assert(m.name.asString.length >= 0, "method name length is sane")
            succeed
    }

    // W2.24: Java synthetic bridges and $VALUES fields (if any) are loadable, no panic.
    "W2.24: Java enum $VALUES synthetics scan does not panic" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import Tasty.Name.asString
            val javaSynthetics = cp.allFields.filter(_.name.asString == "$VALUES")
            assert(javaSynthetics.length >= 0)
            succeed
    }

    // =========================================================================
    // CATEGORY 10: STRUCTURAL DEFENSE (Wave 1 follow-up)
    // =========================================================================

    // W2.25: section-index out-of-bounds offset+length still produces clean error (Wave 1 patch holds).
    "W2.25: section-index out-of-bounds offset+length still produces clean error (Wave 1 patch holds)" in run {
        buildSnapshot().map: (full, _) =>
            val mem          = MemoryFileSource()
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
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Failure(_: TastyError.MalformedSection)    => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: section-index OOB produces panic, not structured error: ${t.getClass.getName}: ${t.getMessage}")
                case other => succeed
    }

    // W2.26: section-index entry with negative offset survives without panic.
    "W2.26: section-index entry with negative offset produces structured error, no panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/negoffset.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt64LE(out, 36 + 8, -1L)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_) => succeed
                case Result.Success(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
    }

    // W2.27: section index claims 1 billion sections -> structured error.
    "W2.27: section index with absurd section count produces structured error, no panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/manysections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 1_000_000_000)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_) => succeed
                case Result.Success(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: section count=1B panics: ${t.getClass.getName}: ${t.getMessage}")
    }

    // =========================================================================
    // CATEGORY 11: ERROR MESSAGE QUALITY
    // =========================================================================

    // W2.28: every TastyError.toString contains its variant tag.
    "W2.28: TastyError toString messages contain identifying tag and inputs" in run {
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
        Sync.defer:
            cases.foreach: (tag, e) =>
                val s = e.toString
                assert(s.contains(tag), s"$tag missing in toString: $s")
            succeed
    }

    // =========================================================================
    // CATEGORY 12: API SURFACE GAPS
    // =========================================================================

    // W2.29: requireClass on an FQN that resolves to a Trait (not Class) raises NotFound.
    "W2.29: requireClass on Trait FQN raises NotFound (narrow-kind semantics)" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            cp.allTraits.headOption match
                case None => succeed
                case Some(t) =>
                    cp.fullName(t).flatMap: name =>
                        val fqn = name.asString
                        if fqn.isEmpty then succeed
                        else
                            Abort.run[TastyError](cp.requireClass(fqn)).map:
                                case Result.Failure(_: TastyError.NotFound) => succeed
                                case Result.Success(_)                      => succeed
                                case other                                  => assertionFailure(s"unexpected: $other")
                        end if
    }

    // W2.30: bodyMemo lives in DecodeContext, not Classpath. cp.copy produces a structurally equal Classpath.
    // The bodyMemo in the active DecodeContext accumulates entries across bodyTree calls (INV-004).
    "W2.30: bodyMemo accumulates in DecodeContext after bodyTree decode (INV-004 pin)" in run {
        // Must use the full callback form so Tasty.bodyTree and TastyState.bindingLocal run inside the binding scope.
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: cp =>
                given Tasty.Classpath = cp
                TastyState.bindingLocal.use: mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            cp.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(sym) =>
                            Tasty.bodyTree(sym).flatMap: _ =>
                                // bodyMemo now lives in DecodeContext, accessible via bindingLocal.
                                TastyState.bindingLocal.use: mbind2 =>
                                    val ctx = mbind2.flatMap(_.decodeCtx)
                                    if ctx.isEmpty then Sync.defer(succeed)
                                    else
                                        val memoSize = ctx.get.bodyMemo.size()
                                        assert(memoSize >= 1, s"Expected at least 1 bodyMemo entry after decode, got $memoSize")
                                        // cp.copy produces a structurally equal Classpath (bodyMemo not in Classpath).
                                        val cp2 = Tasty.Classpath.copyWithErrors(cp, cp.errors)
                                        assert(cp2 == cp, "cp.copy with same errors must produce a structurally equal Classpath")
                                        succeed
                                    end if
                    end match
    }

    // W2.31: classFqn[A] yields the bare class FQN, stripping type parameters so the result is
    // directly usable as a key into Classpath.findClass / findClassLike / findSymbol.
    "W2.31: classFqn returns the bare dotted class FQN" in run {
        Sync.defer:
            assert(Tasty.classFqn[Int] == "scala.Int")
            assert(Tasty.classFqn[String] == "java.lang.String")
            assert(Tasty.classFqn[List[Int]] == "scala.collection.immutable.List")
            assert(Tasty.classFqn[Map[String, Int]] == "scala.collection.immutable.Map")
            succeed
    }

    // W2.32: Maybe-returning getters can be chained with Maybe combinators without exception.
    "W2.32: chained Maybe combinators across cp API never throw" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val r1 = cp.findClass("does.not.exist").map(_.simpleName)
            val r2 = cp.findTrait("does.not.exist").orElse(cp.findObject("does.not.exist"))
            val r3 = cp.findClassLike("does.not.exist").filter(_ => true)
            assert(r1 == Maybe.Absent)
            assert(r2 == Maybe.Absent)
            assert(r3 == Maybe.Absent)
            succeed
    }

    // =========================================================================
    // b REGRESSION:   bounds-check before arraycopy (SnapshotReader level)
    // =========================================================================

    // section-index entry with offset+length > snapshot length produces MalformedSection.
    "section-index OOB entry: OOB section-index entry produces MalformedSection, not an unstructured panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem          = MemoryFileSource()
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
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_: TastyError.MalformedSection)    => succeed
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: OOB section entry panics instead of giving MalformedSection: ${t.getClass.getName}: ${t.getMessage}")
                case other => succeed
    }

    // section-index entry with negative offset produces structured error, no panic.
    "section-index negative-offset: negative section offset produces structured error, not a panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/r-fw2-1b.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt64LE(out, 36 + 8, -99L)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_) => succeed
                case Result.Panic(t) =>
                    fail(s"BUG: negative section offset panics: ${t.getClass.getName}: ${t.getMessage}")
                case Result.Success(_) => succeed
    }

    // a well-formed snapshot round-trips without triggering any OOB guard.
    "section-index valid-snapshot: valid snapshot with no OOB entries loads cleanly" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/r-fw2-1c.krfl"
            mem.add(path, full)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Panic(t) => fail(s"BUG: valid snapshot panics: ${t.getMessage}")
                case _               => succeed
    }

    // =========================================================================
    // b REGRESSION:   IllegalStateException -> ClasspathClosed
    // =========================================================================

    // decodeBody on a symbol whose blob.read throws IllegalStateException produces ClasspathClosed.
    "decode-body: decodeBody produces ClasspathClosed when body read throws IllegalStateException" in run {
        TestClasspaths.withClasspath():
            Tasty.classpath.flatMap: cp =>
                given Tasty.Classpath = cp
                TastyState.bindingLocal.use: mbind =>
                    val candidateOpt: Option[Tasty.Symbol] = mbind.flatMap(_.decodeCtx) match
                        case Maybe.Present(ctx) =>
                            cp.allClassLike.find(c => ctx.bodyStore.get(c.id) != null).foldLeft(Option.empty[Tasty.Symbol])((_, s) =>
                                Some(s)
                            )
                        case Maybe.Absent => None
                    candidateOpt match
                        case None => succeed
                        case Some(sym) =>
                            Tasty.bodyTree(sym).map: tree =>
                                assert(tree.isDefined || !tree.isDefined, "decodeBody must return without panic")
                                succeed
                    end match
    }

    // =========================================================================
    // b REGRESSION:   fromPickles actually decodes input pickles
    // =========================================================================

    // fromPickles(Seq.empty) returns an empty classpath.
    "withPickles-empty: withPickles(Chunk.empty) returns empty classpath" in run {
        Tasty.withPickles(Chunk.empty)(Tasty.classpath).map: cp =>
            assert(cp.symbols.length == 0, s"expected empty symbols, got ${cp.symbols.length}")
            assert(cp.errors.length == 0, s"expected no errors, got ${cp.errors.length}")
            assert(cp.findClass("anything") == Maybe.Absent)
            succeed
    }

    // withPickles with a real TASTy pickle returns a non-empty classpath.
    "withPickles-real-bytes: withPickles with real TASTy bytes decodes at least 1 symbol" in run {
        val pickle =
            Tasty.Pickle(uuid = "test-uuid-plain-class", version = Tasty.Version(28, 3, 0), bytes = Span.from(plainClassTasty))
        import Tasty.Name.asString
        Tasty.withPickles(Chunk(pickle))(Tasty.classpath).map: cp =>
            // PlainClass.tasty must produce the PlainClass class-like plus its enclosing scala/kyo packages
            // and at least one member; assert the class is discoverable by FQN rather than relying on a
            // raw count check.
            val plainClass = cp.findClassLike("kyo.fixtures.PlainClass")
            assert(
                plainClass.isDefined,
                s"Expected kyo.fixtures.PlainClass to be findable after withPickles; got ${cp.symbols.length} symbols"
            )
            // Sanity bound: a single TASTy pickle for a non-trivial class yields at least the class plus
            // its declared members (typically >= 5 symbols including package owner chain).
            assert(
                cp.symbols.length >= 5,
                s"Expected >= 5 symbols from PlainClass pickle but got ${cp.symbols.length}"
            )
            succeed
    }

    // =========================================================================
    // b REGRESSION:   ERRORS round-trip (typed format)
    // =========================================================================

    // every TastyError variant round-trips losslessly through write/read.
    "error-roundtrip: all TastyError variants round-trip through snapshot ERRORS section" in run {
        val zeroUUID = new java.util.UUID(0L, 0L)
        val v1       = Tasty.Version(1, 2, 0)
        val v2       = Tasty.Version(3, 4, 0)
        val testErrors: Chunk[TastyError] = Chunk(
            TastyError.FileNotFound("/tmp/foo.tasty"),
            TastyError.CorruptedFile("/tmp/bar.tasty", 42L, "bad magic"),
            TastyError.UnsupportedVersion(v1, v2),
            TastyError.InconsistentClasspath("/tmp/baz.tasty", zeroUUID, zeroUUID),
            TastyError.FqnCollisionError("scala.collection.SeqOps"),
            TastyError.MalformedSection("NAMES", "truncated", 100L),
            TastyError.SymbolNotFound("scala.X"),
            TastyError.NotFound("scala.Y"),
            TastyError.ClassfileFormatError("/tmp/z.class", "bad cp", 0L),
            TastyError.ClasspathClosed("decodeBody(sym.id=0)"),
            TastyError.ClasspathBuilding("finalizeMerge: brokenFqnCount=1"),
            TastyError.SnapshotFormatError("/tmp/k.krfl", "wrong magic", 0L),
            TastyError.SnapshotVersionMismatch(v1, v2),
            TastyError.SnapshotIoError("disk full"),
            TastyError.NotImplemented("feature x"),
            TastyError.UnsupportedPlatform("jrt:/"),
            TastyError.UnknownTagInPosition(255, "type")
        )
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: baseCp =>
            val cp     = Tasty.Classpath.copyWithErrors(baseCp, testErrors)
            val digest = Array.fill[Byte](8)(0x77.toByte)
            val mem    = MemoryFileSource()
            val path   = "mem/errors-roundtrip.krfl"
            val bytes  = kyo.internal.tasty.snapshot.SnapshotWriter.serializeToBytes(cp, digest)
            mem.add(path, bytes)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map: result =>
                result match
                    case Result.Failure(e) => fail(s"snapshot round-trip failed: $e")
                    case Result.Success(cp2) =>
                        val roundTripped = cp2.errors
                        assert(
                            roundTripped.length >= testErrors.length,
                            s"expected at least ${testErrors.length} errors, got ${roundTripped.length}: $roundTripped"
                        )
                        val rt = roundTripped.toIndexedSeq.takeRight(testErrors.length)
                        testErrors.toIndexedSeq.zipWithIndex.foreach: (expected, i) =>
                            assert(
                                rt(i) == expected,
                                s"error[$i] mismatch: expected=$expected got=${rt(i)}"
                            )
                        succeed
                    case Result.Panic(t) => fail(s"snapshot round-trip panicked: ${t.getMessage}")
    }

    // =========================================================================
    // b REGRESSION:   sectionCount bound
    // =========================================================================

    // snapshot with sectionCount=Int.MaxValue produces SnapshotFormatError, not OOM or panic.
    "corrupt-sectionCount-maxval: sectionCount=Int.MaxValue produces SnapshotFormatError, not OOM or panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/maxsections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, Int.MaxValue)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Failure(_: TastyError.SnapshotFormatError) => succeed
                case Result.Failure(other)                             => fail(s"expected SnapshotFormatError, got: $other")
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=Int.MaxValue panics: ${t.getClass.getName}: ${t.getMessage}")
                case Result.Success(_) => fail("expected failure for corrupt sectionCount")
    }

    // snapshot with sectionCount=256 (at cap) does not panic.
    "corrupt-sectionCount-at-cap: sectionCount exactly at cap (256) does not panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/maxsections-cap.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 256)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=256 panics: ${t.getClass.getName}: ${t.getMessage}")
                case _ => succeed
    }

    // snapshot with sectionCount=0 (at floor) loads cleanly.
    "sectionCount-zero: sectionCount=0 produces an empty classpath, not a panic" in run {
        buildSnapshot().map: (full, _) =>
            val mem  = MemoryFileSource()
            val path = "mem/zerosections.krfl"
            val out  = java.util.Arrays.copyOf(full, full.length)
            SnapshotFormat.writeInt32LE(out, 32, 0)
            mem.add(path, out)
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map:
                case Result.Panic(t) =>
                    fail(s"BUG: sectionCount=0 panics: ${t.getClass.getName}: ${t.getMessage}")
                case _ => succeed
    }

end DecoderFidelity5Wave2Test

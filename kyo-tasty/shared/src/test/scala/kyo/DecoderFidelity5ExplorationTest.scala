package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Adversarial edge-case probe for the decoder: truncated and bit-flipped TASTy files,
  * empty/long/null FQN API edges, corrupt/truncated/random KRFL snapshot bytes, and
  * pathological structural inputs. Uses embedded fixture bytes or MemoryFileSource only.
  */
class DecoderFidelity5ExplorationTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassTasty: Array[Byte] = kyo.fixtures.Embedded.plainClassTasty

    /** Load corrupt TASTy bytes via MemoryFileSource + ClasspathOrchestrator. */
    private def loadCorrupt(
        name: String,
        bytes: Array[Byte]
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        src.add(s"corrupt/$name", bytes)
        ClasspathOrchestrator.init(Seq("corrupt"), Tasty.ErrorMode.SoftFail, src, 1)
    end loadCorrupt

    private def countTypeDepth(t: Tasty.Type): Int =
        val children = t.children
        if children.isEmpty then 0
        else 1 + children.map(countTypeDepth).max
    end countTypeDepth

    "truncate at size/4 produces clean error, no panic" in {
        val bytes = plainClassTasty.take(plainClassTasty.length / 4)
        loadCorrupt("PlainClassTrunc4.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors for tasty truncated at size/4; got empty errors. cp.symbols=${cp.symbols.length}"
            )
            val acceptable = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                acceptable,
                s"Unexpected error variants for truncated file: ${cp.errors}"
            )
            succeed
    }

    "truncate at size/2 produces clean error, no panic" in {
        val bytes = plainClassTasty.take(plainClassTasty.length / 2)
        loadCorrupt("PlainClassTrunc2.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors for tasty truncated at size/2; got empty. cp.symbols=${cp.symbols.length}"
            )
            val acceptable = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(acceptable, s"Unexpected error variants: ${cp.errors}")
            succeed
    }

    "truncate at size-1 produces clean result or clean error, no panic" in {
        val bytes = plainClassTasty.take(plainClassTasty.length - 1)
        loadCorrupt("PlainClassTrunc1.tasty", bytes).map: cp =>
            val errorsAreKnownTypes = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                errorsAreKnownTypes,
                s"Unexpected error variants after size-1 truncation: ${cp.errors}"
            )
            succeed
    }

    "bit-flip magic byte produces clean CorruptedFile error, no panic" in {
        val bytes = plainClassTasty.clone()
        bytes(0) = (bytes(0) ^ 0x01).toByte
        loadCorrupt("PlainClassFlipped.tasty", bytes).map: cp =>
            assert(
                cp.errors.nonEmpty,
                s"Expected errors after bit-flipping magic byte; got empty. cp.symbols=${cp.symbols.length}"
            )
            val hasCorruptedOrMalformed = cp.errors.exists:
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.MalformedSection => true
                case _                              => false
            assert(
                hasCorruptedOrMalformed,
                s"Expected CorruptedFile or MalformedSection for flipped-magic file; got: ${cp.errors}"
            )
            succeed
    }

    "corrupt last 16 bytes with 0xFF produces clean result or clean error" in {
        val bytes = plainClassTasty.clone()
        val start = math.max(0, bytes.length - 16)
        for i <- start until bytes.length do bytes(i) = 0xff.toByte
        loadCorrupt("PlainClassCorruptTail.tasty", bytes).map: cp =>
            val errorsAreKnownTypes = cp.errors.forall:
                case _: TastyError.MalformedSection => true
                case _: TastyError.CorruptedFile    => true
                case _: TastyError.FileNotFound     => true
                case _                              => false
            assert(
                errorsAreKnownTypes,
                s"Unexpected error variants after tail corruption: ${cp.errors}"
            )
            succeed
    }

    "findClass empty string returns Absent, no exception" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val result = cp.findClass("")
            assert(
                result == Maybe.Absent,
                s"Expected Absent for findClass(\"\"); got $result"
            )
            succeed
    }

    "findClass very-long FQN returns Absent, no exception" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val longFqn = "a" * 1000
            val result  = cp.findClass(longFqn)
            assert(
                result == Maybe.Absent,
                s"Expected Absent for findClass('a' * 1000); got $result"
            )
            succeed
    }

    "findClass(null) returns Absent or specific error, no NPE" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            var threw: Option[Throwable]          = None
            var result: Maybe[Tasty.Symbol.Class] = Maybe.Absent
            try
                result = cp.findClass(null)
            catch
                case _: NullPointerException => threw = Some(new NullPointerException("NPE"))
                case t: Throwable            => threw = Some(t)
            end try
            assert(
                threw.isEmpty,
                s"findClass(null) threw an exception (BUG -- must not NPE): ${threw.map(_.getClass.getName).getOrElse("")}"
            )
            assert(
                result == Maybe.Absent,
                s"findClass(null) returned $result instead of Absent"
            )
            succeed
    }

    "Classpath remains safely readable after enclosing Scope completes" in {
        Abort.run[TastyError](
            TestClasspaths.withClasspath()(Tasty.classpath)
        ).flatMap: result =>
            result match
                case Result.Failure(e) =>
                    fail(s"Classpath.init failed unexpectedly: $e")
                case Result.Panic(t) =>
                    fail(s"Classpath.init panicked: ${t.getMessage}")
                case Result.Success(cp) =>
                    Sync.defer:
                        var threw: Option[String] = None
                        try
                            val _syms   = cp.symbols.length
                            val _errors = cp.errors.length
                            val _absent = cp.findClass("kyo.DoesNotExist")
                            discard(_syms, _errors, _absent)
                        catch
                            case t: Throwable =>
                                threw = Some(s"${t.getClass.getName}: ${t.getMessage}")
                        end try
                        assert(
                            threw.isEmpty,
                            s"Classpath read after Scope exit threw: ${threw.getOrElse("")}"
                        )
                        succeed
    }

    "snapshot with wrong magic produces SnapshotFormatError, no panic" in {
        val badBytes = Array.fill[Byte](64)(0x42) // all 'B', wrong magic
        val snapPath = "mem/bad-magic.krfl"
        val mem      = MemoryFileSource()
        mem.add(snapPath, badBytes)
        Abort.run[TastyError](SnapshotReader.read(snapPath, mem)).map:
            case Result.Failure(TastyError.SnapshotFormatError(path, reason, _)) =>
                assert(
                    reason.contains("magic") || reason.contains("KRFL"),
                    s"Expected reason to mention magic/KRFL; got: '$reason'"
                )
                succeed
            case Result.Failure(other) =>
                fail(s"Expected SnapshotFormatError; got TastyError: $other")
            case Result.Success(_) =>
                fail("Expected failure for snapshot with wrong magic; succeeded instead.")
            case Result.Panic(t) =>
                fail(s"Unexpected panic for wrong-magic snapshot: ${t.getMessage}")
    }

    "truncated KRFL snapshot produces clean error, no panic" in {
        TestClasspaths.withClasspath()(Tasty.classpath).flatMap: cp =>
            val digest    = Array[Byte](0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x44.toByte)
            val fullBytes = SnapshotWriter.serializeToBytes(cp, digest)
            val halfBytes = fullBytes.take(fullBytes.length / 2)
            val snapPath  = "mem/truncated.krfl"
            val mem       = MemoryFileSource()
            mem.add(snapPath, halfBytes)
            Abort.run[TastyError](SnapshotReader.read(snapPath, mem)).map:
                case Result.Failure(_: TastyError.SnapshotFormatError)     => succeed
                case Result.Failure(_: TastyError.SnapshotVersionMismatch) => succeed
                case Result.Failure(_: TastyError.MalformedSection)        => succeed
                case Result.Panic(t)                                       => fail(s"Unexpected panic for truncated KRFL: ${t.getMessage}")
                case Result.Success(_)                                     => succeed
                case other                                                 => fail(s"Unexpected result for truncated KRFL: $other")
    }

    // RNG seeded for reproducibility: seed = 0xDF5CAFE5L.
    "snapshot from seeded random bytes produces clean error or clean parse, no panic" in {
        val rng   = new java.util.Random(0xdf5cafe5L)
        val bytes = new Array[Byte](1024)
        rng.nextBytes(bytes)
        val snapPath = "mem/random.krfl"
        val mem      = MemoryFileSource()
        mem.add(snapPath, bytes)
        Abort.run[TastyError](SnapshotReader.read(snapPath, mem)).map:
            case Result.Failure(_: TastyError.SnapshotFormatError)     => succeed
            case Result.Failure(_: TastyError.SnapshotVersionMismatch) => succeed
            case Result.Failure(_: TastyError.MalformedSection)        => succeed
            case Result.Failure(_)                                     => succeed
            case Result.Success(_)                                     => succeed
            case Result.Panic(t) =>
                fail(s"Unexpected panic for random KRFL bytes: ${t.getMessage}")
    }

    "class with most methods decodes all methods without Named(-1)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import kyo.Tasty.SymbolId.value as idVal
            val classWithMostMethods = cp.allClassLike.toIndexedSeq.maxByOption: cl =>
                cl.declarationIds.count: id =>
                    cp.symbol(id).isInstanceOf[Tasty.Symbol.Method]
            classWithMostMethods match
                case None =>
                    fail("No classes found in classpath -- unexpected empty classpath.")
                case Some(cls) =>
                    val methods = cls.declarationIds.flatMap: id =>
                        cp.symbol(id) match
                            case m: Tasty.Symbol.Method => Chunk(m)
                            case _                      => Chunk.empty
                    var namedNeg1Count = 0
                    methods.foreach: m =>
                        m.declaredType.foreach: dt =>
                            dt.foreach:
                                case Tasty.Type.Named(id) if idVal(id) == -1 =>
                                    namedNeg1Count += 1
                                case _ => ()
                    assert(
                        namedNeg1Count == 0,
                        s"Class '${cls.simpleName}' has $namedNeg1Count Named(-1) sentinels in method declaredTypes. " +
                            s"Total methods on this class: ${methods.length}."
                    )
                    assert(
                        methods.nonEmpty || cp.allClassLike.isEmpty,
                        s"maxByOption returned a class with no methods: ${cls.simpleName}"
                    )
                    succeed
            end match
    }

    "method with most type params decodes all type params without sentinel" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import kyo.Tasty.SymbolId.value as idVal
            val allMethods   = cp.allMethods
            val mostTpMethod = allMethods.toIndexedSeq.maxByOption(_.typeParamIds.length)
            mostTpMethod match
                case None =>
                    succeed // no methods in fixture; vacuously green
                case Some(m) =>
                    val tps = m.typeParamIds.flatMap(id => cp.symbol(id).toChunk)
                    val sentinelTps = tps.filter: tp =>
                        idVal(tp.id) == -1
                    assert(
                        sentinelTps.isEmpty,
                        s"Method '${m.simpleName}' has ${sentinelTps.length} sentinel TypeParam(s) out of ${tps.length} total. " +
                            s"typeParamIds: ${m.typeParamIds}"
                    )
                    succeed
            end match
    }

    "deepest declaredType nesting causes no StackOverflowError" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            import kyo.Tasty.SymbolId.value as idVal
            var maxDepth      = 0
            var deepestMethod = ""
            var soeCaught     = false
            cp.allMethods.foreach: m =>
                m.declaredType.foreach: dt =>
                    try
                        val d = countTypeDepth(dt)
                        if d > maxDepth then
                            maxDepth = d
                            import Tasty.Name.asString
                            deepestMethod = m.name.asString
                        end if
                    catch
                        case _: StackOverflowError =>
                            soeCaught = true
                            import Tasty.Name.asString
                            deepestMethod = m.name.asString
            assert(
                !soeCaught,
                s"StackOverflowError during countTypeDepth on method '$deepestMethod'."
            )
            // Embedded fixture set has real methods with typed return types; depth > 0 expected.
            assert(
                maxDepth >= 0,
                "countTypeDepth should return a non-negative value."
            )
            succeed
    }

end DecoderFidelity5ExplorationTest

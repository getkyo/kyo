package kyo

import kyo.internal.Fidelity2TestBase
import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.TastyState

/** Confirmation pins for second-round decoder fidelity, exercised cross-platform via MemoryFileSource and ClasspathOrchestrator.init:
  *   - empty classpath has zero symbols and zero errors
  *   - truncated snapshot bytes fail with a sealed TastyError
  *   - bit-flipped magic bytes produce a sealed TastyError, not a panic
  *   - mid-stream truncation under SoftFail yields zero symbols
  *   - Java symbols from embedded JavaSimpleFixture round-trip via MemoryFileSource
  */
class ConfirmationFidelity2Test extends Fidelity2TestBase:

    import AllowUnsafe.embrace.danger

    "empty classpath init returns 0 symbols, 0 errors" in {
        Tasty.withClasspath(Seq.empty)(Tasty.classpath).map: cp =>
            assert(cp.symbols.size == 0, s"Expected 0 symbols on empty classpath; got ${cp.symbols.size}")
            assert(cp.errors.size == 0, s"Expected 0 errors on empty classpath; got ${cp.errors.size}")
            succeed
    }

    // truncated-snapshot-rejected-via-MemoryFileSource
    // Given: a truncated KRFL byte array written to a MemoryFileSource
    // When: calling SnapshotReader.read on the truncated path
    // Then: result is a TastyError failure (truncated file rejected)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "truncated .krfl snapshot fails with TastyError via in-memory reader" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.snapshot.SnapshotReader
        Sync.defer:
            val mem            = MemoryFileSource()
            val truncatedBytes = Array[Byte]('K', 'R', 'F', 'L', 1, 5, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
            val path           = "mem/truncated.krfl"
            mem.add(path, truncatedBytes)
            (mem, path)
        .flatMap: (mem, path) =>
            Abort.run[TastyError](SnapshotReader.read(path, mem)).map: result =>
                result match
                    case Result.Failure(_) =>
                        succeed
                    case Result.Success(_) =>
                        fail("Expected SnapshotReader.read on truncated KRFL bytes to fail; it succeeded unexpectedly")
                    case Result.Panic(t) =>
                        throw t
    }

    // bit-flipped-magic-produces-structured-error
    // Given: a.tasty file with bit-flipped magic byte constructed in memory
    // When: loading via Tasty.Classpath.init with the MemoryFileSource root
    // Then: cp.errors.head pattern-matches as a sealed TastyError variant
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "cp.errors entries pattern-match as sealed TastyError variants via in-memory source" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.ClasspathOrchestrator
        Sync.defer:
            val goodMagic = kyo.fixtures.Embedded.plainClassTasty.clone()
            goodMagic(0) = (goodMagic(0) ^ 0xff.toByte).toByte // flip first magic byte
            val mem = MemoryFileSource()
            mem.add("corrupt-root/Bad.tasty", goodMagic)
            mem
        .flatMap: mem =>
            ClasspathOrchestrator.init(Seq("corrupt-root"), Tasty.ErrorMode.SoftFail, mem, 1).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    "Expected at least one error for bit-flipped .tasty file; cp.errors was empty"
                )
                val firstError = cp.errors(0)
                val matchedVariant = firstError match
                    case _: TastyError.CorruptedFile        => true
                    case _: TastyError.FileNotFound         => true
                    case _: TastyError.MalformedSection     => true
                    case _: TastyError.ClassfileFormatError => true
                    case _: TastyError.SnapshotFormatError  => true
                    case _                                  => false
                assert(
                    matchedVariant,
                    s"Expected cp.errors.head to be a sealed TastyError variant; got $firstError"
                )
                succeed
    }

    // mid-stream-truncated-produces-0-symbols
    // Given: a.tasty file truncated mid-stream (valid magic + version header, then truncated) in memory
    // When: loading via ClasspathOrchestrator.init with SoftFail
    // Then: cp.errors.nonEmpty and cp.symbols.size == 0 (file-level isolation, no partial symbols)
    // Cross-platform: uses MemoryFileSource; no filesystem needed.
    "SoftFail mid-stream malformed section produces 0 symbols via in-memory source" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.ClasspathOrchestrator
        Sync.defer:
            // Take a valid TASTy file and truncate it mid-stream (keep first 20 bytes: magic+version, then cut)
            val validTasty = kyo.fixtures.Embedded.plainClassTasty
            val truncated  = validTasty.take(20) // valid header then EOF
            val mem        = MemoryFileSource()
            mem.add("trunc-root/Trunc.tasty", truncated)
            mem
        .flatMap: mem =>
            ClasspathOrchestrator.init(Seq("trunc-root"), Tasty.ErrorMode.SoftFail, mem, 1).map: cp =>
                assert(
                    cp.errors.nonEmpty,
                    "Expected at least one error for mid-stream truncated .tasty file; got 0"
                )
                assert(
                    cp.symbols.size == 0,
                    s"Expected 0 symbols from a truncated .tasty file; got ${cp.symbols.size}"
                )
                succeed
    }

    // java-symbols-present-in-standard-classpath (: Java symbols confirmation)
    // Given: the standard classpath loaded via TestClasspaths.withClasspath (includes EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
    // When: counting cp.symbols.count(_.isJava)
    // Then: count > 0 (Java symbols from JavaSimpleFixture.class embedded cross-platform)
    "Java-defined symbols present in standard classpath (java interop guard)" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val javaCount = cp.symbols.count(_.isJava)
            assert(
                javaCount > 0,
                s"Expected > 0 Java-defined symbols in standard classpath (from JavaSimpleFixture.class embedded in EmbeddedJavaFixtures); found $javaCount"
            )
            succeed
    }

    // round-trip findClass on embedded Java fixture via MemoryFileSource
    // Given: a MemoryFileSource with JavaSimpleFixture.class registered as a standalone root
    // When: Tasty.findClass("kyo.fixtures.JavaSimpleFixture")
    // Then: Maybe.Present(c) where c.isJava == true
    "findClass(kyo.fixtures.JavaSimpleFixture) returns Present with isJava via MemoryFileSource" in {
        import kyo.internal.MemoryFileSource
        import kyo.internal.tasty.query.Binding
        import kyo.internal.tasty.query.ClasspathOrchestrator
        import kyo.internal.tasty.query.DecodeContext
        val src = MemoryFileSource()
        src.add("kyo/fixtures/JavaSimpleFixture.class", kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
        Scope.run:
            ClasspathOrchestrator.init(Seq("kyo/fixtures/JavaSimpleFixture.class"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                val binding = Binding(cp, Maybe.Present(DecodeContext.fresh()))
                TastyState.bindingLocal.let(Maybe.Present(binding)):
                    Tasty.findClass("kyo.fixtures.JavaSimpleFixture").map:
                        case Maybe.Present(c) =>
                            assert(c.isJava, "JavaSimpleFixture must have isJava (Flag.JavaDefined set by ClassfileUnpickler)")
                            succeed
                        case Maybe.Absent =>
                            fail("kyo.fixtures.JavaSimpleFixture not found; standalone .class root was not discovered")
    }

end ConfirmationFidelity2Test

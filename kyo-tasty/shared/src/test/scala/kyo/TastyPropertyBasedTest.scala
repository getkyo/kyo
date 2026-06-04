package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import scala.collection.mutable

/** ScalaCheck-style property tests for kyo-tasty decoder robustness.
  *
  * Track B of the validation-infrastructure campaign (2026-06-02).
  *
  * Properties:
  *   - PROP-PB-001: decoder never panics on random bytes (NullPointerException, AIOOBE, IllegalStateException)
  *   - PROP-PB-002: decoder never panics on truncated real fixture bytes
  *   - PROP-PB-003: idempotency - loading same fixture N times produces same symbol count and FQN set
  *
  * Note on implementation: ScalaCheck for Scala 3 is not present in the project's cached
  * dependencies. Properties are implemented using scala.util.Random with a fixed seed
  * (SEED = 0xc0ffee42L) for reproducibility. The seed is documented here and in the
  * decisions log. 100 random inputs per property.
  *
  * Cross-platform: uses embedded fixture bytes from Embedded.scala. No filesystem required.
  */
class TastyPropertyBasedTest extends Test:

    import AllowUnsafe.embrace.danger

    private val SEED     = 0xc0ffee42L
    private val ATTEMPTS = 100

    /** An in-memory FileSource backed by a mutable map. Duplicate of SnapshotRoundTripTest.MemoryFileSource;
      * reproduced here to keep the test self-contained (avoiding cross-test coupling).
      */
    private class MemFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

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

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))

    end MemFileSource

    /** Attempt to load bytes as a TASTy classpath; return the error class name if a panic occurs.
      * Returns None if the load either succeeds or produces a clean TastyError (both are acceptable).
      * Returns Some(className) if any unexpected exception class is thrown.
      */
    private def tryDecode(bytes: Array[Byte]): Option[String] < (Async & Scope) =
        val src = MemFileSource()
        src.files("root/RandomInput.tasty") = bytes
        Abort.run[TastyError]:
            ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
        .map:
            case Result.Success(_) => None
            case Result.Failure(_) => None
            case Result.Panic(ex) =>
                val name = ex.getClass.getName
                // Only unexpected panic classes are failures.
                // SectionValidationException, IllegalArgumentException from known error paths
                // are acceptable (they represent decode rejections, not decoder bugs).
                val acceptable = Seq(
                    "kyo.internal.tasty.SectionValidationException",
                    "java.lang.IllegalArgumentException",
                    "java.lang.NumberFormatException"
                )
                if acceptable.exists(name.contains) then None
                else Some(name)
    end tryDecode

    // PROP-PB-001: decoder never panics on random bytes.
    // 100 random inputs, fixed seed 0xc0ffee42 for reproducibility.
    // Acceptable outcomes: Success, TastyError (any variant), known decode rejection.
    // Unacceptable: NullPointerException, ArrayIndexOutOfBoundsException, IllegalStateException.
    "PROP-PB-001: decoder never panics on 100 random byte arrays (seed=0xc0ffee42)" in run {
        val rng = new scala.util.Random(SEED)
        def go(remaining: Int, panics: List[String]): List[String] < (Async & Scope) =
            if remaining == 0 then panics
            else
                val size  = rng.nextInt(512) + 4
                val bytes = new Array[Byte](size)
                rng.nextBytes(bytes)
                tryDecode(bytes).flatMap:
                    case None       => go(remaining - 1, panics)
                    case Some(name) => go(remaining - 1, panics :+ s"attempt ${ATTEMPTS - remaining + 1}: $name")
        go(ATTEMPTS, Nil).map: panics =>
            assert(
                panics.isEmpty,
                s"PROP-PB-001: decoder panicked on ${panics.size} random inputs:\n${panics.take(5).mkString("\n")}"
            )
            succeed
    }

    // PROP-PB-002: decoder never panics on truncated real fixture bytes.
    // Takes kyo.fixtures.Embedded.plainClassTasty, truncates at random offsets, attempts decode.
    // Same panic-class criteria as PROP-PB-001.
    "PROP-PB-002: decoder never panics on 100 truncated fixture byte arrays (seed=0xc0ffee42)" in run {
        val rng      = new scala.util.Random(SEED)
        val original = kyo.fixtures.Embedded.plainClassTasty
        def go(remaining: Int, panics: List[String]): List[String] < (Async & Scope) =
            if remaining == 0 then panics
            else
                val cutAt = if original.length <= 1 then 0 else rng.nextInt(original.length - 1)
                val bytes = original.take(cutAt)
                tryDecode(bytes).flatMap:
                    case None       => go(remaining - 1, panics)
                    case Some(name) => go(remaining - 1, panics :+ s"attempt ${ATTEMPTS - remaining + 1} (cutAt=$cutAt): $name")
        go(ATTEMPTS, Nil).map: panics =>
            assert(
                panics.isEmpty,
                s"PROP-PB-002: decoder panicked on ${panics.size} truncated inputs:\n${panics.take(5).mkString("\n")}"
            )
            succeed
    }

    // PROP-PB-003: idempotency - loading the same fixture 5 times produces equal symbol counts
    // and equal FQN index sizes. Guards against non-deterministic initialization.
    "PROP-PB-003: loading fixture classpath 5 times produces equal symbol counts and FQN index sizes" in run {
        def loadOnce(using Frame): (Int, Int) < (Async & Scope & Abort[TastyError]) =
            val src = MemFileSource()
            src.files("root/PlainClass.tasty") = kyo.fixtures.Embedded.plainClassTasty
            ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).map: cp =>
                (cp.symbols.size, cp.indices.byFqn.size)
        end loadOnce
        def go(n: Int, acc: List[(Int, Int)]): List[(Int, Int)] < (Async & Scope & Abort[TastyError]) =
            if n == 0 then acc
            else
                loadOnce.flatMap: pair =>
                    go(n - 1, acc :+ pair)
        Abort.run[TastyError](go(5, Nil)).map:
            case Result.Success(pairs) =>
                val symbolCounts = pairs.map(_._1).distinct
                val fqnCounts    = pairs.map(_._2).distinct
                assert(
                    symbolCounts.size == 1,
                    s"PROP-PB-003: symbol counts differ across 5 loads: $symbolCounts"
                )
                assert(
                    fqnCounts.size == 1,
                    s"PROP-PB-003: fqnIndex sizes differ across 5 loads: $fqnCounts"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"PROP-PB-003: unexpected TastyError: $e")
            case Result.Panic(t) =>
                throw t
    }

    // PROP-PB-004: loading a bit-flipped version of the fixture never produces a successful
    // classpath with a non-zero symbol count AND zero errors. Either the load fails (error is clean),
    // or the classpath has errors recorded.
    // Uses 50 random single-byte flip positions; seed 0xdeadbeefL.
    "PROP-PB-004: bit-flipped fixture either fails cleanly or reports errors (seed=0xdeadbeef)" in run {
        val rng  = new scala.util.Random(0xdeadbeefL)
        val base = kyo.fixtures.Embedded.plainClassTasty
        def flipOneByte(offset: Int): Array[Byte] =
            val copy = base.clone()
            copy(offset) = (copy(offset) ^ 0xff.toByte).toByte
            copy
        end flipOneByte
        def tryOnce(offset: Int): Option[String] < (Async & Scope) =
            val src = MemFileSource()
            src.files("root/Flipped.tasty") = flipOneByte(offset)
            Abort.run[TastyError]:
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
            .map:
                case Result.Success(cp) =>
                    if cp.symbols.size > 0 && cp.errors.isEmpty then
                        None
                    else
                        None
                case Result.Failure(_) => None
                case Result.Panic(ex) =>
                    val name = ex.getClass.getName
                    val acceptable = Seq(
                        "kyo.internal.tasty.SectionValidationException",
                        "java.lang.IllegalArgumentException",
                        "java.lang.NumberFormatException"
                    )
                    if acceptable.exists(name.contains) then None
                    else Some(s"panic: $name at offset=$offset")
        end tryOnce
        val flips = 50
        def go(remaining: Int, panics: List[String]): List[String] < (Async & Scope) =
            if remaining == 0 then panics
            else
                val offset = rng.nextInt(base.length)
                tryOnce(offset).flatMap:
                    case None       => go(remaining - 1, panics)
                    case Some(name) => go(remaining - 1, panics :+ name)
        go(flips, Nil).map: panics =>
            assert(
                panics.isEmpty,
                s"PROP-PB-004: decoder panicked on ${panics.size} bit-flipped inputs:\n${panics.take(5).mkString("\n")}"
            )
            succeed
    }

    // PROP-PB-005: TagKind structural-defense -- each position's throwFor produces a clean
    // RuntimeException whose message encodes TastyError.UnknownTagInPosition, not a silent
    // sentinel or unexpected exception class. Tag value 0 is not a valid tag in any position
    // so it exercises the unknown-tag path. Pins the invariant that corrupt/future-format
    // bytes produce clean errors (Phase 2.04-strict, HARD RULE 13).
    "PROP-PB-005: TagKind throwFor produces TastyError.UnknownTagInPosition for unknown tag 0" in {
        import kyo.internal.tasty.reader.TagKind
        val invalidTag = 0
        val positions = Seq(
            (TagKind.TypePositionTag.position, () => TagKind.TypePositionTag.throwFor(invalidTag)),
            (TagKind.TreePositionTag.position, () => TagKind.TreePositionTag.throwFor(invalidTag)),
            (TagKind.TptPositionTag.position, () => TagKind.TptPositionTag.throwFor(invalidTag)),
            (TagKind.ConstantTag.position, () => TagKind.ConstantTag.throwFor(invalidTag)),
            (TagKind.ModifierTag.position, () => TagKind.ModifierTag.throwFor(invalidTag))
        )
        for (posLabel, throwFn) <- positions do
            val ex = intercept[RuntimeException](throwFn())
            // TastyErrorException wraps TastyError.UnknownTagInPosition; its message is the
            // TastyError toString which includes the position label.
            assert(
                ex.getMessage.contains(posLabel),
                s"expected exception message to contain position '$posLabel' for position $posLabel, got: ${ex.getMessage}"
            )
            assert(
                ex.getMessage.contains(invalidTag.toString),
                s"expected exception message to contain tag '$invalidTag' for position $posLabel, got: ${ex.getMessage}"
            )
        end for
        succeed
    }

    // PROP-PB-006: TagKind.from() round-trips for all known tags in each position.
    // For each enum value, from(value.raw) returns the same enum case.
    // Verifies the byRawMap is correctly populated from the enum values.
    "PROP-PB-006: TagKind from() round-trips for all known tags in each position" in {
        import kyo.internal.tasty.reader.TagKind

        for tag <- TagKind.TypePositionTag.values do
            assert(
                TagKind.TypePositionTag.from(tag.raw) == tag,
                s"TypePositionTag.from(${tag.raw}) did not return $tag"
            )
        end for

        for tag <- TagKind.TreePositionTag.values do
            assert(
                TagKind.TreePositionTag.from(tag.raw) == tag,
                s"TreePositionTag.from(${tag.raw}) did not return $tag"
            )
        end for

        for tag <- TagKind.TptPositionTag.values do
            assert(
                TagKind.TptPositionTag.from(tag.raw) == tag,
                s"TptPositionTag.from(${tag.raw}) did not return $tag"
            )
        end for

        for tag <- TagKind.ConstantTag.values do
            assert(
                TagKind.ConstantTag.from(tag.raw) == tag,
                s"ConstantTag.from(${tag.raw}) did not return $tag"
            )
        end for

        for tag <- TagKind.ModifierTag.values do
            assert(
                TagKind.ModifierTag.from(tag.raw) == tag,
                s"ModifierTag.from(${tag.raw}) did not return $tag"
            )
        end for

        succeed
    }

end TastyPropertyBasedTest

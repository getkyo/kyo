package kyo.test.snapshot

import kyo.Base64
import kyo.Chunk
import kyo.Codec
import kyo.Maybe
import kyo.Protobuf
import kyo.Result
import kyo.Schema
import kyo.Span
import kyo.Yaml
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.RunConfig
import kyo.test.internal.TestContext
import kyo.test.prop.Gen
import kyo.test.runner.TestRunner
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** All-primitive fixture type shared by every leaf in this file and by [[GoldenOnlyLeafSuite]], which must sit at file scope so
  * `classOf[GoldenOnlyLeafSuite]` resolves for `TestRunner.runToFuture`.
  */
case class Event(id: Int, kind: String) derives CanEqual, Schema

given Gen[Event] = Gen.derive[Event]

/** Top-level (not nested) so `classOf[...]` resolves for `TestRunner.runToFuture`; extends the marker-free `SnapshotTestBase[Any]` (not
  * `SnapshotTest[Any]`) so sbt/Native discovery does not ALSO pick this up as a separate real suite, mirroring `RunnerTest.scala`'s
  * `RTFailPassSuite` convention.
  */
private class GoldenOnlyLeafSuite extends SnapshotTestBase[Any]:
    override protected def snapshotDir: String         = s"target/snap-golden-runner-test-${java.lang.System.nanoTime()}"
    override protected def snapshotUpdateMode: Boolean = true
    "golden-only" in assertGoldenSnapshot[Event]("event")
end GoldenOnlyLeafSuite

/** End-to-end tests for `assertGoldenSnapshot`, mirroring `SnapshotSchemaTest`'s pattern: most leaves drive a private `SnapshotTest[Any]`
  * fixture directly and assert synchronously; one leaf drives the dedicated top-level [[GoldenOnlyLeafSuite]] fixture through the real
  * kyo-test runner to prove the no-assertion guard is satisfied by a golden-only leaf.
  *
  * Covers: first-run write-then-fail, update-mode write-and-pass, the no-assertion-guard interaction, format-tolerant structural compare,
  * a genuine field-value mismatch, positional per-sample pairing, schema-evolution decode failures, a raw codec panic propagating
  * unwrapped, the Protobuf raw-bytes round trip, golden/schema coexistence on one suite, the non-positive sampleCount guard, the
  * sample-count length-mismatch short-circuit, normalize-before-encode-and-before-compare, the samples-keyed storage envelope, a pinned
  * cross-platform byte-identical encoding, a Chunk-field type exercising the collection-field generator, and name validation.
  */
class SnapshotGoldenTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    // These tests drive `assertGoldenSnapshot` outside the runner, so they supply a standalone scope to
    // satisfy the assert family's using-clause; the golden throws fire on the synchronous path.
    private given AssertScope = new AssertScope(Chunk.empty)

    private def tmpDir(): String =
        s"target/snap-golden-test-${java.lang.System.nanoTime()}"

    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk.empty))

    case class Rec(id: Int, ts: Long) derives CanEqual, Schema

    case class EventWithPayload(id: Int, kind: String, payload: Chunk[Byte]) derives CanEqual, Schema

    given Gen[EventWithPayload] = Gen.derive[EventWithPayload]

    /** A non-`DecodeException` runtime defect: a raw codec failure must propagate unwrapped, never rewrapped as `SnapshotSchemaEvolution`. */
    class GoldenDecodePanic(message: String) extends RuntimeException(message)

    /** A `Codec` whose writer delegates to `Yaml` (so encode succeeds and a golden file is produced) but whose reader unconditionally
      * throws a [[GoldenDecodePanic]], deterministically producing a `Result.Panic` on decode.
      */
    private def panickingYamlCodec(): Codec =
        new Codec:
            def newWriter(): Codec.Writer = Yaml().newWriter()
            def newReader(input: Span[Byte])(using kyo.Frame): Codec.Reader =
                throw GoldenDecodePanic("golden decode panic")

    /** Minimal `SnapshotTest[Any]` subclass; storage dir, update mode, and codec are all controlled by constructor parameters. */
    private class GoldenFixture(dir: String, update: Boolean, codecOverride: SnapshotCodec = SnapshotCodec.Yaml) extends SnapshotTest[Any]:
        override protected def snapshotDir: String          = dir
        override protected def snapshotUpdateMode: Boolean  = update
        override protected def snapshotCodec: SnapshotCodec = codecOverride

        def golden[A](
            name: String,
            config: GoldenConfig[A] => GoldenConfig[A] = identity[GoldenConfig[A]]
        )(using Gen[A], Schema[A], AssertScope): Unit =
            assertGoldenSnapshot[A](name, config): Unit

        def schemaSnapshot[A](actual: A, name: String)(using Schema[A], kyo.Frame, AssertScope): Unit =
            assertSchemaSnapshot(actual, name): Unit

    end GoldenFixture

    private def storedEvents(path: String): Chunk[Event] =
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                Schema[GoldenSamples[Event]].decodeString[Yaml](stored) match
                    case Result.Success(decoded) => decoded.samples
                    case other                   => fail(s"Expected a decodable GoldenSamples[Event], got $other")
            case Maybe.Absent => fail(s"expected a golden file at $path")

    private def writeGoldenEvents(path: String, samples: Chunk[Event]): Unit =
        val encoded = Schema[GoldenSamples[Event]].encodeString[Yaml](GoldenSamples(samples))
        SnapshotStore.write(path, encoded)

    private def mutateAt(samples: Chunk[Event], idx: Int)(f: Event => Event): Chunk[Event] =
        Chunk.from(samples.zipWithIndex.map { case (e, i) => if i == idx then f(e) else e })

    // ── tests ────────────────────────────────────────────────────────────────

    "first run with no golden writes the proposed file then fails SnapshotNotFound" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            fixture.golden[Event]("event")
        }
        assert(ex.diagram.contains("SnapshotNotFound"), s"Expected a SnapshotNotFound diagram, got: ${ex.diagram}")

        val path     = s"$dir/GoldenFixture/event.golden.yaml"
        val expected = summon[Gen[Event]].samples(0L, 10, 20)
        assert(storedEvents(path) == expected, "Expected the stored spread to equal the proposed 20 samples")
        Future.successful(succeed)
    }

    "update mode with no prior golden writes and passes without reading" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = true)
        fixture.golden[Event]("event")

        val path     = s"$dir/GoldenFixture/event.golden.yaml"
        val expected = summon[Gen[Event]].samples(0L, 10, 20)
        assert(storedEvents(path) == expected, "Expected the stored spread to equal the freshly-generated 20 samples")
        Future.successful(succeed)
    }

    "a golden-only leaf records an assertion and does not trip the no-assertion guard" in {
        TestRunner.runToFuture(classOf[GoldenOnlyLeafSuite], RunConfig.default).map { report =>
            assert(
                report.passed == 1 && report.failed == 0,
                s"expected the golden-only leaf to pass without tripping the no-assertion guard, got $report"
            )
        }
    }

    "format-tolerant compare passes on a whitespace-only re-encode" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[Event]("event")

        val path = s"$dir/GoldenFixture/event.golden.yaml"
        val original = SnapshotStore.read(path) match
            case Maybe.Present(content) => content
            case Maybe.Absent           => fail(s"expected the update-mode write to produce $path")
        val reformatted = original.linesIterator.map(line => s"$line  ").mkString("\n") + "\n"
        assert(reformatted.trim != original.trim, "expected the reformatted content to differ from the original encode")
        SnapshotStore.write(path, reformatted)

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        reader.golden[Event]("event")
        Future.successful(succeed)
    }

    "a real field-value change (not formatting) in one sample FAILS (non-vacuous tolerance)" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[Event]("event")

        val path    = s"$dir/GoldenFixture/event.golden.yaml"
        val stored  = storedEvents(path)
        val mutated = mutateAt(stored, 0)(e => e.copy(kind = e.kind + "-mutated"))
        writeGoldenEvents(path, mutated)

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.golden[Event]("event")
        }
        assert(ex.diagram.contains("changed fields:"), s"Expected 'changed fields:' in the diagram, got: ${ex.diagram}")
        assert(ex.diagram.contains("sample["), s"Expected a 'sample[' token in the diagram, got: ${ex.diagram}")
        Future.successful(succeed)
    }

    "a single-field change confined to sample index 4 reports EXACTLY sample[4] (positional pairing)" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[Event]("event")

        val path    = s"$dir/GoldenFixture/event.golden.yaml"
        val stored  = storedEvents(path)
        val mutated = mutateAt(stored, 4)(e => e.copy(kind = e.kind + "-mutated"))
        writeGoldenEvents(path, mutated)

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.golden[Event]("event")
        }
        assert(ex.diagram.contains("sample[4]"), s"Expected the literal token 'sample[4]', got: ${ex.diagram}")
        val otherIndices = (0 until stored.length).filterNot(_ == 4).map(i => s"sample[$i]")
        assert(!otherIndices.exists(ex.diagram.contains), s"Expected no other sample[N] index, got: ${ex.diagram}")
        Future.successful(succeed)
    }

    "a stored golden that no longer decodes fails SnapshotSchemaEvolution, distinct from a value mismatch" in {
        val dir  = tmpDir()
        val path = s"$dir/GoldenFixture/event.golden.yaml"
        SnapshotStore.write(path, "not-a-decodable-golden-payload")

        installContexts()
        val fixture = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            fixture.golden[Event]("event")
        }
        assert(ex.diagram.contains("SnapshotSchemaEvolution:"), s"Expected the SnapshotSchemaEvolution prefix, got: ${ex.diagram}")
        assert(!ex.diagram.contains("Golden mismatch"), s"Expected no Golden mismatch prefix, got: ${ex.diagram}")
        Future.successful(succeed)
    }

    "a decode Panic is rethrown unwrapped, never rewrapped as SnapshotSchemaEvolution" in {
        val dir        = tmpDir()
        val panicCodec = SnapshotCodec.Text(panickingYamlCodec(), "snap.panicgld")
        installContexts()
        val writer = new GoldenFixture(dir, update = true, panicCodec)
        writer.golden[Event]("event")

        installContexts()
        val reader = new GoldenFixture(dir, update = false, panicCodec)
        val ex = intercept[GoldenDecodePanic] {
            reader.golden[Event]("event")
        }
        assert(ex.getMessage == "golden decode panic", s"Expected message 'golden decode panic', got: ${ex.getMessage}")
        Future.successful(succeed)
    }

    "Binary (Protobuf) golden round-trips byte-identically" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true, SnapshotCodec.Protobuf)
        writer.golden[Event]("event")

        val path     = s"$dir/GoldenFixture/event.golden.pb"
        val expected = Schema[GoldenSamples[Event]].encode[Protobuf](GoldenSamples(summon[Gen[Event]].samples(0L, 10, 20)))
        SnapshotStore.readBytes(path) match
            case Maybe.Present(stored) =>
                assert(stored.size == expected.size, s"Expected length ${expected.size}, got ${stored.size}")
                assert(stored.is(expected), "Expected the stored bytes to be byte-identical to a fresh schema.encode")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match

        installContexts()
        val reader = new GoldenFixture(dir, update = false, SnapshotCodec.Protobuf)
        reader.golden[Event]("event")
        Future.successful(succeed)
    }

    "Binary golden stores raw wire bytes, no base64 and no trailing-newline munge" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = true, SnapshotCodec.Protobuf)
        fixture.golden[Event]("event")

        val path     = s"$dir/GoldenFixture/event.golden.pb"
        val expected = Schema[GoldenSamples[Event]].encode[Protobuf](GoldenSamples(summon[Gen[Event]].samples(0L, 10, 20)))
        SnapshotStore.readBytes(path) match
            case Maybe.Present(stored) =>
                assert(stored.size == expected.size, s"Expected stored length ${expected.size}, got ${stored.size}")
                val base64Length = Base64.encode(expected).size
                assert(
                    stored.size != base64Length,
                    s"Stored byte length (${stored.size}) must not equal the base64-encoded length ($base64Length)"
                )
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
        Future.successful(succeed)
    }

    "golden and schema snapshots coexist on one suite writing distinct files, no cross-write" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = true)
        fixture.golden[Event]("event")
        fixture.schemaSnapshot(Event(1, "x"), "event")

        val goldenPath = s"$dir/GoldenFixture/event.golden.yaml"
        val schemaPath = s"$dir/GoldenFixture/event.snap.yaml"
        SnapshotStore.read(goldenPath) match
            case Maybe.Present(_) => ()
            case Maybe.Absent     => fail(s"expected $goldenPath to exist")
        SnapshotStore.read(schemaPath) match
            case Maybe.Present(stored) =>
                Schema[Event].decodeString[Yaml](stored) match
                    case Result.Success(decoded) =>
                        assert(decoded == Event(1, "x"), s"Expected the schema snapshot unaffected by the golden write, got $decoded")
                    case other => fail(s"Expected a decodable Event, got $other")
            case Maybe.Absent => fail(s"expected $schemaPath to exist")
        end match
        Future.successful(succeed)
    }

    "a non-positive sampleCount raises IllegalArgumentException at the boundary" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = false)
        val ex = intercept[IllegalArgumentException] {
            fixture.golden[Event]("event", _.sampleCount(0))
        }
        assert(ex.getMessage.contains("0"), s"Expected the message to name the invalid count, got: ${ex.getMessage}")
        Future.successful(succeed)
    }

    "no golden file is written after the sampleCount guard throws" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = false)
        intercept[IllegalArgumentException] {
            fixture.golden[Event]("event", _.sampleCount(0))
        }
        val path = s"$dir/GoldenFixture/event.golden.yaml"
        val result = SnapshotStore.read(path) match
            case Maybe.Absent     => succeed
            case Maybe.Present(_) => fail(s"expected no golden file at $path after the sampleCount guard throws")
        Future.successful(result)
    }

    "a sample-count length mismatch fails naming the count delta before any per-index compare" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[Event]("event", _.sampleCount(20))

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.golden[Event]("event", _.sampleCount(25))
        }
        assert(ex.diagram.contains("20") && ex.diagram.contains("25"), s"Expected the message to name both counts, got: ${ex.diagram}")
        Future.successful(succeed)
    }

    "the length-mismatch message carries no per-sample changed-path text (short-circuit)" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[Event]("event", _.sampleCount(20))

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.golden[Event]("event", _.sampleCount(25))
        }
        assert(!ex.diagram.contains("sample["), s"Expected no per-sample changed-path token, got: ${ex.diagram}")
        Future.successful(succeed)
    }

    "normalization scrubs a volatile field on BOTH the stored and the compared side" in {
        val dir = tmpDir()
        installContexts()
        locally {
            val writer     = new GoldenFixture(dir, update = true)
            given Gen[Rec] = Gen.const(Rec(1, java.lang.System.nanoTime()))
            writer.golden[Rec]("rec", _.sampleCount(1).normalize(_.set(_.ts)(0L)))
        }

        installContexts()
        locally {
            val reader     = new GoldenFixture(dir, update = false)
            given Gen[Rec] = Gen.const(Rec(1, java.lang.System.nanoTime() + 1L))
            reader.golden[Rec]("rec", _.sampleCount(1).normalize(_.set(_.ts)(0L)))
        }
        Future.successful(succeed)
    }

    "the stored document is one file with a top-level samples-keyed mapping wrapping the sequence" in {
        val dir = tmpDir()
        installContexts()
        val fixture = new GoldenFixture(dir, update = true)
        fixture.golden[Event]("event")

        val path = s"$dir/GoldenFixture/event.golden.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                assert(stored.trim.startsWith("samples:"), s"Expected a top-level 'samples:' mapping key, got: $stored")
                val decoded = storedEvents(path)
                assert(decoded.length == 20, s"Expected all 20 samples in the single stored document, got ${decoded.length}")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
        Future.successful(succeed)
    }

    "golden bytes are cross-platform byte-identical (pinned determinism)" in {
        val samples = summon[Gen[Event]].samples(0L, 10, 3)
        val encoded = Schema[GoldenSamples[Event]].encodeString[Yaml](GoldenSamples(samples))
        assert(encoded == SnapshotGoldenTest.pinnedEventGoldenYaml, s"Expected the pinned golden encoding, got:\n$encoded")
        Future.successful(succeed)
    }

    "a golden over a Chunk-field type compiles and round-trips end-to-end (chunkGen consumer)" in {
        val dir = tmpDir()
        installContexts()
        val writer = new GoldenFixture(dir, update = true)
        writer.golden[EventWithPayload]("event")

        val path = s"$dir/GoldenFixture/event.golden.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(_) => ()
            case Maybe.Absent     => fail(s"expected the update-mode write to produce $path")

        installContexts()
        val reader = new GoldenFixture(dir, update = false)
        reader.golden[EventWithPayload]("event")
        Future.successful(succeed)
    }

    "assertGoldenSnapshot rejects each of the five invalid names (path-sep, empty, dot, dot-dot, space)" in {
        val invalidNames = List("a/b", "", ".", "..", "a b")
        invalidNames.foreach { invalidName =>
            val dir = tmpDir()
            installContexts()
            val fixture = new GoldenFixture(dir, update = true)
            intercept[IllegalArgumentException] {
                fixture.golden[Event](invalidName)
            }
            if !invalidName.contains('/') then
                val path = s"$dir/GoldenFixture/$invalidName.golden.yaml"
                SnapshotStore.read(path) match
                    case Maybe.Absent     => ()
                    case Maybe.Present(_) => fail(s"expected no golden file written for invalid name '$invalidName'")
            end if
        }
        Future.successful(succeed)
    }

end SnapshotGoldenTest

object SnapshotGoldenTest:
    /** Pinned encoding of `GoldenSamples(Gen[Event].samples(seed = 0L, size = 10, count = 3))` through the Yaml codec, obtained by
      * running the encode once; this same literal must match on JVM, JS, Native, and Wasm.
      */
    private val pinnedEventGoldenYaml: String =
        "samples:\n" +
            "  -\n" +
            "    id: 1\n" +
            "    kind: qv7\n" +
            "  -\n" +
            "    id: 9\n" +
            "    kind: jG\n" +
            "  -\n" +
            "    id: -6\n" +
            "    kind: clv2C5uc\n"
end SnapshotGoldenTest

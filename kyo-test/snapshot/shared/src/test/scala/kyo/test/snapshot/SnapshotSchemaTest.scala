package kyo.test.snapshot

import kyo.Base64
import kyo.Chunk
import kyo.Codec
import kyo.Maybe
import kyo.Modify
import kyo.Protobuf
import kyo.Render
import kyo.Result
import kyo.Schema
import kyo.Span
import kyo.Yaml
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.internal.TestContext
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** End-to-end tests for `assertSchemaSnapshot`, driven outside the kyo-test runner via a private `SnapshotTest[Any]` fixture, mirroring
  * `SnapshotUpdateModeTest`'s pattern. All assertions are synchronous (file I/O + try/catch); uses ScalaTest directly.
  *
  * Covers: field-named Text encoding, normalize-before-encode-and-compare, the Text/Binary mismatch report shapes, the recursive nested
  * changed-path report, format-tolerant structural compare, schema-evolution decode failures, name validation, update mode, first-run
  * behavior, the Protobuf raw-bytes round trip, a raw codec defect propagating unwrapped from both the Binary and Text decode arms,
  * and the Render-based `assertSnapshot` path, whose name validation and first-run behavior match `assertSchemaSnapshot` exactly.
  */
class SnapshotSchemaTest extends AnyFunSuite with NonImplicitAssertions:

    // ── helpers ──────────────────────────────────────────────────────────────

    // These tests drive `assertSchemaSnapshot` outside the runner, so they supply a standalone scope to
    // satisfy the assert family's using-clause; the snapshot throws fire on the synchronous path.
    private given AssertScope = new AssertScope(Chunk.empty)

    private def tmpDir(): String =
        s"target/snap-schema-test-${java.lang.System.nanoTime()}"

    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk.empty))

    case class Point(x: Int, y: Int) derives CanEqual, Schema
    case class Line(a: Point, b: Point) derives CanEqual, Schema
    case class Event(id: Int, ts: Long) derives CanEqual, Schema

    /** A fixture whose `equals`/`hashCode` ignore field `b`, so value-equality and schema-structural equality diverge:
      * the schema still encodes `b`, so a `Changeset` diff detects the difference even though `.equals` does not.
      */
    case class Ver(a: Int, b: Int) derives Schema:
        override def equals(other: Any): Boolean = other match
            case that: Ver => this.a == that.a
            case _         => false
        override def hashCode(): Int = a.hashCode()
    end Ver

    /** A non-`DecodeException` runtime defect: a raw codec failure must propagate unwrapped, never rewrapped as
      * `SnapshotSchemaEvolution`.
      */
    class RawCodecDefect(message: String) extends RuntimeException(message)

    /** A `Codec` whose writer delegates to `Protobuf` (so encode succeeds and a snapshot file is produced) but whose
      * reader unconditionally throws a [[RawCodecDefect]], deterministically producing a `Result.Panic` on decode
      * (`Result.catching[DecodeException]` maps a non-matching throwable to `Panic`).
      */
    private def throwingCodec(): Codec =
        new Codec:
            def newWriter(): Codec.Writer = Protobuf().newWriter()
            def newReader(input: Span[Byte])(using kyo.Frame): Codec.Reader =
                throw RawCodecDefect("raw codec defect")

    /** A `Codec` whose writer delegates to `Yaml` (so encode succeeds and a snapshot file is produced) but whose reader
      * unconditionally throws a [[RawCodecDefect]], deterministically producing a `Result.Panic` on decode, mirroring
      * `throwingCodec` for the Text decode arm rather than the Binary one.
      */
    private def throwingTextCodec(): Codec =
        new Codec:
            def newWriter(): Codec.Writer = Yaml().newWriter()
            def newReader(input: Span[Byte])(using kyo.Frame): Codec.Reader =
                throw RawCodecDefect("raw text codec defect")

    /** Minimal `SnapshotTest[Any]` subclass; storage dir, update mode, and codec are all controlled by constructor parameters. */
    private class SchemaFixture(dir: String, update: Boolean, codecOverride: SnapshotCodec = SnapshotCodec.Yaml) extends SnapshotTest[Any]:
        override protected def snapshotDir: String          = dir
        override protected def snapshotUpdateMode: Boolean  = update
        override protected def snapshotCodec: SnapshotCodec = codecOverride

        def assertSchema[A](actual: A, name: String)(using Schema[A], kyo.Frame, AssertScope): Unit =
            assertSchemaSnapshot(actual, name): Unit

        def assertSchema[A](actual: A, name: String, normalize: Modify[A] => Modify[A])(using Schema[A], kyo.Frame, AssertScope): Unit =
            assertSchemaSnapshot(actual, name, _.normalize(normalize)): Unit

        def assertRendered[A](actual: A, name: String)(using Render[A], kyo.Frame, AssertScope): Unit =
            assertSnapshot(actual, name): Unit

    end SchemaFixture

    // ── tests ────────────────────────────────────────────────────────────────

    test("default Yaml output is field-named, not a positional flat toString") {
        val dir = tmpDir()
        installContexts()
        val fixture = new SchemaFixture(dir, update = true)
        fixture.assertSchema(Point(1, 2), "point")

        val path = s"$dir/SchemaFixture/point.snap.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                assert(stored.contains("x:"), s"Expected a field-named 'x:' key, got: $stored")
                assert(stored.contains("y:"), s"Expected a field-named 'y:' key, got: $stored")
                assert(stored.stripTrailing() != "Point(1,2)", s"Expected a Yaml mapping, not the positional Render form, got: $stored")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
    }

    test("normalize scrubs a non-deterministic timestamp before encode and before compare so repeated runs pass") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true)
        val ts1    = java.lang.System.nanoTime()
        writer.assertSchema(Event(1, ts1), "event", _.set(_.ts)(0L))

        val path = s"$dir/SchemaFixture/event.snap.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                Schema[Event].decodeString[Yaml](stored) match
                    case Result.Success(decoded) => assert(decoded.ts == 0L, s"Expected the stored ts scrubbed to 0, got: $decoded")
                    case other                   => fail(s"Expected a decodable Event, got $other")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match

        installContexts()
        val reader = new SchemaFixture(dir, update = false)
        val ts2    = ts1 + 1L
        reader.assertSchema(Event(1, ts2), "event", _.set(_.ts)(0L))
        succeed
    }

    test("Text (Yaml) mismatch report carries a unified textual diff and the changed field path") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true)
        writer.assertSchema(Point(1, 2), "point")

        installContexts()
        val reader = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.assertSchema(Point(1, 3), "point")
        }
        assert(ex.diagram.contains("changed fields: y"), s"Expected the changed field 'y', got: ${ex.diagram}")
        assert(ex.diagram.contains("actual vs expected"), s"Expected a unified textual diff block, got: ${ex.diagram}")
    }

    test("Binary (Protobuf) mismatch report carries the changed field path but no textual diff") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true, SnapshotCodec.Protobuf)
        writer.assertSchema(Point(1, 2), "point")

        installContexts()
        val reader = new SchemaFixture(dir, update = false, SnapshotCodec.Protobuf)
        val ex = intercept[AssertionFailed] {
            reader.assertSchema(Point(1, 3), "point")
        }
        assert(ex.diagram.contains("changed fields: y"), s"Expected the changed field 'y', got: ${ex.diagram}")
        assert(!ex.diagram.contains("actual vs expected"), s"Expected no textual diff block for a Binary codec, got: ${ex.diagram}")
    }

    test("mismatch on a nested field reports the full dotted nested path") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true)
        writer.assertSchema(Line(Point(1, 2), Point(3, 4)), "line")

        installContexts()
        val reader = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.assertSchema(Line(Point(1, 2), Point(3, 9)), "line")
        }
        assert(ex.diagram.contains("changed fields: b.y"), s"Expected the dotted nested path 'b.y', got: ${ex.diagram}")
    }

    test("format-tolerant compare: a hand-reformatted stored snapshot that decodes equal still passes") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true)
        writer.assertSchema(Point(1, 2), "point")

        val path = s"$dir/SchemaFixture/point.snap.yaml"
        val original = SnapshotStore.read(path) match
            case Maybe.Present(content) => content
            case Maybe.Absent           => fail(s"expected the update-mode write to produce $path")
        val reformatted = original.linesIterator.toList.reverse.map(line => s"$line   ").mkString("\n") + "\n"
        assert(reformatted.trim != original.trim, "expected the hand-reformatted content to differ from the original encode")
        SnapshotStore.write(path, reformatted)

        installContexts()
        val reader = new SchemaFixture(dir, update = false)
        reader.assertSchema(Point(1, 2), "point")
        succeed
    }

    test("a stored snapshot that fails to decode routes to SnapshotSchemaEvolution, distinct from a value mismatch") {
        val dir  = tmpDir()
        val path = s"$dir/SchemaFixture/point.snap.yaml"
        SnapshotStore.write(path, "not-a-decodable-point-payload")

        installContexts()
        val fixture = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            fixture.assertSchema(Point(1, 2), "point")
        }
        assert(ex.diagram.contains("SnapshotSchemaEvolution:"), s"Expected the SnapshotSchemaEvolution prefix, got: ${ex.diagram}")
        assert(!ex.diagram.contains("Snapshot mismatch"), s"Expected no Snapshot mismatch prefix, got: ${ex.diagram}")
    }

    test("name validation rejects a path separator, empty, dot, dot-dot, and an embedded space") {
        val invalidNames = List("a/b", "", ".", "..", "a b")
        invalidNames.foreach { invalidName =>
            val dir = tmpDir()
            installContexts()
            val fixture = new SchemaFixture(dir, update = true)
            intercept[IllegalArgumentException] {
                fixture.assertSchema(Point(1, 2), invalidName)
            }
        }
    }

    test("update mode writes the encoded value and passes without reading a prior file") {
        val dir = tmpDir()
        installContexts()
        val fixture = new SchemaFixture(dir, update = true)
        fixture.assertSchema(Point(5, 6), "fresh")

        val path = s"$dir/SchemaFixture/fresh.snap.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                Schema[Point].decodeString[Yaml](stored) match
                    case Result.Success(decoded) => assert(decoded == Point(5, 6), s"Expected Point(5, 6), got $decoded")
                    case other                   => fail(s"Expected a decodable Point(5, 6), got $other")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
    }

    test("first run with no stored file writes the proposed value then fails with SnapshotNotFound") {
        val dir = tmpDir()
        installContexts()
        val fixture = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            fixture.assertSchema(Point(7, 8), "first-run")
        }
        assert(!ex.diagram.contains("SnapshotSchemaEvolution:"), s"Expected no SnapshotSchemaEvolution prefix, got: ${ex.diagram}")
        assert(!ex.diagram.contains("Snapshot mismatch"), s"Expected no Snapshot mismatch prefix, got: ${ex.diagram}")
        assert(ex.diagram.contains("SnapshotNotFound"), s"Expected a SnapshotNotFound diagram, got: ${ex.diagram}")

        val path = s"$dir/SchemaFixture/first-run.snap.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                Schema[Point].decodeString[Yaml](stored) match
                    case Result.Success(decoded) => assert(decoded == Point(7, 8), s"Expected Point(7, 8), got $decoded")
                    case other                   => fail(s"Expected a decodable Point(7, 8), got $other")
            case Maybe.Absent =>
                fail(s"expected the first-run write to produce $path")
        end match
    }

    test("Protobuf round-trip stores raw wire bytes byte-identical to schema.encode on every platform") {
        val dir = tmpDir()
        installContexts()
        val fixture = new SchemaFixture(dir, update = true, SnapshotCodec.Protobuf)
        fixture.assertSchema(Point(1, 2), "protobuf-point")

        val path     = s"$dir/SchemaFixture/protobuf-point.snap.pb"
        val expected = Schema[Point].encode[Protobuf](Point(1, 2))
        SnapshotStore.readBytes(path) match
            case Maybe.Present(stored) =>
                assert(stored.size == expected.size, s"Expected length ${expected.size}, got ${stored.size}")
                assert(stored.is(expected), "Expected the stored bytes to be byte-identical to a fresh schema.encode")
                val encodedLength = Base64.encode(expected).size
                assert(
                    stored.size != encodedLength,
                    s"Stored byte length (${stored.size}) must not equal the base64-encoded length ($encodedLength)"
                )
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
    }

    test("assertSnapshot still fails first-run with SnapshotNotFound for a type with only a Render instance") {
        val dir = tmpDir()
        installContexts()
        val fixture = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            fixture.assertRendered(42, "render-only")
        }
        assert(ex.diagram.contains("SnapshotNotFound"), s"Expected a SnapshotNotFound diagram, got: ${ex.diagram}")
    }

    test("assertSnapshot rejects a path separator, empty, dot, dot-dot, and an embedded space, same as assertSchemaSnapshot") {
        val invalidNames = List("a/b", "", ".", "..", "a b")
        invalidNames.foreach { invalidName =>
            val dir = tmpDir()
            installContexts()
            val fixture = new SchemaFixture(dir, update = true)
            intercept[IllegalArgumentException] {
                fixture.assertRendered(42, invalidName)
            }
        }
    }

    test("a Binary (Protobuf) stored snapshot that fails to decode routes to SnapshotSchemaEvolution, distinct from a value mismatch") {
        val dir       = tmpDir()
        val path      = s"$dir/SchemaFixture/point.snap.pb"
        val encoded   = Schema[Point].encode[Protobuf](Point(1, 2))
        val truncated = encoded.dropRight(1)
        SnapshotStore.writeBytes(path, truncated)

        installContexts()
        val fixture = new SchemaFixture(dir, update = false, SnapshotCodec.Protobuf)
        val ex = intercept[AssertionFailed] {
            fixture.assertSchema(Point(1, 2), "point")
        }
        assert(ex.diagram.contains("SnapshotSchemaEvolution:"), s"Expected the SnapshotSchemaEvolution prefix, got: ${ex.diagram}")
        assert(!ex.diagram.contains("Snapshot mismatch"), s"Expected no Snapshot mismatch prefix, got: ${ex.diagram}")
    }

    test("the pass/fail gate follows schema-structural Changeset.operations, not value .equals") {
        val dir = tmpDir()
        installContexts()
        val writer = new SchemaFixture(dir, update = true)
        writer.assertSchema(Ver(1, 2), "ver")

        assert(Ver(1, 2).equals(Ver(1, 9)), "expected the fixture's custom equals to ignore field b")

        installContexts()
        val reader = new SchemaFixture(dir, update = false)
        val ex = intercept[AssertionFailed] {
            reader.assertSchema(Ver(1, 9), "ver")
        }
        assert(ex.diagram.contains("changed fields: b"), s"Expected the changed field 'b', got: ${ex.diagram}")
    }

    test(
        "a raw (non-DecodeException) Binary codec defect yields Result.Panic and propagates unwrapped, never rewrapped as SnapshotSchemaEvolution"
    ) {
        val dir        = tmpDir()
        val panicCodec = SnapshotCodec.Binary(throwingCodec(), "snap.panicbin")
        installContexts()
        val writer = new SchemaFixture(dir, update = true, panicCodec)
        writer.assertSchema(Point(1, 2), "panic-point")

        installContexts()
        val reader = new SchemaFixture(dir, update = false, panicCodec)
        val ex = intercept[RawCodecDefect] {
            reader.assertSchema(Point(1, 2), "panic-point")
        }
        assert(ex.getMessage == "raw codec defect", s"Expected message 'raw codec defect', got: ${ex.getMessage}")
    }

    test(
        "a raw (non-DecodeException) Text codec defect yields Result.Panic and propagates unwrapped, never rewrapped as SnapshotSchemaEvolution"
    ) {
        val dir        = tmpDir()
        val panicCodec = SnapshotCodec.Text(throwingTextCodec(), "snap.panictxt")
        installContexts()
        val writer = new SchemaFixture(dir, update = true, panicCodec)
        writer.assertSchema(Point(1, 2), "panic-point")

        installContexts()
        val reader = new SchemaFixture(dir, update = false, panicCodec)
        val ex = intercept[RawCodecDefect] {
            reader.assertSchema(Point(1, 2), "panic-point")
        }
        assert(ex.getMessage == "raw text codec defect", s"Expected message 'raw text codec defect', got: ${ex.getMessage}")
    }

end SnapshotSchemaTest

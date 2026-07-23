package kyo.test.snapshot

import kyo.Codec
import kyo.Frame
import kyo.Span
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for `SnapshotCodec`: the locked preset kind/extension data and the open Text/Binary constructors.
  *
  * All assertions are synchronous plain value/pattern-match checks (no file I/O, no Sync/Async boundary); uses ScalaTest directly,
  * mirroring `SnapshotStoreBytesTest`.
  *
  * Covers:
  *   1. The seven companion presets carry the locked kind (Text/Binary) and extension, all pairwise-distinct and none equal to the plain
  *      `Render`-based `assertSnapshot`'s `"snap"` extension.
  *   2. The open `Text`/`Binary` constructors expose the exact codec and extension passed to them.
  */
class SnapshotCodecTest extends AnyFunSuite with NonImplicitAssertions:

    /** A minimal Codec double: only identity (never encode/decode) is exercised by this test. */
    private def fakeCodec(): Codec =
        new Codec:
            def newWriter(): Codec.Writer = throw NotImplementedError("fakeCodec.newWriter is not exercised by this test")
            def newReader(input: Span[Byte])(using Frame): Codec.Reader =
                throw NotImplementedError("fakeCodec.newReader is not exercised by this test")

    test("the seven presets carry the locked kind and extension") {
        SnapshotCodec.Yaml match
            case SnapshotCodec.Text(_, ext) => assert(ext == "snap.yaml", s"Expected ext 'snap.yaml', got '$ext'")
            case other                      => fail(s"Expected SnapshotCodec.Text, got $other")

        SnapshotCodec.Json match
            case SnapshotCodec.Text(_, ext) => assert(ext == "snap.json", s"Expected ext 'snap.json', got '$ext'")
            case other                      => fail(s"Expected SnapshotCodec.Text, got $other")

        SnapshotCodec.Ion match
            case SnapshotCodec.Text(_, ext) => assert(ext == "snap.ion", s"Expected ext 'snap.ion', got '$ext'")
            case other                      => fail(s"Expected SnapshotCodec.Text, got $other")

        SnapshotCodec.Protobuf match
            case SnapshotCodec.Binary(_, ext) => assert(ext == "snap.pb", s"Expected ext 'snap.pb', got '$ext'")
            case other                        => fail(s"Expected SnapshotCodec.Binary, got $other")

        SnapshotCodec.Bson match
            case SnapshotCodec.Binary(_, ext) => assert(ext == "snap.bson", s"Expected ext 'snap.bson', got '$ext'")
            case other                        => fail(s"Expected SnapshotCodec.Binary, got $other")

        SnapshotCodec.MsgPack match
            case SnapshotCodec.Binary(_, ext) => assert(ext == "snap.msgpack", s"Expected ext 'snap.msgpack', got '$ext'")
            case other                        => fail(s"Expected SnapshotCodec.Binary, got $other")

        SnapshotCodec.IonBinary match
            case SnapshotCodec.Binary(_, ext) => assert(ext == "snap.ionb", s"Expected ext 'snap.ionb', got '$ext'")
            case other                        => fail(s"Expected SnapshotCodec.Binary, got $other")

        val allExts = List(
            SnapshotCodec.Yaml.ext,
            SnapshotCodec.Json.ext,
            SnapshotCodec.Ion.ext,
            SnapshotCodec.Protobuf.ext,
            SnapshotCodec.Bson.ext,
            SnapshotCodec.MsgPack.ext,
            SnapshotCodec.IonBinary.ext
        )
        assert(allExts.distinct.size == allExts.size, s"Expected all seven extensions pairwise distinct, got $allExts")
        assert(
            !allExts.contains("snap"),
            s"Expected no preset extension to equal the plain Render assertSnapshot extension 'snap', got $allExts"
        )
        val goldenExts = allExts.map(ext => s"golden.${ext.stripPrefix("snap.")}")
        val combined   = allExts ++ goldenExts
        assert(
            combined.distinct.size == combined.size,
            s"Expected all fourteen extensions (seven snap.* plus seven golden.*) pairwise distinct, got $combined"
        )
        assert(
            !combined.contains("snap"),
            s"Expected no golden extension to equal the plain Render assertSnapshot extension 'snap', got $combined"
        )
    }

    test("the open Text and Binary constructors expose the given codec and extension") {
        val textCodec   = fakeCodec()
        val binaryCodec = fakeCodec()

        val text: SnapshotCodec   = SnapshotCodec.Text(textCodec, "snap.custom")
        val binary: SnapshotCodec = SnapshotCodec.Binary(binaryCodec, "bin.custom")

        assert(text.codec eq textCodec, "Expected Text.codec to be the exact codec instance passed in")
        assert(text.ext == "snap.custom", s"Expected Text.ext 'snap.custom', got '${text.ext}'")
        assert(binary.codec eq binaryCodec, "Expected Binary.codec to be the exact codec instance passed in")
        assert(binary.ext == "bin.custom", s"Expected Binary.ext 'bin.custom', got '${binary.ext}'")

        text match
            case SnapshotCodec.Text(_, _) => succeed
            case other                    => fail(s"Expected SnapshotCodec.Text, got $other")
        binary match
            case SnapshotCodec.Binary(_, _) => succeed
            case other                      => fail(s"Expected SnapshotCodec.Binary, got $other")
    }

end SnapshotCodecTest

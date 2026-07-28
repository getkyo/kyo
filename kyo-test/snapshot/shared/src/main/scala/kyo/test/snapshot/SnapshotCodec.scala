package kyo.test.snapshot

import kyo.Codec

/** Serialization format for a schema-driven snapshot, wrapping a kyo-schema [[kyo.Codec]] with the text-versus-binary distinction
  * the codec itself does not carry.
  *
  * A snapshot codec is either [[Text]] or [[Binary]]. The KIND (which of the two cases) decides two things a raw `Codec` cannot
  * express: whether the stored file holds a UTF-8 string or raw wire bytes, and whether a mismatch report can carry a unified
  * textual diff (only `Text` can). The seven companion presets cover every codec kyo-schema ships (`Yaml`, `Json`, `Ion`,
  * `Protobuf`, `Bson`, `MsgPack`, `IonBinary`); `Text` and `Binary` remain the open extension point for any custom `Codec`.
  *
  * @param codec
  *   the underlying kyo-schema codec that encodes and decodes the snapshotted value
  * @param ext
  *   the file extension for stored snapshots, kept pairwise-distinct across presets so a text and a binary snapshot of the same
  *   name never collide, and distinct from the plain `snap` extension of the `Render`-based `assertSnapshot` so a suite mixing
  *   both assertions on the same name never cross-writes one file
  * @see
  *   [[kyo.test.snapshot.SnapshotTestBase.snapshotCodec]] the per-suite hook selecting a codec
  * @see
  *   [[kyo.test.snapshot.SnapshotTestBase.assertSchemaSnapshot]] the assertion that reads it
  */
enum SnapshotCodec(val codec: Codec, val ext: String):

    /** A text codec: the snapshot stores a UTF-8 string and a mismatch reports a textual diff. */
    case Text(override val codec: Codec, override val ext: String) extends SnapshotCodec(codec, ext)

    /** A binary codec: the snapshot stores raw wire bytes and a mismatch reports no textual diff. */
    case Binary(override val codec: Codec, override val ext: String) extends SnapshotCodec(codec, ext)

end SnapshotCodec

object SnapshotCodec:

    /** YAML, the default readable field-named text format. */
    val Yaml: SnapshotCodec = Text(kyo.Yaml(), "snap.yaml")

    /** JSON text format. */
    val Json: SnapshotCodec = Text(kyo.Json(), "snap.json")

    /** Amazon Ion text format. */
    val Ion: SnapshotCodec = Text(kyo.Ion(), "snap.ion")

    /** Protocol Buffers binary format. */
    val Protobuf: SnapshotCodec = Binary(kyo.Protobuf(), "snap.pb")

    /** BSON binary format. */
    val Bson: SnapshotCodec = Binary(kyo.Bson(), "snap.bson")

    /** MessagePack binary format. */
    val MsgPack: SnapshotCodec = Binary(kyo.MsgPack(), "snap.msgpack")

    /** Amazon Ion Binary format. */
    val IonBinary: SnapshotCodec = Binary(kyo.IonBinary(), "snap.ionb")

end SnapshotCodec

package kyo

/** Strategy for encoding [[EventMetadata]] in binary segment records.
  *
  * A metadata codec is selected through [[FileJournal.Config.metadataCodec]] when a file backend
  * is constructed. It governs how metadata bytes are written to binary (`.seg`) segments and how
  * JSONL segments normalize metadata into the binary shadow form stored in [[DecodedRecord]].
  *
  * Two built-in codecs are available through the companion:
  *
  *   - [[kyo.EventMetadataCodec.ionBinary]] is the default wire format (metadata version `0x02`).
  *     Its decoder also accepts legacy `0x01` MsgPack bodies so existing journals still open.
  *   - [[kyo.EventMetadataCodec.msgPack]] preserves the original `0x01` MsgPack wire format.
  *
  * @see [[kyo.FileJournal.Config]] for selecting a metadata codec at open time
  * @see [[kyo.EventMetadata]] for the metadata map type
  */
trait EventMetadataCodec:
    private[kyo] def encode(md: EventMetadata): Array[Byte]
    private[kyo] def decode(bytes: Array[Byte])(using Frame): Result[JournalInvalidIdentifierError, EventMetadata]
end EventMetadataCodec

object EventMetadataCodec:
    /** Metadata version byte for MsgPack-encoded bodies. */
    val MetadataVersionMsgPack: Byte = 0x01

    /** Metadata version byte for Ion Binary-encoded bodies. */
    val MetadataVersionIonBinary: Byte = 0x02

    /** Default metadata codec: Ion Binary on write, Ion Binary or legacy MsgPack on read. */
    val ionBinary: EventMetadataCodec = IonBinaryMetadataCodec

    /** MsgPack-only metadata codec (version `0x01` on both read and write). */
    val msgPack: EventMetadataCodec = MsgPackMetadataCodec

    /** Production default: [[ionBinary]]. */
    val default: EventMetadataCodec = ionBinary

    private[kyo] def encodeWithCodec(md: EventMetadata, version: Byte, codec: Codec): Array[Byte] =
        val writer = codec.newWriter()
        writer.mapStart(md.values.size)
        md.values.foreach((k, v) =>
            writer.field(k.value, 0); MetadataValue.write(writer, v)
        )
        writer.mapEnd()
        val body = writer.result().toArray
        val out  = new Array[Byte](1 + body.length)
        out(0) = version
        java.lang.System.arraycopy(body, 0, out, 1, body.length)
        out
    end encodeWithCodec

    private[kyo] def decodeBody(
        bytes: Array[Byte],
        codec: Codec
    )(using Frame): Result[JournalInvalidIdentifierError, EventMetadata] =
        val payload = Span.from(java.util.Arrays.copyOfRange(bytes, 1, bytes.length))
        val reader  = codec.newReader(payload)
        try
            discard(reader.objectStart())
            val rawPairs = Chunk.newBuilder[(String, MetadataValue)]
            while reader.hasNextField() do
                val keyStr = reader.field()
                val v      = MetadataValue.read(reader)
                rawPairs += (keyStr -> v)
            end while
            reader.objectEnd()
            val pairs = rawPairs.result().map((k, v) => MetadataKey(k).map(mk => (mk, v)))
            Result.collect(pairs).map(ps => EventMetadata(ps.toMap))
        catch
            case e: DecodeException =>
                Result.fail(JournalInvalidIdentifierError("metadata value tag", e.getMessage))
        end try
    end decodeBody

    private object IonBinaryMetadataCodec extends EventMetadataCodec:
        private[kyo] def encode(md: EventMetadata): Array[Byte] =
            encodeWithCodec(md, MetadataVersionIonBinary, IonBinary())

        private[kyo] def decode(bytes: Array[Byte])(using Frame): Result[JournalInvalidIdentifierError, EventMetadata] =
            if bytes.isEmpty then Result.succeed(EventMetadata.empty)
            else
                bytes(0) match
                    case MetadataVersionIonBinary => decodeBody(bytes, IonBinary())
                    case MetadataVersionMsgPack   => decodeBody(bytes, MsgPack())
                    case other =>
                        Result.fail(JournalInvalidIdentifierError(
                            "metadata encoding version",
                            s"unknown byte 0x${(other & 0xff).toHexString}"
                        ))
        end decode
    end IonBinaryMetadataCodec

    private object MsgPackMetadataCodec extends EventMetadataCodec:
        private[kyo] def encode(md: EventMetadata): Array[Byte] =
            encodeWithCodec(md, MetadataVersionMsgPack, MsgPack())

        private[kyo] def decode(bytes: Array[Byte])(using Frame): Result[JournalInvalidIdentifierError, EventMetadata] =
            if bytes.isEmpty then Result.succeed(EventMetadata.empty)
            else if bytes(0) != MetadataVersionMsgPack then
                Result.fail(JournalInvalidIdentifierError(
                    "metadata encoding version",
                    s"unknown byte 0x${(bytes(0) & 0xff).toHexString}"
                ))
            else decodeBody(bytes, MsgPack())
        end decode
    end MsgPackMetadataCodec
end EventMetadataCodec

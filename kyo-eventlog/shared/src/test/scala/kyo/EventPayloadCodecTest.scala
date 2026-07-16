package kyo

final case class CodecTestEvent(name: String, value: Int) derives Schema, CanEqual

class EventPayloadCodecTest extends kyo.test.Test[Any]:

    "schema codec encodes and decodes a value round-trip with Ion Binary default" in {
        val event = CodecTestEvent("hello", 42)
        val codec = new SchemaPayloadCodec[CodecTestEvent]()
        val bytes = codec.encode(event)
        codec.decode(bytes) match
            case Result.Success(decoded) =>
                assert(decoded == event)
            case other =>
                fail(s"expected success, got: $other")
        end match
    }

    "schema codec produces distinct bytes for distinct values" in {
        val codec = new SchemaPayloadCodec[CodecTestEvent]()
        val a     = codec.encode(CodecTestEvent("x", 1))
        val b     = codec.encode(CodecTestEvent("y", 2))
        assert(!a.is(b))
    }

    "EventPayloadCodec.schema factory defaults to Ion Binary" in {
        val codec = EventPayloadCodec.schema[CodecTestEvent].asInstanceOf[SchemaPayloadCodec[CodecTestEvent]]
        val event = CodecTestEvent("factory", 7)
        codec.decode(codec.encode(event)) match
            case Result.Success(decoded) => assert(decoded == event)
            case other                   => fail(s"expected round-trip success from schema factory, got: $other")
    }

    "EventPayloadCodec.schema(MsgPack()) preserves explicit MsgPack encoding" in {
        val codec = EventPayloadCodec.schema[CodecTestEvent](MsgPack()).asInstanceOf[SchemaPayloadCodec[CodecTestEvent]]
        val event = CodecTestEvent("msgpack", 3)
        codec.decode(codec.encode(event)) match
            case Result.Success(decoded) => assert(decoded == event)
            case other                   => fail(s"expected MsgPack round-trip, got: $other")
    }

    "EventPayloadCodec.bytes factory is the identity codec" in {
        val input = Span.from("identity-payload".getBytes("UTF-8"))
        BytesPayloadCodec.decode(BytesPayloadCodec.encode(input)) match
            case Result.Success(decoded) => assert(decoded.is(input))
            case other                   => fail(s"expected identity round-trip, got: $other")
    }

    "schema codec round-trips a typed value through the JSONL transcode path" in {
        val event  = CodecTestEvent("roundtrip", 99)
        val codec  = new SchemaPayloadCodec[CodecTestEvent]()
        val binary = codec.encode(event)
        val jsonStr = codec.encodeForJsonl(binary)(using Frame.internal) match
            case Result.Success(s) => s
            case other             => fail(s"encodeForJsonl failed: $other")
        val reader = new Json().newReader(Span.from(jsonStr.getBytes("UTF-8")))(using Frame.internal)
        val recovered = codec.decodeFromJsonl(reader)(using Frame.internal) match
            case Result.Success(bytes) => bytes
            case other                 => fail(s"decodeFromJsonl failed: $other")
        codec.decode(recovered)(using Frame.internal) match
            case Result.Success(decoded) => assert(decoded == event)
            case other                   => fail(s"final decode failed: $other")
    }

end EventPayloadCodecTest

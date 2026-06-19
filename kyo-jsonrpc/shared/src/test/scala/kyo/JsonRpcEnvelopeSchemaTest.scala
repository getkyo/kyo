package kyo

import kyo.Abort
import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Result
import kyo.Schema
import kyo.Structure
import kyo.Structure.Value
import kyo.Structure.Value.Integer
import kyo.Structure.Value.Null
import kyo.Structure.Value.Record
import kyo.Structure.Value.Str

class JsonRpcEnvelopeSchemaTest extends JsonRpcTest:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val strict: Schema[JsonRpcEnvelope]  = internal.codec.JsonRpcEnvelopeSchema.strict
    private val lenient: Schema[JsonRpcEnvelope] = internal.codec.JsonRpcEnvelopeSchema.lenient

    // Structure.encode is pure but throws a JsonRpcError for the unencodable cases (a Malformed
    // message, or a Lenient reserved-extras key); Abort.catching reifies that so tests that expected a
    // Result from the old codec keep destructuring Success/Failure exactly as before.
    private def encode(env: JsonRpcEnvelope, schema: Schema[JsonRpcEnvelope])(using Frame): Value < Abort[JsonRpcError] =
        Abort.catching[JsonRpcError](Structure.encode[JsonRpcEnvelope](env)(using schema))

    // Structure.decode is total for the envelope schema: a bad shape decodes to a Malformed envelope
    // rather than a Result.Failure, so getOrThrow never throws here.
    private def decode(raw: Value, schema: Schema[JsonRpcEnvelope])(using Frame): JsonRpcEnvelope =
        Structure.decode[JsonRpcEnvelope](raw)(using schema).getOrThrow

    private def getSuccess[E, A](result: Result[E, A])(using kyo.test.AssertScope, Frame): A =
        result match
            case Result.Success(v) => v
            case Result.Failure(e) => fail(s"expected success but got failure: $e")
            case Result.Panic(t)   => fail(s"expected success but got panic: $t")

    "Strict2_0 encodes a request with jsonrpc id method and no params key" in {
        val env = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent)
        Abort.run[JsonRpcError](encode(env, strict)).map: result =>
            val sv = getSuccess(result)
            sv match
                case Record(fields) =>
                    val keys = fields.map(_._1).toSet
                    assert(keys == Set("jsonrpc", "id", "method"))
                    assert(fields.exists(_ == ("jsonrpc", Str("2.0"))))
                    assert(fields.exists(_ == ("id", Integer(1L))))
                    assert(fields.exists(_ == ("method", Str("m"))))
                case _ =>
                    fail(s"expected Record, got $sv")
            end match
    }

    "Strict2_0 decodes the encoded request back to the original envelope" in {
        val env = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent)
        Abort.run[JsonRpcError](encode(env, strict)).map: encResult =>
            val sv      = getSuccess(encResult)
            val decoded = decode(sv, strict)
            assert(decoded == JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent))
    }

    "JsonRpcId.Num encodes to Integer not a wrapper Record" in {
        val sv = Structure.encode[JsonRpcId](JsonRpcId.Num(1L))
        assert(sv == Integer(1L))
    }

    "JsonRpcId.Str encodes to Str not a wrapper Record" in {
        val sv = Structure.encode[JsonRpcId](JsonRpcId.Str("req-1"))
        assert(sv == Str("req-1"))
    }

    "Strict2_0 notification has no id key" in {
        val env = JsonRpcNotification("ping", Absent, Absent)
        Abort.run[JsonRpcError](encode(env, strict)).map: result =>
            val sv = getSuccess(result)
            sv match
                case Record(fields) =>
                    assert(!fields.exists(_._1 == "id"))
                    assert(fields.exists(_ == ("method", Str("ping"))))
                case _ =>
                    fail(s"expected Record, got $sv")
            end match
    }

    "Strict2_0 encodes success response with result but no error key" in {
        val env = JsonRpcResponse(JsonRpcId.Num(1L), Present(Str("ok")), Absent, Absent)
        Abort.run[JsonRpcError](encode(env, strict)).map: result =>
            val sv = getSuccess(result)
            sv match
                case Record(fields) =>
                    assert(fields.exists(_ == ("result", Str("ok"))))
                    assert(!fields.exists(_._1 == "error"))
                case _ =>
                    fail(s"expected Record, got $sv")
            end match
    }

    "Strict2_0 decodes response with both result and error as Malformed" in {
        val raw = Record(Chunk(
            "jsonrpc" -> Str("2.0"),
            "id"      -> Integer(1L),
            "result"  -> Record(Chunk.empty),
            "error" -> Record(Chunk(
                "code"    -> Integer(-32600L),
                "message" -> Str("Invalid Request")
            ))
        ))
        val decoded = decode(raw, strict)
        assert(decoded.isInstanceOf[JsonRpcMalformedMessage])
    }

    "Strict2_0 decodes id null on wire as Maybe JsonRpcId Absent" in {
        val raw = Record(Chunk(
            "jsonrpc" -> Str("2.0"),
            "id"      -> Null,
            "error" -> Record(Chunk(
                "code"    -> Integer(-32700L),
                "message" -> Str("Parse error")
            ))
        ))
        decode(raw, strict) match
            case JsonRpcMalformedMessage(_, _, _) => succeed
            case other => fail(
                    s"expected Malformed (null-id Response cannot be represented as Response, JsonRpcResponse.id has type JsonRpcId not Maybe), got: $other"
                )
        end match
    }

    "Lenient encode stamps extras at top level without jsonrpc field" in {
        val extras = Present(Record(Chunk("sessionId" -> Str("s1"))))
        val env    = JsonRpcRequest(JsonRpcId.Num(2L), "m", Absent, extras)
        Abort.run[JsonRpcError](encode(env, lenient)).map: result =>
            val sv = getSuccess(result)
            sv match
                case Record(fields) =>
                    assert(fields.exists(_ == ("sessionId", Str("s1"))))
                    assert(!fields.exists(_._1 == "jsonrpc"))
                case _ =>
                    fail(s"expected Record, got $sv")
            end match
    }

    "Lenient encode fails with invalidRequest when extras contains reserved key" in {
        val badExtras = Present(Record(Chunk("method" -> Str("hijack"))))
        val env       = JsonRpcRequest(JsonRpcId.Num(1L), "legit", Absent, badExtras)
        Abort.run[JsonRpcError](encode(env, lenient)).map: result =>
            result match
                case Result.Failure(e) => assert(e.code == -32600)
                case _                 => fail(s"expected failure, got $result")
    }

    "Lenient decode harvests unknown top-level fields into extras" in {
        val raw = Record(Chunk(
            "id"        -> Integer(2L),
            "method"    -> Str("Page.navigate"),
            "params"    -> Record(Chunk("url" -> Str("https://example.com"))),
            "sessionId" -> Str("abc123")
        ))
        val decoded = decode(raw, lenient)
        assert(decoded == JsonRpcRequest(
            JsonRpcId.Num(2L),
            "Page.navigate",
            Present(Record(Chunk("url" -> Str("https://example.com")))),
            Present(Record(Chunk("sessionId" -> Str("abc123"))))
        ))
    }

    "Strict2_0 decodes unclassifiable envelope as Malformed" in {
        val raw     = Record(Chunk("foo" -> Str("bar")))
        val decoded = decode(raw, strict)
        assert(decoded.isInstanceOf[JsonRpcMalformedMessage])
    }

    "extras Absent versus Present Null are distinct on the wire" in {
        val envAbsent = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent)
        val envNull   = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Present(Null))
        Abort.run[JsonRpcError](encode(envAbsent, lenient)).flatMap: rAbsent =>
            Abort.run[JsonRpcError](encode(envNull, lenient)).map: rNull =>
                val vAbsent = getSuccess(rAbsent)
                val vNull   = getSuccess(rNull)
                assert(vAbsent != vNull)
                vAbsent match
                    case Record(absFields) =>
                        assert(!absFields.map(_._1).toSet.contains("_extras"))
                    case _ =>
                        fail(s"expected Record, got $vAbsent")
                end match
    }

    "JsonRpcCustomError with code -32800 has code -32800" in {
        val err = JsonRpcCustomError(-32800, "user-cancel", Present(Str("user")))
        assert(err.code == -32800)
        assert(err.data == Present(Str("user")))
    }

    "Lenient omits jsonrpc field and Strict2_0 always includes it" in {
        val env = JsonRpcRequest(JsonRpcId.Num(1L), "m", Absent, Absent)
        Abort.run[JsonRpcError](encode(env, strict)).flatMap: rStrict =>
            Abort.run[JsonRpcError](encode(env, lenient)).map: rLenient =>
                val vStrict  = getSuccess(rStrict)
                val vLenient = getSuccess(rLenient)
                vStrict match
                    case Record(sf) => assert(sf.exists(_._1 == "jsonrpc"))
                    case _          => fail("expected Record")
                vLenient match
                    case Record(cf) => assert(!cf.exists(_._1 == "jsonrpc"))
                    case _          => fail("expected Record")
    }

    "Strict2_0 decoder recovers id from malformed response" in {
        val raw = Structure.Value.Record(Chunk(
            "jsonrpc" -> Structure.Value.Str("2.0"),
            "id"      -> Structure.Value.Integer(42),
            "error"   -> Structure.Value.Str("stringy")
        ))
        decode(raw, strict) match
            case JsonRpcMalformedMessage(Present(JsonRpcId.Num(42)), reason, _) =>
                assert(reason.nonEmpty)
            case other => fail(s"expected Malformed-with-id, got $other")
        end match
    }

    "Lenient decoder recovers id from malformed response" in {
        val raw = Structure.Value.Record(Chunk(
            "id"     -> Structure.Value.Integer(99),
            "result" -> Structure.Value.Record(Chunk("x" -> Structure.Value.Integer(1))),
            "error"  -> Structure.Value.Str("boom")
        ))
        decode(raw, lenient) match
            case JsonRpcMalformedMessage(Present(JsonRpcId.Num(99)), _, _) => succeed
            case other                                                     => fail(s"expected Malformed-with-id, got $other")
        end match
    }

    "Lenient decoder emits Malformed for non-Record error field" in {
        val raw = Structure.Value.Record(Chunk(
            "id"    -> Structure.Value.Integer(7),
            "error" -> Structure.Value.Str("stringy error")
        ))
        decode(raw, lenient) match
            case JsonRpcMalformedMessage(Present(JsonRpcId.Num(7)), reason, _) =>
                assert(reason.nonEmpty)
            case other => fail(s"expected Malformed-with-id for non-Record error, got $other")
        end match
    }

    "Malformed for non-Record carries Absent id" in {
        decode(Structure.Value.Str("not a record"), strict) match
            case JsonRpcMalformedMessage(Absent, reason, _) =>
                assert(reason == "expected a Record")
            case other => fail(s"expected Malformed(Absent, ...), got $other")
        end match
    }

end JsonRpcEnvelopeSchemaTest

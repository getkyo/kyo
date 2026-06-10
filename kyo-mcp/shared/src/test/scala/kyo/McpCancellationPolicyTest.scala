package kyo

import kyo.internal.mcp.McpCancellationPolicy

/** Tests for McpCancellationPolicy.default.
  *
  * Verifies the MCP notifications/cancelled protocol adapter: cancelMethod field name,
  * encodeParams / decodeParams round-trip, protectedMethods set, and expectReplyForCancelledRequest.
  */
class McpCancellationPolicyTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val policy = McpCancellationPolicy.default

    "cancelMethod is notifications/cancelled" in {
        assert(policy.cancelMethod == "notifications/cancelled")
    }

    "expectReplyForCancelledRequest is false" in {
        assert(!policy.expectReplyForCancelledRequest)
    }

    "cancelledError is Absent (delegates to substrate default)" in {
        assert(policy.cancelledError == Absent)
    }

    "protectedMethods contains initialize" in {
        assert(policy.protectedMethods.contains("initialize"))
    }

    "encodeParams: numeric id encodes to requestId Integer field" in {
        val id = JsonRpcId(42L)
        policy.encodeParams(id, Absent).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val reqId = Maybe.fromOption(fields.iterator.collectFirst { case ("requestId", v) => v })
                    assert(reqId == Present(Structure.Value.Integer(42L)))
                case _ => fail(s"expected Record, got $result")
        }
    }

    "encodeParams: string id encodes to requestId Str field" in {
        val id = JsonRpcId("req-abc")
        policy.encodeParams(id, Absent).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val reqId = Maybe.fromOption(fields.iterator.collectFirst { case ("requestId", v) => v })
                    assert(reqId == Present(Structure.Value.Str("req-abc")))
                case _ => fail(s"expected Record, got $result")
        }
    }

    "encodeParams: includes reason field when Present" in {
        val id = JsonRpcId(1L)
        policy.encodeParams(id, Present("user cancelled")).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val reason = Maybe.fromOption(fields.iterator.collectFirst { case ("reason", v) => v })
                    assert(reason == Present(Structure.Value.Str("user cancelled")))
                case _ => fail(s"expected Record, got $result")
        }
    }

    "encodeParams: no reason field when Absent" in {
        val id = JsonRpcId(1L)
        policy.encodeParams(id, Absent).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val hasReason = fields.exists(_._1 == "reason")
                    assert(!hasReason)
                case _ => fail(s"expected Record, got $result")
        }
    }

    "decodeParams: numeric requestId decodes to Present(JsonRpcId(n))" in {
        val params = Structure.Value.Record(Chunk("requestId" -> Structure.Value.Integer(7L)))
        policy.decodeParams(params).map { result =>
            assert(result == Present(JsonRpcId(7L)))
        }
    }

    "decodeParams: string requestId decodes to Present(JsonRpcId(s))" in {
        val params = Structure.Value.Record(Chunk("requestId" -> Structure.Value.Str("req-xyz")))
        policy.decodeParams(params).map { result =>
            assert(result == Present(JsonRpcId("req-xyz")))
        }
    }

    "decodeParams: missing requestId field returns Absent" in {
        val params = Structure.Value.Record(Chunk("other" -> Structure.Value.Str("x")))
        policy.decodeParams(params).map { result =>
            assert(result == Absent)
        }
    }

    "decodeParams: non-Record returns Absent" in {
        val params = Structure.Value.Str("not-a-record")
        policy.decodeParams(params).map { result =>
            assert(result == Absent)
        }
    }

    "decodeParams: requestId with unsupported type returns Absent" in {
        val params = Structure.Value.Record(Chunk("requestId" -> Structure.Value.Bool(true)))
        policy.decodeParams(params).map { result =>
            assert(result == Absent)
        }
    }

    "encodeParams then decodeParams round-trips a numeric id" in {
        val id = JsonRpcId(99L)
        policy.encodeParams(id, Absent).flatMap { encoded =>
            policy.decodeParams(encoded).map { decoded =>
                assert(decoded == Present(id))
            }
        }
    }

    "encodeParams then decodeParams round-trips a string id" in {
        val id = JsonRpcId("round-trip")
        policy.encodeParams(id, Absent).flatMap { encoded =>
            policy.decodeParams(encoded).map { decoded =>
                assert(decoded == Present(id))
            }
        }
    }

end McpCancellationPolicyTest

package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspCancellationPolicyTest extends Test:

    "LspCancellationPolicyTest" - {

        "cancelMethod is $/cancelRequest" in {
            assert(LspCancellationPolicy.default.cancelMethod == "$/cancelRequest")
        }

        "expectReplyForCancelledRequest is true" in {
            assert(LspCancellationPolicy.default.expectReplyForCancelledRequest)
        }

        "protectedMethods includes initialize" in {
            assert(LspCancellationPolicy.default.protectedMethods.contains("initialize"))
        }

        "cancelledError is Absent" in {
            assert(LspCancellationPolicy.default.cancelledError == Absent)
        }

        "encodeParams produces { id: <integer> } for integer id" in run {
            LspCancellationPolicy.default.encodeParams(JsonRpcId(42), Absent).map { sv =>
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.toMap
                        assert(m.get("id").contains(Structure.Value.Integer(42)))
                    case _ => fail("Expected Record")
            }
        }

        "encodeParams produces { id: <string> } for string id" in run {
            LspCancellationPolicy.default.encodeParams(JsonRpcId("req-1"), Absent).map { sv =>
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.toMap
                        assert(m.get("id").contains(Structure.Value.Str("req-1")))
                    case _ => fail("Expected Record")
            }
        }

        "encodeParams includes reason when Present" in run {
            LspCancellationPolicy.default.encodeParams(JsonRpcId(1), Present("timeout")).map { sv =>
                sv match
                    case Structure.Value.Record(fields) =>
                        val m = fields.toMap
                        assert(m.get("reason").contains(Structure.Value.Str("timeout")))
                    case _ => fail("Expected Record")
            }
        }

        "decodeParams extracts integer id" in run {
            val sv = Structure.Value.Record(Chunk("id" -> Structure.Value.Integer(99)))
            LspCancellationPolicy.default.decodeParams(sv).map { id =>
                assert(id == Present(JsonRpcId(99)))
            }
        }

        "decodeParams extracts string id" in run {
            val sv = Structure.Value.Record(Chunk("id" -> Structure.Value.Str("abc")))
            LspCancellationPolicy.default.decodeParams(sv).map { id =>
                assert(id == Present(JsonRpcId("abc")))
            }
        }

        "decodeParams returns Absent for non-Record" in run {
            LspCancellationPolicy.default.decodeParams(Structure.Value.Null).map { id =>
                assert(id == Absent)
            }
        }

    }

end LspCancellationPolicyTest

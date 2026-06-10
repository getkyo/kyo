package kyo

import kyo.internal.mcp.McpProgressPolicy

/** Tests for McpProgressPolicy.default.
  *
  * Pins that progress tokens are extracted from params._meta.progressToken (not top-level
  * params.progressToken) on the request side. The extractInboundToken (progress notification)
  * reads from top-level params.progressToken.
  *
  * All policy functions return Structure.Value < Sync; tests unwrap via `run` to get concrete values.
  */
class McpProgressPolicyTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val policy = McpProgressPolicy.default

    // -------------------------------------------------------------------------
    // Basic field assertions
    // -------------------------------------------------------------------------

    "progressMethod is notifications/progress" in {
        assert(policy.progressMethod == "notifications/progress")
    }

    "enforceMonotonic is true" in {
        assert(policy.enforceMonotonic)
    }

    // -------------------------------------------------------------------------
    // extractRequestToken reads from _meta.progressToken only
    // -------------------------------------------------------------------------

    "extractRequestToken: extracts token from _meta.progressToken" in {
        val paramsA = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Integer(1L)))
        ))
        policy.extractRequestToken(paramsA).map { result =>
            assert(result == Present(Structure.Value.Integer(1L)))
        }
    }

    "extractRequestToken: returns Absent for top-level progressToken (wrong location)" in {
        val paramsB = Structure.Value.Record(Chunk(
            "progressToken" -> Structure.Value.Integer(1L)
        ))
        policy.extractRequestToken(paramsB).map { result =>
            assert(result == Absent)
        }
    }

    "extractRequestToken: returns Absent for missing _meta field" in {
        val paramsC = Structure.Value.Record(Chunk(
            "method" -> Structure.Value.Str("tools/call")
        ))
        policy.extractRequestToken(paramsC).map { result =>
            assert(result == Absent)
        }
    }

    "extractRequestToken: returns Absent for _meta without progressToken" in {
        val paramsD = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("other" -> Structure.Value.Str("x")))
        ))
        policy.extractRequestToken(paramsD).map { result =>
            assert(result == Absent)
        }
    }

    "extractRequestToken: handles string token" in {
        val paramsE = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Str("tok-abc")))
        ))
        policy.extractRequestToken(paramsE).map { result =>
            assert(result == Present(Structure.Value.Str("tok-abc")))
        }
    }

    // -------------------------------------------------------------------------
    // extractInboundToken: reads from top-level params.progressToken
    // -------------------------------------------------------------------------

    "extractInboundToken: extracts token from top-level progressToken" in {
        val paramsB = Structure.Value.Record(Chunk(
            "progressToken" -> Structure.Value.Integer(1L)
        ))
        policy.extractInboundToken(paramsB).map { result =>
            assert(result == Present(Structure.Value.Integer(1L)))
        }
    }

    "extractInboundToken: returns Absent for _meta.progressToken (wrong location)" in {
        val paramsA = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Integer(1L)))
        ))
        policy.extractInboundToken(paramsA).map { result =>
            assert(result == Absent)
        }
    }

    // -------------------------------------------------------------------------
    // stampOutboundToken: injects token into params._meta.progressToken
    // -------------------------------------------------------------------------

    "stampOutboundToken: injects progressToken into _meta for Record params" in {
        val params = Structure.Value.Record(Chunk("arg" -> Structure.Value.Str("val")))
        val token  = Structure.Value.Integer(42L)
        policy.stampOutboundToken(params, token).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val meta = fields.iterator.collectFirst { case ("_meta", v) => v }
                    assert(meta.isDefined)
                    meta.get match
                        case Structure.Value.Record(metaFields) =>
                            val pt = Maybe.fromOption(metaFields.iterator.collectFirst { case ("progressToken", v) => v })
                            assert(pt == Present(Structure.Value.Integer(42L)))
                        case _ => fail("_meta is not a Record")
                    end match
                case _ => fail("result is not a Record")
        }
    }

    // -------------------------------------------------------------------------
    // encodeProgressParams: builds progress notification params
    // -------------------------------------------------------------------------

    "encodeProgressParams: wraps token and value into a Record" in {
        val token = Structure.Value.Integer(7L)
        val value = Structure.Value.Record(Chunk("progress" -> Structure.Value.Integer(50L)))
        policy.encodeProgressParams(token, value).map { result =>
            result match
                case Structure.Value.Record(fields) =>
                    val hasToken = fields.exists(_._1 == "progressToken")
                    assert(hasToken)
                case _ => fail("result is not a Record")
        }
    }

    // -------------------------------------------------------------------------
    // extractProgressValue: strips progressToken field from notification params
    // -------------------------------------------------------------------------

    "extractProgressValue: returns Record without progressToken field" in {
        val params = Structure.Value.Record(Chunk(
            "progressToken" -> Structure.Value.Integer(7L),
            "progress"      -> Structure.Value.Integer(50L)
        ))
        policy.extractProgressValue(params).map { result =>
            result match
                case Present(Structure.Value.Record(fields)) =>
                    assert(!fields.exists(_._1 == "progressToken"))
                    assert(fields.exists(_._1 == "progress"))
                case _ => fail(s"unexpected result: $result")
        }
    }

    "extractProgressValue: returns Absent for non-Record params" in {
        val params = Structure.Value.Str("not-a-record")
        policy.extractProgressValue(params).map { result =>
            assert(result == Absent)
        }
    }

end McpProgressPolicyTest

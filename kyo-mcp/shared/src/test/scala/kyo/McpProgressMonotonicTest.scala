package kyo

import kyo.internal.mcp.McpProgressPolicy

/** Focused tests for the McpProgressPolicy.default monotonic flag and token-location invariant.
  *
  * Covers the monotonic enforcement flag value and the invariant that progress tokens on
  * requests live at params._meta.progressToken, not at top-level params.progressToken.
  */
class McpProgressMonotonicTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private val policy = McpProgressPolicy.default

    // -------------------------------------------------------------------------
    // Monotonic flag pin
    // -------------------------------------------------------------------------

    "enforceMonotonic is true" in {
        assert(policy.enforceMonotonic)
    }

    // -------------------------------------------------------------------------
    // Token-location invariant
    // -------------------------------------------------------------------------

    "extractRequestToken: accepts _meta.progressToken" in {
        val params = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Integer(1L)))
        ))
        policy.extractRequestToken(params).map { result =>
            assert(result.isDefined)
            assert(result == Present(Structure.Value.Integer(1L)))
        }
    }

    "extractRequestToken: rejects top-level progressToken" in {
        val params = Structure.Value.Record(Chunk(
            "progressToken" -> Structure.Value.Integer(1L)
        ))
        policy.extractRequestToken(params).map { result =>
            assert(result == Absent, s"Expected Absent but got $result; token must be at _meta.progressToken, not top-level")
        }
    }

    "extractInboundToken: accepts top-level progressToken (progress notification)" in {
        val params = Structure.Value.Record(Chunk(
            "progressToken" -> Structure.Value.Integer(1L)
        ))
        policy.extractInboundToken(params).map { result =>
            assert(result == Present(Structure.Value.Integer(1L)))
        }
    }

    "extractInboundToken: returns Absent for _meta.progressToken" in {
        val params = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Integer(1L)))
        ))
        policy.extractInboundToken(params).map { result =>
            assert(result == Absent)
        }
    }

    "extractRequestToken: handles nested _meta with additional fields" in {
        val params = Structure.Value.Record(Chunk(
            "arg" -> Structure.Value.Str("val"),
            "_meta" -> Structure.Value.Record(Chunk(
                "other"         -> Structure.Value.Str("x"),
                "progressToken" -> Structure.Value.Integer(42L)
            ))
        ))
        policy.extractRequestToken(params).map { result =>
            assert(result == Present(Structure.Value.Integer(42L)))
        }
    }

    "extractRequestToken: returns Absent when _meta has no progressToken" in {
        val params = Structure.Value.Record(Chunk(
            "_meta" -> Structure.Value.Record(Chunk("unrelated" -> Structure.Value.Str("x")))
        ))
        policy.extractRequestToken(params).map { result =>
            assert(result == Absent)
        }
    }

    "extractRequestToken: returns Absent for non-Record params" in {
        val params = Structure.Value.Null
        policy.extractRequestToken(params).map { result =>
            assert(result == Absent)
        }
    }

end McpProgressMonotonicTest

package kyo.internal

import kyo.*
import kyo.internal.lsp.*

class LspProgressPolicyTest extends Test:

    "LspProgressPolicyTest" - {

        "progressMethod is $/progress" in {
            assert(LspProgressPolicy.default.progressMethod == "$/progress")
        }

        "extractInboundToken reads params.token" in {
            val sv = Structure.Value.Record(Chunk(
                "token" -> Structure.Value.Str("my-token"),
                "value" -> Structure.Value.Record(Chunk.empty)
            ))
            LspProgressPolicy.default.extractInboundToken(sv).map { tok =>
                assert(tok == Present(Structure.Value.Str("my-token")))
            }
        }

        "extractRequestToken reads workDoneToken first" in {
            val sv = Structure.Value.Record(Chunk(
                "textDocument"       -> Structure.Value.Record(Chunk.empty),
                "workDoneToken"      -> Structure.Value.Str("wdt-1"),
                "partialResultToken" -> Structure.Value.Str("prt-1")
            ))
            LspProgressPolicy.default.extractRequestToken(sv).map { tok =>
                assert(tok == Present(Structure.Value.Str("wdt-1")))
            }
        }

        "extractRequestToken falls back to partialResultToken" in {
            val sv = Structure.Value.Record(Chunk(
                "textDocument"       -> Structure.Value.Record(Chunk.empty),
                "partialResultToken" -> Structure.Value.Str("prt-1")
            ))
            LspProgressPolicy.default.extractRequestToken(sv).map { tok =>
                assert(tok == Present(Structure.Value.Str("prt-1")))
            }
        }

        "extractRequestToken returns Absent when neither token present" in {
            val sv = Structure.Value.Record(Chunk(
                "textDocument" -> Structure.Value.Record(Chunk.empty)
            ))
            LspProgressPolicy.default.extractRequestToken(sv).map { tok =>
                assert(tok == Absent)
            }
        }

        "encodeProgressParams builds { token, ...fields }" in {
            val token = Structure.Value.Str("tok-1")
            val value = Structure.Value.Record(Chunk("kind" -> Structure.Value.Str("begin")))
            LspProgressPolicy.default.encodeProgressParams(token, value).map { encoded =>
                encoded match
                    case Structure.Value.Record(fields) =>
                        val m = fields.toMap
                        assert(m.get("token").contains(token))
                        assert(m.get("kind").contains(Structure.Value.Str("begin")))
                    case _ => fail("Expected Record")
            }
        }

        "extractProgressValue strips token field" in {
            val sv = Structure.Value.Record(Chunk(
                "token" -> Structure.Value.Str("tok-1"),
                "kind"  -> Structure.Value.Str("report")
            ))
            LspProgressPolicy.default.extractProgressValue(sv).map { v =>
                v match
                    case Present(Structure.Value.Record(fields)) =>
                        assert(!fields.exists(_._1 == "token"))
                        assert(fields.exists(_._1 == "kind"))
                    case _ => fail("Expected Present(Record)")
            }
        }

        "enforceMonotonic is false" in {
            assert(!LspProgressPolicy.default.enforceMonotonic)
        }

    }

end LspProgressPolicyTest

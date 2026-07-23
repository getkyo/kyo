package kyo

class AIExceptionTest extends kyo.test.Test[Any]:
    "hierarchy" - {
        "super-types track operations, leaves track failures" in {
            summon[AIGenException <:< AIException]
            summon[AIStreamException <:< AIException]
            summon[AIException <:< KyoException]
            summon[AIEvalExhaustedException <:< AIGenException]
            summon[AIInvalidThoughtException <:< AIGenException]
            summon[AIDecodeException <:< AIGenException]
            summon[AIStreamDeltaException <:< AIStreamException]
            summon[AIStreamIncompleteException <:< AIStreamException]
            summon[AIContextOverflowException <:< AIGenException]
            succeed
        }
        "AIEmbeddingUnsupportedException is gone from the hierarchy (compile-absence)" in
            typeCheckFailure("kyo.AIEmbeddingUnsupportedException(\"p\")")("AIEmbeddingUnsupportedException")
        "a shared failure belongs to multiple operations" in {
            // missing-key and transport occur in BOTH gen and stream, so each leaf mixes in both super-types.
            summon[AIMissingApiKeyException <:< AIGenException]
            summon[AIMissingApiKeyException <:< AIStreamException]
            summon[AITransportException <:< AIGenException]
            summon[AITransportException <:< AIStreamException]
            val shared: AIGenException & AIStreamException = AIMissingApiKeyException("gpt-4")
            assert(shared.isInstanceOf[AIGenException] && shared.isInstanceOf[AIStreamException])
        }
    }
    "leaves" - {
        "messages are built from typed fields" in {
            assert(AIMissingApiKeyException("gpt-4").getMessage.contains("gpt-4"))
            assert(AIInvalidThoughtException("Reflect").getMessage.contains("Reflect"))
            assert(AIStreamDeltaException("not-json").getMessage.contains("not-json"))
            assert(AIEvalExhaustedException(8).getMessage.contains("8"))
        }
        "the transport leaf carries the HttpException cause" in {
            val http = HttpConnectException("localhost", 80, new RuntimeException("refused"))
            val e    = AITransportException(http)
            assert(e.cause eq http)
            assert(e.getCause() eq http)
        }
    }
    "AIContextOverflowException is a typed leaf under AIGenException only, never a raw Throwable, and does not ride AIStreamException" in {
        // The single user-observable compaction failure (the forced path's terminal abort). It rides the
        // gen row only; the 'stream path' clause is vacuous because renderView runs eagerly on the outer
        // Abort[AIGenException] row before the Stream's lazy Abort[AIStreamException] value is built.
        summon[AIContextOverflowException <:< AIGenException]
        val e: AIException = AIContextOverflowException(171001, 171000)
        assert(e.isInstanceOf[AIGenException], "an instance IS an AIGenException")
        assert(!e.isInstanceOf[AIStreamException], "it is NOT an AIStreamException (no stream-row mix-in)")
        assert(e.isInstanceOf[KyoException], "it is a typed KyoException leaf, never a bare Throwable/HttpException")
        assert(!(e: Throwable).isInstanceOf[HttpException], "it is never surfaced as a raw HttpException on a public row")
    }

    "misuse stays off the operation rows" - {
        "cross-run and meter-closed are AIException but not gen/stream failures" in {
            summon[AICrossRunException <:< AIException]
            summon[AIMeterClosedException <:< AIException]
            assert(!(AICrossRunException(7L): AIException).isInstanceOf[AIGenException])
            assert(!(AIMeterClosedException(): AIException).isInstanceOf[AIStreamException])
            assert(AICrossRunException(7L).getMessage.contains("different LLM.run"))
        }
    }
end AIExceptionTest

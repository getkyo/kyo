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
            succeed
        }
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

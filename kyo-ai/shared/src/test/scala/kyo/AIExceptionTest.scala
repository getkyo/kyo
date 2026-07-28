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
        "AIRequestRejectedException carries provider/status/detail, is non-transient, and rides both operation sets" in {
            val e = AIRequestRejectedException("p", 400, "bad request")
            assert(e.getMessage.contains("p rejected the request (400): bad request"), s"message: ${e.getMessage}")
            assert(e.isInstanceOf[AIGenException] && e.isInstanceOf[AIStreamException])
            assert(!e.isInstanceOf[AITransientException], "a rejected request is not transient: retrying the same request fails again")
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
    "a ceiling stop names which lever fixes it" - {
        // Naming only the ceiling sends the reader after whichever lever they guess, and the two are
        // opposites: a stop that spent its allowance reasoning does not want a bigger allowance, it
        // wants less reasoning, and raising the limit buys another expensive stop in the same place.
        "a stop that spent most of its ceiling reasoning points at the reasoning, not the ceiling" in {
            val msg = AIOutputLimitException("P", "m", Present(64000), Absent, Present(55000)).getMessage
            assert(msg.contains("55000 of those 64000 tokens"), msg)
            assert(msg.contains("85%"), msg)
            assert(msg.contains("asking for less reasoning is the lever"), msg)
        }
        "a stop that barely reasoned points at the ceiling" in {
            val msg = AIOutputLimitException("P", "m", Present(64000), Absent, Present(1200)).getMessage
            assert(msg.contains("1200 of those 64000 tokens"), msg)
            assert(msg.contains("a larger ceiling is the lever"), msg)
        }
        "a split that says little names both levers rather than committing to one" in {
            // The advice used to flip at exactly half, so one token either side produced opposite
            // counsel from a number that had not meaningfully changed. Between the bands it now
            // declines to choose.
            val msg = AIOutputLimitException("P", "m", Present(64000), Absent, Present(32000)).getMessage
            assert(msg.contains("50%"), msg)
            assert(msg.contains("either a larger ceiling or less reasoning will move it"), msg)
            assert(!msg.contains("is the lever"), msg)
        }
        "a wire that does not report reasoning usage says nothing about it rather than guessing" in {
            val msg = AIOutputLimitException("P", "m", Present(64000), Absent, Absent).getMessage
            // Narrower than "the word never appears": Frame renders the calling source line into the
            // message, so a test whose own text mentions reasoning would match itself.
            assert(!msg.contains("were spent reasoning"), msg)
            assert(!msg.contains("is the lever"), msg)
            assert(msg.contains("64000 tokens"), msg)
        }
        "the prior-rejection history still rides alongside it" in {
            val msg = AIOutputLimitException("P", "m", Present(64000), Present("it was rejected twice"), Present(60000)).getMessage
            assert(msg.contains("were spent reasoning"), msg)
            assert(msg.contains("it was rejected twice"), msg)
        }
    }
end AIExceptionTest

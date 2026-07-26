package kyo.ai.completion

import kyo.*
import kyo.ai.Context
import kyo.ai.Context.*

class HarnessCompletionTest extends kyo.test.Test[Any]:

    "commandFailure classifies Present(429) as a transient AIRateLimitException" in {
        val ex = HarnessCompletion.commandFailure("p", Present(429), "throttled")
        assert(ex == AIRateLimitException("p", "throttled"))
        assert(ex.isInstanceOf[AITransientException], "a rate limit is a recoverable throttle, retryable with backoff")
    }

    "commandFailure classifies Present(401) and Present(403) as a non-transient AIProviderAuthException" in {
        val unauthorized = HarnessCompletion.commandFailure("p", Present(401), "not authenticated")
        val forbidden    = HarnessCompletion.commandFailure("p", Present(403), "forbidden")
        assert(unauthorized == AIProviderAuthException("p", "not authenticated"))
        assert(forbidden == AIProviderAuthException("p", "forbidden"))
        assert(!unauthorized.isInstanceOf[AITransientException], "an auth failure halts, it does not retry")
    }

    "commandFailure classifies Present(503) and Present(529) as a transient AIProviderUnavailableException" in {
        val overloaded = HarnessCompletion.commandFailure("p", Present(503), "overloaded")
        val busy       = HarnessCompletion.commandFailure("p", Present(529), "busy")
        assert(overloaded == AIProviderUnavailableException("p", "overloaded"))
        assert(busy == AIProviderUnavailableException("p", "busy"))
        assert(overloaded.isInstanceOf[AITransientException] && busy.isInstanceOf[AITransientException], "an outage retries")
    }

    "commandFailure classifies a rejected 4xx status (400) as AIRequestRejectedException, matching classifyHttp exactly" in {
        val ex = HarnessCompletion.commandFailure("p", Present(400), "bad request")
        assert(ex == AIRequestRejectedException("p", 400, "bad request"))
        assert(!ex.isInstanceOf[AITransientException], "a rejected request fails fast, it does not retry")
        // The same leaf Completion.classifyHttp produces for a 400 on the HTTP backends: a rejected,
        // non-auth, non-throttle, non-5xx status classifies identically across both backend families.
        val httpLeaf =
            Completion.classifyHttp(kyo.ai.Config.OpenAI.default, HttpStatusException(HttpStatus(400), "POST", "https://example.test"))
        assert(
            httpLeaf.isInstanceOf[AIRequestRejectedException],
            s"classifyHttp must classify a rejected 400 as the same leaf type the harness family uses: $httpLeaf"
        )
    }

    "commandFailure classifies Absent (no provider status) as AIHarnessException" in {
        val ex = HarnessCompletion.commandFailure("p", Absent, "spawn failed")
        assert(ex == AIHarnessException("p", "spawn failed"))
        assert(!ex.isInstanceOf[AITransientException], "a broken harness is not retried")
    }

    "streamFailure classifies the same structured-status buckets and mixes in AIStreamException" in {
        val throttled = HarnessCompletion.streamFailure("p", Present(429), "throttled")
        val broken    = HarnessCompletion.streamFailure("p", Absent, "crashed")
        assert(throttled == AIRateLimitException("p", "throttled"))
        assert(broken == AIHarnessException("p", "crashed"))
        assert(throttled.isInstanceOf[AIStreamException] && broken.isInstanceOf[AIStreamException])
    }

    "classify carries the correct exception axes (transient vs halt) and has no timeout branch" in {
        val throttle    = HarnessCompletion.commandFailure("p", Present(429), "d")
        val auth        = HarnessCompletion.commandFailure("p", Present(401), "d")
        val unavailable = HarnessCompletion.commandFailure("p", Present(503), "d")
        val harness     = HarnessCompletion.commandFailure("p", Absent, "d")
        assert(throttle.isInstanceOf[AITransientException] && unavailable.isInstanceOf[AITransientException])
        assert(!auth.isInstanceOf[AITransientException] && !harness.isInstanceOf[AITransientException])
        // A structured status match has no timeout arm: the subprocess-wait boundary classifies a genuine
        // timeout before commandFailure is ever reached, so no leaf here is a timeout leaf.
        assert(!auth.isInstanceOf[AICompletionTimeoutException] && !harness.isInstanceOf[AICompletionTimeoutException])
    }

    "a Codex terminal error with an Absent status classifies as AIHarnessException (harness-family feed exercised on Codex)" in {
        val ex = HarnessCompletion.commandFailure("Codex", Absent, "app-server crashed")
        assert(ex == AIHarnessException("Codex", "app-server crashed"))
        assert(!ex.isInstanceOf[AITransientException])
    }

    "continuationRequest names recorded tool results for a tool-terminated body and stays bare otherwise" in {
        val toolEnded = Context.empty
            .userMessage("q")
            .assistantMessage("", Chunk(Call(CallId("c1"), "lookup", "{}")))
            .toolMessage(CallId("c1"), "42")
        assert(
            HarnessCompletion.continuationRequest(toolEnded.messages) ==
                "Continue: complete the original request using the recorded tool results above; do not repeat completed tool calls.",
            "a tool-terminated body must name the recorded results so the model consumes them instead of re-calling"
        )
        val assistantEnded = Context.empty.userMessage("q").assistantMessage("partial answer")
        assert(HarnessCompletion.continuationRequest(assistantEnded.messages) == "Continue.")
        assert(HarnessCompletion.continuationRequest(Chunk.empty) == "Continue.")
    }

    "trailingSystemCount counts only the trailing run of system messages" in {
        val ctx = Context.empty
            .systemMessage("ambient")
            .userMessage("q")
            .systemMessage("reminder")
            .systemMessage("finalize")
        assert(HarnessCompletion.trailingSystemCount(ctx.messages) == 2)
        assert(HarnessCompletion.trailingSystemCount(Context.empty.userMessage("q").messages) == 0)
        assert(HarnessCompletion.trailingSystemCount(Chunk.empty) == 0)
    }

    "the provider/transient axes group the leaves so callers classify by type, not by a leaf set" in {
        // provider failures (the provider/account is the problem)
        assert(AIRateLimitException("p", "429").isInstanceOf[AIProviderException])
        assert(AIProviderAuthException("p", "401").isInstanceOf[AIProviderException])
        assert(AIMissingApiKeyException("m").isInstanceOf[AIProviderException])
        assert(AIProviderUnavailableException("p", "503").isInstanceOf[AIProviderException])
        // the transient subset also refines AITransientException (retried by LLM.gen)
        assert(AIProviderUnavailableException("p", "503").isInstanceOf[AITransientException])
        assert(
            AIRateLimitException("p", "429").isInstanceOf[AITransientException],
            "a recoverable rate-limit throttle is transient (retried with backoff)"
        )
        // response-side failures are neither provider nor transient
        assert(!AICompletionTimeoutException("p", 1.minute).isInstanceOf[AIProviderException], "a timeout is this request's failure")
        assert(!AIDecodeException("bad").isInstanceOf[AIProviderException], "a decode failure is response-side")
        assert(!AIHarnessException("p", "garbled").isInstanceOf[AIProviderException], "a harness malfunction is per-call")
    }

end HarnessCompletionTest

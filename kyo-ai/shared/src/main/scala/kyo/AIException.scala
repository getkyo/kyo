package kyo

/** The module's exception hierarchy, organized by the two operations that produce failures.
  *
  * The super-types track operations, the leaves track specific failures, and a leaf mixes in every operation
  * it can occur in:
  *   - [[kyo.AIGenException]]: a generation's failure set (`AI.gen` / `ai.gen`), the row of `LLM.run`'s
  *     residual, surfacing at the run boundary as the `Gen` op's eval loop runs.
  *   - [[kyo.AIStreamException]]: a stream's failure set (`AI.stream` / `ai.stream`), carried inside the
  *     returned `Stream`'s effect parameter and raised lazily as it is consumed.
  *
  * A failure shared by both operations (missing API key, transport error) mixes in both super-types, so
  * `Abort.fail` of that leaf type-checks against either row. Cross-run instance use and a closed meter are
  * misuse and impossible-state: they panic and stay off both rows.
  *
  * Two subcategories refine BOTH operation traits so callers classify by type, not by matching leaves.
  * [[kyo.AIProviderException]] is the failures where the provider or account is the problem (missing key,
  * auth, throttle, transport, outage), which retrying the same request cannot get past.
  * [[kyo.AITransientException]] refines that to the provider failures that are temporary by nature
  * (transport, transient outage, throttle), which `LLM.gen` retries with backoff. The response-side failures
  * (decode, per-call timeout, request rejection, harness) are neither.
  */
sealed abstract class AIException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** The failures a generation (`AI.gen` / `ai.gen`) can produce; the row of `LLM.run`'s residual. */
sealed trait AIGenException extends AIException

/** The failures a stream (`AI.stream` / `ai.stream`) can produce; the row of the returned `Stream`. */
sealed trait AIStreamException extends AIException

/** Failures where the PROVIDER or the account/credential is the problem, not the request or its response:
  * a missing key, an auth rejection, a throttle, a transport error, or an outage. Retrying the same request
  * will not succeed until the provider or account changes (recovers, is topped up, is re-authenticated), so
  * a caller that cannot proceed without the provider stops rather than blaming this request. Refines both
  * operation traits. The response-side failures (decode, per-call timeout, rejected request, harness) are
  * NOT provider failures.
  */
sealed trait AIProviderException extends AIGenException with AIStreamException

/** Provider failures that are additionally TEMPORARY by nature: a transport blip, a transiently-unavailable
  * provider, or a rate-limit throttle that clears, for which retrying with backoff is correct. `LLM.gen`
  * retries these across both backend families by naming this one type rather than enumerating leaves. An
  * auth failure is a provider failure but NOT transient; a per-call timeout and a decode failure are neither.
  */
sealed trait AITransientException extends AIProviderException

/** No API key is configured for the model's provider: a provider-access failure. Either operation
  * reaches the provider, so it is in both failure sets.
  */
case class AIMissingApiKeyException(model: String)(using Frame)
    extends AIException(s"Can't locate API key for model: $model") with AIProviderException

/** The provider HTTP call failed; the originating kyo-http `HttpException` is carried as the cause. Mapped
  * from the raw transport error at the eval/stream boundary so no non-module exception rides a public row.
  * Either operation makes the call, so it is in both failure sets, and a transport error is transient.
  */
case class AITransportException(cause: HttpException)(using Frame)
    extends AIException(cause.getMessage, cause) with AITransientException

/** The result tool was forced but no usable result was accepted, even after the one repair turn granted
  * past `maxIterations`. `detail` distinguishes the two ways: Absent means the model never called the
  * result tool (a command harness, or an HTTP backend whose wire does not compel the call); Present carries
  * the rejection bookkeeping when the tool was called but every payload was rejected.
  */
case class AIEvalExhaustedException(maxIterations: Int, detail: Maybe[String] = Absent)(using Frame)
    extends AIException(
        detail match
            case Present(d) =>
                s"Eval loop forced the result tool but no usable result was accepted within $maxIterations iterations plus one repair turn: $d"
            case Absent =>
                s"Eval loop forced the result tool but the model never called it within $maxIterations iterations plus one repair turn"
    ) with AIGenException

/** The model named a reasoning field (thought) that is not enabled for the generation. */
case class AIInvalidThoughtException(name: String)(using Frame)
    extends AIException(s"invalid thought: $name") with AIGenException

/** The model's reply could not be decoded into the requested result (an undecodable thought or result
  * envelope, or a response with no choices).
  */
case class AIDecodeException(detail: String)(using Frame)
    extends AIException(detail) with AIGenException

/** A provider was transiently unreachable: overloaded (503/529), a network/DNS/connection error, or an
  * upstream timeout. Transient, so retrying with backoff is the correct response. Distinct from a rate
  * limit (throttled, retrying makes it worse), an auth failure (misconfigured), and a per-call timeout
  * (this call is too slow). Either operation makes the call, so it is in both failure sets.
  */
case class AIProviderUnavailableException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider provider is temporarily unavailable: $detail") with AITransientException

/** The provider throttled the request: a rate limit, an exhausted quota, or a billing/credit problem
  * (429). A recoverable throttle by nature (a rate-limit window resets, a quota refills), so it is
  * TRANSIENT: retrying with backoff is the correct response, and `LLM.gen` retries it on the configured
  * schedule. A bounded schedule still surfaces a genuinely-stuck account once its attempts are spent.
  * Either operation makes the call, so it is in both failure sets.
  */
case class AIRateLimitException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider rate limit or quota exceeded: $detail") with AITransientException

/** The provider rejected authentication: an invalid or unauthorized credential (401/403), or a
  * not-logged-in command harness. NOT transient: retrying with the same broken credential fails again, so
  * a caller halts. Distinct from [[AIMissingApiKeyException]] (no credential configured at all). Either
  * operation makes the call, so it is in both failure sets.
  */
case class AIProviderAuthException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider authentication failed: $detail") with AIProviderException

/** The wire refused the request because it judged the MODEL's tool call invalid, rather than returning
  * that call for the eval loop to read. Most wires hand back whatever the model produced, malformed
  * arguments included, and the loop repairs it next turn; a wire that validates instead answers with a
  * rejection the loop never sees.
  *
  * NOT transient, and deliberately not retried: a retry redraws a sample blind, while the eval loop can
  * tell the model what went wrong and spend one turn on the correction. The loop feeds the failure forward
  * and its iteration budget bounds the attempts, so a wire that rejects every one ends in exhaustion. On
  * the stream row there is no repair loop: the stream fails with this leaf and the caller decides whether
  * to re-stream.
  */
case class AIToolCallRejectedException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider refused the model's tool call: $detail") with AIGenException with AIStreamException

/** The provider rejected this request as invalid: a non-success status that is neither auth nor
  * throttle nor outage (e.g. a 400 malformed-request error). NOT transient: the same request fails
  * the same way again, so it surfaces without retry as this call's failure. Either operation makes
  * the call, so it is in both failure sets.
  */
case class AIRequestRejectedException(provider: String, status: Int, detail: String)(using Frame)
    extends AIException(s"$provider rejected the request ($status): $detail") with AIGenException with AIStreamException

/** A completion call did not finish within its configured `timeout`. The deadline covers the call's
  * retries, so this is the call's own failure (skip or score it) and NOT transient: retrying it would
  * only start a fresh deadline on the same slow work. Carries the elapsed `timeout` as a typed field.
  * Either operation makes the call, so it is in both failure sets.
  */
case class AICompletionTimeoutException(provider: String, timeout: Duration)(using Frame)
    extends AIException(s"$provider completion did not finish within ${timeout.show}") with AIGenException with AIStreamException

/** The provider stopped the reply at the output-token ceiling before the turn carried anything usable. A
  * normal, structured provider outcome decoded from the reply's own termination field, NOT a malfunction:
  * the provider did exactly what the request's ceiling asked.
  *
  * `maxOutputTokens` is the ceiling the request carried, `Absent` when the request set none and the
  * provider's own limit applied. Reasoning tokens count against it, so on a model whose thinking is
  * unbounded this can occur before any visible output exists.
  *
  * NOT transient: retrying spends the whole ceiling again to stop at the same wall. The levers belong to
  * the caller: raise `Config.maxTokens` toward the model's maximum, ask for less output, or choose a model
  * whose reasoning is budget-bounded. In both failure sets.
  */
case class AIOutputLimitException(
    provider: String,
    model: String,
    maxOutputTokens: Maybe[Int],
    // What happened before the ceiling was reached. If earlier turns produced results this loop rejected,
    // reporting only the ceiling sends the reader after the wrong lever: the answers were arriving, and
    // something else was refusing them.
    priorRejections: Maybe[String] = Absent,
    // How much of the ceiling went to reasoning rather than the answer, where the wire states it. This is
    // the number that picks the lever: a stop that spent nearly its whole allowance reasoning is not short
    // of ceiling, and raising the limit buys another expensive stop; one that barely reasoned is genuinely
    // short.
    reasoningTokens: Maybe[Long] = Absent
)(using Frame)
    extends AIException(
        s"$provider stopped generating for model $model at the output-token ceiling" +
            maxOutputTokens.fold(" (the provider's own limit)")(n => s" ($n tokens)") +
            " before producing a result" +
            reasoningTokens.fold("")(spent =>
                maxOutputTokens.fold(s"; $spent of those tokens were spent reasoning")(ceiling =>
                    s"; $spent of those $ceiling tokens (${spent * 100 / math.max(ceiling, 1)}%) were spent reasoning" +
                        // Committing to one lever needs a clear reading; near the middle the split says
                        // little and a knife-edge at half would flip advice for one token either side, so
                        // the middle band names both rather than guessing.
                        (if spent * 5 >= ceiling * 4 then ", so asking for less reasoning is the lever rather than a larger ceiling"
                         else if spent * 5 <= ceiling then ", so a larger ceiling is the lever rather than less reasoning"
                         else ", so either a larger ceiling or less reasoning will move it")
                )
            ) +
            priorRejections.fold("")(detail => s"; earlier in this generation, $detail")
    ) with AIGenException with AIStreamException

/** The command harness itself malfunctioned: a nonzero exit, or output that is not a decodable harness
  * envelope (as opposed to [[AIDecodeException]], where the harness worked but the MODEL's reply could
  * not be decoded into the requested result). NOT transient: a broken harness produces the same bad
  * output on retry. Carries the raw `detail` for diagnosis. Either operation makes the call, so it is in
  * both failure sets.
  */
case class AIHarnessException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider harness malfunctioned: $detail") with AIGenException with AIStreamException

/** A streaming SSE delta was not a parseable event for the provider. */
case class AIStreamDeltaException(detail: String)(using Frame)
    extends AIException(s"malformed SSE delta: $detail") with AIStreamException

/** The stream ended having buffered argument JSON but never yielded a decodable value. */
case class AIStreamIncompleteException(buffered: String)(using Frame)
    extends AIException(s"stream ended without a decodable value: $buffered") with AIStreamException

/** An `AI` was used inside a different `LLM.run` than the one that created it. Misuse: it panics rather than
  * riding a row, since an instance can only address the slots of its own run.
  */
case class AICrossRunException(id: Long)(using Frame)
    extends AIException(
        s"AI #$id was created by a different LLM.run and can't be used here. An AI's conversation lives " +
            "inside the run that created it; to carry it across runs, capture it with ai.snapshot and " +
            "restore it with AI.recover."
    )

/** The run's concurrency meter was closed under an in-flight generation (the run is being torn down).
  * Impossible under normal operation: it panics rather than riding a row.
  */
case class AIMeterClosedException()(using Frame)
    extends AIException("LLM meter is closed")

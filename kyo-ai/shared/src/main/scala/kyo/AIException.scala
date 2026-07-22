package kyo

/** The module's exception hierarchy, organized by the two operations that produce failures.
  *
  * The super-types track operations, the concrete leaves track specific failures, and a leaf mixes in every
  * operation it can occur in:
  *   - [[kyo.AIGenException]] is the failure set of a generation (`AI.gen` / `ai.gen`), the row of
  *     `LLM.run`'s residual: a generation raises its failures while the `Gen` op's eval loop runs, so they
  *     surface at the run boundary.
  *   - [[kyo.AIStreamException]] is the failure set of a stream (`AI.stream` / `ai.stream`), the row carried
  *     inside the returned `Stream`'s effect parameter: a stream raises its failures lazily as it is
  *     consumed, not at the run boundary.
  *
  * A failure shared by both operations (a missing API key, a transport error) mixes in both super-types, so
  * `Abort.fail` of that leaf type-checks against either row. Cross-run instance use and a closed meter are
  * misuse and impossible-state respectively: they panic and stay off both rows.
  */
sealed abstract class AIException(message: String, cause: String | Throwable = "")(using Frame)
    extends KyoException(message, cause)

/** The failures a generation (`AI.gen` / `ai.gen`) can produce; the row of `LLM.run`'s residual. */
sealed trait AIGenException extends AIException

/** The failures a stream (`AI.stream` / `ai.stream`) can produce; the row of the returned `Stream`. */
sealed trait AIStreamException extends AIException

/** No API key is configured for the model's provider. Either operation reaches the provider, so it is in
  * both failure sets.
  */
case class AIMissingApiKeyException(model: String)(using Frame)
    extends AIException(s"Can't locate API key for model: $model") with AIGenException with AIStreamException

/** The provider HTTP call failed; the originating kyo-http `HttpException` is carried as the cause. Mapped
  * from the raw transport error at the eval/stream boundary so no non-module exception rides a public row.
  * Either operation makes the call, so it is in both failure sets.
  */
case class AITransportException(cause: HttpException)(using Frame)
    extends AIException(cause.getMessage, cause) with AIGenException with AIStreamException

/** The eval loop ran twice the configured `maxIterations` without the model producing a result. */
case class AIEvalExhaustedException(maxIterations: Int)(using Frame)
    extends AIException(s"Eval loop exceeded twice the max of $maxIterations iterations without a result") with AIGenException

/** The model named a reasoning field (thought) that is not enabled for the generation. */
case class AIInvalidThoughtException(name: String)(using Frame)
    extends AIException(s"invalid thought: $name") with AIGenException

/** The model's reply could not be decoded into the requested result (an undecodable thought or result
  * envelope, or a response with no choices).
  */
case class AIDecodeException(detail: String)(using Frame)
    extends AIException(detail) with AIGenException

/** A command-backed provider could not be reached because its local account, quota, or network transport is
  * unavailable.
  */
case class AIProviderUnavailableException(provider: String, detail: String)(using Frame)
    extends AIException(s"$provider provider is unavailable: $detail") with AIGenException with AIStreamException

/** The forced path could not fit the transcript's unshrinkable roots (system, first/latest
  * user, unresolved pairs, recent tail) inside the model's hard window, so it fails loudly
  * rather than sending an over-limit request. The transcript loses nothing; only the view
  * omits. It surfaces on `Compactor.render`'s `Abort[AIGenException]` row.
  */
case class AIContextOverflowException(viewTokens: Int, modelMaxTokens: Int)(using Frame)
    extends AIException(
        s"projected view of $viewTokens tokens exceeds the model's hard window of $modelMaxTokens tokens " +
            "with no further demotable units"
    ) with AIGenException

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

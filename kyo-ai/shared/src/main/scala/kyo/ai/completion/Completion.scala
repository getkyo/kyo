package kyo.ai.completion

import java.time.Instant as JInstant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kyo.*
import kyo.Json.JsonSchema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

/** The provider completion-backend contract: turn a config + conversation + tool set into transcript messages.
  *
  * One `Completion` is the wire layer for one provider family: `apply` serializes the conversation and tool
  * definitions to the provider request DTO, posts it over kyo-http, and decodes the reply into `Message`
  * values. Transport failures surface as the typed `Abort[HttpException]`, a missing key or undecodable reply
  * as `Abort[AIGenException]` leaves; the eval loop classifies raw `HttpException`s via [[Completion.classifyHttp]]
  * so retry policy is uniform across families. `resultSchema`, when present, supplies the real parameter schema
  * for the dynamic `result_tool` (whose own `inputSchema` is the opaque `Structure.Value`). Backends are reached
  * through `Config.Provider`, not constructed by users.
  */
trait Completion:

    def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException])

    /** Provides raw JSON fragments for the `{ resultValue: ... }` envelope consumed by `LLM.stream`.
      *
      * HTTP providers implement this by posting their native SSE request and projecting tool-call argument
      * deltas. Harness providers implement it through their native event or stream-json output.
      */
    def streamFragments(
        config: Config,
        context: Context,
        resultSchema: JsonSchema,
        resultTool: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Stream[Completion.StreamElement, Async & Scope & Abort[AIStreamException]] < (LLM & Async & Abort[AIGenException])

    /** Whether this backend delivers a stream in pieces as the model produces it.
      *
      * `true` where the wire carries incremental deltas, which is every HTTP family (they stream over
      * SSE). `false` where the backend can only hand back a finished result, which `LLM.stream` then
      * emits in one go: the elements and their order are exactly the same, and nothing fails, but
      * time-to-first-token equals time-to-last-token.
      *
      * That difference is invisible from the stream itself, and it is the number a chat UI lives on, so
      * it is declared rather than discovered. Read it to decide whether to render progressively or show a
      * pending state:
      *
      * {{{
      * val incremental = config.provider.completion.streamsIncrementally
      * }}}
      */
    def streamsIncrementally: Boolean

end Completion

object Completion:

    /** The completion backend for every provider whose endpoint speaks the OpenAI-compatible request shape.
      * The concrete implementation is package-private; reach it through this accessor.
      */
    val openAI: Completion = OpenAICompletion

    /** The Anthropic Messages backend. */
    val anthropic: Completion = AnthropicCompletion

    /** The Claude Code CLI harness backend. */
    val claudeCode: Completion = ClaudeCodeCompletion

    /** The Codex CLI harness backend. */
    val codex: Completion = CodexCompletion

    /** The reserved name of the result tool. A backend matches a tool by this name to substitute the
      * thought-aware result envelope schema for the tool's opaque `Structure.Value` input schema.
      */
    private[kyo] val resultToolName = "result_tool"

    /** The prefix a converted system message carries, so its origin survives the role change. */
    private[kyo] val systemInstructionPrefix: String = "[system instruction]"

    /** Fits a context to a wire that delivers a single system instruction.
      *
      * The first system message keeps the role, absorbing the run of system messages immediately following
      * it. Every later one becomes a user turn carrying the same text behind a short prelude naming what it is.
      *
      * Converted IN PLACE rather than hoisted, because a system message is positional: it governs from where
      * it appears. Hoisting a later one to the front would make it govern the turns that preceded it, a
      * different instruction than the caller wrote. On a one-slot wire the alternative to a role change is not
      * "keeps its role" but "never arrives".
      */
    private[kyo] def fitSystemMessages(
        config: Config,
        messages: Chunk[Message],
        convert: String => Message
    ): Chunk[Message] =
        if config.systemInstructions == Config.SystemMessages.AllDelivered then messages
        else
            val firstSystem = messages.indexWhere:
                case SystemMessage(_) => true
                case _                => false
            if firstSystem < 0 then messages
            else
                // The run at the first system message merges into it: contiguous instructions carry no
                // position between them to preserve.
                var leadingEnd = firstSystem
                while leadingEnd + 1 < messages.size && messages(leadingEnd + 1).isInstanceOf[SystemMessage] do
                    leadingEnd += 1
                val merged = messages
                    .slice(firstSystem, leadingEnd + 1)
                    .collect { case SystemMessage(content) => content }
                    .mkString("\n\n")
                messages.zipWithIndex.flatMap { (message, index) =>
                    if index == firstSystem then Chunk(SystemMessage(merged))
                    else if index > firstSystem && index <= leadingEnd then Chunk.empty
                    else
                        message match
                            case SystemMessage(content) => Chunk(convert(content))
                            case other                  => Chunk(other)
                }
            end if
    end fitSystemMessages

    /** Caps a logged body at a fixed length, marking the true length. A vision request body is megabytes
      * of base64; the diagnostic value is the structure, not the bytes.
      */
    private[kyo] def elideBody(text: String): String =
        if text.length <= 8192 then text
        else s"${text.take(8192)} [truncated from ${text.length} chars]"

    /** Classifies a raw kyo-http failure into the module's typed leaves, so retry policy is decided by
      * type and is uniform across backend families (the command harnesses produce the same leaves through
      * their own classification). The per-status mapping is below.
      */
    private[kyo] def classifyHttp(config: Config, e: HttpException)(using Frame): AIGenException & AIStreamException =
        e match
            // An endpoint that refuses a tool call it judges invalid reports it as a 400 with its own code
            // in the body. Matched, this leaf carries the failure to the eval loop, which spends a turn on
            // the correction just as for an endpoint that returns the malformed call. FAIL CLOSED: any
            // doubt (no declaration, absent/undecodable body, absent/different code) stays an ordinary
            // rejected request, so a genuinely bad request is never respun as repairable.
            case e: HttpStatusException if e.status.code == 400 && rejectedToolCall(config, e) =>
                AIToolCallRejectedException(config.provider.name, e.getMessage)
            case e => classifyHttp(config.provider.name, e)

    /** The status mapping every HTTP wire shares, keyed by the provider name that the leaves report. The
      * decider's TypeSafe wire classifies through this form: it has no completion config and no tool
      * calls, so the rejected-tool-call reading above never applies to it. A raw exception carries no
      * headers, so a rate limit classified here has no `retryAfter`; a wire that has the response in hand
      * classifies through [[statusFailure]] instead.
      */
    private[kyo] def classifyHttp(provider: String, e: HttpException)(using Frame): AIGenException & AIStreamException =
        e match
            case e: HttpTimeoutException => AICompletionTimeoutException(provider, e.duration)
            case e: HttpStatusException  => classifyStatus(provider, e.status.code, e.getMessage, Absent)
            case e                       => AITransportException(e)
        end match
    end classifyHttp

    /** A non-2xx response as the module's leaf, with the response in hand: the body keeps its request id
      * (`prefix`, put ahead of the body so a long rejection cannot push it out of the message) and a rate
      * limit keeps its `Retry-After`. A wire reads the response with `failOnError = false` and raises
      * this in place of the client's own status exception.
      */
    private[kyo] def statusFailure(
        provider: String,
        method: String,
        url: String,
        response: HttpResponse["body" ~ String],
        prefix: String
    )(using Frame): (AIGenException & AIStreamException) < Sync =
        val message = HttpStatusException(response.status, method, url, prefix + response.fields.body).getMessage
        Clock.now.map(now => classifyStatus(provider, response.status.code, message, retryAfterOf(response.headers, now)))
    end statusFailure

    /** Posts a completion request and returns the response body; a non-2xx response is classified with
      * its headers in hand, the rejected-tool-call reading of a 400 included, so every HTTP completion
      * backend shares one status path and one `Retry-After` reading.
      */
    private[kyo] def post(config: Config, url: String, body: String, headers: Seq[(String, String)])(using
        Frame
    ): String < (Async & Abort[HttpException | AIGenException]) =
        HttpClient.postTextResponse(url, body, headers, failOnError = false).map { response =>
            if response.status.isSuccess then response.fields.body
            else
                val status = HttpStatusException(response.status, "POST", url, response.fields.body)
                if response.status.code == 400 && rejectedToolCall(config, status) then
                    Abort.fail(AIToolCallRejectedException(config.provider.name, status.getMessage))
                else statusFailure(config.provider.name, "POST", url, response, "").map(Abort.fail(_))
        }

    // 408 (the server gave up waiting for the request) and 429 are transient like 5xx; the vendor SDKs
    // retry exactly this set.
    private def classifyStatus(provider: String, code: Int, message: String, retryAfter: Maybe[Duration])(using
        Frame
    ): AIGenException & AIStreamException =
        code match
            case 401 | 403     => AIProviderAuthException(provider, message)
            case 408           => AIProviderUnavailableException(provider, message)
            case 429           => AIRateLimitException(provider, message, retryAfter)
            case c if c >= 500 => AIProviderUnavailableException(provider, message)
            case c             => AIRequestRejectedException(provider, c, message)

    /** The wait a rate-limited response asks for: `retry-after-ms`, else `retry-after` as delta-seconds or
      * as an HTTP-date (read against the response's own `date` header when it has one, so clock skew
      * cancels; else against `now`). Absent when neither header parses.
      */
    private[kyo] def retryAfterOf(headers: HttpHeaders, now: Instant): Maybe[Duration] =
        def millis(s: String): Maybe[Duration] =
            Maybe.fromOption(s.trim.toLongOption).filter(_ >= 0).map(_.millis)
        def seconds(s: String): Maybe[Duration] =
            Maybe.fromOption(s.trim.toLongOption).filter(_ >= 0).map(_.seconds)
        def httpDate(s: String): Maybe[Instant] =
            Result.catching[DateTimeParseException](
                Instant.fromJava(JInstant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(s.trim)))
            ).toMaybe
        def until(at: Instant): Duration =
            val reference = headers.get("date").flatMap(httpDate).getOrElse(now)
            if at <= reference then Duration.Zero else at - reference
        headers.get("retry-after-ms").flatMap(millis)
            .orElse(headers.get("retry-after").flatMap(v => seconds(v).orElse(httpDate(v).map(until))))
    end retryAfterOf

    /** Waits out a rate limit's `Retry-After` before re-raising it, so the retry schedule's next attempt
      * lands at or after the moment the server asked for. Bounded by the deadline the caller runs under:
      * a wait longer than `timeout` is cut to it, and the deadline then decides. Sits between the
      * classification and the retry clause in every HTTP wire's handler chain.
      */
    private[kyo] def awaitRetryAfter[A, S](timeout: Duration)(v: A < (S & Abort[AIGenException]))(using
        Frame
    ): A < (S & Async & Abort[AIGenException]) =
        Abort.recover[AIGenException] {
            case e @ AIRateLimitException(_, _, Present(wait)) => Async.sleep(wait.min(timeout)).andThen(Abort.fail(e))
            case e                                             => Abort.fail(e)
        }(v)

    private case class ErrorDetail(
        code: Maybe[String] = Absent,
        message: Maybe[String] = Absent,
        status_code: Maybe[Int] = Absent
    ) derives Schema
    private case class ErrorBody(error: Maybe[ErrorDetail] = Absent) derives Schema

    /** True only when the entry declares a rejection code and the 400's body carries exactly it. Every
      * other outcome is false, so classifyHttp keeps the ordinary rejected-request reading.
      */
    private def rejectedToolCall(config: Config, e: HttpStatusException)(using Frame): Boolean =
        config.invalidToolCalls match
            case Config.InvalidToolCalls.Rejected(code) =>
                e.body match
                    case Present(body) =>
                        Json.decode[ErrorBody](body).toMaybe.flatMap(_.error).flatMap(_.code).contains(code)
                    case Absent => false
            case Config.InvalidToolCalls.Returned => false

    /** Classifies a provider error delivered inside a 200 SSE stream, where `classifyHttp` never runs. An
      * endpoint can enforce a forced tool choice by ending the stream with an `{"error":{...}}` event instead
      * of a 400; left unread it decodes as an all-absent chunk the delta parser skips, so the stream ends with
      * an empty buffer that hides the message behind an opaque incomplete failure. Returns the typed leaf to
      * fail with, or `Absent` for an ordinary delta. Fails closed on the tool-call reading exactly as
      * classifyHttp does (rejection leaf only when the entry declares a code and the body carries exactly it);
      * any other error is classified by its status, so no provider error is silently skipped.
      */
    private[kyo] def classifyStreamError(config: Config, data: String)(using Frame): Maybe[AIStreamException] =
        Json.decode[ErrorBody](data).toMaybe.flatMap(_.error).map { detail =>
            val provider = config.provider.name
            val message  = detail.message.getOrElse(data)
            val rejected = config.invalidToolCalls match
                case Config.InvalidToolCalls.Rejected(code) => detail.code.contains(code)
                case Config.InvalidToolCalls.Returned       => false
            val exc: AIStreamException =
                if rejected then AIToolCallRejectedException(provider, message)
                else
                    detail.status_code match
                        case Present(401) | Present(403)      => AIProviderAuthException(provider, message)
                        case Present(429)                     => AIRateLimitException(provider, message)
                        case Present(status) if status >= 500 => AIProviderUnavailableException(provider, message)
                        case Present(status)                  => AIRequestRejectedException(provider, status, message)
                        case Absent                           => AIRequestRejectedException(provider, 0, message)
            exc
        }

    /** What a turn produced, together with how the wire said it ended and what it spent.
      *
      * The stop reason travels with the messages because deciding what a ceiling stop MEANS needs both
      * halves: a backend knows the wire said "stopped at the ceiling" but must not inspect a tool payload
      * (that belongs to the tool loop), and the loop cannot see the wire. Carrying the reason lets the policy
      * be decided once, with both facts in hand.
      *
      * `usage` travels for the same reason and is the record each enabled [[kyo.Observe]] receives: the
      * wire is the only place the numbers exist, and at a ceiling stop the reasoning subset separates the
      * two levers (a stop that spent most of its allowance reasoning needs less reasoning; one that barely
      * reasoned needs a larger ceiling). A synthetic reply that reached no model carries [[AIStats.empty]].
      * Fields may grow with the wire contract; consumers should read fields, not match exhaustively.
      */
    case class Reply(messages: Chunk[Message], stopReason: StopReason, usage: AIStats = AIStats.empty)

    /** Why a provider stopped a reply, decoded once at each wire boundary from that provider's own
      * vocabulary so no decision elsewhere reads a raw wire string.
      *
      * Only [[StopReason.MaxOutputTokens]] changes behavior. [[StopReason.Other]] keeps the
      * unrecognized value for diagnosis and behaves like [[StopReason.Completed]]: vocabulary a
      * provider adds later must never fail a reply whose content is perfectly usable, and any consumer
      * (an [[kyo.Observe]] included) must treat it the same way.
      */
    enum StopReason derives CanEqual:
        case Completed
        case MaxOutputTokens
        case Other(raw: String)
    end StopReason

    /** One element of a streaming completion: a fragment of the result envelope's argument JSON, or a
      * usage report. Usage elements are PARTIAL and disjoint by construction (a wire may report the
      * input and output sides in separate events); the consumers sum them, and the record site forces
      * `turns = 1` on the sum, so a wire that emitted no usage element still reports the structural
      * fact that a fully consumed stream is one turn.
      */
    enum StreamElement derives CanEqual:
        case Fragment(value: String)
        case Usage(stats: AIStats)
    end StreamElement

    /** One parsed streaming event's contribution to the element stream: a fragment to emit, a usage
      * report, nothing to emit, or the provider reporting that it stopped at the output ceiling.
      */
    private[completion] enum Delta derives CanEqual:
        case Fragment(value: String)
        case Usage(stats: AIStats)
        case Skip
        case OutputLimit
    end Delta

    /** The provider-specific pieces of a streaming completion request: endpoint URL, headers, and body. */
    case class StreamRequest(url: String, headers: Seq[(String, String)], body: String)

    /** Bridges a provider's SSE response into the module's fragment stream under the call's deadline.
      *
      * The request runs in a producer fiber that owns the connection and puts decoded fragments on a bounded
      * carrier the returned stream drains. The deadline bounds the provider's PRODUCTION, not the consumer's
      * wall-clock: a slow consumer still receives the whole stream, while a provider that stops producing
      * mid-stream fails with `AICompletionTimeoutException` instead of hanging. Every failure rides the carrier
      * as a `Result` and is re-raised consumer-side, so a typed leaf stays typed across the fiber boundary. A
      * streaming call is not retried: fragments already delivered cannot be un-emitted.
      */
    private[completion] def sseFragments(
        config: Config,
        request: StreamRequest < Abort[AIStreamException],
        parseDeltaArguments: String => Result[String, Delta],
        outputLimit: Maybe[Int]
    )(using Frame): Stream[StreamElement, Async & Scope & Abort[AIStreamException]] < Async =
        given sseTag: Tag[Emit[Chunk[HttpSseEvent[String]]]] = Tag[Emit[Chunk[HttpSseEvent[String]]]]
        val route                                            = HttpRoute.postRaw("").request(_.bodyText).response(_.bodySseText)
        Stream.unwrap {
            for
                channel <- Channel.init[Result[AIStreamException, Maybe[Chunk[StreamElement]]]](sseBufferSize)
                // Releasing the consumer's scope interrupts the producer. The deadline's own inner fiber is
                // unscoped, so an abandoned connection can linger until the remaining deadline elapses,
                // bounded by config.timeout.
                _ <- Scope.acquireRelease(
                    Fiber.initUnscoped {
                        Abort.run[AIStreamException] {
                            Async.timeoutWithError[AIStreamException, Unit, Any](
                                config.timeout,
                                Result.Failure(AICompletionTimeoutException(config.provider.name, config.timeout))
                            ) {
                                Scope.run {
                                    for
                                        req <- request
                                        // Trace, not debug: the body carries the conversation. Pairs with the
                                        // stream-event and stream-end traces so an SSE turn reads back from the
                                        // log like fetch's request/reply.
                                        _ <- Log.trace(
                                            s"kyo-ai stream request ${config.provider.name} ${req.url} ${elideBody(req.body)}"
                                        )
                                        sseStream <- Abort.recover[HttpException](e =>
                                            Abort.fail(classifyHttp(config, e))
                                        ) {
                                            for
                                                baseReq <- Abort.get(HttpRequest.postRaw(req.url))
                                                httpRequest = req.headers.foldLeft(baseReq)((r, kv) =>
                                                    r.addHeader(kv._1, kv._2)
                                                ).addField("body", req.body)
                                                sseStream <- HttpClient.withConfig(_.timeout(config.timeout)) {
                                                    HttpClient.use { client =>
                                                        client.sendWith(route, httpRequest) { response =>
                                                            if response.status.isSuccess then response.fields.body
                                                            else
                                                                Abort.fail(HttpStatusException(
                                                                    response.status,
                                                                    "POST",
                                                                    req.url,
                                                                    response.rawBody.getOrElse("")
                                                                ))
                                                        }
                                                    }
                                                }
                                            yield sseStream
                                        }
                                        _ <- Abort.recover[Closed](_ => ()) {
                                            sseStream.map { event =>
                                                // Trace, not debug: the raw SSE payload shows whether a turn
                                                // emitted a tool-call delta or, as some providers do under a
                                                // forced tool choice, only reasoning/content deltas that leave
                                                // the result buffer empty and fail the generation.
                                                Log.trace(s"kyo-ai stream event ${config.provider.name} ${elideBody(event.data)}").andThen {
                                                    // An empty event carries no element. A stream may hold the
                                                    // connection open with a keepalive between fragments; handing
                                                    // that to a decoder expecting a chunk fails a healthy generation.
                                                    if event.data.trim.isEmpty then Maybe.empty[StreamElement]
                                                    else
                                                        // A provider can end a 200 stream with an error event instead
                                                        // of an HTTP status; surface it typed rather than let the delta
                                                        // parser skip it into an empty buffer. The substring guard
                                                        // keeps the decode off the hot path for ordinary deltas.
                                                        val streamError =
                                                            if event.data.contains("\"error\"") then classifyStreamError(config, event.data)
                                                            else Absent
                                                        streamError match
                                                            case Present(exc) => Abort.fail(exc)
                                                            case Absent       =>
                                                                parseDeltaArguments(event.data) match
                                                                    case Result.Success(Delta.Fragment(fragment)) =>
                                                                        Present(StreamElement.Fragment(fragment))
                                                                    case Result.Success(Delta.Usage(stats)) =>
                                                                        Present(StreamElement.Usage(stats))
                                                                    case Result.Success(Delta.Skip)        => Maybe.empty[StreamElement]
                                                                    case Result.Success(Delta.OutputLimit) =>
                                                                        Abort.fail(AIOutputLimitException(
                                                                            config.provider.name,
                                                                            config.modelName,
                                                                            outputLimit
                                                                        ))
                                                                    case Result.Failure(err) => Abort.fail(AIStreamDeltaException(err))
                                                        end match
                                                }
                                            }.filterPure(_.nonEmpty).mapPure(_.get)
                                                .foreachChunk(chunk => channel.put(Result.Success(Present(chunk))))
                                        }
                                    yield ()
                                }
                            }
                        }.map { outcome =>
                            Abort.recover[Closed](_ => ()) {
                                outcome match
                                    case Result.Success(_) =>
                                        // Trace, not debug: pairs the stream-request trace and marks the turn
                                        // fully produced. A completed stream with an empty result buffer carried
                                        // no tool-call fragment (see the event traces above).
                                        Log.trace(s"kyo-ai stream end ${config.provider.name} completed").andThen {
                                            channel.put(Result.Success(Absent))
                                        }
                                    case Result.Failure(err) =>
                                        Log.trace(s"kyo-ai stream end ${config.provider.name} failed: $err").andThen {
                                            channel.put(Result.Failure(err))
                                        }
                                    case panic @ Result.Panic(_) =>
                                        Log.trace(s"kyo-ai stream end ${config.provider.name} panicked: $panic").andThen {
                                            channel.put(panic)
                                        }
                            }
                        }
                    }
                )(_.interrupt)
            yield Stream {
                // A closed carrier means the producer is gone (its scope released), which ends the stream:
                // a producer that failed puts its typed failure on the carrier before closing.
                Abort.recover[Closed](_ => ()) {
                    Loop.foreach {
                        channel.take.map {
                            case Result.Success(Absent)     => Loop.done
                            case Result.Success(Present(c)) => Emit.valueWith(c)(Loop.continue)
                            case Result.Failure(err)        => Abort.fail(err)
                            case panic @ Result.Panic(_)    => Abort.get(panic)
                        }
                    }
                }
            }
        }
    end sseFragments

    /** Chunks a streaming call may run ahead of its consumer. A consumer further behind than this suspends
      * the producer, which spends the call's production budget.
      */
    private val sseBufferSize = 1024

end Completion

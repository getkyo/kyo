package kyo.ai.decider

import kyo.*
import kyo.Decider.internal.*
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.ai.completion.Completion

/** The decider backend for TypeSafe AI's System One endpoint (`POST /v1/systemone`): every question kind,
  * answered with calibrated probabilities in one request.
  *
  * The state is the conversation the glue resolved, as a chat log: one `{role, content}` record per
  * message, an assistant turn also carrying its `calls` and a tool turn its `callId`, so a decision about
  * an agent's conversation sees the tool use. A one-shot's context is its single user record, the JSON it
  * encodes to. An empty conversation sends `""` (the endpoint refuses a null or missing state). Images
  * do not reach the endpoint (it reads text only). Question ids are positional (`q1`, `q2`, ...); the
  * model never sees them.
  *
  * The transport is the generation loop's: the decider's timeout (else the config's) covers the call and
  * its retries, failures classify through `Completion.classifyHttp(provider.name, e)` into the module's
  * leaves, the decider's meter (else the config's) bounds concurrency, and transient leaves retry on the
  * decider's schedule (else the config's), waiting out a `Retry-After` first. A rejection carries the
  * endpoint's request id and its body (which names the offending field).
  */
private[kyo] object TypeSafeDecider extends Decider.Backend:

    private val requestIdHeader = "x-typesafe-request-id"

    def decide(config: Config, decider: DeciderConfig, context: Context, questions: Chunk[Question])(using
        Frame
    ): Reply < (LLM & Async & Abort[AIGenException]) =
        for
            key <- apiKey(decider)
            body = Json.encode(request(decider.modelName, stateOf(context), questions))
            _ <- Log.debug(
                s"kyo-ai decide backend=${decider.provider.name} model=${decider.modelName} " +
                    s"questions=${questions.size} messages=${context.messages.size}"
            )
            response <- call(config, decider, key, body)
            reply    <- Abort.get(decode(response, questions))
        yield reply
        end for
    end decide

    private def apiKey(decider: DeciderConfig)(using Frame): String < (Async & Abort[AIGenException]) =
        decider.apiKey match
            case Present(key) => key
            case Absent =>
                Config.read(decider.provider.keyName).map {
                    case Present(key) => key
                    case Absent       => Abort.fail(AIMissingApiKeyException(decider.modelName, decider.provider.keyName))
                }

    /** One message of the chat log the endpoint reads as the state. */
    private[kyo] case class Turn(
        role: String,
        content: String,
        calls: Maybe[Chunk[TurnCall]] = Absent,
        callId: Maybe[String] = Absent
    ) derives Schema

    /** A tool call on an assistant turn: its id, function and arguments (the JSON text they are). */
    private[kyo] case class TurnCall(id: String, function: String, arguments: String) derives Schema

    /** The conversation as the endpoint's `state`.
      *
      * A mapping rather than `Structure.encode(context)` for three reasons: a `UserMessage.image` is base64
      * that the endpoint cannot read and that alone exceeds the state's token limit; kyo-schema encodes the
      * `Message` sum as `{"UserMessage": {...}}` with nested `CallId` objects, kyo's own names where the
      * endpoint documents a `role`/`content` chat log; and `Call.providerExtra` is an opaque token for the
      * completion provider, noise to a judge.
      */
    private[kyo] def stateOf(context: Context)(using Frame): Structure.Value =
        if context.isEmpty then Structure.Value.Str("")
        else
            Structure.encode(context.messages.map {
                case AssistantMessage(content, calls) =>
                    val turnCalls = calls.map(call => TurnCall(call.id.id, call.function, call.arguments))
                    Turn("assistant", content, calls = if turnCalls.isEmpty then Absent else Present(turnCalls))
                case ToolMessage(callId, content) => Turn("tool", content, callId = Present(callId.id))
                case other                        => Turn(other.role.name, other.content)
            })

    private[kyo] def request(model: String, state: Structure.Value, questions: Chunk[Question]): Request =
        val wire = questions.zipWithIndex.foldLeft(OrderedDict.empty[String, WireQuestion]) { case (acc, (q, i)) =>
            acc.update(s"q${i + 1}", wireQuestion(q))
        }
        Request(model, state, wire)
    end request

    // One call under the generation loop's transport discipline: the deadline covers the retries, a
    // non-2xx response is classified into the module's leaves before the retry clause sees it (with the
    // request id ahead of the body, so a long rejection keeps it), a rate limit's Retry-After is waited
    // out under the deadline before the schedule's own backoff, and the meter bounds concurrency. The
    // decider's own timeout, meter and schedule apply when set, the config's otherwise.
    private def call(config: Config, decider: DeciderConfig, key: String, body: String)(using
        Frame
    ): String < (LLM & Async & Abort[AIGenException]) =
        val provider      = decider.provider.name
        val url           = s"${decider.apiUrl.stripSuffix("/")}/systemone"
        val headers       = Seq("content-type" -> "application/json", "Authorization" -> s"Bearer $key")
        val timeout       = decider.timeout.getOrElse(config.timeout)
        val meter         = decider.meter.getOrElse(config.meter)
        val retrySchedule = decider.retrySchedule.getOrElse(config.retrySchedule)
        Async.timeoutWithError[AIGenException, Result[AIGenException, String], LLM](
            timeout,
            Result.Failure(AICompletionTimeoutException(provider, timeout))
        ) {
            Abort.run[AIGenException] {
                HttpClient.withConfig(_.timeout(timeout)) {
                    Abort.run[Closed] {
                        HttpClient.postTextResponse(url, body, headers, failOnError = false).map { response =>
                            if response.status.isSuccess then (response.fields.body: String < (Sync & Abort[AIGenException]))
                            else
                                val requestId = response.headers.get(requestIdHeader).fold("")(id => s"[request id $id] ")
                                Completion.statusFailure(provider, "POST", url, response, requestId).map(Abort.fail(_))
                        }.handle(
                            Abort.recover[HttpException](e => Abort.fail(Completion.classifyHttp(provider, e)))(_),
                            meter.run,
                            Completion.awaitRetryAfter(timeout)(_),
                            Retry[AITransientException](retrySchedule)(_)
                        )
                    }.map {
                        case Result.Success(r) => r
                        case Result.Failure(_) => Abort.panic(AIMeterClosedException())
                        case Result.Panic(ex)  => Abort.panic(ex)
                    }
                }
            }
        }.map(Abort.get(_))
    end call

    private[kyo] def decode(body: String, questions: Chunk[Question])(using Frame): Result[AIGenException, Reply] =
        Json.decode[Response](body) match
            case Result.Success(response) =>
                val answers = questions.zipWithIndex.foldLeft(Result.succeed[AIGenException, Chunk[Answer]](Chunk.empty)) {
                    case (acc, (question, i)) =>
                        acc.flatMap { answers =>
                            val id = s"q${i + 1}"
                            response.answers.get(id) match
                                case Absent => Result.fail(AIDecodeException(s"no answer for question ${i + 1}"))
                                case Present(wire) =>
                                    if wire.`type` != question.kind then
                                        Result.fail(AIDecodeException(
                                            s"question ${i + 1} is a ${question.kind} but the answer is a ${wire.`type`}"
                                        ))
                                    else answer(question, wire, i + 1).map(answers.append)
                            end match
                        }
                }
                answers.map(as => Reply(as, AIStats(response.usage.input_tokens, Absent, response.usage.output_tokens, Absent, 1)))
            case Result.Failure(e) => Result.fail(AIDecodeException(s"undecodable decider response: ${e.getMessage}"))
            case Result.Panic(e)   => Result.fail(AIDecodeException(s"undecodable decider response: ${e.getMessage}"))

    private def answer(question: Question, wire: WireAnswer, position: Int)(using Frame): Result[AIGenException, Answer] =
        def missing(field: String) = Result.fail(AIDecodeException(s"answer ${position} carries no '$field'"))
        question match
            case _: Question.Noul =>
                wire.noul.fold(missing("noul"))(p => Result.succeed(Answer.Noul(p)))
            case _: Question.Choice =>
                (wire.choice, wire.confidence, wire.probabilities) match
                    case (Present(choice), Present(confidence), Present(probabilities)) =>
                        Result.succeed(Answer.Choice(choice, confidence, probabilities.toChunk))
                    case (Absent, _, _) => missing("choice")
                    case (_, Absent, _) => missing("confidence")
                    case _              => missing("probabilities")
            case Question.Score(_, levels) =>
                (wire.score, wire.confidence, wire.probabilities) match
                    case (Present(score), Present(confidence), Present(probabilities)) =>
                        // One probability per level, by index, each present.
                        (0 until levels.size).foldLeft(Result.succeed[AIGenException, Chunk[Double]](Chunk.empty)) { (acc, i) =>
                            acc.flatMap { ps =>
                                probabilities.get(i.toString) match
                                    case Present(p) => Result.succeed(ps.append(p))
                                    case Absent =>
                                        Result.fail(
                                            AIDecodeException(s"answer $position lacks a probability for one of ${levels.size} levels")
                                        )
                            }
                        }.map(ps => Answer.Score(score, confidence, ps))
                    case (Absent, _, _) => missing("score")
                    case (_, Absent, _) => missing("confidence")
                    case _              => missing("probabilities")
        end match
    end answer

    // Wire DTOs, field names verbatim; the question and answer DTOs are the shared ones in
    // Decider.internal, since a recorded decision uses the same shape. The maps are OrderedDict so the
    // request keeps the caller's option order. Unknown response fields (legend, future additions) are
    // ignored on decode.
    private[kyo] case class Request(model: String, state: Structure.Value, questions: OrderedDict[String, WireQuestion]) derives Schema
    private[kyo] case class Response(model: String, answers: OrderedDict[String, WireAnswer], usage: Usage) derives Schema
    private[kyo] case class Usage(input_tokens: Long, output_tokens: Long) derives Schema

end TypeSafeDecider

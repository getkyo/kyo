package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.Tool
import kyo.ai.*
import kyo.ai.Context.*

abstract private[completion] class HarnessCompletion(providerName: String) extends Completion:

    final def apply(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: Maybe[JsonSchema] = Absent
    )(using Frame): Completion.Reply < (LLM & Async & Abort[HttpException | AIGenException]) =
        resultSchema match
            case Absent => Abort.fail(AIDecodeException(s"$providerName completion requires a result schema"))
            // A command harness has no reply-level stop vocabulary: one learns it stopped at a ceiling only
            // from a failed invocation (raised directly on that path), the other cannot learn it at all. A
            // normal reply is reported as completed: whatever these harnesses hand back, they hand back whole.
            case Present(schema) =>
                run(config, context, tools, schema).map((messages, usage) =>
                    Completion.Reply(messages, Completion.StopReason.Completed, usage)
                )

    protected def run(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using Frame): (Chunk[Message], AIStats) < (LLM & Async & Abort[AIGenException])

    final protected def commandFailure(status: Maybe[Int], detail: String)(using Frame): AIGenException =
        HarnessCompletion.commandFailure(providerName, status, detail)

    final protected def streamFailure(status: Maybe[Int], detail: String)(using Frame): AIStreamException =
        HarnessCompletion.streamFailure(providerName, status, detail)

end HarnessCompletion

private[completion] object HarnessCompletion:

    /** Classifies a command-harness failure into a precise leaf from the STRUCTURED status the harness
      * surfaced, mapping status to leaf exactly as [[Completion.classifyHttp]] does, so retry policy matches
      * the HTTP backends. NO content or error string is parsed: the Claude Code path reads `api_error_status`
      * off the terminal result event and the Codex path reads its structured error notification, so the
      * coincidence hazards of scraping a status out of free text (a uuid fragment `401e`, a token count equal
      * to a status code) cannot arise by construction.
      *
      * `status` is the upstream provider HTTP status when the turn reached the provider and failed there;
      * `Absent` covers a failure with no provider status (a hard crash, a missing binary, an RPC protocol
      * error), a harness malfunction (distinct from a MODEL decode failure, which arises in `Thought.handle`).
      * Halt-vs-retry follows the leaf's exception trait (`AITransientException` retries; auth and harness do not).
      */
    private def classify(provider: String, status: Maybe[Int], detail: String)(using Frame): AIGenException & AIStreamException =
        status match
            case Present(429)                 => AIRateLimitException(provider, detail)
            case Present(401) | Present(403)  => AIProviderAuthException(provider, detail)
            case Present(code) if code >= 500 => AIProviderUnavailableException(provider, detail)
            case Present(code)                => AIRequestRejectedException(provider, code, detail)
            case Absent                       => AIHarnessException(provider, detail)
    end classify

    private[kyo] def commandFailure(provider: String, status: Maybe[Int], detail: String)(using Frame): AIGenException =
        classify(provider, status, detail)

    private[kyo] def streamFailure(provider: String, status: Maybe[Int], detail: String)(using Frame): AIStreamException =
        classify(provider, status, detail)

    /** The synthetic continuation request a command harness sends when the conversation body does not end on
      * a user message (a CLI turn acts only on user input, where the HTTP wires send the conversation as-is).
      * When the body ends on tool results the request names them explicitly: a bare "Continue." invites the
      * model to re-derive the original imperative ("call tool X first") and repeat a completed call instead of
      * consuming its recorded result. Both command wires call this, so the pair shares one continuation contract.
      */
    private[completion] def continuationRequest(body: Chunk[Message]): String =
        body.lastMaybe match
            case Present(_: ToolMessage) =>
                "Continue: complete the original request using the recorded tool results above; " +
                    "do not repeat completed tool calls."
            case _ => "Continue."

    /** The number of trailing SystemMessages: the floating reminders and the forced-turn finalize
      * directive the eval loop appends after the conversation body. Both command wires strip these
      * before choosing the continuation, so a directive appended after a tool round never masks the
      * tool-results trigger.
      */
    private[completion] def trailingSystemCount(messages: Chunk[Message]): Int =
        @scala.annotation.tailrec
        def loop(index: Int, count: Int): Int =
            if index < 0 then count
            else
                messages(index) match
                    case _: SystemMessage => loop(index - 1, count + 1)
                    case _                => count
        loop(messages.size - 1, 0)
    end trailingSystemCount

end HarnessCompletion

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
    )(using Frame): Completion.Result < (LLM & Async & Abort[HttpException | AIGenException]) =
        resultSchema match
            case Absent          => Abort.fail(AIDecodeException(s"$providerName completion requires a result schema"))
            case Present(schema) => run(config, context, tools, schema).map(msgs => Completion.Result(msgs, Absent))

    protected def run(
        config: Config,
        context: Context,
        tools: Chunk[Tool.internal.Info[?, ?, LLM]],
        resultSchema: JsonSchema
    )(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException])

    final protected def resultInstructions(context: Context)(using Frame): String =
        HarnessCompletion.resultInstructions(context)

    final protected def resultOutput(raw: String)(using Frame): Chunk[Message] < Abort[AIGenException] =
        HarnessCompletion.resultOutput(providerName, raw)

    final protected def commandFailure(detail: String)(using Frame): AIGenException =
        HarnessCompletion.commandFailure(providerName, detail)

    final protected def streamFailure(detail: String)(using Frame): AIStreamException =
        HarnessCompletion.streamFailure(providerName, detail)

end HarnessCompletion

private[completion] object HarnessCompletion:

    private[kyo] def resultInstructions(context: Context)(using Frame): String =
        context.messages.collect { case SystemMessage(content) => content }.mkString("\n\n")
    end resultInstructions

    private[kyo] def resultOutput(provider: String, raw: String)(using Frame): Chunk[Message] < Abort[AIGenException] =
        decodeResult(raw) match
            case Result.Success(value) =>
                Chunk(AssistantMessage(
                    "",
                    Chunk(Call(
                        CallId("harness-result"),
                        Completion.resultToolName,
                        Json.encode(resultArguments(value))
                    ))
                ))
            case Result.Failure(err) =>
                Abort.fail(AIDecodeException(s"$provider harness result is not valid JSON: $err\n$raw"))
            case Result.Panic(ex) =>
                Abort.panic(ex)
    end resultOutput

    private def decodeResult(raw: String)(using Frame): Result[Throwable, Structure.Value] =
        Json.decode[Structure.Value](raw) match
            case Result.Success(value) => Result.Success(value)
            case Result.Failure(_) =>
                val trimmed = raw.trim
                val index   = trimmed.indexOf('{')
                if index > 0 then Json.decode[Structure.Value](trimmed.drop(index))
                else Json.decode[Structure.Value](trimmed)
            case Result.Panic(ex) =>
                Result.Panic(ex)
    end decodeResult

    private def resultArguments(value: Structure.Value)(using Frame): Structure.Value =
        value match
            case Structure.Value.Record(fields) if fields.exists(_._1 == "resultValue") =>
                fields.collectFirst { case ("resultValue", Structure.Value.Str(raw)) => raw } match
                    case Some(raw) =>
                        decodeStringifiedResult(raw) match
                            case Result.Success(decoded @ Structure.Value.Record(decodedFields))
                                if decodedFields.exists(_._1 == "resultValue") =>
                                decoded
                            case Result.Success(decoded) =>
                                Structure.Value.Record(fields.map {
                                    case ("resultValue", _) => "resultValue" -> decoded
                                    case field              => field
                                })
                            case _ =>
                                value
                    case None =>
                        value
            case Structure.Value.Record(Chunk(("Valueresult", value))) =>
                Structure.Value.Record(Chunk("resultValue" -> value))
            case Structure.Value.Record(Chunk(("", value))) =>
                Structure.Value.Record(Chunk("resultValue" -> value))
            case _ => Structure.Value.Record(Chunk("resultValue" -> value))
    end resultArguments

    private def decodeStringifiedResult(raw: String)(using Frame): Result[Throwable, Structure.Value] =
        Json.decode[Structure.Value](raw) match
            case success @ Result.Success(_) => success
            case Result.Failure(_)           => Json.decode[Structure.Value](LLM.completePartialJson(raw))
            case Result.Panic(ex)            => Result.Panic(ex)
    end decodeStringifiedResult

    private[kyo] def commandFailure(provider: String, detail: String)(using Frame): AIGenException =
        val lower = detail.toLowerCase
        if lower.contains("not authenticated") ||
            lower.contains("login") ||
            lower.contains("quota") ||
            lower.contains("rate limit") ||
            lower.contains("credit balance") ||
            lower.contains("billing") ||
            lower.contains("429") ||
            lower.contains("529") ||
            lower.contains("401") ||
            lower.contains("403") ||
            lower.contains("overloaded") ||
            lower.contains("service unavailable") ||
            lower.contains("temporarily unavailable") ||
            lower.contains("network") ||
            lower.contains("could not resolve") ||
            lower.contains("connection") ||
            lower.contains("timeout") ||
            lower.contains("timed out")
        then AIProviderUnavailableException(provider, detail)
        else AIDecodeException(s"$provider harness failed: $detail")
        end if
    end commandFailure

    private[kyo] def streamFailure(provider: String, detail: String)(using Frame): AIStreamException =
        commandFailure(provider, detail) match
            case ex: AIProviderUnavailableException => ex
            case ex                                 => AIStreamDeltaException(ex.getMessage)
    end streamFailure

end HarnessCompletion

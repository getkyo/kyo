package kyo

import kyo.Schema
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.nowarn

/** A typed function the language model may invoke, with integrated prompting and automatic dispatch.
  *
  * A `Tool` pairs an input/output type (each with a `Schema` for wire codec and JSON-schema derivation)
  * with a run function and an optional tool-specific prompt. Enabling a tool (via `AI.enable` / `ai.enable`)
  * registers it in `LLM.State.env.tools` via LLM effect state operations; the active tools are surfaced to
  * the model as tool definitions, their calls are detected and dispatched, and results are returned to the
  * model. `aggregate` composes tools; `empty` is the no-tool aggregate.
  *
  * @tparam S
  *   the capability set the tool's run function requires
  */
sealed trait Tool[-S] extends AI.Enablement[S]:

    private[kyo] def infos: Chunk[Tool.internal.Info[?, ?, S]]

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.addTools(Chunk(this.asInstanceOf[Tool[Any]]))
    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.addTool(this.asInstanceOf[Tool[Any]])
end Tool

object Tool:

    import internal.*

    /** Declares whether a tool reads or writes the state its `compactionKey` names, so the
      * compactor can tell a re-read from a write when applying supersession.
      */
    enum Kind derives CanEqual:
        case Read, Write

    /** Builds a typed tool from an input type, output type, name, description, prompt, and run function.
      *
      * `kind` and `compactionKey` are the compaction-supersession metadata: a tool supplying a
      * `compactionKey` extractor opts into key-based supersession (a later same-key unit supersedes an
      * earlier one); the default `_ => Absent` is keyless (never supersedes, never superseded). `kind`
      * distinguishes a re-read from a write. Both default, so specifying either is optional.
      */
    def init[In](using
        Schema[In]
    )[Out: Schema, S](
        name: String,
        description: String = "",
        prompt: Prompt[S] = Prompt.empty,
        kind: Tool.Kind = Tool.Kind.Read,
        compactionKey: In => Maybe[String] = (_: In) => Absent
    )(
        run: In => Out < S
    )(using frame: Frame): Tool[S] =
        new Tool[S]:
            def infos = Chunk(Info(
                name,
                description,
                prompt,
                run,
                summon[Schema[In]],
                summon[Schema[Out]],
                frame,
                kind,
                compactionKey
            ))

    /** Combines tools into one. */
    def aggregate[S](tools: Tool[S]*): Tool[S] =
        new Tool[S]:
            def infos = Chunk.from(tools.flatMap(_.infos))

    /** The no-tool aggregate. */
    val empty: Tool[Any] = aggregate()

    private[kyo] object internal:

        /** The outcome of decoding a tool call's arguments and running the tool.
          *
          * `DecodeFailed` tags a failure to decode the caller's JSON arguments into the tool's input
          * type: the model supplied arguments that do not match the schema, so `handle` answers with a
          * schema-repair message. `Ran` carries the result of the tool's own run body, whether it
          * succeeded or failed with a thrown value; a failure originating inside user run code routes to
          * the generic tool-failure message and is never mistaken for malformed arguments, even when the
          * run body itself fails with a `DecodeException`.
          */
        enum RunOutcome:
            case DecodeFailed(error: Throwable)
            case Ran(result: Result[Throwable, String])

        case class Info[In, Out, -S](
            name: String,
            description: String,
            prompt: Prompt[LLM & S],
            run: In => Out < (LLM & S),
            inputSchema: Schema[In],
            outputSchema: Schema[Out],
            createdAt: Frame,
            kind: Tool.Kind = Tool.Kind.Read,
            compactionKey: In => Maybe[String] = (_: In) => Absent
        ):
            // Existential-closure decoders: each method closes over this Info's own In/Out with the
            // captured inputSchema/outputSchema, so callers decode/encode/run with NO Schema[Any] cast.
            def compactionKeyFor(arguments: String)(using Frame): Maybe[String] =
                Json.decode[In](arguments)(using summon, inputSchema, summon) match
                    case Result.Success(in) => compactionKey(in)
                    case _                  => Absent

            def inputJsonSchema: Json.JsonSchema = Json.jsonSchema(using inputSchema)

            def decodeAndRun(arguments: String)(using Frame): RunOutcome < (LLM & S) =
                Json.decode[In](arguments)(using summon, inputSchema, summon) match
                    case Result.Success(in) =>
                        // Contain ANY throw (not just NonFatal): run is user code; its failure must
                        // surface as a tool message, never escape the eval loop.
                        Abort.run[Throwable](run(in).map(out => Json.encode(out)(using outputSchema, summon)))
                            .map(RunOutcome.Ran(_))
                    case Result.Failure(e) => RunOutcome.DecodeFailed(e)
                    case Result.Panic(e)   => RunOutcome.DecodeFailed(e)
        end Info

        def infos(using Frame): Chunk[Info[?, ?, LLM]] < LLM =
            // State.tools holds Tool[Any]; Tool is contravariant, so Tool[Any] <: Tool[LLM] and .infos
            // flattens directly with no recast.
            LLM.env.map(_.tools.flatMap(_.infos))

        // The result tool's definition: the model calls "result_tool" with its structured result, and the
        // eval loop extracts the call's arguments directly (no capturing run, no ref). The run is a no-op,
        // kept only so the tool dispatch records a result_tool message in the conversation. The input is a
        // Structure.Value (heterogeneous record) so the wire parameter schema is the thought-aware envelope,
        // supplied separately at request build.
        def resultToolInfo(using frame: Frame): Tool[LLM] =
            val description =
                "Call this tool with the result. Do not make parallel calls to this tool in the same completion. " +
                    "Only the first invocation will be considered."
            val info = Info[Structure.Value, Unit, LLM](
                name = Completion.resultToolName,
                description = description,
                prompt = Prompt.empty,
                run = _ => (),
                inputSchema = summon[Schema[Structure.Value]],
                outputSchema = summon[Schema[Unit]],
                createdAt = frame
            )
            // kind/compactionKey take their Read/keyless defaults: the result tool never supersedes.
            new Tool[LLM]:
                def infos = Chunk(info)
        end resultToolInfo

        def handle(ai: AI, tools: Chunk[Info[?, ?, LLM]], calls: Chunk[Call])(using Frame): Unit < LLM =
            Kyo.foreachDiscard(calls) { call =>
                tools.filter(_.name == call.function).headMaybe match
                    case Absent =>
                        ai.updateContext(_.toolMessage(
                            call.id,
                            s"Tool '${call.function}' not found for call id: ${call.id}"
                        ))
                    case Present(tool) =>
                        val processingMessage = ToolMessage(call.id, s"Processing tool call: ${tool.name}")
                        ai.updateContext(_.add(processingMessage)).andThen {
                            tool.decodeAndRun(call.arguments).map {
                                case RunOutcome.DecodeFailed(error) =>
                                    ai.updateContext { ctx =>
                                        ctx.copy(messages =
                                            ctx.messages
                                                .filterNot(_ == processingMessage)
                                                .map {
                                                    case msg: AssistantMessage =>
                                                        msg.copy(calls = msg.calls.filterNot(_.id == call.id))
                                                    case other => other
                                                }
                                        ).systemMessage(
                                            s"Before calling '${tool.name}', carefully review its schema and provide arguments that match the expected format. " +
                                                s"Calling this tool is known to be difficult for LLMs and fail with the following error: $error. Make an extra effort " +
                                                s"to ensure the correctness of the argument json by paying close attention to its schema and required fields and making " +
                                                s"sure your tool calls won't produce the same error message. Do not omit required fields. Strictly follow the tool schema."
                                        )
                                    }
                                case RunOutcome.Ran(Result.Success(out)) =>
                                    ai.updateContext { ctx =>
                                        ctx.copy(messages = ctx.messages.map {
                                            case `processingMessage` => ToolMessage(call.id, out)
                                            case other               => other
                                        })
                                    }
                                case RunOutcome.Ran(failure) =>
                                    val text = p"""
                                        Tool '${tool.name}' failed:
                                        ${failure}
                                        Call ID: ${call.id.id}
                                    """
                                    ai.updateContext { ctx =>
                                        ctx.copy(messages = ctx.messages.map {
                                            case `processingMessage` => ToolMessage(call.id, text)
                                            case other               => other
                                        })
                                    }
                            }
                        }
            }

    end internal
end Tool

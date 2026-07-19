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
                compactionKey.asInstanceOf[Any => Maybe[String]]
            ))

    /** Combines tools into one. */
    def aggregate[S](tools: Tool[S]*): Tool[S] =
        new Tool[S]:
            def infos = Chunk.from(tools.flatMap(_.infos))

    /** The no-tool aggregate. */
    val empty: Tool[Any] = aggregate()

    private[kyo] object internal:

        case class Info[In, Out, -S](
            name: String,
            description: String,
            prompt: Prompt[LLM & S],
            run: In => Out < (LLM & S),
            inputSchema: Schema[In],
            outputSchema: Schema[Out],
            createdAt: Frame,
            kind: Tool.Kind = Tool.Kind.Read,
            compactionKey: Any => Maybe[String] = (_: Any) => Absent
        )

        def infos(using Frame): Chunk[Info[?, ?, LLM]] < LLM =
            // cast: State.tools holds Tool[Any]; re-widen to the LLM-erased Info the eval loop consumes.
            LLM.env.map(_.tools.asInstanceOf[Chunk[Tool[LLM]]].flatMap(_.infos))

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
                            // cast: tool is an existential Info[?, ?, LLM]; its captured inputSchema decodes the call
                            // arguments to that tool's own In, erased to Any here and fed straight to its own run.
                            Json.decode[Any](call.arguments)(using summon, tool.inputSchema.asInstanceOf[Schema[Any]], summon) match
                                case Result.Success(decoded) =>
                                    // Contain ANY throw (not just NonFatal): tool.run is user code; its
                                    // failure must surface as a tool message, never escape the eval loop.
                                    Abort.run[Throwable](
                                        // cast: bridge the existential tool's run/outputSchema to the Any-erased decoded
                                        // input; In/Out are the same erased type the inputSchema produced, so the run is total.
                                        tool.run.asInstanceOf[Any => Any < (LLM)](decoded).map(out =>
                                            Json.encode(out)(using tool.outputSchema.asInstanceOf[Schema[Any]], summon)
                                        )
                                    ).map {
                                        case Result.Success(out) => out
                                        case error =>
                                            p"""
                                                Tool '${tool.name}' failed:
                                                ${error}
                                                Call ID: ${call.id.id}
                                            """
                                    }.map { out =>
                                        ai.updateContext { ctx =>
                                            ctx.copy(messages = ctx.messages.map {
                                                case `processingMessage` => ToolMessage(call.id, out)
                                                case other               => other
                                            })
                                        }
                                    }
                                case error =>
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
                        }
            }

    end internal
end Tool

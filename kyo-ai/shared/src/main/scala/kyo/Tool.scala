package kyo

import kyo.Schema
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.nowarn

/** A typed function the language model may invoke, with integrated prompting and automatic dispatch.
  *
  * A `Tool` pairs an input/output type (each with a `Schema` for wire codec and JSON-schema derivation) with
  * a run function and an optional tool-specific prompt. Enabling one (via `AI.enable` / `ai.enable`) surfaces
  * it to the model as a tool definition; its calls are detected, dispatched, and their results returned to
  * the model. `aggregate` composes tools; `empty` is the no-tool aggregate.
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

    /** Builds a typed tool from an input type, output type, name, description, prompt, and run function. */
    def init[In](using
        Schema[In]
    )[Out: Schema, S](
        name: String,
        description: String = "",
        prompt: Prompt[S] = Prompt.empty
    )(
        run: In => Out < S
    )(using frame: Frame): Tool[S] =
        new Tool[S]:
            def infos = Chunk(Info(name, description, prompt, run, summon[Schema[In]], summon[Schema[Out]], frame))

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
            createdAt: Frame
        )

        def infos(using Frame): Chunk[Info[?, ?, LLM]] < LLM =
            // cast: State.tools holds Tool[Any]; re-widen to the LLM-erased Info the eval loop consumes.
            LLM.env.map(_.tools.asInstanceOf[Chunk[Tool[LLM]]].flatMap(_.infos))

        private val resultToolDescription =
            "Use this tool to return your final response in the requested structured format. You MUST " +
                "call this tool exactly once at the end of your response to provide the structured " +
                "output. Do not make parallel calls to this tool in the same completion; only the " +
                "first invocation will be considered."

        /** The typed result envelope the result tool declares: the wire's `{resultValue: A}`. Thought
          * groups, when enabled, ride alongside as the sibling fields of [[ThoughtfulEvalResult]].
          */
        case class EvalResult[A](resultValue: A)
        object EvalResult:
            given [A](using Schema[A]): Schema[EvalResult[A]] = Schema.derived

        /** The envelope with thought groups enabled: the group objects are heterogeneous per-thought
          * records, so they ride as open values and are dispatched by Thought internals inside the
          * result tool's run (still the tool loop). The result itself stays typed.
          */
        case class ThoughtfulEvalResult[A](
            openingThoughts: Maybe[Structure.Value] = Absent,
            resultValue: A,
            closingThoughts: Maybe[Structure.Value] = Absent
        )
        object ThoughtfulEvalResult:
            given [A](using Schema[A]): Schema[ThoughtfulEvalResult[A]] = Schema.derived

        /** The per-eval capture the result tool writes and genLoop reads: the first accepted value, plus
          * the rejection bookkeeping the exhaustion error reports.
          */
        final class ResultCapture[A](ref: AtomicRef[(Maybe[A], Int, Maybe[String])]):
            def value(using Frame): Maybe[A] < Sync = ref.get.map(_._1)

            /** True when this call captured (set-once: only the first invocation is considered). */
            def offer(a: A)(using Frame): Boolean < Sync =
                ref.getAndUpdate(s => if s._1.isEmpty then (Present(a), s._2, s._3) else s).map(_._1.isEmpty)
            def rejected(reason: String)(using Frame): Unit < Sync =
                ref.getAndUpdate(s => (s._1, s._2 + 1, Present(reason))).unit
            def rejections(using Frame): (Int, Maybe[String]) < Sync = ref.get.map(s => (s._2, s._3))
        end ResultCapture

        /** A REAL typed tool whose declared input schema is the result envelope, decoded once by the tool
          * loop like any other tool. Its run only interprets the decoded envelope: fire the thought hooks,
          * enforce conformance for an open-shape result, capture the value. Any rejection is raised so the
          * tool loop's standard error path feeds it back; nothing outside the tool loop touches the payload.
          * The wire parameter schema stays the thought-aware envelope supplied at request build.
          */
        def resultTool[A](thoughts: Chunk[Thought.internal.Info[?, ?]])(using
            schema: Schema[A],
            frame: Frame
        ): (Chunk[Info[?, ?, LLM]], ResultCapture[A]) < Sync =
            AtomicRef.init((Maybe.empty[A], 0, Maybe.empty[String])).map { ref =>
                val capture = ResultCapture[A](ref)
                def accept(resultValue: A): String < (LLM & Sync & Abort[AIGenException]) =
                    val conformed =
                        resultValue match
                            // An open-shape result (a shape-dynamic schema over Structure.Value) decodes
                            // through a passthrough codec, so the declared structure is enforced here, still
                            // inside the tool loop. Typed results were already enforced by the decode.
                            case v: Structure.Value =>
                                Structure.conform(v, schema.structure) match
                                    case Present(violation) =>
                                        val reason = s"result does not conform to the declared result schema: $violation"
                                        capture.rejected(reason).andThen(Abort.fail(AIDecodeException(reason)))
                                    case Absent => Kyo.unit
                            case _ => Kyo.unit
                    conformed.andThen(capture.offer(resultValue).map {
                        case true  => "Result received."
                        case false => "A result was already recorded; only the first invocation is considered."
                    })
                end accept
                val tool =
                    if thoughts.isEmpty then
                        Tool.init[EvalResult[A]](Completion.resultToolName, resultToolDescription) { envelope =>
                            accept(envelope.resultValue)
                        }
                    else
                        Tool.init[ThoughtfulEvalResult[A]](Completion.resultToolName, resultToolDescription) { envelope =>
                            Thought.internal.handleThoughtGroups(thoughts, envelope.openingThoughts, envelope.closingThoughts)
                                .andThen(accept(envelope.resultValue))
                        }
                // cast: erase the run's capability row to the LLM-erased Info the eval loop consumes,
                // matching the existing tool-dispatch discipline (the loop evaluates under Async).
                (tool.infos.asInstanceOf[Chunk[Info[?, ?, LLM]]], capture)
            }
        end resultTool

        /** Definition-only surface for contexts that list the result tool without dispatching it (session
          * context assembly, streaming): name, description, empty prompt. The wire parameter schema is
          * supplied separately at request build, so the placeholder input type never reaches a provider.
          */
        def resultToolDefinition(using frame: Frame): Tool[Any] =
            val info = Info[Unit, Unit, Any](
                name = Completion.resultToolName,
                description = resultToolDescription,
                prompt = Prompt.empty,
                run = _ => (),
                inputSchema = summon[Schema[Unit]],
                outputSchema = summon[Schema[Unit]],
                createdAt = frame
            )
            new Tool[Any]:
                def infos = Chunk(info)
        end resultToolDefinition

        /** Decodes a call's arguments, salvaging a complete value that carries surplus closing brackets.
          *
          * Tool arguments are model output, not a document: a strict whole-input decode once rejected
          * complete, schema-conforming payloads over one surplus bracket at the very end, and since every
          * rejection regenerates from scratch and runs longer, the retries crossed the model's output
          * ceiling and the run failed on a byte no caller can act on.
          *
          * The tolerance is deliberately narrow: only a suffix made entirely of whitespace and closing
          * brackets is dropped, and what was dropped is returned rather than hidden. Two values back to back
          * still fail, and a value trailed by prose still fails, since those say the model misunderstood the
          * format. Nothing is completed or rewritten, and the schema still binds in full.
          */
        private[kyo] def decodeArguments(arguments: String)(using Schema[Any], Frame): (Result[DecodeException, Any], Maybe[String]) =
            Json.decode[Any](arguments) match
                case ok @ Result.Success(_) => (ok, Absent)
                case failed =>
                    @scala.annotation.tailrec
                    def salvage(end: Int): (Result[DecodeException, Any], Maybe[String]) =
                        if end <= 0 then (failed, Absent)
                        else
                            val c = arguments.charAt(end - 1)
                            if c != '}' && c != ']' && !c.isWhitespace then (failed, Absent)
                            else
                                Json.decode[Any](arguments.substring(0, end - 1)) match
                                    case ok @ Result.Success(_) => (ok, Present(arguments.substring(end - 1)))
                                    case _                      => salvage(end - 1)
                            end if
                    salvage(arguments.length)

        /** The end of what the model actually sent, appended to a rejected-arguments message.
          *
          * A decode failure reports a byte offset the model cannot count through, and the payload is
          * removed from the conversation before the retry, so the model is asked to correct what it can no
          * longer see. Showing the tail costs little and is often the whole story, since the defects that
          * survive a long generation tend to live at its end (a bracket too many, a string left open, a
          * value cut off mid-token). Nothing is repaired or inferred; the model is shown its own bytes.
          */
        private def argumentTail(arguments: String): String =
            if arguments.isEmpty then ""
            else
                val tail = if arguments.length <= tailWindow then arguments else arguments.takeRight(tailWindow)
                s" Your arguments were ${arguments.length} characters and ended with: $tail"

        private val tailWindow = 240

        // Sync is carried so a payload accepted after dropping surplus bytes can say so in the log:
        // discarding part of what a model sent silently is the invisibility this loop removes.
        def handle(ai: AI, tools: Chunk[Info[?, ?, LLM]], calls: Chunk[Call])(using Frame): Unit < (LLM & Sync) =
            Kyo.foreachDiscard(calls) { call =>
                tools.filter(_.name == call.function).headMaybe match
                    case Absent =>
                        // Name what IS callable, not only what failed. A model that calls a tool the request
                        // never carried tends to repeat the same unavailable call until the budget is gone;
                        // the current set is the fact that lets it recover. Why the tool is absent is not
                        // asserted here (this path cannot know): it runs for a hallucinated name and a set
                        // the caller narrowed alike.
                        val available = if tools.isEmpty then "none" else tools.map(t => s"'${t.name}'").mkString(", ")
                        ai.updateContext(_.toolMessage(
                            call.id,
                            s"Tool '${call.function}' is not available. Available tools: $available"
                        ))
                    case Present(tool) =>
                        val processingMessage = ToolMessage(call.id, s"Processing tool call: ${tool.name}")
                        ai.updateContext(_.add(processingMessage)).andThen {
                            // cast: tool is an existential Info[?, ?, LLM]; its captured inputSchema decodes the call
                            // arguments to that tool's own In, erased to Any here and fed straight to its own run.
                            val (decodedArgs, surplus) =
                                decodeArguments(call.arguments)(using tool.inputSchema.asInstanceOf[Schema[Any]])
                            decodedArgs match
                                case Result.Success(decoded) =>
                                    // Salvaging is never silent: dropping bytes a model sent is the same
                                    // invisibility this loop exists to remove.
                                    Kyo.when(surplus.nonEmpty)(
                                        Log.warn(
                                            s"kyo-ai tool '${tool.name}' arguments carried a complete value followed by " +
                                                s"${surplus.map(_.length).getOrElse(0)} surplus closing byte(s) " +
                                                s"(${surplus.getOrElse("")}); the value was used and the excess dropped"
                                        )
                                    ).andThen(
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
                                    )
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
                                                s"sure your tool calls won't produce the same error message. Do not omit required fields. Strictly follow the tool schema." +
                                                argumentTail(call.arguments)
                                        )
                                    }
                            end match
                        }
            }

    end internal
end Tool

package kyo

import kyo.Json
import kyo.Json.JsonSchema
import kyo.Schema
import kyo.schema.doc
import scala.reflect.ClassTag

/** Structured self-prompting woven into the model's required output schema.
  *
  * A `Thought[A]` injects a typed reasoning field into the result schema: an `opening` thought precedes
  * `resultValue`, a `closing` thought follows it, so field ORDER frames the answer (opening reasoning, then
  * answer, then closing checks) and drives autoregressive generation. Each thought's `process` hook fires on
  * the decoded value after generation, for verification, metrics, or follow-up. Thought name is the type's
  * unqualified name via `Schema.structure.name`. No reasoning is woven in by default; enable a `Thought` (or
  * the built-in `Thought.reflective`, a `Reflect` opening and a `Check` closing) when you want one.
  *
  * @tparam S
  *   the capability set the process hook requires
  */
sealed trait Thought[-S] extends AI.Enablement[S]:

    private[kyo] def infos: Chunk[Thought.internal.Info[?, S]]

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.addThoughts(Chunk(this.asInstanceOf[Thought[Any]]))
    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.addThought(this.asInstanceOf[Thought[Any]])
end Thought

object Thought:

    import internal.*

    /** Whether a thought is woven before (Opening) or after (Closing) the result value. */
    enum Position derives CanEqual:
        case Opening, Closing

    def opening[A: Schema: ClassTag]: Thought[Any] = opening[A](_ => ())
    def closing[A: Schema: ClassTag]: Thought[Any] = closing[A](_ => ())

    def opening[A: Schema](using ClassTag[A])[S](process: A => Unit < (LLM & S)): Thought[S] =
        init[A](Position.Opening)(process)

    def closing[A: Schema](using ClassTag[A])[S](process: A => Unit < (LLM & S)): Thought[S] =
        init[A](Position.Closing)(process)

    def aggregate[S](thoughts: Thought[S]*): Thought[S] =
        new Thought[S]:
            def infos = Chunk.from(thoughts.flatMap(_.infos))

    /** The built-in reflective scaffold: a `Reflect` opening (the model states its understanding and commits
      * to following instructions) and a `Check` closing (a self-check). Not applied automatically; enable it
      * explicitly (`AI.enable(Thought.reflective)(...)`) when you want the reasoning scaffold.
      */
    def reflective: Thought[Any] = internal.reflective

    def init[A: Schema](position: Position)[S](process: A => Unit < (LLM & S))(
        using ClassTag[A]
    ): Thought[S] =
        new Thought[S]:
            def infos = Chunk(Info(summon[Schema[A]].structure.name, position, summon[Schema[A]], process))

    private[kyo] object internal:

        case class Info[A, -S](
            name: String,
            position: Position,
            schema: Schema[A],
            process: A => Unit < (LLM & S)
        )

        case class Reflect(
            @doc("Let me reflect if I understand my role, what I need to do, and how I'll proceed")
            understanding: String,
            @doc("I'll strictly follow the tool's json schema and system instructions")
            willFollow: Boolean = true
        ) derives Schema

        case class Check(
            @doc("I have strictly followed my role and all system instructions")
            followed: Boolean = true
        ) derives Schema

        val reflective: Thought[Any] =
            Thought.aggregate(Thought.opening[Reflect], Thought.closing[Check])

        def infos(using Frame): Chunk[Info[?, ?]] < LLM =
            LLM.env.map(e => e.thoughts.flatMap(_.infos))

        // Assemble the result-tool parameter schema as a JsonSchema.Obj from each thought's Json.jsonSchema,
        // in opening -> resultValue -> closing order. Assembling per-thought schemas directly preserves each
        // field's @doc description, which a Structure.Type.Product round-trip through fromStructure would drop.
        def resultJson[A](thoughts: Chunk[Info[?, ?]], resultSchema: JsonSchema): JsonSchema =
            val (opening, closing) = thoughts.partition(_.position == Position.Opening)
            def group(name: String, l: Chunk[Info[?, ?]]): JsonSchema =
                JsonSchema.Obj(
                    // cast: each info's existential schema renders its own thought type's jsonSchema; erasing to
                    // Schema[Any] is sound because jsonSchema reads only the schema's structure, not the value type.
                    properties = l.toList.map(t => (t.name, Json.jsonSchema(using t.schema.asInstanceOf[Schema[Any]]))),
                    required = l.toList.map(_.name)
                )
            // Omit an empty thought group entirely: with no opening (or closing) thoughts the envelope drops
            // that key, so a default no-thought generation carries just `resultValue`.
            val props =
                (if opening.nonEmpty then List("openingThoughts" -> group("OpeningThoughts", opening)) else Nil) :::
                    List("resultValue" -> resultSchema) :::
                    (if closing.nonEmpty then List("closingThoughts" -> group("ClosingThoughts", closing)) else Nil)
            JsonSchema.Obj(properties = props, required = props.map(_._1))
        end resultJson

        // Fire each opening/closing thought's process hook on its decoded group field; an unrecognized
        // thought name is an AIInvalidThoughtException, an undecodable field an AIDecodeException, both
        // aborting (a thought that will not decode is a reasoning-scaffold bug). Called from the result
        // tool's run: the group objects are the one heterogeneous part of the envelope (per-thought names
        // and schemas), so they ride as open values and are dispatched here; the result stays typed.
        def handleThoughtGroups(
            thoughts: Chunk[Info[?, ?]],
            opening: Maybe[Structure.Value],
            closing: Maybe[Structure.Value]
        )(using Frame): Unit < (LLM & Sync & Abort[AIGenException]) =
            def fieldsOf(group: Maybe[Structure.Value]): Chunk[(String, Structure.Value)] =
                group.collect { case Structure.Value.Record(fs) => fs }.getOrElse(Chunk.empty)
            Kyo.foreachDiscard(fieldsOf(opening).concat(fieldsOf(closing))) { (name, sub) =>
                thoughts.filter(_.name == name).headMaybe match
                    case Absent =>
                        Abort.fail(AIInvalidThoughtException(name))
                    case Present(info) =>
                        // cast: decode the sub-value with the matched thought's own existential schema and feed it to
                        // that same thought's process; In is the one erased type the schema produced, so the call is total.
                        Abort.run[DecodeException](Abort.get(Structure.decode(sub)(using
                            info.schema.asInstanceOf[Schema[Any]],
                            summon
                        ))).map {
                            case kyo.Result.Success(value) => info.asInstanceOf[Info[Any, Any]].process(value)
                            case _                         => Abort.fail(AIDecodeException(s"failed to decode thought: $name"))
                        }
            }
        end handleThoughtGroups

    end internal

end Thought

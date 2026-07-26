package kyo

import kyo.ai.Context
import scala.annotation.nowarn
import scala.language.implicitConversions

/** A composable instruction set with floating reminders, to maintain LLM instruction adherence.
  *
  * A `Prompt` splits guidance into primary instructions (at the context start) and reminders (floated at the
  * context end, right before generation, so long contexts do not push critical guidance out of attention).
  * `andThen` merges two prompts, deduplicating both lists via `.distinct`. Instructions are added as SEPARATE
  * system messages (not one concatenated block) so providers can cache individual blocks. The `p`
  * interpolator normalizes per-line leading whitespace and trims, for readable multi-line prompts in source.
  *
  * @tparam S
  *   the capability set the prompt's instructions and reminders require
  */
sealed trait Prompt[-S] extends AI.Enablement[S]:

    /** Merges this prompt with another, deduplicating instructions and reminders. */
    final def andThen[S2](other: Prompt[S2])(using Frame): Prompt[S & S2] =
        // cast: Prompt is contravariant in S, so the identical-prompt fast path widens S to S & S2 safely.
        if this.equals(other) then this.asInstanceOf[Prompt[S & S2]]
        else
            Prompt._init[S & S2](
                for
                    p0 <- prompts
                    p1 <- other.prompts
                yield p0.concat(p1).distinct,
                for
                    r0 <- reminders
                    r1 <- other.reminders
                yield r0.concat(r1).distinct
            )

    private[kyo] val prompts: Chunk[String] < (LLM & S)
    private[kyo] val reminders: Chunk[String] < (LLM & S)

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.addPrompt(this.asInstanceOf[Prompt[Any]])
    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.addPrompt(this.asInstanceOf[Prompt[Any]])

end Prompt

extension (sc: StringContext)
    /** Normalizes per-line leading whitespace (`\n\s+` -> `\n`) and trims, for readable multi-line prompts. */
    def p(args: Any*): String =
        sc.s(args*)
            .replaceAll("\n\\s+", "\n")
            .trim
end extension

object Prompt:

    import internal.*

    /** The empty prompt: no instructions, no reminders. */
    def empty: Prompt[Any] = Empty

    /** Builds a prompt from primary instructions and optional reminders. */
    inline def init[S](
        inline prompt: => String < (LLM & S),
        inline reminder: => String < (LLM & S) = ""
    )(using Frame): Prompt[S] =
        _init(prompt.map(Chunk(_)), reminder.map(r => if r.isEmpty then Chunk.empty[String] else Chunk(r)))

    @nowarn("msg=anonymous")
    private inline def _init[S](inline _prompts: => Chunk[String] < (LLM & S), inline _reminders: => Chunk[String] < (LLM & S))(using
        Frame
    ): Prompt[S] =
        new Prompt:
            val prompts   = _prompts
            val reminders = _reminders

    private[kyo] object internal:

        // Frame.internal is required for val-init sites inside the kyo package: the Frame macro cannot
        // auto-derive a Frame here (synthesized boundary, not a user call site).
        private given Frame = Frame.internal

        case object Empty extends Prompt[Any]:
            val prompts   = Chunk.empty
            val reminders = Chunk.empty
        end Empty

        def enrichedContext(context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]])(using Frame): Context < LLM =
            LLM.env.map(e => enrichedContext(e.prompt, context, tools))

        def enrichedContext(p: Prompt[Any], context: Context, tools: Chunk[Tool.internal.Info[?, ?, LLM]])(using
            Frame
        ): Context < LLM =
            for
                prompts       <- p.prompts
                reminders     <- p.reminders
                toolPrompts   <- Kyo.foreach(tools)(t => t.prompt.prompts.map(ps => (t.name, t.description, ps)))
                toolReminders <- Kyo.foreach(tools)(t => t.prompt.reminders.map(rs => (t.name, t.description, rs)))
            yield
                val mainPromptMessages = prompts
                val toolPromptMessages = toolPrompts.flatMap { case (toolName, toolDesc, toolPromptChunk) =>
                    val header      = s"================== TOOL: $toolName ==================\n\n"
                    val description = if toolDesc.nonEmpty then s"DESCRIPTION: $toolDesc\n\n" else ""
                    toolPromptChunk.map(prompt => s"$header$description$prompt")
                }
                val mainReminderMessages =
                    if reminders.nonEmpty then
                        val reminderHeader  = "================== REMINDERS ==================\n\n"
                        val reminderContent = reminders.mkString("\n\n--------------------------------------------------\n\n")
                        Chunk(s"$reminderHeader$reminderContent")
                    else
                        Chunk.empty
                val toolReminderMessages = toolReminders.flatMap { case (toolName, toolDesc, toolReminderChunk) =>
                    if toolReminderChunk.nonEmpty then
                        val header      = s"================== TOOL REMINDER: $toolName ==================\n\n"
                        val description = if toolDesc.nonEmpty then s"DESCRIPTION: $toolDesc\n\n" else ""
                        val content     = toolReminderChunk.mkString("\n\n--------------------------------------------------\n\n")
                        Chunk(s"$header$description$content")
                    else
                        Chunk.empty
                }
                val allPromptMessages   = mainPromptMessages ++ toolPromptMessages
                val allReminderMessages = mainReminderMessages ++ toolReminderMessages
                val contextWithPrompts  = allPromptMessages.foldLeft(Context.empty)((ctx, msg) => ctx.systemMessage(msg))
                val mergedContext       = contextWithPrompts.merge(context)
                val finalContext        = allReminderMessages.foldLeft(mergedContext)((ctx, msg) => ctx.systemMessage(msg))
                finalContext
            end for
        end enrichedContext

    end internal

end Prompt

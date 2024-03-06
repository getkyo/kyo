package kyo.llm.modes

import kyo.*
import kyo.llm.*
import kyo.llm.completions.*

case class Cooldown(hot: Double = 1, cold: Double = 0, parallelism: Int = 3) extends Mode:

    def apply(ai: AI)(next: AI => Completion < AIs): Completion < AIs =
        AIs.ephemeral {
            AIs.parallel(List.fill(parallelism)(Configs.let(_.temperature(hot))(next(ai))))
        }.map { hot =>
            ai.systemMessage(
                p"""
            Cooldown Mode
            =============
            This prompt initiates the 'Cooldown Mode'. The following are potential completions generated 
            at a higher temperature setting. Analyze these responses for their common themes and elements. Your task, at 
            zero temperature, is to synthesize these varied inputs, focusing on their shared aspects to minimize the 
            likelihood of inaccuracies or hallucinations. Use this analysis to construct a refined, coherent, and 
            enhanced output.

            High-temperature Completions
            ============================
            ${hot.mkString("\n\n")}
          """
            ).andThen {
                Configs.let(_.temperature(cold))(next(ai))
            }
        }
end Cooldown

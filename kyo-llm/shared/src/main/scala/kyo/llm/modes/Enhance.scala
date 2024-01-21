package kyo.llm.modes

import kyo._
import kyo.llm._

case class Enhance(iterations: Int = 1) extends Mode {
  def apply(ai: AI)(next: AI => Completion < AIs): Completion < AIs = {
    def loop(l: List[Completion], iterations: Int): Completion < AIs =
      if (iterations == 0) {
        l.last
      } else {
        AIs.ephemeral {
          ai.systemMessage(
              p"""
                  Enhance Loop
                  ============
                  This is part of the ongoing 'Enhance Loop Mechanism'. You have previously generated one or 
                  more responses, which are now subject to successive refinement and improvement. In this next 
                  iteration, carefully examine all previous outputs. Identify and rectify any inaccuracies, 
                  inconsistencies, or potential hallucinations from these responses. Focus on enhancing the 
                  clarity, coherence, and depth of the information, ensuring each iteration aligns more closely 
                  with the query's intent and factual accuracy. Use this comprehensive analysis to produce an 
                  even more polished and accurate completion than the previous iterations.

                  Previous Completions
                  ====================
                  ${l.mkString("\n\n")}
                """
          ).andThen(next(ai))
        }.map(c => loop(c :: l, iterations - 1))
      }
    AIs.ephemeral(next(ai))
      .map(c => loop(c :: Nil, iterations))
      .map { c =>
        ai.assistantMessage(c.content, c.calls).andThen(c)
      }
  }
}

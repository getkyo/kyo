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
                  Enhance Mode
                  ============
                  This is the 'Enhance Mode' in continuous operation. You have generated multiple 
                  responses in previous iterations, which now serve as a foundation for further refinement. 
                  In this next iteration, your task is to meticulously analyze all prior outputs, extracting 
                  and synthesizing as much relevant information as possible. Identify any inaccuracies, 
                  inconsistencies, or potential hallucinations, and address them. Focus on enhancing the 
                  clarity, coherence, and informational depth, ensuring each iteration not only aligns more 
                  closely with the query's intent and factual accuracy but also builds upon the accumulated 
                  knowledge from previous responses. Use this comprehensive analysis to produce a completion 
                  that is more polished, accurate, and informative than all previous iterations.

                  Previous Completions
                  ====================
                  ${l.mkString("\n\n")}
                """
          ).andThen(next(ai))
        }.map(c => loop(c :: l, iterations - 1))
      }
    AIs.ephemeral(next(ai))
      .map(c => loop(c :: Nil, iterations))
  }
}

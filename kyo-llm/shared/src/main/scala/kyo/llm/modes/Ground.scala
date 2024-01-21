package kyo.llm.modes

import kyo._
import kyo.llm._

object Ground extends Mode {
  def apply(ai: AI)(next: AI => Completion < AIs): Completion < AIs =
    AIs.ephemeral(next(ai)).map { c =>
      ai.systemMessage(
          p"""
            Ground Mode
            ===========
            Initiate the 'Ground Mode'. Your task is to critically review the initial completion. 
            Scrutinize it for any elements that may be considered hallucinations - these include inaccuracies, 
            speculations, unverified information, or deviations from established facts. Identify these elements 
            and correct them to ensure the response is factually accurate, coherent, and strictly adheres to known 
            and verifiable information. Use this process to produce a response that is clear, precise, and devoid 
            of any misleading or false content.

            Initial completion
            ==================
            $c
          """
      ).andThen(next(ai))
    }
}

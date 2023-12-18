package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
    The Synthesis thought combines disparate elements into a coherent whole.
    - Integrates information for unified understanding.
    - Develops hypotheses based on integration.
    - Creates comprehensive summaries.
    - Relevant techniques: Idea Integration, Summary Creation.
    """
)
case class Synthesis(
    `Integrate different pieces of information to form a unified understanding`: String,
    `Develop hypotheses or theories based on the integrated information`: String,
    `Create a comprehensive summary that encapsulates the essence of the input`: String
)

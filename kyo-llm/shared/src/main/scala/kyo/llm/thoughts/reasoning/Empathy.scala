package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      Empathy thought in AI reasoning focuses on understanding emotions and social contexts.
      - Interprets emotional cues in text.
      - Deepens insight into emotional expressions.
      - Considers social and cultural contexts.
      - Reflects on the influence of social norms and values.
    """
)
case class Empathy(
    `Interpret emotional cues in text`: String,
    `Gain insight into emotional expressions`: String,
    `Understand social and cultural contexts`: String,
    `Reflect on social norms and values`: String
)

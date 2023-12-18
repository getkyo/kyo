package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Emotional thought focuses on incorporating emotional and social intelligence in AI reasoning.
      - Applies principles from affective computing to interpret emotional tones.
      - Uses concepts from social psychology to analyze social contexts.
      - Incorporates empathetic algorithms for generating responses.
      - Relevant techniques: Sentiment Analysis, Social Network Analysis.
    """
)
case class EmotionalReasoning(
    understandEmotions: UnderstandEmotions,
    analyzeContext: AnalyzeContext
)

case class UnderstandEmotions(
    `Identify the emotional tone and sentiment in the text`: String,
    `Recognize emotional cues and expressions`: String
)

case class AnalyzeContext(
    `Relate information to broader social and cultural settings`: String,
    `Consider social norms and values relevant to the text`: String
)

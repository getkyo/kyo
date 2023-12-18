package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Prediction thought involves forecasting future scenarios.
      - Forecasts based on current understanding.
      - Identifies trends for future developments.
      - Considers impact of variables on outcomes.
      - Relevant techniques: Forecasting Methods, Scenario Planning.
    """
)
case class Prediction(
    `Forecast potential future scenarios based on current understanding`: String,
    `Identify trends or patterns that could influence future developments`: String,
    `Consider how different variables might impact future outcomes`: String
)

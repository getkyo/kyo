package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Repair thought is used for introspection and correction following errors in the LLM's reasoning or output.
      - Focuses on analyzing recent actions to identify the causes of failures.
      - Aims to develop strategies to prevent similar errors in future reasoning processes.
      - Encourages the LLM to critically evaluate its performance and apply corrective measures.
      - This thought is crucial for the LLM's learning process, allowing it to adapt and improve over time.
      - Example: If an error is detected in JSON generation, Repair directs the LLM to pinpoint the error's source and 
        modify its approach to ensure compliance with JSON standards.
    """
)
case class Repair(
    `Analyze recent actions to identify failure causes`: String,
    `Develop strategies to avoid similar errors`: String,
    `Elaborate on the corrective measures to be applied`: String
)

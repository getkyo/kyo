package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    p"""
      The Humorize thought directs the LLM in crafting humor within a given context.
      - Explores the context for potential comedic elements.
      - Develops humor that is engaging, witty, and contextually relevant.
      - Ensures that the humor is appropriate and sensitive to the audience.
    """
)
case class Humorize(
    `Context exploration for humor`: String,
    `Comedic elements identified`: String,
    `Strategy for humorous content creation`: String,
    `Appropriateness and audience sensitivity`: String
) extends Thought.Opening
package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Creative thought encourages creative and divergent thinking in AI processes.
      - Draws on concepts from creative problem solving and design thinking.
      - Utilizes divergent thinking models from cognitive psychology.
      - Applies ideation techniques and lateral thinking for novel idea generation.
      - Relevant techniques: TRIZ, SCAMPER, Mind Mapping.
      - Each 'Elaborate' field prompts the AI to provide detailed explanations and insights, enhancing the depth of the creative process.
    """
)
case class Creative(
    brainstormIdeas: BrainstormIdeas,
    explorePossibilities: ExplorePossibilities,
    synthesizeConcepts: SynthesizeConcepts
)

case class BrainstormIdeas(
    `Generate a list of creative ideas and concepts`: String,
    `Elaborate on the creative ideas and concepts`: String,
    `Apply lateral thinking to explore new perspectives`: String,
    `Elaborate on the new perspectives`: String
)

case class ExplorePossibilities(
    `Consider alternative solutions and challenge existing assumptions`: String,
    `Elaborate on alternative solutions and challenged assumptions`: String,
    `Assess the feasibility and impact of different ideas`: String,
    `Elaborate on the feasibility and impact assessment`: String
)

case class SynthesizeConcepts(
    `Integrate diverse ideas into coherent concepts`: String,
    `Elaborate on the integration of ideas into concepts`: String,
    `Create innovative approaches or solutions`: String,
    `Elaborate on the innovative approaches or solutions`: String
)

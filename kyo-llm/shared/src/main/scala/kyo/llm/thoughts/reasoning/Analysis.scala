package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
        The Analysis thought breaks down the input into more manageable parts.
        - Dissects the input into fundamental elements.
        - Explores connections among elements.
        - Critically assesses the validity and logic.
        - Relevant techniques: Data Decomposition, Logical Analysis.
    """
)
case class Analysis(
    `Dissect the input into fundamental elements for a detailed examination`: String,
    `Explore connections and relationships among these elements`: String,
    `Critically assess the validity and logic of the information presented`: String
)

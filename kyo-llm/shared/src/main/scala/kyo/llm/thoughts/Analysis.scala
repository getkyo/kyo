package kyo.llm.thoughts

import kyo.llm.ais._

case class Analysis(
    `Dissect the input into fundamental elements for a detailed examination`: String,
    `Explore connections and relationships among these elements`: String,
    `Critically assess the validity and logic of the information presented`: String
) extends Thought

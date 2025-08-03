package kyo.parse

import kyo.Chunk

case class ParseState[In](input: ParseInput[In], errors: Chunk[ParseError])

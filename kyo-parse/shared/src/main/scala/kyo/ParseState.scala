package kyo

case class ParseState[In](
    input: ParseInput[In],
    failures: Chunk[ParseFailure],
    isDiscarded: In => Boolean = (_: In) => false
)

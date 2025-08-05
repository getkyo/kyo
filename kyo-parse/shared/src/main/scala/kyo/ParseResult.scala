package kyo.parse

import kyo.*

case class ParseResult[+Out](errors: Chunk[ParseFailure], out: Option[Out]):

    def isFailure: Boolean = errors.nonEmpty || out.isEmpty

    def orAbort(using Frame): Out < Abort[ParseError] =
        if errors.nonEmpty then Abort.fail(ParseError(errors))
        else if out.isEmpty then Abort.fail(ParseError(Chunk.empty))
        else out.get

end ParseResult

object ParseResult:
    def success[Out](errors: Chunk[ParseFailure], out: Out): ParseResult[Out] =
        ParseResult(errors, Some(out))

    def failure(errors: Chunk[ParseFailure]): ParseResult[Nothing] =
        ParseResult(errors, None)
end ParseResult

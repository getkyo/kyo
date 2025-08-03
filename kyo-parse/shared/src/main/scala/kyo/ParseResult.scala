package kyo.parse

import kyo.Chunk

case class ParseResult[+Out](errors: Chunk[ParseError], out: Option[Out])

object ParseResult:
    def success[Out](errors: Chunk[ParseError], out: Out): ParseResult[Out] =
        ParseResult(errors, Some(out))

    def failure(errors: Chunk[ParseError]): ParseResult[Nothing] =
        ParseResult(errors, None)
end ParseResult

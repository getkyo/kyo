package kyo

case class ParseResult[+Out](errors: Chunk[ParseFailure], out: Maybe[Out], fatal: Boolean = false):

    def isFailure: Boolean = errors.nonEmpty || out.isEmpty

    def orAbort(using Frame): Out < Abort[ParseError] =
        if errors.nonEmpty then Abort.fail(ParseError(errors))
        else if out.isEmpty then Abort.fail(ParseError(Chunk.empty))
        else out.get

end ParseResult

object ParseResult:
    def success[Out](errors: Chunk[ParseFailure], out: Out): ParseResult[Out] =
        ParseResult(errors, Present(out))

    def failure(errors: Chunk[ParseFailure], fatal: Boolean = false): ParseResult[Nothing] =
        ParseResult(errors, Absent, fatal)
end ParseResult

package kyo

case class ParseError(failures: Chunk[ParseFailure])(using Frame) extends KyoException(failures.toString)

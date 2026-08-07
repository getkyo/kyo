package kyo.internal

import kyo.Chunk
import kyo.Glob

/** Runtime entry point for glob values emitted by the `glob` literal macro.
  *
  * The versioned method is a binary compatibility boundary for compiled client
  * code. It reconstructs the compiled automaton from trusted macro output without
  * parsing or compiling the source pattern again.
  */
object GlobLiteral:

    def fromEncodedV1(source: String, parts: Chunk[String]): Glob =
        Glob.fromEncodedV1(source, parts)
end GlobLiteral

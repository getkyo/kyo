package kyo.parse

import kyo.Chunk

case class ParseInput[In](tokens: Chunk[In], position: Int):

    def remaining: Chunk[In] = tokens.drop(position)

    /** Advances the position by n characters, not exceeding input length
      *
      * @param n
      *   Number of characters to advance
      * @return
      *   New State with updated position
      */
    def advance(n: Int): ParseInput[In] =
        copy(position = Math.min(tokens.length, position + n))

    /** Checks if all input has been consumed
      *
      * @return
      *   true if position has reached the end of input
      */
    def done: Boolean = position == tokens.length
end ParseInput

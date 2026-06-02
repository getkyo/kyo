package kyo

case class ParseInput[In](tokens: Chunk[In], position: Int):

    // The backing tokens as an indexed chunk, so per-token reads index in O(1) instead of copying the tail.
    private val indexed: Chunk.Indexed[In] = tokens.toIndexed

    def remaining: Chunk[In] = indexed.drop(position)

    /** The next token, or `Absent` at end of input. O(1).
      *
      * Reads the token at `position` by direct indexing rather than `remaining.head`. The latter routes through the generic `Seq.head`, which
      * materializes a fresh copy of the entire remaining input on every call; driving a `repeat` over an n-token input that way is O(n^2).
      */
    def headMaybe: Maybe[In] =
        if position < indexed.length then Maybe(indexed(position))
        else Maybe.empty

    /** True if the tokens from `position` start with `prefix`, compared by `==`. O(prefix.length), no tail copy. */
    def startsWith(prefix: Seq[In])(using CanEqual[In, In]): Boolean =
        val n = prefix.length
        if position + n > indexed.length then false
        else
            var i  = 0
            var ok = true
            while ok && i < n do
                if indexed(position + i) != prefix(i) then ok = false
                i += 1
            ok
        end if
    end startsWith

    /** True if the tokens from `position` start with `prefix`. O(prefix.length), no tail copy. */
    def startsWith(prefix: String)(using ev: In =:= Char): Boolean =
        val n = prefix.length
        if position + n > indexed.length then false
        else
            var i  = 0
            var ok = true
            while ok && i < n do
                if !(ev(indexed(position + i)) == prefix.charAt(i)) then ok = false
                i += 1
            ok
        end if
    end startsWith

    /** Advances the position by n characters, not exceeding input length
      *
      * @param n
      *   Number of characters to advance
      * @return
      *   New State with updated position
      */
    def advance(n: Int): ParseInput[In] =
        copy(position = Math.min(tokens.length, position + n))

    def advanceWhile(f: In => Boolean): ParseInput[In] =
        var pos = position
        while pos < indexed.length && f(indexed(pos)) do
            pos += 1

        copy(position = pos)
    end advanceWhile

    /** Checks if all input has been consumed
      *
      * @return
      *   true if position has reached the end of input
      */
    def done: Boolean = position == tokens.length
end ParseInput

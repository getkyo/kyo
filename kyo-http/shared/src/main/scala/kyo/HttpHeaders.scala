package kyo

import scala.annotation.tailrec

/** Immutable HTTP headers backed by a flat interleaved `Chunk[String]` for zero tuple allocation.
  *
  * Headers are stored as `[name0, value0, name1, value1, ...]` in a single `Chunk[String]`. This layout eliminates the per-header `Tuple2`
  * allocation of `Seq[(String, String)]` and enables O(1) append via Chunk's structural sharing.
  *
  * All name lookups are case-insensitive per HTTP semantics. Header names preserve their original case for wire serialization.
  *
  * IMPORTANT: `add` appends without deduplication (suitable for requests), while `set` replaces existing headers with the same name
  * (suitable for responses). Choose the method matching your HTTP message semantics.
  *
  * @see
  *   [[kyo.HttpRequest]]
  * @see
  *   [[kyo.HttpResponse]]
  */
opaque type HttpHeaders = Chunk[String]

object HttpHeaders:

    inline given CanEqual[HttpHeaders, HttpHeaders] = CanEqual.derived

    // --- Factories ---

    /** Empty headers. */
    val empty: HttpHeaders = Chunk.empty[String]

    // --- Extension methods ---

    extension (self: HttpHeaders)

        /** Number of header entries. */
        def size: Int = self.length / 2

        /** Whether this contains no headers. */
        inline def isEmpty: Boolean = self.length == 0

        /** Whether this contains at least one header. */
        inline def nonEmpty: Boolean = self.length != 0

        // --- Lookup ---

        /** Returns the value of the first header matching `name` (case-insensitive).
          *
          * @param name
          *   header name to find
          */
        def get(name: String): Maybe[String] =
            @tailrec def loop(i: Int): Maybe[String] =
                if i >= self.length then Absent
                else if self(i).equalsIgnoreCase(name) then Present(self(i + 1))
                else loop(i + 2)
            loop(0)
        end get

        /** Returns the value of the last header matching `name` (case-insensitive).
          *
          * Useful for response headers where the last value takes precedence.
          *
          * @param name
          *   header name to find
          */
        def getLast(name: String): Maybe[String] =
            @tailrec def loop(i: Int, result: Maybe[String]): Maybe[String] =
                if i >= self.length then result
                else if self(i).equalsIgnoreCase(name) then loop(i + 2, Present(self(i + 1)))
                else loop(i + 2, result)
            loop(0, Absent)
        end getLast

        /** Whether a header with the given name exists (case-insensitive). */
        def contains(name: String): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= self.length then false
                else if self(i).equalsIgnoreCase(name) then true
                else loop(i + 2)
            loop(0)
        end contains

        /** Whether any header entry matches the predicate. */
        inline def exists(inline f: (String, String) => Boolean): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= self.length then false
                else if f(self(i), self(i + 1)) then true
                else loop(i + 2)
            loop(0)
        end exists

        // --- Modification ---

        /** Appends a header without replacing existing ones (multi-value / request semantics). */
        def add(name: String, value: String): HttpHeaders =
            self.append(name).append(value)

        /** Replaces any existing header with the same name, then appends (set semantics / response semantics). */
        def set(name: String, value: String): HttpHeaders =
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    if !self(i).equalsIgnoreCase(name) then
                        discard(builder += self(i))
                        discard(builder += self(i + 1))
                    loop(i + 2)
            loop(0)
            discard(builder += name)
            discard(builder += value)
            builder.result()
        end set

        /** Concatenates two header collections. */
        def concat(other: HttpHeaders): HttpHeaders =
            if self.isEmpty then other
            else if other.isEmpty then self
            else (self: Chunk[String]) ++ (other: Chunk[String])

        /** Removes all headers with the given name (case-insensitive). */
        def remove(name: String): HttpHeaders =
            val builder = ChunkBuilder.init[String]
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    if !self(i).equalsIgnoreCase(name) then
                        discard(builder += self(i))
                        discard(builder += self(i + 1))
                    loop(i + 2)
            loop(0)
            builder.result()
        end remove

        // --- Iteration ---

        /** Iterates over all headers as name-value pairs. */
        inline def foreach(inline f: (String, String) => Unit): Unit =
            def loop(i: Int): Unit =
                if i < self.length then
                    f(self(i), self(i + 1))
                    loop(i + 2)
            loop(0)
        end foreach

        /** Folds over all headers as name-value pairs. */
        inline def foldLeft[A](init: A)(inline f: (A, String, String) => A): A =
            def loop(i: Int, acc: A): A =
                if i >= self.length then acc
                else loop(i + 2, f(acc, self(i), self(i + 1)))
            loop(0, init)
        end foldLeft

    end extension

    // --- Internal ---

    /** Creates headers from a flat interleaved array `[name0, value0, name1, value1, ...]`.
      *
      * @param arr
      *   flat interleaved name-value array, must have even length
      */
    private[kyo] def fromFlatArrayNoCopy(arr: Array[String]): HttpHeaders =
        require(arr.length % 2 == 0, s"Header array length must be even, got ${arr.length}")
        if arr.length == 0 then empty
        else Chunk.fromNoCopy(arr)
    end fromFlatArrayNoCopy

end HttpHeaders

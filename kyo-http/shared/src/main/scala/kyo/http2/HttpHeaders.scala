package kyo.http2

import kyo.Absent
import kyo.Chunk
import kyo.ChunkBuilder
import kyo.Maybe
import kyo.Present
import kyo.discard
import scala.annotation.tailrec

/** Immutable HTTP headers backed by a flat interleaved `Chunk[String]`.
  *
  * Headers are stored as `[name0, value0, name1, value1, ...]`. All name lookups are case-insensitive per HTTP semantics. Header names
  * preserve their original case for wire serialization.
  *
  * `add` appends without deduplication (multi-value semantics). `set` replaces existing headers with the same name.
  */
opaque type HttpHeaders = Chunk[String]

object HttpHeaders:

    given CanEqual[HttpHeaders, HttpHeaders] = CanEqual.derived

    val empty: HttpHeaders = Chunk.empty[String]

    extension (self: HttpHeaders)

        def size: Int = self.length / 2

        def isEmpty: Boolean = self.length == 0

        def nonEmpty: Boolean = self.length != 0

        // --- Lookup ---

        /** Returns the value of the first header matching `name` (case-insensitive). */
        def get(name: String): Maybe[String] =
            @tailrec def loop(i: Int): Maybe[String] =
                if i >= self.length then Absent
                else if self(i).equalsIgnoreCase(name) then Present(self(i + 1))
                else loop(i + 2)
            loop(0)
        end get

        /** Returns all values for headers matching `name` (case-insensitive). */
        def getAll(name: String): Seq[String] =
            val builder = Seq.newBuilder[String]
            @tailrec def loop(i: Int): Seq[String] =
                if i >= self.length then builder.result()
                else
                    if self(i).equalsIgnoreCase(name) then
                        builder += self(i + 1)
                    loop(i + 2)
            loop(0)
        end getAll

        /** Whether a header with the given name exists (case-insensitive). */
        def contains(name: String): Boolean =
            @tailrec def loop(i: Int): Boolean =
                if i >= self.length then false
                else if self(i).equalsIgnoreCase(name) then true
                else loop(i + 2)
            loop(0)
        end contains

        // --- Modification ---

        /** Appends a header without replacing existing ones (multi-value semantics). */
        def add(name: String, value: String): HttpHeaders =
            self.append(name).append(value)

        /** Replaces any existing header with the same name, then appends. */
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

        /** Iterates over all headers as name-value pairs. */
        def foreach(f: (String, String) => Unit): Unit =
            @tailrec def loop(i: Int): Unit =
                if i < self.length then
                    f(self(i), self(i + 1))
                    loop(i + 2)
            loop(0)
        end foreach

        /** Folds over all headers as name-value pairs. */
        def foldLeft[A](init: A)(f: (A, String, String) => A): A =
            @tailrec def loop(i: Int, acc: A): A =
                if i >= self.length then acc
                else loop(i + 2, f(acc, self(i), self(i + 1)))
            loop(0, init)
        end foldLeft

    end extension

end HttpHeaders

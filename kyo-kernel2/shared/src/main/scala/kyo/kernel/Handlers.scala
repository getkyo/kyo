package kyo.kernel

import kyo.Chunk
import kyo.Tag
import scala.annotation.tailrec

opaque type Handlers = Chunk[Handler[?, ?, ?]]

object Handlers:

    val empty: Handlers = Chunk.empty

    extension (self: Handlers)

        def add(handler: Handler[?, ?, ?]): Handlers =
            self.append(handler)

        def indexOf[E](tag: Tag[E]): Int =
            scan(self, tag)

        def apply(i: Int): Handler[?, ?, ?] =
            (self: Chunk[Handler[?, ?, ?]])(i)

        def take(n: Int): Handlers =
            (self: Chunk[Handler[?, ?, ?]]).take(n)

        def updated(i: Int, handler: Handler[?, ?, ?]): Handlers =
            (self: Chunk[Handler[?, ?, ?]]).updated(i, handler)

        def size: Int =
            (self: Chunk[Handler[?, ?, ?]]).length

    end extension

    // Chunk extends Seq, whose indexOf searches elements: inside this file the
    // opaque is transparent, so an unqualified sibling call would resolve to
    // the Seq member. The lookup routes through this helper instead.
    private def scan[E](self: Chunk[Handler[?, ?, ?]], tag: Tag[E]): Int =
        @tailrec def loop(i: Int): Int =
            if i < 0 then i
            else if tag.erased <:< self(i).tag.erased then i
            else loop(i - 1)
        loop(self.size - 1)
    end scan

end Handlers

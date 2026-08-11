package kyo.kernel.internal

import kyo.Chunk
import kyo.Tag
import scala.annotation.tailrec

// the layer stack is a Chunk: entering a scope appends a chain node and
// settling pops one, both in constant time, and closures capture the value
// as is. Reads walk the chain, so the eval loop adopts flat storage via
// compact when a scan walks deep; compact is a no-op on flat storage
opaque type Handlers = Chunk[Handler[?, ?, ?, ?, ?]]

object Handlers:

    val empty: Handlers = Chunk.empty

    extension (self: Handlers)

        def add(handler: Handler[?, ?, ?, ?, ?]): Handlers =
            self.append(handler)

        def indexOf[E](tag: Tag[E]): Int =
            @tailrec def loop(i: Int): Int =
                if i < 0 then i
                else if tag <:< self(i).tag then i
                else loop(i - 1)
            loop(self.length - 1)
        end indexOf

        def apply(i: Int): Handler[?, ?, ?, ?, ?] =
            self(i)

        def take(n: Int): Handlers =
            self.take(n)

        def updated(i: Int, handler: Handler[?, ?, ?, ?, ?]): Handlers =
            self.updated(i, handler)

        def size: Int =
            self.length

        def compact: Handlers =
            self.toIndexed

    end extension

end Handlers

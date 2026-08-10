package kyo.kernel

import kyo.Chunk
import kyo.Maybe
import kyo.Tag
import scala.annotation.tailrec

opaque type Handlers = Chunk[Handler[?, ?, ?]]

object Handlers:

    val empty: Handlers = Chunk.empty

    extension (self: Handlers)

        def add(handler: Handler[?, ?, ?]): Handlers =
            self.append(handler)

        def find[E](tag: Tag[E]): Maybe[Handler[?, ?, ?]] =
            @tailrec def loop(i: Int): Maybe[Handler[?, ?, ?]] =
                if i < 0 then Maybe.Absent
                else
                    val handler = self(i)
                    if tag.erased <:< handler.tag.erased then Maybe.Present(handler)
                    else loop(i - 1)
            loop(self.size - 1)
        end find

    end extension

end Handlers

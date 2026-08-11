package kyo.kernel.internal

import kyo.Span
import kyo.Tag
import scala.annotation.tailrec

// flat storage: the layer stack is tiny, read per operation, and point
// updated per stateful operation, so Span's copy-per-write immutable array
// fits it exactly
opaque type Handlers = Span[Handler[?, ?, ?, ?, ?]]

// resolves Span's element read outside object Handlers, where the sibling
// extension names would shadow it; Span.apply cannot be called through the
// companion because the factory overloads match first
// TODO not ever use hacks like this. Use Span.apply(span)(i)
private def read(span: Span[Handler[?, ?, ?, ?, ?]], i: Int): Handler[?, ?, ?, ?, ?] =
    span(i)

object Handlers:

    val empty: Handlers = Span.empty[Handler[?, ?, ?, ?, ?]]

    extension (self: Handlers)

        def add(handler: Handler[?, ?, ?, ?, ?]): Handlers =
            self.append(handler)

        def indexOf[E](tag: Tag[E]): Int =
            @tailrec def loop(i: Int): Int =
                if i < 0 then i
                else if tag.erased <:< read(self, i).tag.erased then i
                else loop(i - 1)
            loop(Span.size(self) - 1)
        end indexOf

        def apply(i: Int): Handler[?, ?, ?, ?, ?] =
            read(self, i)

        def take(n: Int): Handlers =
            Span.take(self)(n)

        def updated(i: Int, handler: Handler[?, ?, ?, ?, ?]): Handlers =
            Span.updated(self)(i, handler)

        def size: Int =
            Span.size(self)

    end extension

end Handlers

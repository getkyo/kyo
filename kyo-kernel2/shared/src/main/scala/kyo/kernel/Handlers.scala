package kyo.kernel

import kyo.Maybe
import kyo.Tag
import scala.annotation.tailrec

final class Handlers private (array: Array[Handler[?, ?, ?]]):

    def add(handler: Handler[?, ?, ?]): Handlers =
        val size = array.length
        val next = new Array[Handler[?, ?, ?]](size + 1)
        java.lang.System.arraycopy(array, 0, next, 0, size)
        next(size) = handler
        new Handlers(next)
    end add

    def find[E](tag: Tag[E]): Maybe[Handler[?, ?, ?]] =
        @tailrec def loop(i: Int): Maybe[Handler[?, ?, ?]] =
            if i < 0 then Maybe.Absent
            else
                val handler = array(i)
                if tag.erased <:< handler.tag.erased then Maybe.Present(handler)
                else loop(i - 1)
        loop(array.length - 1)
    end find

end Handlers

object Handlers:
    val empty: Handlers = new Handlers(new Array(0))

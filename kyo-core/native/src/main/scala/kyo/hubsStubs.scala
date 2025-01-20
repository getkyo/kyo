package java.util.concurrent

import java.util.function.Predicate
import scala.collection.mutable
import scala.reflect.ClassTag

class CopyOnWriteArraySet[E]:
    given [A, B]: CanEqual[A, B] = CanEqual.derived
    private var elements         = mutable.ArrayBuffer[E]()

    def add(e: E): Boolean =
        synchronized {
            if !elements.contains(e) then
                elements += e
                true
            else
                false
        }

    def remove(o: Any): Boolean =
        synchronized {
            val idx = elements.indexWhere(_ == o)
            if idx >= 0 then
                val _ = elements.remove(idx)
                true
            else
                false
            end if
        }

    def removeIf(filter: Predicate[? >: E]): Boolean =
        synchronized {
            var modified = false
            elements = elements.filter { e =>
                val shouldRemove = !filter.test(e)
                if shouldRemove then modified = true
                !shouldRemove
            }
            modified
        }

    def toArray(): Array[AnyRef] =
        synchronized {
            elements.toArray(using ClassTag.Any).map(_.asInstanceOf[AnyRef])
        }
end CopyOnWriteArraySet

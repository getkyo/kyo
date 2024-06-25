package java.lang

import java.util.function.Supplier

class ThreadLocal[T](private var value: T):
    def this() = this(null.asInstanceOf[T])
    def get()     = value
    def set(v: T) = value = v
end ThreadLocal

object ThreadLocal:
    def withInitial[T](f: Supplier[T]): ThreadLocal[T] = ThreadLocal(f.get())

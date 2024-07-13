package java.lang

import java.util.function.Supplier

class ThreadLocal[A](private var value: A):
    def this() = this(null.asInstanceOf[A])
    def get()     = value
    def set(v: A) = value = v
end ThreadLocal

object ThreadLocal:
    def withInitial[A](f: Supplier[A]): ThreadLocal[A] = ThreadLocal(f.get())

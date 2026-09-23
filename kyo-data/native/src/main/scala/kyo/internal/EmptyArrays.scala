package kyo.internal

/** Shared zero-length arrays of reference classes.
  *
  * Scala Native has one runtime class for every reference array, so one empty array serves all of them. The same classes also run on
  * the JVM, inside the compiler when a macro executes, where each reference class has its own array class and a shared array fails the
  * cast to it. There each call allocates an array of the requested class; the cost is paid only at compile time.
  */
private[kyo] object EmptyArrays:

    private val sharesReferenceArrayClass: Boolean = classOf[Array[String]] eq classOf[Array[AnyRef]]

    private val empty: Array[?] = new Array[AnyRef](0)

    def get(cls: Class[?]): Array[?] =
        if sharesReferenceArrayClass then empty
        else java.lang.reflect.Array.newInstance(cls, 0).asInstanceOf[Array[?]]

end EmptyArrays

package kyo.internal

/** Shared zero-length arrays of reference classes.
  *
  * A ClassValue stores each entry in its class's own table, so a cached array keeps its class and class loader collectable. A map keyed
  * strongly on `Class` would pin every class that ever reached it, together with its loader.
  */
private[kyo] object EmptyArrays:

    private val cache = new ClassValue[Array[?]]:
        override def computeValue(cls: Class[?]): Array[?] =
            java.lang.reflect.Array.newInstance(cls, 0).asInstanceOf[Array[?]]

    def get(cls: Class[?]): Array[?] = cache.get(cls)

end EmptyArrays

package kyo.internal

import kyo.*

/** Shared zero-length arrays of reference classes. The runtime is single-threaded and never unloads classes, so a plain permanent map is
  * enough.
  */
private[kyo] object EmptyArrays:

    private val cache = new java.util.HashMap[Class[?], Array[?]]()

    def get(cls: Class[?]): Array[?] =
        val cached = cache.get(cls)
        if !isNull(cached) then cached
        else
            val created = java.lang.reflect.Array.newInstance(cls, 0).asInstanceOf[Array[?]]
            discard(cache.put(cls, created))
            created
        end if
    end get

end EmptyArrays

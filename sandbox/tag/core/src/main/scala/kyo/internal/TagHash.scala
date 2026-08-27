package kyo.internal

/** A `Tag`'s hash code. `String.hashCode` is already memoized in the `String` itself here, so this
  * inlines away to the call it replaces and the dispatch path is unchanged. The JS twin exists because
  * that platform has no such memoization.
  */
private[kyo] object TagHash:
    inline def of(tag: Any): Int = tag.hashCode
end TagHash

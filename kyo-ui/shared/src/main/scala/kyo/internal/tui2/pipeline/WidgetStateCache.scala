package kyo.internal.tui2.pipeline

import kyo.*

/** Per-widget state cache. Maps WidgetKey → SignalRef.Unsafe values.
  *
  * Mark-and-sweep lifecycle:
  *   - beginFrame(): clears the accessed set
  *   - getOrCreate(): marks the key as accessed, creates if absent
  *   - sweep(): removes entries not accessed this frame
  *
  * The refs inside are reactive (changes trigger re-render). The cache itself is a plain HashMap because making it a Signal would mean
  * every widget state creation triggers a re-render, which is circular.
  *
  * Hidden widgets survive: Lower runs widget expansion even for hidden elements (the result is discarded), so getOrCreate marks their keys.
  * Only widgets truly absent from the UI tree are evicted.
  */
class WidgetStateCache:
    private val map      = new java.util.HashMap[WidgetKey, Any]
    private val accessed = new java.util.HashSet[WidgetKey]

    def beginFrame()(using AllowUnsafe): Unit = accessed.clear()

    def getOrCreate[S](key: WidgetKey, init: => S)(using AllowUnsafe): S =
        discard(accessed.add(key))
        val existing = map.get(key)
        if existing.asInstanceOf[AnyRef] ne null then existing.asInstanceOf[S]
        else
            val v = init
            discard(map.put(key, v))
            v
        end if
    end getOrCreate

    def get[S](key: WidgetKey)(using AllowUnsafe): Maybe[S] =
        val v = map.get(key)
        if v.asInstanceOf[AnyRef] ne null then Maybe(v.asInstanceOf[S]) else Absent

    def sweep()(using AllowUnsafe): Unit =
        discard(map.keySet().removeIf(k => !accessed.contains(k)))
end WidgetStateCache

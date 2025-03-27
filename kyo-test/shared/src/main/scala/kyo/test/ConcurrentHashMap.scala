package kyo.test

import java.util.concurrent.ConcurrentHashMap as JConcurrentHashMap

final private[test] case class ConcurrentHashMap[K, V] private (private val map: JConcurrentHashMap[K, V]):
    def foldLeft[B](z: B)(op: (B, (K, V)) => B): B =
        var result = z
        val it     = map.entrySet.iterator
        while it.hasNext do
            val e = it.next()
            result = op(result, (e.getKey, e.getValue))
        result
    end foldLeft
    def getOrElseUpdate(key: K, op: => V): V =
        map.computeIfAbsent(key, _ => op)
end ConcurrentHashMap

private[test] object ConcurrentHashMap:
    def empty[K, V]: ConcurrentHashMap[K, V] =
        new ConcurrentHashMap[K, V](new JConcurrentHashMap[K, V]())

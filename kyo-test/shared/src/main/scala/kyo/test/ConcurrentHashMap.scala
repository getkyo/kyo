package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.{ConcurrentHashMap => JConcurrentHashMap}

private[test] final case class ConcurrentHashMap[K, V] private (private val map: JConcurrentHashMap[K, V]) {
  def foldLeft[B](z: B)(op: (B, (K, V)) => B): B = {
    var result = z
    val it     = map.entrySet.iterator
    while (it.hasNext) {
      val e = it.next()
      result = op(result, (e.getKey, e.getValue))
    }
    result
  }
  def getOrElseUpdate(key: K, op: => V): V =
    map.computeIfAbsent(key, _ => op)
}

private[test] object ConcurrentHashMap {
  def empty[K, V]: ConcurrentHashMap[K, V] =
    new ConcurrentHashMap[K, V](new JConcurrentHashMap[K, V]())
}

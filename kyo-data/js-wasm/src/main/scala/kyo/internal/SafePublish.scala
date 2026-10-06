package kyo.internal

/** Inert here: JavaScript and Wasm run one thread over this heap, so there is no other thread to publish to. Scala
  * Native orders a final field only when it is marked `@safePublish`, which its counterpart names.
  */
final private[kyo] class SafePublish extends scala.annotation.StaticAnnotation

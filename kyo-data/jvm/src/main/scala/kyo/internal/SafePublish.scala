package kyo.internal

/** Inert here: the JVM already orders every final field's construction before reads through another thread's reference.
  * Scala Native gives that guarantee only to fields marked `@safePublish`, which its counterpart names.
  */
final private[kyo] class SafePublish extends scala.annotation.StaticAnnotation

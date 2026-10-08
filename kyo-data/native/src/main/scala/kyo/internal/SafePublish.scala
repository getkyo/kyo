package kyo.internal

/** Scala Native orders a final field's construction before reads through another thread's reference only for fields
  * marked `@safePublish`, the guarantee the JVM gives every final field.
  */
private[kyo] type SafePublish = scala.scalanative.annotation.safePublish

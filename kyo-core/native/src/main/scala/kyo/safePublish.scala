package kyo

/** Enforces Java Memory Model's safe publication guarantees for final fields (vals).
  *
  * When applied to a field or class, ensures strict JMM semantics rather than Scala Native's default relaxed memory model. Has no effect on
  * JVM or JavaScript platforms.
  *
  * @see
  *   [[scala.scalanative.annotation.safePublish]]
  */
type safePublish = scala.scalanative.annotation.safePublish

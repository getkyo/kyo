package kyo

/** A dummy class used to use to help the compiler distinguish between overloaded methods that have subtle type differences.
  */
sealed abstract class Discriminator {}

object Discriminator:
    private val cached  = new Discriminator {}
    given Discriminator = cached

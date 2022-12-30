package kyo

import scala.quoted._

trait transform {
  val q: Quotes
  given Quotes = q
  import quotes.reflect._

  object Transform {
    def apply(tree: Term): Term =
      unapply(tree).getOrElse(tree)

    def unapply(tree: Term): Option[Term] =
      ???
  }

}

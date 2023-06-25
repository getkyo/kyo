package kyo

final class NotGiven[+T] private ()

trait LowPriorityNotGiven {

  /** A fallback method used to emulate negation in Scala 2 */
  implicit def default[T]: NotGiven[T] = NotGiven.value
}

object NotGiven extends LowPriorityNotGiven {

  /** A value of type `NotGiven` to signal a successful search for `NotGiven[C]` (i.e. a failing
    * search for `C`). A reference to this value will be explicitly constructed by Dotty's implicit
    * search algorithm
    */
  def value: NotGiven[Nothing] = new NotGiven[Nothing]()

  /** One of two ambiguous methods used to emulate negation in Scala 2 */
  implicit def amb1[T](implicit ev: T): NotGiven[T] = ???

  /** One of two ambiguous methods used to emulate negation in Scala 2 */
  implicit def amb2[T](implicit ev: T): NotGiven[T] = ???
}

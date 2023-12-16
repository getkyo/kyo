package kyo

import kyo.sums._
import kyo.ios._

object ttt extends KyoApp {
  import edits._

  case class Address(street: String)
  case class Person(name: String, age: Int, address: Address)

  def run =
    Edits(Person("test", 10, Address("rua")))(_[Address]("address").set(Address("rua 2")))
}

object edits {

  private val sums = Sums[Map[Edit[Any], Any]]

  class Edit[T](path: List[String], value: T) {
    def apply[U](id: String): Edit[U] =
      new Edit[U](id :: path, value.asInstanceOf[U])
    def get: T > Edits =
      sums.get.map(_.getOrElse(this.asInstanceOf[Edit[Any]], value).asInstanceOf[T])
    def set(v: T): Unit > Edits =
      sums.add(Map(this.asInstanceOf[Edit[Any]] -> v)).unit

    override def toString = s"Edit(${path.reverse.mkString(".")})"
  }

  type Edits >: Edits.Effects <: Edits.Effects

  object Edits {

    type Effects = Sums[Map[Edit[Any], Any]]

    def apply[T, S](v: T)(f: Edit[T] => Unit > (Edits with S)): T > S =
      sums.run(f(new Edit(Nil, v))).map {
        case ((), edits) =>
          println(edits)
          v
      }
  }

}

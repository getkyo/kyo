package kyo

import core._
import tries._
import options._
import direct._

object directTest extends App {

  case class Person(name: String, age: Int, minor: Boolean)

  def test(name: Option[String], ageString: String): Person > (Options | Tries) = select {
    val age = from(Tries(Integer.parseInt(ageString)))
    Person(from(name > Options), age, age < 18)
  }
}

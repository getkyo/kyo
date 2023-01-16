package kyoTest

import kyo.core._
import kyo.tries._
import kyo.options._
import kyo.direct._
import kyo.ios._
import kyo.envs._
import kyo.concurrent.fibers._
import scala.util.Try
import kyo.consoles._

object directTest extends App {

  case class Person(name: String, age: Int, minor: Boolean)

  def test(
      nameOption: Option[String],
      ageString: String
  ) =
    select {
      val name  = from(nameOption > Options)
      val age   = from(Tries(Integer.parseInt(ageString)))
      val minor = from(Fibers.fork(age < 18))
      val name2 = from(nameOption > Options)
      val name3 = from(nameOption > Options)
      val x     = from(Consoles.readln(i => Tries(Integer.parseInt(i))))
      Person(name + name2 + x, age, age < x)
    }

  val a: Person > (Fibers | Consoles | (IOs | Options | Tries)) = test(Some("John"), "10")
  val b: Option[Person] > (Fibers | Consoles | (IOs | Tries)) = a < Options
  val c: Try[Option[Person]] > (Fibers | Consoles | IOs) = b < Tries
  val d: Try[Option[Person]] > (IOs | Fibers) = Consoles.run(c)
  val e = Fibers.block(d)
  val f: Try[Option[Person]] = IOs.run(e)
  println(f)
}

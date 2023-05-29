package kyoTest

import kyo.arrows._
import kyo._
import kyo.ios.IOs
import kyo.locals.Locals
import kyo.options._
import kyo.options._

class arrowsTest extends KyoTest {

  "non-recursive" - {
    "pure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Any, String, Any](_.map(v => "" + v))
        val arrow: Int > Any => String > IOs = a
        checkEquals[String, Any](
            IOs.run[String](arrow(1)),
            "1"
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Any, String, Options](_.map(v => Options.get(Option("" + v))))
        val arrow: Int > Any => String > (Options with IOs) = a
        checkEquals[Option[String], Any](
            IOs.run[Option[String]](Options.run(arrow(1))),
            Option("1")
        )
      }
      "locals" in {
        val l     = Locals.init(10)
        val arrow = Arrows[Int, Any, Int, IOs](_.map(v1 => l.get.map(v2 => v1 + v2)))
        checkEquals[Int, Any](
            IOs.run[Int](arrow(20)),
            30
        )
      }
    }
    "impure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_.map(v => "" + v))
        val arrow: Int > Options => String > (Options with IOs) = a
        checkEquals[Option[String], Any](
            IOs.run[Option[String]](Options.run(arrow(Options.get(Option(1))))),
            Option("1")
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_.map(v => Options.get(Option("" + v))))
        val arrow: Int > Options => String > (Options with IOs) = a
        checkEquals[Option[String], Any](
            IOs.run[Option[String]](Options.run(arrow(Options.get(Option(1))))),
            Option("1")
        )
      }
      "locals" in {
        val l = Locals.init(10)
        val arrow =
          Arrows[Int, Options, Int, (Options with IOs)](_.map(v1 => l.get.map(v2 => v1 + v2)))
        checkEquals[Option[Int], Any](
            IOs.run[Option[Int]](Options.run(arrow(Options.get(Option(20))))),
            Option(30)
        )
      }
    }
    "handle input effect" - {
      "transform + handle" in {
        val a =
          Arrows[Int, Options, Option[String], Any](v => Options.run(v.map(v => "" + v)))
        val arrow: Int > Options => Option[String] > IOs = a
        checkEquals[Option[String], Any](
            IOs.run[Option[String]](arrow(Options.get(Option(1)))),
            Option("1")
        )
      }
      "transform + handle + transform" in {
        val a =
          Arrows[Int, Options, Option[String], Any](i =>
            Options.run(i.map(_ + 1)).map(_.map("" + _))
          )
        val arrow: Int > Options => Option[String] > IOs = a
        checkEquals[Option[String], Any](
            IOs.run[Option[String]](arrow(Options.get(Option(1)))),
            Option("2")
        )
      }
    }
  }

  "recursive" - {
    "pure recurse" in {
      val a =
        Arrows.recursive[Int, Any, String, Any] { (i, self) =>
          i.map {
            case 0 => "0"
            case i => self(i - 1)
          }
        }
      val arrow: Int > Any => String > IOs = a
      checkEquals[String, Any](
          IOs.run[String](arrow(10)),
          "0"
      )
    }
    "impure recurse" in {
      val a =
        Arrows.recursive[Int, Options, String, Options] { (i, self) =>
          i.map {
            case 0 => "0"
            case i if (i % 5 == 0) =>
              self(Options.get(Option(i - 1)))
            case i =>
              self(i - 1)
          }
        }
      val arrow: Int > Options => String > (Options with IOs) = a
      checkEquals[Option[String], Any](
          IOs.run[Option[String]](Options.run(arrow(10))),
          Option("0")
      )
    }
    "locals" in {
      val l = Locals.init(10)
      val a =
        Arrows.recursive[Int, Any, Int, IOs] { (i, self) =>
          i.map {
            case 0 => l.get
            case i => self(i - 1)
          }
        }
      val arrow: Int > Any => Int > IOs = a
      checkEquals[Int, Any](
          IOs.run[Int](l.let(1)(arrow(10))),
          1
      )
    }
  }
}

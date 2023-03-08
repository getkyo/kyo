package kyoTest

import kyo.arrows._
import kyo.core._
import kyo.options._
import kyo.options._
import kyo.locals.Locals
import kyo.ios.IOs

class arrowsTest extends KyoTest {

  "non-recursive" - {
    "pure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Nothing, String, Nothing](_(v => "" + v))
        val arrow: Int > Nothing => String > IOs = a
        checkEquals[String, Nothing](
            IOs.run[String](arrow(1)),
            "1"
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Nothing, String, Options](_(v => Option("" + v) > Options))
        val arrow: Int > Nothing => String > (Options | IOs) = a
        checkEquals[Option[String], Nothing](
            IOs.run[Option[String]](arrow(1) < Options),
            Option("1")
        )
      }
      "locals" in {
        val l     = Locals(10)
        val arrow = Arrows[Int, Nothing, Int, IOs](_(v1 => l.get(v2 => v1 + v2)))
        checkEquals[Int, Nothing](
            IOs.run[Int](arrow(20)),
            30
        )
      }
    }
    "impure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_(v => "" + v))
        val arrow: Int > Options => String > (Options | IOs) = a
        checkEquals[Option[String], Nothing](
            IOs.run[Option[String]](arrow(Option(1) > Options) < Options),
            Option("1")
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_(v => Option("" + v) > Options))
        val arrow: Int > Options => String > (Options | IOs) = a
        checkEquals[Option[String], Nothing](
            IOs.run[Option[String]](arrow(Option(1) > Options) < Options),
            Option("1")
        )
      }
      "locals" in {
        val l     = Locals(10)
        val arrow = Arrows[Int, Options, Int, (Options | IOs)](_(v1 => l.get(v2 => v1 + v2)))
        checkEquals[Option[Int], Nothing](
            IOs.run[Option[Int]](arrow(Option(20) > Options) < Options),
            Option(30)
        )
      }
    }
    "handle input effect" - {
      "transform + handle" in {
        val a =
          Arrows[Int, Options, Option[String], Nothing](_(v => "" + v) < Options)
        val arrow: Int > Options => Option[String] > IOs = a
        checkEquals[Option[String], Nothing](
            IOs.run[Option[String]](arrow(Option(1) > Options)),
            Option("1")
        )
      }
      "transform + handle + transform" in {
        val a =
          Arrows[Int, Options, Option[String], Nothing](i => (i(_ + 1) < Options)(_.map("" + _)))
        val arrow: Int > Options => Option[String] > IOs = a
        checkEquals[Option[String], Nothing](
            IOs.run[Option[String]](arrow(Option(1) > Options)),
            Option("2")
        )
      }
    }
  }

  "recursive" - {
    "pure recurse" in {
      val a =
        Arrows.recursive[Int, Nothing, String, Nothing] { (i, self) =>
          i {
            case 0 => "0"
            case i => self(i - 1)
          }
        }
      val arrow: Int > Nothing => String > IOs = a
      checkEquals[String, Nothing](
          IOs.run[String](arrow(10)),
          "0"
      )
    }
    "impure recurse" in {
      val a =
        Arrows.recursive[Int, Options, String, Options] { (i, self) =>
          i {
            case 0 => "0"
            case i if (i % 5 == 0) =>
              self(Option(i - 1) > Options)
            case i =>
              self(i - 1)
          }
        }
      val arrow: Int > Options => String > (Options | IOs) = a
      checkEquals[Option[String], Nothing](
          IOs.run[Option[String]](arrow(10) < Options),
          Option("0")
      )
    }
    "locals" in {
      val l = Locals(10)
      val a =
        Arrows.recursive[Int, Nothing, Int, IOs] { (i, self) =>
          i {
            case 0 => l.get(v => v)
            case i => self(i - 1)
          }
        }
      val arrow: Int > Nothing => Int > IOs = a
      checkEquals[Int, Nothing](
          IOs.run[Int](l.let(1)(arrow(10))),
          1
      )
    }
  }
}

package kyoTest

import kyo._

class arrowsTest extends KyoTest {

  "non-recursive" - {
    "pure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Nothing, String, Nothing](_(v => "" + v))
        val arrow: Int > Nothing => String > Nothing = a
        checkEquals[String, Nothing](
            arrow(1),
            "1"
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Nothing, String, Options](_(v => Option("" + v) > Options))
        val arrow: Int > Nothing => String > Options = a
        checkEquals[Option[String], Nothing](
            arrow(1) < Options,
            Option("1")
        )
      }
    }
    "impure input" - {
      "pure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_(v => "" + v))
        val arrow: Int > Options => String > Options = a
        checkEquals[Option[String], Nothing](
            arrow(Option(1) > Options) < Options,
            Option("1")
        )
      }
      "impure transform" in {
        val a =
          Arrows[Int, Options, String, Options](_(v => Option("" + v) > Options))
        val arrow: Int > Options => String > Options = a
        checkEquals[Option[String], Nothing](
            arrow(Option(1) > Options) < Options,
            Option("1")
        )
      }
    }
    "handle input effect" - {
      "transform + handle" in {
        val a =
          Arrows[Int, Options, Option[String], Nothing](_(v => "" + v) < Options)
        val arrow: Int > Options => Option[String] > Nothing = a
        checkEquals[Option[String], Nothing](
            arrow(Option(1) > Options),
            Option("1")
        )
      }
      "transform + handle + transform" in {
        val a =
          Arrows[Int, Options, Option[String], Nothing](i => (i(_ + 1) < Options)(_.map("" + _)))
        val arrow: Int > Options => Option[String] > Nothing = a
        checkEquals[Option[String], Nothing](
            arrow(Option(1) > Options),
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
      val arrow: Int > Nothing => String > Nothing = a
      checkEquals[String, Nothing](
          arrow(10),
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
      val arrow: Int > Options => String > Options = a
      checkEquals[Option[String], Nothing](
          arrow(10) < Options,
          Option("0")
      )
    }
  }
}

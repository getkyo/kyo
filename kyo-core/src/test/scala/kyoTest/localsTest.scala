package kyoTest

import kyo.core._
import kyo.locals._
import kyo.ios._
import kyo.options._

class localsTest extends KyoTest {

  "default" - {
    "method" in {
      val l = Locals.make(10)
      assert(l.default == 10)
    }
    "get" in {
      val l = Locals.make(10)
      checkEquals[Int, Nothing](
          IOs.run[Int](l.get),
          10
      )
    }
    "effect + get" in {
      val l = Locals.make(10)
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](Options.run(Options(1)(_ => l.get))),
          Some(10)
      )
    }
    "effect + get + effect" in {
      val l = Locals.make(10)
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](Options.run(Options(1)(_ => l.get)(Options(_)))),
          Some(10)
      )
    }
    "multiple" in {
      val l1 = Locals.make(10)
      val l2 = Locals.make(20)
      checkEquals[(Int, Int), Nothing](
          IOs.run[(Int, Int)](zip(l1.get, l2.get)),
          (10, 20)
      )
    }
  }

  "let" - {
    "get" in {
      val l = Locals.make(10)
      checkEquals[Int, Nothing](
          IOs.run[Int](l.let(20)(l.get)),
          20
      )
    }
    "effect + get" in {
      val l = Locals.make(10)
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](Options.run(Options(1)(_ => l.let(20)(l.get)))),
          Some(20)
      )
    }
    "effect + get + effect" in {
      val l = Locals.make(10)
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](Options.run(Options(1)(_ => l.let(20)(l.get)(Options(_))))),
          Some(20)
      )
    }
    "multiple" in {
      val l1 = Locals.make(10)
      val l2 = Locals.make(20)
      checkEquals[(Int, Int), Nothing](
          IOs.run[(Int, Int)](zip(l1.let(30)(l1.get), l2.let(40)(l2.get))),
          (30, 40)
      )
    }
  }

  "save" - {
    "let + save" in {
      val l = Locals.make(10)
      checkEquals[Locals.State, Nothing](
          IOs.run[Locals.State](l.let(20)(Locals.save)),
          Map(l -> 20)
      )
    }
    "let + effect + save" in {
      val l = Locals.make(10)
      checkEquals[Option[Locals.State], Nothing](
          IOs.run[Option[Locals.State]](Options.run(l.let(20)(Options(1)(_ => Locals.save)))),
          Some(Map(l -> 20))
      )
    }
    "effect + let + save" in {
      val l = Locals.make(10)
      checkEquals[Option[Locals.State], Nothing](
          IOs.run[Option[Locals.State]](Options.run(Options(1)(_ => l.let(20)(Locals.save)))),
          Some(Map(l -> 20))
      )
    }
    "effect + let + save + effect" in {
      val l = Locals.make(10)
      checkEquals[Option[Locals.State], Nothing](
          IOs.run[Option[Locals.State]](Options.run(Options(1)(_ =>
            l.let(20)(Locals.save)(Options(_))
          ))),
          Some(Map(l -> 20))
      )
    }
    "nested" in {
      val l1 = Locals.make(10)
      val l2 = Locals.make(20)
      checkEquals[Locals.State, Nothing](
          IOs.run[Locals.State](
              l1.let(30)(
                  l2.let(40)(
                      Locals.save
                  )
              )
          ),
          Map(l1 -> 30, l2 -> 40)
      )
    }
    "nested + effect" in {
      val l1 = Locals.make(10)
      val l2 = Locals.make(20)
      checkEquals[Option[Locals.State], Nothing](
          IOs.run[Option[Locals.State]](
              Options.run(
                  l1.let(30)(
                      l2.let(40)(
                          Options(1)(_ => Locals.save)
                      )
                  )
              )
          ),
          Some(Map(l1 -> 30, l2 -> 40))
      )
    }
    "nested + effects" in {
      val l1 = Locals.make(10)
      val l2 = Locals.make(20)
      checkEquals[Option[Locals.State], Nothing](
          IOs.run[Option[Locals.State]](
              Options.run(
                  l1.let(30)(
                      l2.let(40)(
                          Options(1)(_ => Locals.save)(Options(_))
                      )(Options(_))
                  )(Options(_))
              )
          ),
          Some(Map(l1 -> 30, l2 -> 40))
      )
    }
    "multiple" in {
      val l1 = Locals.make(0)
      val l2 = Locals.make(0)
      val l3 = Locals.make(0)
      checkEquals[(Locals.State, Locals.State), Nothing](
          IOs.run[(Locals.State, Locals.State)](
              l3.let(20) {
                zip(
                    l1.let(30)(Locals.save),
                    l2.let(40)(Locals.save)
                )
              }
          ),
          (Map(l3 -> 20, l1 -> 30), Map(l3 -> 20, l2 -> 40))
      )
    }
    "multiple + effect" in {
      val l1 = Locals.make(0)
      val l2 = Locals.make(0)
      val l3 = Locals.make(0)
      checkEquals[Option[(Locals.State, Locals.State)], Nothing](
          IOs.run[Option[(Locals.State, Locals.State)]](
              Options.run(
                  l3.let(20) {
                    Options(1)(_ =>
                      zip(
                          l1.let(30)(Locals.save)(Options(_)),
                          l2.let(40)(Locals.save)
                      )
                    )
                  }
              )
          ),
          Some((Map(l3 -> 20, l1 -> 30), Map(l3 -> 20, l2 -> 40)))
      )
    }
  }

  "restore" - {
    val l1 = Locals.make(0)
    val l2 = Locals.make(0)
    val l3 = Locals.make(0)
    val state: Locals.State =
      IOs.run {
        l1.let(10) {
          l2.let(20) {
            l3.let(30) {
              Locals.save
            }
          }
        }
      }
    "get" in {
      checkEquals[Int, Nothing](
          IOs.run[Int](Locals.restore(state)(l1.get)),
          10
      )
    }
    "effect + get" in {
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](Options.run(Locals.restore(state)(Options(1)(_ => l1.get)))),
          Some(10)
      )
    }
    "effect + get + effect" in {
      checkEquals[Option[Int], Nothing](
          IOs.run[Option[Int]](
              Options.run(Locals.restore(state)(Options(1)(_ => l1.get)(Options(_))))
          ),
          Some(10)
      )
    }
    "multiple" in {
      checkEquals[(Int, Int), Nothing](
          IOs.run[(Int, Int)](Locals.restore(state)(zip(l1.get, l2.get))),
          (10, 20)
      )
    }
    "multiple + effect" in {
      checkEquals[Option[(Int, Int)], Nothing](
          IOs.run[Option[(Int, Int)]](Options.run(Locals.restore(state)(Options(1)(_ =>
            zip(l1.get, l2.get)
          )))),
          Some((10, 20))
      )
    }
    "nested" in {
      checkEquals[(Int, Int), Nothing](
          IOs.run[(Int, Int)](
              l1.let(30) {
                Locals.restore(state)(zip(l1.get, l2.get))
              }
          ),
          (10, 20)
      )
    }
  }

}

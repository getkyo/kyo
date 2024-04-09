package kyoTest

import kyo.*
import scala.util.Failure
import scala.util.Success
import scala.util.Try

class optionsTest extends KyoTest:

    "apply" - {
        "null" in {
            assert(Options.run(Options(null: String)).pure == None)
            assert(
                Options.run(Options(null: String)).pure ==
                    None
            )
        }
        "value" in {
            assert(
                Options.run(Options("hi")).pure ==
                    Option("hi")
            )
        }
    }

    "pure" - {
        "handle" in {
            assert(
                Options.run(1: Int < Options).pure ==
                    Option(1)
            )
        }
        "handle + transform" in {
            assert(
                Options.run((1: Int < Options).map(_ + 1)).pure ==
                    Option(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Options.run((1: Int < Options).map(i => Options.get(Option(i + 1)))).pure ==
                    Option(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Options.run(
                    (1: Int < Options).map(_ + 1).map(i => Options.get(Option(i + 1)))
                ).pure ==
                    Option(3)
            )
        }
    }

    "effectful" - {
        "handle" in {
            assert(
                Options.run(Options.get(Option(1))).pure ==
                    Option(1)
            )
        }
        "handle + transform" in {
            assert(
                Options.run(Options.get(Option(1)).map(_ + 1)).pure ==
                    Option(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Options.run(Options.get(Option(1)).map(i => Options.get(Option(i + 1)))).pure ==
                    Option(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Options.run(
                    Options.get(Option(1)).map(_ + 1).map(i => Options.get(Option(i + 1)))
                ).pure ==
                    Option(3)
            )
        }
    }

    "Options.run" - {
        "pure" in {
            assert(
                Options.run(1: Int < Options).pure ==
                    Option(1)
            )
        }
        "not empty" in {
            assert(
                Options.run(Options.get(Option(1))).pure ==
                    Option(1)
            )
        }
        "empty" in {
            assert(
                Options.run(Options.get(Option.empty[Int])).pure ==
                    None
            )
        }
    }

    "orElse" - {
        "empty" in {
            assert(
                Options.run(Options.orElse[Int, Any]()).pure ==
                    None
            )
        }
        "not empty" in {
            assert(
                Options.run(Options.orElse(Options.get(Option(1)))).pure ==
                    Option(1)
            )
        }
        "not empty + empty" in {
            assert(
                Options.run(
                    Options.orElse(Options.get(Option(1)), Options.get(Option.empty[Int]))
                ).pure ==
                    Option(1)
            )
        }
        "empty + not empty" in {
            assert(
                Options.run(
                    Options.orElse(Options.get(Option.empty[Int]), Options.get(Option(1)))
                ).pure ==
                    Option(1)
            )
        }
        "empty + empty" in {
            assert(
                Options.run(Options.orElse(
                    Options.get(Option.empty[Int]),
                    Options.get(Option.empty[Int])
                )).pure ==
                    None
            )
        }
        "not empty + not empty" in {
            assert(
                Options.run(Options.orElse(Options.get(Option(1)), Options.get(Option(2)))).pure ==
                    Option(1)
            )
        }
        "not empty + not empty + not empty" in {
            assert(
                Options.run(Options.orElse(
                    Options.get(Option(1)),
                    Options.get(Option(2)),
                    Options.get(Option(3))
                )).pure ==
                    Option(1)
            )
        }
        "not empty + not empty + not empty + not empty" in {
            assert(
                Options.run(
                    Options.orElse(
                        Options.get(Option(1)),
                        Options.get(Option(2)),
                        Options.get(Option(3)),
                        Options.get(Option(4))
                    )
                ).pure ==
                    Option(1)
            )
        }
        "not empty + not empty + not empty + not empty + not empty" in {
            assert(
                Options.run(
                    Options.orElse(
                        Options.get(Option(1)),
                        Options.get(Option(2)),
                        Options.get(Option(3)),
                        Options.get(Option(4)),
                        Options.get(Option(5))
                    )
                ).pure ==
                    Option(1)
            )
        }
    }

    "getOrElse" - {
        "empty" in {
            assert(
                Options.getOrElse(Option.empty[Int], 1).pure ==
                    1
            )
        }
        "not empty" in {
            assert(
                Options.getOrElse(Some(2), 1).pure ==
                    2
            )
        }
        "or fail" in {
            case object e extends Exception derives CanEqual
            assert(
                IOs.run(IOs.attempt(Options.getOrElse(Option.empty[Int], IOs.fail(e)))).pure ==
                    Failure(e)
            )
            assert(
                IOs.run(IOs.attempt(Options.getOrElse(Some(1), IOs.fail(e)))).pure ==
                    Success(1)
            )
        }
    }
end optionsTest

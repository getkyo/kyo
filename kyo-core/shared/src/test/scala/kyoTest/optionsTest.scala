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
                Options.run(Options(null: String)) ==
                    None
            )
        }
        "value" in {
            assert(
                Options.run(Options("hi")) ==
                    Option("hi")
            )
        }
    }

    "pure" - {
        "handle" in {
            assert(
                Options.run(1: Int < Options) ==
                    Option(1)
            )
        }
        "handle + transform" in {
            assert(
                Options.run((1: Int < Options).map(_ + 1)) ==
                    Option(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Options.run((1: Int < Options).map(i => Options.get(Option(i + 1)))) ==
                    Option(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Options.run((1: Int < Options).map(_ + 1).map(i => Options.get(Option(i + 1)))) ==
                    Option(3)
            )
        }
    }

    "effectful" - {
        "handle" in {
            assert(
                Options.run(Options.get(Option(1))) ==
                    Option(1)
            )
        }
        "handle + transform" in {
            assert(
                Options.run(Options.get(Option(1)).map(_ + 1)) ==
                    Option(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Options.run(Options.get(Option(1)).map(i => Options.get(Option(i + 1)))) ==
                    Option(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Options.run(
                    Options.get(Option(1)).map(_ + 1).map(i => Options.get(Option(i + 1)))
                ) ==
                    Option(3)
            )
        }
    }

    "Options.run" - {
        "pure" in {
            assert(
                Options.run(1: Int < Options) ==
                    Option(1)
            )
        }
        "not empty" in {
            assert(
                Options.run(Options.get(Option(1))) ==
                    Option(1)
            )
        }
        "empty" in {
            assert(
                Options.run(Options.get(Option.empty[Int])) ==
                    None
            )
        }
    }

    "orElse" - {
        "empty" in {
            assert(
                Options.run(Options.orElse[Int, Any]()) ==
                    None
            )
        }
        "not empty" in {
            assert(
                Options.run(Options.orElse(Options.get(Option(1)))) ==
                    Option(1)
            )
        }
        "not empty + empty" in {
            assert(
                Options.run(
                    Options.orElse(Options.get(Option(1)), Options.get(Option.empty[Int]))
                ) ==
                    Option(1)
            )
        }
        "empty + not empty" in {
            assert(
                Options.run(
                    Options.orElse(Options.get(Option.empty[Int]), Options.get(Option(1)))
                ) ==
                    Option(1)
            )
        }
        "empty + empty" in {
            assert(
                Options.run(Options.orElse(
                    Options.get(Option.empty[Int]),
                    Options.get(Option.empty[Int])
                )) ==
                    None
            )
        }
        "not empty + not empty" in {
            assert(
                Options.run(Options.orElse(Options.get(Option(1)), Options.get(Option(2)))) ==
                    Option(1)
            )
        }
        "not empty + not empty + not empty" in {
            assert(
                Options.run(Options.orElse(
                    Options.get(Option(1)),
                    Options.get(Option(2)),
                    Options.get(Option(3))
                )) ==
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
                ) ==
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
                ) ==
                    Option(1)
            )
        }
    }

    "getOrElse" - {
        "empty" in {
            assert(
                Options.getOrElse(Option.empty[Int], 1) ==
                    1
            )
        }
        "not empty" in {
            assert(
                Options.getOrElse(Some(2), 1) ==
                    2
            )
        }
        "or fail" in {
            val e = new Exception()
            assert(
                IOs.run(IOs.attempt(Options.getOrElse(Option.empty[Int], IOs.fail(e)))) ==
                    Failure(e)
            )
            assert(
                IOs.run(IOs.attempt(Options.getOrElse(Some(1), IOs.fail(e)))) ==
                    Success(1)
            )
        }
    }
end optionsTest

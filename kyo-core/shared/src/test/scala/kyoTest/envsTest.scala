package kyoTest

import kyo._
import kyo.envs._
import kyo.options._

class envsTest extends KyoTest {

  "value" in {
    val v1 =
      Envs[Int].get.map(_ + 1)
    val v2: Int > Envs[Int] = v1
    checkEquals[Int, Any](
        Envs[Int].run(1)(v2),
        2
    )
  }

  "pure services" - {

    trait Service1 {
      def apply(i: Int): Int
    }
    trait Service2 {
      def apply(i: Int): Int
    }

    val service1 = new Service1 {
      def apply(i: Int) = i + 1
    }
    val service2 = new Service2 {
      def apply(i: Int) = i + 2
    }

    "one service" in {
      val a =
        Envs[Service1].get.map(_(1))
      val b: Int > Envs[Service1] = a
      checkEquals[Int, Any](
          Envs[Service1].run(service1)(a),
          2
      )
    }
    "two services" - {
      val a =
        Envs[Service1].get.map(_(1)).map { i =>
          Envs[Service2].get.map(_(i))
        }
      val v: Int > (Envs[Service1] with Envs[Service2]) = a
      "same handling order" in {
        checkEquals[Int, Any](
            Envs[Service1].run[Int, Envs[Service2]](service1)(Envs[Service2].run(service2)(v)),
            4
        )
      }
      "reverse handling order" in {
        checkEquals[Int, Any](
            Envs[Service2].run[Int, Envs[Service1]](service2)(Envs[Service1].run(service1)(v)),
            4
        )
      }
      "dependent services" in {
        val s1 = Envs[Service2].get.map(service2 =>
          new Service1 {
            def apply(i: Int) = service2(i * 10)
          }
        )
        val v1: Service1 > Envs[Service2] = s1
        val v2 =
          Envs[Service1].run[Int, Envs[Service2]](service1)(v)
        val v3: Int > Envs[Service2] = v2
        checkEquals[Int, Any](
            Envs[Service2].run(service2)(v3),
            4
        )
      }
    }
  }

  "effectful services" - {

    trait Service1 {
      def apply(i: Int): Int > Options
    }
    trait Service2 {
      def apply(i: Int): Int > Options
    }

    val service1 = new Service1 {
      def apply(i: Int) = i match {
        case 0 => Options.get(Option.empty[Int])
        case i => i + 1
      }
    }
    val service2 = new Service2 {
      def apply(i: Int) = i match {
        case 0 => Options.get(Some(1))
        case i => i + 1
      }
    }

    "one service" - {
      "continue" in {
        val a =
          Envs[Service1].get.map(_(1))
        val b: Int > (Envs[Service1] with Options) = a
        checkEquals[Option[Int], Any](
            Options.run(Envs[Service1].run(service1)(a)),
            Some(2)
        )
      }
      "short circuit" in {
        val a =
          Envs[Service1].get.map(_(0))
        val b: Int > (Envs[Service1] with Options) = a
        checkEquals[Option[Int], Any](
            Options.run(Envs[Service1].run(service1)(a)),
            None
        )
      }
    }
    "two services" - {
      "continue" - {
        val a =
          Envs[Service1].get.map(_(1)).map { i =>
            Envs[Service2].get.map(_(i))
          }
        val v: Int > (Envs[Service1] with Envs[Service2] with Options) = a
        "same handling order" in {
          checkEquals[Option[Int], Any](
              Options.run(Envs[Service1].run(service1)(
                  Envs[Service2].run(service2)(v)
              ): Int > Options),
              Option(3)
          )
        }
        "reverse handling order" in {
          checkEquals[Option[Int], Any](
              Options.run(Envs[Service2].run(service2)(
                  Envs[Service1].run(service1)(v)
              ): Int > Options),
              Option(3)
          )
        }
        "dependent services" in {
          val s1 = Envs[Service2].get.map(service2 =>
            new Service1 {
              def apply(i: Int) = service2(i * 10)
            }
          )
          val v1: Service1 > Envs[Service2]           = s1
          val v2: Int > (Envs[Service2] with Options) = Envs[Service1].run(service1)(v)
          checkEquals[Option[Int], Any](
              Options.run(Envs[Service2].run(service2)(v2)),
              Some(3)
          )
        }
      }
    }
  }

}

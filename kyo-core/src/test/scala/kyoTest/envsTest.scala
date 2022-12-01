package kyoTest

import kyo.core._
import kyo.envs._
import kyo.options._

class envsTest extends KyoTest {

  "value" in {
    val v1 =
      Envs[Int](_ + 1)
    val v2: Int > Envs[Int] = v1
    checkEquals[Int, Nothing](
        Envs.let(1)(v2),
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
        Envs[Service1](_(1))
      val b: Int > Envs[Service1] = a
      checkEquals[Int, Nothing](
          Envs.let(service1)(a),
          2
      )
    }
    "two services" - {
      val a =
        Envs[Service1](_(1)) { i =>
          Envs[Service2](_(i))
        }
      val v: Int > (Envs[Service1] | Envs[Service2]) = a
      "same handling order" in {
        checkEquals[Int, Nothing](
            Envs.let(service1)(Envs.let(service2)(v)),
            4
        )
      }
      "reverse handling order" in {
        checkEquals[Int, Nothing](
            Envs.let(service2)(Envs.let(service1)(v)),
            4
        )
      }
      "dependent services" in {
        val s1 = Envs[Service2](service2 =>
          new Service1 {
            def apply(i: Int) = service2(i * 10)
          }
        )
        val v1: Service1 > Envs[Service2] = s1
        val v2 =
          Envs.let(service1)(v)
        val v3: Int > Envs[Service2] = v2
        checkEquals[Int, Nothing](
            Envs.let(service2)(v3),
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
        case 0 => Option.empty[Int] > Options
        case i => i + 1
      }
    }
    val service2 = new Service2 {
      def apply(i: Int) = i match {
        case 0 => Some(1) > Options
        case i => i + 1
      }
    }

    "one service" - {
      "continue" in {
        val a =
          Envs[Service1](_(1))
        val b: Int > (Envs[Service1] | Options) = a
        checkEquals[Option[Int], Nothing](
            Envs.let(service1)(a) < Options,
            Some(2)
        )
      }
      "short circuit" in {
        val a =
          Envs[Service1](_(0))
        val b: Int > (Envs[Service1] | Options) = a
        checkEquals[Option[Int], Nothing](
            Envs.let(service1)(a) < Options,
            None
        )
      }
    }
    "two services" - {
      "continue" - {
        val a =
          Envs[Service1](_(1)) { i =>
            Envs[Service2](_(i))
          }
        val v: Int > (Envs[Service1] | Envs[Service2] | Options) = a
        "same handling order" in {
          checkEquals[Option[Int], Nothing](
              (Envs.let(service1)(Envs.let(service2)(v)): Int > Options) < Options,
              Option(3)
          )
        }
        "reverse handling order" in {
          checkEquals[Option[Int], Nothing](
              (Envs.let(service2)(Envs.let(service1)(v)): Int > Options) < Options,
              Option(3)
          )
        }
        "dependent services" in {
          val s1 = Envs[Service2](service2 =>
            new Service1 {
              def apply(i: Int) = service2(i * 10)
            }
          )
          val v1: Service1 > Envs[Service2]        = s1
          val v2: Int > (Envs[Service2] | Options) = Envs.let(service1)(v)
          checkEquals[Option[Int], Nothing](
              Envs.let(service2)(v2) < Options,
              Some(3)
          )
        }
      }
    }
  }

}

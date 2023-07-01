package kyo

import kyo.concurrent.fibers._
import kyo.envs._
import kyo.ios.IOs

object scopes {

  abstract class Scope[E] { self =>

    def run[T, S](v: T > (E with S)): T > S > (E with S)

    def andThen[E2](implicit s2: Scope[E2]): Scope[E with E2] =
      new Scope[E with E2] {
        def run[T, S](v: T > (E with E2 with S)) =
          self.run[T, E2 with S](v).map(s2.run[T, S](_))
      }
  }

  object Scope {

    def apply[E1: Scope]: Scope[E1] =
      implicitly[Scope[E1]]

    def apply[E1: Scope, E2: Scope]: Scope[E1 with E2] =
      Scope[E1].andThen[E2]

    def apply[E1: Scope, E2: Scope, E3: Scope]: Scope[E1 with E2 with E3] =
      Scope[E1, E2].andThen[E3]

    def apply[E1: Scope, E2: Scope, E3: Scope, E4: Scope]: Scope[E1 with E2 with E3 with E4] =
      Scope[E1, E2, E3].andThen[E4]

  }

  implicit val identity: Scope[Any] =
    new Scope[Any] {
      def run[T, S](v: T > (Any with S)) = v
    }
}

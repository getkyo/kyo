package kyo

import kyo.concurrent.fibers._
import kyo.envs._
import kyo.ios.IOs

import scala.util.NotGiven

object scopes {

  abstract class Scope[E] { self =>

    def run[T, S](v: T > (E with S)): T > (E with S) =
      sandbox(v)(identity)

    def sandbox[T, U, S1, S2](v: T > (E with S1))(f: T > S1 => U > S2): U > (E with S1 with S2)

    def andThen[E2](implicit s2: Scope[E2]): Scope[E with E2] =
      new Scope[E with E2] {
        def sandbox[T, U, S1, S2](v: T > (E with E2 with S1))(f: T > S1 => U > S2) =
          self.sandbox(v)(s2.sandbox(_)(f))
      }
  }

  object Scopes {

    def apply[E1](implicit s: Scope[E1]): Scope[E1] =
      s

    def apply[E1: Scope, E2: Scope]: Scope[E1 with E2] =
      Scopes[E1].andThen[E2]

    def apply[E1: Scope, E2: Scope, E3: Scope]: Scope[E1 with E2 with E3] =
      Scopes[E1, E2].andThen[E3]

    def apply[E1: Scope, E2: Scope, E3: Scope, E4: Scope]: Scope[E1 with E2 with E3 with E4] =
      Scopes[E1, E2, E3].andThen[E4]

  }
}

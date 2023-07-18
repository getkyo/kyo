package kyo

import kyo.concurrent.fibers._
import kyo.envs._
import kyo.ios.IOs
import kyo.scopes.Scopes.Op

object scopes {

  abstract class Scopes[E] { self =>

    def sandbox[S1, S2](f: Scopes.Op[S1, S2]): Scopes.Op[E with S1, E with S1 with S2]

    def run[T, S](v: T > (E with S)): T > (E with S) =
      sandbox(Scopes.Op.identity[S])(v)

    def andThen[E2](implicit s2: Scopes[E2]): Scopes[E with E2] =
      new Scopes[E with E2] {
        def sandbox[S1, S2](f: Scopes.Op[S1, S2]) =
          s2.sandbox[E with S1, E with (S1 with S2)](self.sandbox[S1, S2](f))
      }
  }

  object Scopes {

    abstract class Op[S1, S2] {
      def apply[T](v: T > S1): T > (S1 with S2)
    }

    object Op {
      def identity[S] = new Op[S, S] {
        def apply[T](v: T > S) = v
      }
    }

    def apply[E1: Scopes]: Scopes[E1] =
      implicitly[Scopes[E1]]

    def apply[E1: Scopes, E2: Scopes]: Scopes[E1 with E2] =
      Scopes[E1].andThen[E2]

    def apply[E1: Scopes, E2: Scopes, E3: Scopes]: Scopes[E1 with E2 with E3] =
      Scopes[E1, E2].andThen[E3]

    def apply[E1: Scopes, E2: Scopes, E3: Scopes, E4: Scopes]: Scopes[E1 with E2 with E3 with E4] =
      Scopes[E1, E2, E3].andThen[E4]
  }

  implicit val identityScope: Scopes[Any] =
    new Scopes[Any] {
      def sandbox[S1, S2](f: Scopes.Op[S1, S2]) =
        new Scopes.Op[(Any with S1), (Any with S1 with S2)] {
          def apply[T](v: T > (Any with S1)) = v
        }
    }
}

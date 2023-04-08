package kyo

import locals._
import core._
import envs._
import kyo.clocks.Clocks
import kyo.consoles.Consoles
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs
import kyo.randoms.Randoms
import kyo.resources.Resources
import kyo.concurrent.timers.Timers

object aspects {

  opaque type Aspects = Envs[Map[Aspect[_, _, _], Any]]

  object Aspects {
    def init[T, U, S]: Aspect[T, U, S] = new Aspect[T, U, S]

    def run[T, S](v: T > (S | Aspects)): T > S =
      envs.let(Map.empty)(v)
  }

  class Aspect[T, U, S1] { aspect =>

    def apply[S](v: T)(f: T => U > (S | Aspects)): U > (S | S1 | Aspects) =
      envs.get { map =>
        map.get(aspect) match {
          case Some(h: Handle @unchecked) =>
            envs.let(map - aspect) {
              h(v)(f)
            }
          case _ =>
            f(v)
        }
      }

    trait Handle { handle =>

      def apply[S](v: T)(f: T => U > (S | S1 | Aspects)): U > (S | S1 | Aspects)

      def apply[V, S](v: => V > (S | S1 | Aspects)): V > (S | S1 | Aspects) =
        envs.get { map =>
          val h =
            map.get(aspect) match {
              case Some(g: Handle @unchecked) =>
                new Handle {
                  def apply[S2](v: T)(f: T => U > (S1 | S2 | Aspects)) =
                    g(v) { v =>
                      envs.let(map) {
                        handle(v)(f)
                      }
                    }
                }
              case _ =>
                handle
            }
          envs.let(map + (aspect -> h))(v)
        }

    }
  }
  private val envs = Envs[Map[Aspect[_, _, _], Any]]
}

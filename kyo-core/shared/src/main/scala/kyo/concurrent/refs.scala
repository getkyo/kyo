package kyo.concurrent

import kyo._
import kyo.ios._
import kyo.sums._
import kyo.choices._
import kyo.concurrent.fibers._
import kyo.concurrent.meters._
import kyo.concurrent.atomics._
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

object test extends kyo.App {
  import refs._
  import kyo.consoles._
  import kyo.concurrent.timers._
  import scala.concurrent.duration._

  def fetch(fork: Ref[Option[String]], philosopher: String, order: Boolean): Boolean > Refs =
    fork.get.map {
      case None =>
        fork.set(Some(philosopher)).andThen(true)
      case _ =>
        false
    }

  val philosophers = List("Aristotle", "Hippocrates", "Plato", "Pythagoras", "Socrates")

  val io =
    for {
      forks <- Choices.collect(List.fill(philosophers.length)(Refs.init(Option.empty[String])))
    } yield {
      Fibers.parallel {
        philosophers.zipWithIndex.map { case (philosopher, index) =>
          def loop(meals: Int): Unit > (Fibers with IOs with Consoles with Timers) =
            if (meals == 0) {
              ()
            } else {
              val leftFork  = forks(index)
              val rightFork = forks((index + 1) % philosophers.length)
              val (firstFork, secondFork) =
                if (index % 2 == 0) (leftFork, rightFork) else (rightFork, leftFork)

              Refs.run[Unit, Fibers with IOs with Consoles with Timers] {
                Consoles.println(
                    s"$philosopher thinking. Remaining: $meals"
                ).andThen {
                  Fibers.sleep(1.millis).andThen {
                    fetch(firstFork, philosopher, true).map {
                      case true =>
                        fetch(secondFork, philosopher, false).map {
                          case true =>
                            Consoles.println(
                                s"$philosopher eating. Remaining: $meals"
                            )
                              .andThen(Fibers.sleep(1.millis))
                              .andThen {
                                firstFork.set(None).andThen(secondFork.set(None)).andThen(loop(
                                    meals - 1
                                ))
                              }
                          case false =>
                            firstFork.set(None).andThen(loop(meals))
                        }
                      case false =>
                        loop(meals)
                    }
                  }
                }
              }
            }
          Timers.run(Consoles.run(loop(100)))
        }
      }
    }

  def run(l: List[String]) = Refs.run(io).unit
}

object refs {

  type Refs = Sums[Refs.Log] with Fibers with IOs

  private val sums = Sums[Refs.Log]

  trait Ref[+T] {
    def get: T > Refs
    def set[B >: T](v: B): Unit > Refs
    def version: Long > Refs
    private[refs] def sync[B >: T](st: Refs.State.Write[B]): Unit > Refs
  }

  object Refs {

    sealed trait State[+T] {
      def value: T
      def version: Long
    }
    object State {
      case class Read[+T](value: T, version: Long)  extends State[T]
      case class Write[+T](value: T, version: Long) extends State[T]
    }

    type Log = Map[Ref[Any], State[Any]]

    private val nextTransactionId = IOs.run(Atomics.initLong(0))
    private val mutex             = IOs.run(Meters.mutex)

    def init[T](v: T): Ref[T] > Refs =
      Atomics.initRef(State.Write(v, 0L)).map { state =>
        new Ref[T] {

          val get: T > Refs =
            sums.get.map { st =>
              st.get(this) match {
                case None =>
                  IOs(state.get).map { snapshot =>
                    sums
                      .add(Map(this -> snapshot))
                      .map(_ => snapshot.value)
                  }
                case Some(s) =>
                  s.asInstanceOf[State[T]].value
              }
            }

          def set[B >: T](v: B): Unit > Refs =
            IOs(state.get).map { snapshot =>
              sums
                .add(Map(this -> State.Write(v, snapshot.version + 1)))
                .unit
            }

          def version = state.get.map(_.version)

          private[kyo] def sync[B >: T](st: State.Write[B]): Unit =
            state.set(st.asInstanceOf[State.Write[T]])
        }
      }

    def run[T, S](v: T > (Refs with S)): T > (Fibers with IOs with S) = {
      nextTransactionId.incrementAndGet.map { tid =>
        sums.run[Option[T], S with Fibers with IOs] {
          v.map { r =>
            sums.get.map { st =>
              val log = st.toList
              mutex.run {
                def validate(log: List[(Ref[Any], State[Any])]): Boolean > Refs =
                  log match {
                    case Nil => true
                    case (ref, st) :: tail =>
                      ref.version.map { v =>
                        if (v >= 0 && v < tid) {
                          validate(tail)
                        } else {
                          false
                        }
                      }
                  }
                def commit(log: List[(Ref[Any], State[Any])]): Unit > Refs =
                  log match {
                    case Nil => ()
                    case (ref, st: State.Write[Any]) :: tail =>
                      ref.sync(st).andThen(commit(tail))
                    case _ :: tail =>
                      commit(tail)
                  }
                validate(log).map {
                  case true =>
                    commit(log).andThen(Some(r))
                  case false =>
                    None
                }
              }
            }
          }
        }
      }.map[T, Fibers with IOs with S] {
        case Some(r) =>
          r
        case _ =>
          run[T, Fibers with IOs with S](v)
      }
    }

    private implicit val logSummer: Summer[Refs.Log] =
      Summer[Refs.Log](Map.empty) { (a, b) =>
        (a.keySet ++ b.keySet).map { key =>
          val value =
            ((a.get(key), b.get(key)): @unchecked) match {
              case (Some(stateA), Some(stateB)) =>
                if (stateA.version == stateB.version) {
                  stateB
                } else {
                  State.Write(stateA.value, -1)
                }
              case (Some(stateA), None) => stateA
              case (None, Some(stateB)) => stateB
            }
          key -> value
        }.toMap
      }
  }
}

package kyo.llm

import kyo._

import scala.util.Success
import scala.util.Failure
import scala.util.hashing.MurmurHash3
import scala.concurrent.duration._

import Listener._

object Listeners {

  def silent[T, S](v: T < S): T < (AIs & S) =
    listeners.let(Nil)(v)

  def observe[T, S](event: String)(v: T < S)(implicit f: Flat[T < S]): T < (AIs & S) =
    listeners.get.map { l =>
      def loop(l: List[Listener]): T < (AIs & S) =
        l match {
          case Nil => v
          case h :: t =>
            h(event)(loop(t))
        }
      loop(l)
    }
  def listen[T, S](f: State => Unit < IOs, interval: Duration)(v: T < S): T < (AIs & S) =
    Atomics.initRef[State](State(Nil)).map { ref =>
      val l = new Listener(ref)
      Fibers.init {
        def loop(curr: State): Unit < Fibers =
          Fibers.sleep(interval).andThen {
            ref.get.map {
              case `curr` => loop(curr)
              case curr   => f(curr).andThen(loop(curr))
            }
          }
        ref.get.map(loop)
      }.map { fiber =>
        IOs.ensure(fiber.interrupt.unit.andThen(ref.get.map(f))) {
          listeners.update(l :: _)(v)
        }
      }
    }
}

private val parent    = Locals.init(0)
private val listeners = Locals.init(List.empty[Listener])

private class Listener(
    state: AtomicRef[State]
) {
  def apply[T, S](event: String)(v: T < S): T < (AIs & S) =
    parent.get.map { p =>
      val id = (p, event, v).hashCode
      state.update(_.upsert(p, Task(id, event, Status.Running, Nil))).andThen {
        parent.let(id)(IOs.attempt(v)).map {
          case Success(v) =>
            state.update(_.upsert(p, Task(id, event, Status.Done, Nil)))
              .andThen(v)
          case Failure(ex) =>
            state.update(_.upsert(p, Task(id, event, Status.Failed, Nil)))
              .andThen(IOs.fail(ex))
        }
      }
    }
}

object Listener {

  case class State(tasks: List[Task]) {
    private[kyo] def upsert(parent: Int, task: Task): State =
      parent match {
        case 0 =>
          val c = tasks.map(c =>
            if (c.id == task.id) task.copy(children = c.children ::: task.children) else c
          )
          if (c == tasks) {
            State(tasks :+ task)
          } else {
            State(c)
          }
        case p =>
          def loop(l: List[Task], acc: List[Task] = Nil): List[Task] =
            l match {
              case Nil =>
                acc.reverse
              case h :: t if (h.id == p) =>
                val c = h.children.map(c =>
                  if (c.id == task.id) task.copy(children = c.children ::: task.children) else c
                )
                if (c == h.children) {
                  (acc.reverse :+ h.copy(children = h.children :+ task)) ::: t
                } else {
                  (acc.reverse :+ h.copy(children = c)) ::: t
                }
              case h :: t =>
                loop(t, h.copy(children = loop(h.children)) :: acc)
            }
          State(loop(tasks))
      }

    def show: String = {
      def formatTask(task: Task, indent: String = ""): String = {
        val taskLine    = s"${indent}${task.status.show} ${task.desc}\n"
        val childIndent = indent + "      "

        taskLine + task.children.map(child => formatTask(child, childIndent)).mkString
      }

      tasks.map(task => formatTask(task)).mkString
    }

    override def toString: String = show
  }

  case class Task(
      id: Int,
      desc: String,
      status: Status,
      children: List[Task]
  )

  sealed trait Status {
    def show =
      this match {
        case Status.Running => "🕒"
        case Status.Done    => "✅"
        case Status.Failed  => "⛔"
      }
  }
  object Status {
    case object Running extends Status
    case object Done    extends Status
    case object Failed  extends Status
  }
}

package kyo.scheduler

abstract class Preemptable {
  def run(preempt: () => Boolean): Preemptable
}

object Preemptable {
  val Done = new Preemptable {
    def run(preempt: () => Boolean) = this
  }
}
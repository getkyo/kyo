package kyo.bench

import cats.effect.unsafe.implicits.global
import cats.effect.IO

object CatsRuntime {

  def run[A](io: IO[A]): A =
    io.unsafeRunSync()

  def runFork[A](io: IO[A]): A =
    run(IO.cede.flatMap(_ => io))
}

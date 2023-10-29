package kyo.bench

import cats.effect.IO
import kyo._
import kyo.bench.Bench
import kyo.lists._
import kyo.concurrent.fibers._
import kyo.ios._
import zio.UIO
import zio.ZIO

class SchedulingBench extends Bench.ForkOnly[Int] {

  val depth = 1000
  val range = List.range(0, depth)

  def catsBench() = {
    import cats.implicits._

    def fiber(i: Int): IO[Int] =
      IO.cede.flatMap { _ =>
        IO(i).flatMap { j =>
          IO.cede.flatMap { _ =>
            if (j > depth)
              IO.cede.flatMap(_ => IO(j))
            else
              IO.cede.flatMap(_ => fiber(j + 1))
          }
        }
      }

    range.traverse(fiber(_).start)
      .flatMap(_.traverse(_.joinWithNever))
      .map(_.sum)
  }

  override def kyoBenchFiber() = {

    def fiber(i: Int): Int > IOs =
      IOs.unit.flatMap { _ =>
        IOs(i).flatMap { j =>
          IOs.unit.flatMap { _ =>
            if (j > depth)
              IOs.unit.flatMap(_ => IOs(j))
            else
              IOs.unit.flatMap(_ => fiber(j + 1))
          }
        }
      }

    Lists.traverse(range) { i =>
      Fibers.forkFiber(fiber(i))
    }.map { fibers =>
      Lists.traverse(fibers)(_.get)
    }.map(_.sum)
  }

  def zioBench() = {
    def fiber(i: Int): UIO[Int] =
      ZIO.yieldNow.flatMap { _ =>
        ZIO.succeed(i).flatMap { j =>
          ZIO.yieldNow.flatMap { _ =>
            if (j > depth)
              ZIO.yieldNow.flatMap(_ => ZIO.succeed(j))
            else
              ZIO.yieldNow.flatMap(_ => fiber(j + 1))
          }
        }
      }

    ZIO.foreach(range) { n =>
      fiber(n).forkDaemon
    }.flatMap { list =>
      ZIO.foreach(list)(_.join)
    }.map(_.sum)
  }
}

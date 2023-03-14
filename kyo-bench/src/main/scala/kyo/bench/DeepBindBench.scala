package kyo.bench

import cats.effect.IO
import kyo.core._
import kyo.ios._
import org.openjdk.jmh.annotations._
import zio.UIO
import zio.ZIO

import java.util.concurrent.atomic.AtomicInteger

import kyo.bench.Bench

class DeepBindBench extends Bench[Unit] {

  val depth = 10000

  def kyoBench() = {
    def loop(i: Int): Unit > IOs =
      IOs {
        if (i > depth)
          ()
        else
          loop(i + 1)
      }
    loop(0)
  }

  def catsBench() = {
    def loop(i: Int): IO[Unit] =
      IO.unit.flatMap { _ =>
        if (i > depth)
          IO.unit
        else
          loop(i + 1)
      }
    loop(0)
  }

  def zioBench() = {
    def loop(i: Int): UIO[Unit] =
      ZIO.unit.flatMap { _ =>
        if (i > depth)
          ZIO.unit
        else
          loop(i + 1)
      }
    loop(0)
  }
}

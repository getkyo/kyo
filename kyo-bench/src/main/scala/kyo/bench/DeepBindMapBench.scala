package kyo.bench

import cats.effect.IO
import kyo.arrows._
import kyo._
import kyo.ios.IOs
import kyo.concurrent.scheduler.Scheduler
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole
import zio.UIO
import zio.ZIO

import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util._

import kyo.bench.Bench

import kyo.concurrent.scheduler.Scheduler

class DeepBindMapBench extends Bench[Int] {

  val depth = 10000

  def kyoBench() = {
    def loop(i: Int): Int > IOs =
      IOs {
        if (i > depth) i
        else
          IOs(i + 11).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1)
            .map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(_ - 1).map(loop)
      }
    loop(0)
  }

  def catsBench() = {
    def loop(i: Int): IO[Int] =
      IO.unit.flatMap { _ =>
        if (i > depth)
          IO.pure(i)
        else
          IO(i + 11)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .flatMap(loop)
      }
    loop(0)
  }

  def zioBench() = {
    def loop(i: Int): UIO[Int] =
      ZIO.unit.flatMap { _ =>
        if (i > depth)
          ZIO.succeed(i)
        else
          ZIO.unit
            .map(_ => (i + 11))
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .map(_ - 1)
            .flatMap(loop)
      }
    loop(0)
  }
}

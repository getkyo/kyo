package kyo.test

import zio.{UIO, Trace}
import zio.stacktracer.TracingImplicits.disableAutoTrace

trait Restorable extends Serializable {
  def save(implicit trace: Trace): UIO[UIO[Unit]]
}

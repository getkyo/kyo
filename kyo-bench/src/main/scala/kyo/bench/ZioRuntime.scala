package kyo.bench

object ZioRuntime {

  private val zioRuntime = zio.Runtime.default

  def run[A](io: zio.ZIO[Any, Throwable, A]): A =
    zio.Unsafe.unsafe(implicit u =>
      zioRuntime.unsafe.run(io).getOrThrow()
    )

  def runFork[A](io: zio.ZIO[Any, Throwable, A]): A =
    run(zio.ZIO.yieldNow.flatMap(_ => io))
}

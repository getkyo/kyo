package kyo.bench

import sttp.tapir._
import sttp.client3._
import org.openjdk.jmh.annotations._

@State(Scope.Benchmark)
class KyoState {
  import kyo._
  import kyo.routes._
  import kyo.server.NettyKyoServer
  val server =
    App.run {
      Routes.run(NettyKyoServer().port(9999)) {
        Routes.add[Unit, String, Any](
            _.get.in("ping").out(stringBody)
        ) { _ =>
          "pong"
        }
      }
    }
  @TearDown
  def stop(): Unit =
    App.run(server.stop())
}

@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = Array("-Dcats.effect.tracing.mode=DISABLED"))
@BenchmarkMode(Array(Mode.Throughput))
class TapirHttpBench {

  val depth = 10000

  def kyoBench() = {
    import kyo._
    import kyo.requests._
    import kyo.tries._
    Tries.run {
      Requests.run {
        Requests(_.get(uri"http://localhost:9999/ping"))
      }
    }
  }

  @Benchmark
  def forkKyo(s: KyoState) = {
    import kyo._
    import kyo.ios._
    import kyo.concurrent.fibers._
    IOs.run(Fibers.forkFiber(kyoBench()).flatMap(_.block))
  }
}

package kyo

import ZIOs.toExit
import zio.Cause
import zio.Exit
import zio.Runtime
import zio.Scope
import zio.Tag as ZTag
import zio.Trace
import zio.Unsafe
import zio.ZEnvironment
import zio.ZLayer

object ZLayers:

    /** Lifts a zio.ZLayer into a Kyo Layer.
      *
      * This function automatically handles resource scoping.
      *
      * @param layer
      *   The zio.ZLayer to lift
      * @return
      *   A Kyo Layer that, when run, will instantiate the resource from the ZLayer
      */
    def get[E, A: ZTag: Tag](layer: => ZLayer[Any, E, A])(using Frame, Trace): Layer[A, Abort[E] & Async & Resource] =
        Layer {
            Sync.Unsafe {
                val scope = Unsafe.unsafely(Scope.unsafe.make)

                Resource.ensure(ex => ZIOs.get(scope.close(ex.fold(Exit.unit)(_.toExit)))).andThen:
                    ZIOs.get(layer.build(scope).map(_.get[A]))
            }
        }
    end get

    /** Interprets a Kyo Layer with environment dependencies to ZLayer.
      *
      * @param layer
      *   The Kyo Layer to run
      * @return
      *   A zio.ZLayer that requires R and produces A
      */
    def run[A: ZTag: Tag, E](layer: Layer[A, Abort[E] & Async])(using frame: Frame, trace: zio.Trace): ZLayer[Any, E, A] =
        ZLayer.fromZIO(ZIOs.run(Memo.run(layer.run).map(_.get[A])))
    end run

end ZLayers

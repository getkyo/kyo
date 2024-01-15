package sttp.client3

import sttp.client3.internal.httpclient.Sequencer
import kyo.internal.KyoSttpMonad
import kyo.meters.Meter
import kyo.fibers.Fibers
import kyo.ios.IOs

class KyoSequencer(mutex: Meter) extends Sequencer[KyoSttpMonad.M] {

  def apply[T](t: => KyoSttpMonad.M[T]) =
    mutex.run[T, Fibers](t)
}

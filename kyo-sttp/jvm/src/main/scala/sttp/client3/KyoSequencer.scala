package sttp.client3

import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.httpclient.Sequencer

class KyoSequencer(mutex: Meter) extends Sequencer[KyoSttpMonad.M]:

    def apply[T](t: => KyoSttpMonad.M[T]) =
        mutex.run[T, Fibers](t)
end KyoSequencer

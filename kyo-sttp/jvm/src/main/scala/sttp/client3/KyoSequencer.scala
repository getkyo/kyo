package sttp.client3

import kyo.*
import kyo.internal.KyoSttpMonad
import sttp.client3.internal.httpclient.Sequencer

class KyoSequencer(mutex: Meter) extends Sequencer[KyoSttpMonad.M]:

    def apply[A](t: => KyoSttpMonad.M[A]) =
        Abort.run(mutex.run(t)).map(_.getOrThrow) // safe since the meter will never be closed
end KyoSequencer

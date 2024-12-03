package kyo

/** An acknowledgement type used to control emission of values.
  *
  * It is either a [[Continue]] with a positive maximum number of values to emit, or [[Stop]] to indicate that no more values should be
  * emitted.
  */
opaque type Ack = Int
object Ack:
    given CanEqual[Ack, Ack]         = CanEqual.derived
    inline given Flat[Ack]           = Flat.unsafe.bypass
    inline given Flat[Ack.Stop.type] = Flat.unsafe.bypass
    inline given Flat[Ack.Continue]  = Flat.unsafe.bypass

    /** Creates an [[Ack]] from a maximum number of values to emit.
      *
      * @param maxValues
      *   The mamximum number of values to emit
      * @return
      *   [[Continue]] if the maximum number of values is positive, [[Stop]] otherwise
      */
    def apply(maxValues: Int): Ack = Math.max(0, maxValues)

    extension (self: Ack)
        /** Limits the acknowledgement to a maximum number of values.
          *
          * If this acknowledgement is [[Stop]] or `n` is non-positive then the returned acknowledgement is [[Stop]]. Otherwise, if this
          * acknowledgement is [[Continue]] then the returned acknowledgement is [[Continue]] with the minimum of the current maximum number
          * of values and `n`.
          *
          * @param n
          *   The maximum number of values to emit
          * @return
          *   [[Continue]] if the minimum of the current maximum number of values and `n` is positive, [[Stop]] otherwise
          */
        def maxValues(n: Int): Ack = Ack(Math.min(self, n))

        /** Chains acknowledgements by executing a function only if not stopped.
          *
          * If the current acknowledgement is [[Stop]], returns [[Stop]] immediately. Otherwise, executes the provided function to get the
          * next acknowledgement.
          *
          * @param f
          *   The function to execute to get the next acknowledgement
          * @return
          *   [[Stop]] if current acknowledgement is [[Stop]], otherwise the result of `f`
          */
        inline def next[S](inline f: => Ack < S): Ack < S =
            if stop then Stop
            else f

        // Workaround for compiler issue with inlined `next`
        private def stop: Boolean = self == Stop
    end extension

    /** Indicates to continue emitting values */
    opaque type Continue <: Ack = Int
    object Continue:
        def apply(): Continue = Int.MaxValue

        def unapply(ack: Ack): Maybe.Ops[Int] = Maybe.when(ack > 0)(ack)
    end Continue

    /** Indicates to stop emitting values */
    val Stop: Ack = 0
end Ack

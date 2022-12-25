package kyo.scheduler

private final class MovingStdDev(exp: Int) {

  private val window = 1 << exp
  private val mask   = window - 1
  private var values = new Array[Long](window)
  private var devs   = new Array[Long](window)
  private var idx    = 0
  private var sum    = 0L
  private var sumDev = 0L

  private var avg           = 0L
  @volatile private var dev = 0L

  def apply(): Long = dev

  def observe(v: Long): Unit =
    val prev = values(idx)
    values(idx) = v
    sum = sum - prev + v
    avg = sum >> exp

    val currDev = Math.abs((v - avg) << 1)
    val prevDev = devs(idx)
    devs(idx) = currDev
    sumDev = sumDev - prevDev + currDev
    dev = Math.sqrt(sumDev >> exp).asInstanceOf[Int]

    idx = (idx + 1) & mask

  override def toString = s"MovingStdDev(avg=$avg,dev=$dev)"

}

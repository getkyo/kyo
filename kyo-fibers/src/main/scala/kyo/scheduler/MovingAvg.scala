package kyo.scheduler

class MovingStdDev(exp: Int) {

  private[this] val window = 1 << exp
  private[this] val mask   = window - 1
  private[this] var values = new Array[Long](window)
  private[this] var devs   = new Array[Long](window)
  private[this] var idx    = 0
  private[this] var sum    = 0L
  private[this] var sumDev = 0L

  private[this] var avg           = 0L
  @volatile private[this] var dev = 0L

  def apply(): Long = dev

  def avgg() = avg

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

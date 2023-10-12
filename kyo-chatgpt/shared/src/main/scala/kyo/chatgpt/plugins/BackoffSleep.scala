package kyo.chatgpt.plugins

import kyo._
import scala.concurrent.duration._
import kyo.concurrent.fibers.Fibers

object BackoffSleep {
  val plugin = Plugins.init[Int, Unit](
      "backoff_sleep_millis",
      "Sleeps for the specified number of milliseconds. Feel free to use this plugin as backoff " +
        "mechanism but use a reasonable backoff policy to ensure you're able to fullfill the " +
        "user's request in a reasonable time frame."
  ) { (ai, v) =>
    Fibers.sleep(v.millis).unit
  }
}

package kyo.concurrent.scheduler

import java.util.concurrent.ThreadFactory

object ThreadFactory {

  def apply(name: String): ThreadFactory = new ThreadFactory

  def apply(name: String, create: Runnable => Thread): ThreadFactory = new ThreadFactory
}

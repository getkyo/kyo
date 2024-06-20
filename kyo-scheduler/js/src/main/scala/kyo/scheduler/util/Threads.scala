package kyo.scheduler.util

import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadFactoryStub

object Threads {

    def apply(name: String): ThreadFactory =
        ThreadFactoryStub.get

    def apply(name: String, create: Runnable => Thread): ThreadFactory =
        ThreadFactoryStub.get
}

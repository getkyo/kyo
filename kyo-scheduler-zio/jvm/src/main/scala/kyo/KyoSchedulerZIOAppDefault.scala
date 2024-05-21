package kyo

import zio.ZIOAppDefault

trait KyoSchedulerZIOAppDefault extends ZIOAppDefault {
    override val runtime = KyoSchedulerZioRuntime.default
}

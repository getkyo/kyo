package kyoTest.scheduler

import kyo.scheduler.Task

given CanEqual[Task, Task] = CanEqual.derived

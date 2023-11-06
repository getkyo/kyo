package kyo.chatgpt.tools

import kyo._
import scala.concurrent.duration._
import kyo.concurrent.fibers.Fibers
import kyo.chatgpt.ais.AIs
import kyo.lists.Lists
import kyo.chatgpt.tools.Tools

object ForkTasks {
  val tool = Tools.init[List[String], List[String]](
      "fork_tasks",
      "This function allows you to fork the execution of sub-tasks to be handled by you in parallel. " +
        "Each task will continue this session in isolation and then later the task results will be provided back to you."
  ) { (ai, tasks) =>
    AIs.ephemeral {
      Lists.traverse(tasks) { task =>
        ai.infer[String](task)
      }
    }
  }
}

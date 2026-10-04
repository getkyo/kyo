package kyo.internal

import kyo.*
import scala.scalajs.js

/** The process-tree operations [[ProcessTree]] composes, on Node.
  *
  * Node has no API for another process's children. On Linux they are read from `/proc`; elsewhere from `ps`, the one portable source.
  * Signals go through `process.kill`, which throws for a pid that is gone and for a signal the platform lacks, such as SIGSTOP on Windows.
  */
private[kyo] object ProcessTreePlatform:

    private def isLinux: Boolean = js.Dynamic.global.process.platform.asInstanceOf[String] == "linux"

    def children(parents: Chunk[Long])(using Frame): Chunk[Long] < Async =
        val wanted = parents.toSet
        if isLinux then Sync.defer(linuxChildren(wanted))
        else
            Abort.run[CommandException](Command("ps", "-A", "-o", "pid=", "-o", "ppid=").text).map {
                case Result.Success(table) =>
                    Chunk.from(table.linesIterator.flatMap { line =>
                        line.trim.split("\\s+") match
                            case Array(pid, ppid) => ppid.toLongOption.filter(wanted.contains).flatMap(_ => pid.toLongOption)
                            case _                => None
                    }.toSeq)
                case _ => Chunk.empty
            }
        end if
    end children

    def stop(pids: Chunk[Long])(using Frame): Unit < Sync = signal(pids, "SIGSTOP")

    def kill(pids: Chunk[Long])(using Frame): Unit < Sync = signal(pids, "SIGKILL")

    def exists(pid: Long)(using Frame): Boolean < Sync =
        Sync.defer {
            try
                discard(js.Dynamic.global.process.kill(pid.toDouble, 0))
                true
            catch
                case e: js.JavaScriptException =>
                    val code = e.exception.asInstanceOf[js.Dynamic].code
                    js.typeOf(code) == "string" && code.asInstanceOf[String] == "EPERM"
        }

    private def signal(pids: Chunk[Long], name: String)(using Frame): Unit < Sync =
        Sync.defer {
            pids.foreach { pid =>
                try discard(js.Dynamic.global.process.kill(pid.toDouble, name))
                catch case _: js.JavaScriptException => ()
            }
        }

    // Each /proc/<pid>/stat reads `pid (comm) state ppid ...`; comm may contain spaces and parentheses, so fields are read after the
    // last `)`.
    private def linuxChildren(parents: Set[Long]): Chunk[Long] =
        val fs      = NodeFsModule.asInstanceOf[js.Dynamic]
        val entries =
            try fs.readdirSync("/proc").asInstanceOf[js.Array[String]].toSeq
            catch case _: js.JavaScriptException => Seq.empty
        Chunk.from(entries.filter(name => name.nonEmpty && name.forall(_.isDigit)).flatMap { name =>
            val stat =
                try Some(fs.readFileSync(s"/proc/$name/stat", "utf8").asInstanceOf[String])
                catch case _: js.JavaScriptException => None
            stat.flatMap { text =>
                val fields = text.substring(text.lastIndexOf(')') + 1).trim.split(" ")
                if fields.length > 1 then fields(1).toLongOption.filter(parents.contains).map(_ => name.toLong)
                else None
            }
        })
    end linuxChildren

end ProcessTreePlatform

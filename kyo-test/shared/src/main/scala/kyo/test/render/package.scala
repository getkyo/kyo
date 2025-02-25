package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.LogLine.Fragment.Style
import zio.test.render.LogLine.{Fragment, Line}

package object render {

  def info(s: String): Fragment                    = Fragment(s, Style.Info)
  def error(s: String): Fragment                   = Fragment(s, Style.Error)
  def warn(s: String): Fragment                    = Fragment(s, Style.Warning)
  def primary(s: String): Fragment                 = Fragment(s, Style.Primary)
  def detail(s: String): Fragment                  = Fragment(s, Style.Detail)
  def fr(s: String): Fragment                      = Fragment(s, Style.Default)
  def dim(s: String): Fragment                     = Fragment(s, Style.Dimmed)
  def bold(s: String): Fragment                    = fr(s).bold
  def underlined(s: String): Fragment              = fr(s).underlined
  def ansi(s: String, ansiColor: String): Fragment = fr(s).ansi(ansiColor)
  val sp: Fragment                                 = Fragment(" ")

  def withOffset(i: Int)(line: LogLine.Line): Line = line.withOffset(i)
}

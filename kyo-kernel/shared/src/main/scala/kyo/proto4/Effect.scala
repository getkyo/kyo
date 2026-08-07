package kyo.proto4

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Tag
import language.implicitConversions
import scala.annotation.nowarn
import scala.annotation.static
import scala.annotation.tailrec

trait Effect[I[_], O[_]]

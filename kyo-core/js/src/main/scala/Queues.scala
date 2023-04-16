package org.jctools.queues

import java.util.ArrayDeque

case class MpmcArrayQueue[T](capacity: Int) extends ArrayDeque[T] {}

case class MpscArrayQueue[T](capacity: Int) extends ArrayDeque[T] {}

case class SpmcArrayQueue[T](capacity: Int) extends ArrayDeque[T] {}

case class SpscArrayQueue[T](capacity: Int) extends ArrayDeque[T] {}

case class MpmcUnboundedXaddArrayQueue[T](chunkSize: Int) extends ArrayDeque[T] {}

case class MpscUnboundedArrayQueue[T](chunkSize: Int) extends ArrayDeque[T] {}

case class SpscUnboundedArrayQueue[T](chunkSize: Int) extends ArrayDeque[T] {}

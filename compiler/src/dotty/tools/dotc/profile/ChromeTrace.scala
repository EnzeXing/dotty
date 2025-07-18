// Scala 2 compiler backport of https://github.com/scala/scala/pull/7364
/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package dotty.tools.dotc.profile

import java.io.Closeable
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path}
import java.util
import java.util.concurrent.TimeUnit

import scala.collection.mutable

object ChromeTrace {
  private object EventType {
    final val Start = "B"
    final val Instant = "I"
    final val End = "E"
    final val Complete = "X"

    final val Counter = "C"

    final val AsyncStart = "b"
    final val AsyncInstant = "n"
    final val AsyncEnd = "e"
  }
}

/** Allows writing a subset of captrue traces based on https://docs.google.com/document/d/1CvAClvFfyA5R-PhYUmn5OOQtYMH4h6I0nSsKchNAySU/preview#
  * Can be visualized using https://ui.perfetto.dev/, Chrome's about://tracing (outdated) or the tooling in https://www.google.com.au/search?q=catapult+tracing&oq=catapult+tracing+&aqs=chrome..69i57.3974j0j4&sourceid=chrome&ie=UTF-8 */
final class ChromeTrace(f: Path) extends Closeable {
  import ChromeTrace.EventType
  private val traceWriter = FileUtils.newAsyncBufferedWriter(f)
  private val context = mutable.Stack[JsonContext](TopContext)
  private val tidCache = new ThreadLocal[String]() {
    @annotation.nowarn("cat=deprecation")
    override def initialValue(): String = "%05d".format(Thread.currentThread().getId())
  }
  objStart()
  fld("traceEvents")
  context.push(ValueContext)
  arrStart()
  traceWriter.newLine()

  private val pid = ManagementFactory.getRuntimeMXBean().getName().replaceAll("@.*", "")

  override def close(): Unit = {
    arrEnd()
    objEnd()
    context.pop()
    tidCache.remove()
    traceWriter.close()
  }

  def traceDurationEvent(name: String, startNanos: Long, durationNanos: Long, tid: String = this.tid(), pidSuffix: String = ""): Unit = {
    val durationMicros = nanosToMicros(durationNanos)
    val startMicros = nanosToMicros(startNanos)
    objStart()
    str("cat", "scalac")
    str("name", name)
    str("ph", EventType.Complete)
    str("tid", tid)
    writePid(pidSuffix)
    lng("ts", startMicros)
    lng("dur", durationMicros)
    objEnd()
    traceWriter.newLine()
  }

  private def writePid(pidSuffix: String) = {
    if (pidSuffix == "")
      str("pid", pid)
    else
      str2("pid", pid, "-", pidSuffix)
  }

  def traceCounterEvent(name: String, counterName: String, count: Long, processWide: Boolean): Unit = {
    objStart()
    str("cat", "scalac")
    str("name", name)
    str("ph", EventType.Counter)
    str("tid", tid())
    writePid(pidSuffix = if (processWide) "" else tid())
    lng("ts", microTime())
    fld("args")
    objStart()
    lng(counterName, count)
    objEnd()
    objEnd()
    traceWriter.newLine()
  }

  def traceDurationEventStart(cat: String, name: String, colour: String = "", pidSuffix: String = tid()): Unit = traceDurationEventStartEnd(EventType.Start, cat, name, colour, pidSuffix)
  def traceDurationEventEnd(cat: String, name: String, colour: String = "", pidSuffix: String = tid()): Unit = traceDurationEventStartEnd(EventType.End, cat, name, colour, pidSuffix)

  private def traceDurationEventStartEnd(eventType: String, cat: String, name: String, colour: String, pidSuffix: String = ""): Unit = {
    objStart()
    str("cat", cat)
    str("name", name)
    str("ph", eventType)
    writePid(pidSuffix)
    str("tid", tid())
    lng("ts", microTime())
    if (colour != "") {
      str("cname", colour)
    }
    objEnd()
    traceWriter.newLine()
  }

  private def tid(): String = tidCache.get()

  private def nanosToMicros(t: Long): Long = TimeUnit.NANOSECONDS.toMicros(t)

  private def microTime(): Long = nanosToMicros(System.nanoTime())

  private sealed abstract class JsonContext
  private case class ArrayContext(var first: Boolean) extends JsonContext
  private case class ObjectContext(var first: Boolean) extends JsonContext
  private case object ValueContext extends JsonContext
  private case object TopContext extends JsonContext

  private def str(name: String, value: String): Unit = {
    fld(name)
    traceWriter.write("\"")
    traceWriter.write(value) // This assumes no escaping is needed
    traceWriter.write("\"")
  }
  private def str2(name: String, value: String, valueContinued1: String, valueContinued2: String): Unit = {
    fld(name)
    traceWriter.write("\"")
    traceWriter.write(value) // This assumes no escaping is needed
    traceWriter.write(valueContinued1) // This assumes no escaping is needed
    traceWriter.write(valueContinued2) // This assumes no escaping is needed
    traceWriter.write("\"")
  }
  private def lng(name: String, value: Long): Unit = {
    fld(name)
    traceWriter.write(String.valueOf(value))
    traceWriter.write("")
  }
  private def objStart(): Unit = {
    context.top match {
      case ac @ ArrayContext(first) =>
        if (first) ac.first = false
        else traceWriter.write(",")
      case _ =>
    }
    context.push(ObjectContext(true))
    traceWriter.write("{")
  }
  private def objEnd(): Unit = {
    traceWriter.write("}")
    context.pop()
  }
  private def arrStart(): Unit = {
    traceWriter.write("[")
    context.push(ArrayContext(true))
  }
  private def arrEnd(): Unit = {
    traceWriter.write("]")
    context.pop()
  }

  private def fld(name: String) = {
    val topContext = context.top
    topContext match {
      case oc @ ObjectContext(first) =>
        if (first) oc.first = false
        else traceWriter.write(",")
      case context =>
        throw new IllegalStateException("Wrong context: " + context)
    }
    traceWriter.write("\"")
    traceWriter.write(name)
    traceWriter.write("\"")
    traceWriter.write(":")
  }
}
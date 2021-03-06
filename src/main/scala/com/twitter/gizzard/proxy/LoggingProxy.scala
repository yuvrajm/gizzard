package com.twitter.gizzard.proxy

import scala.reflect.Manifest
import com.twitter.util.{Duration, Time}
import com.twitter.gizzard.{Stats, TransactionalStatsConsumer}
import com.twitter.gizzard.scheduler.JsonJob


/**
 * Wrap an object's method calls in a logger that sends the method name, arguments, and elapsed
 * time to a transactional logger.
 */
class LoggingProxy[T <: AnyRef](
  consumers: Seq[TransactionalStatsConsumer], statGrouping: String, name: Option[String])(implicit manifest: Manifest[T]) {

  private val proxy = new ProxyFactory[T]

  def apply(obj: T): T = {
    proxy(obj) { method =>
      Stats.beginTransaction()
      val namePrefix = name.map(_+"-").getOrElse("")
      val startTime = Time.now
      try {
        method()
      } catch {
        case e: Exception =>
          Stats.transaction.record("Transaction failed with exception: " + e.toString())
          Stats.transaction.set("exception", e)
          Stats.incr(namePrefix+statGrouping+"-failed")
          Stats.transaction.name.foreach { opName =>
            Stats.incr(namePrefix+"operation-"+opName+"-failed")
          }
          throw e
      } finally {
        Stats.incr(namePrefix+statGrouping+"-total")

        val duration = Time.now - startTime
        Stats.transaction.record("Total duration: "+duration.inMillis)
        Stats.transaction.set("duration", duration.inMillis.asInstanceOf[AnyRef])

        Stats.transaction.name.foreach { opName =>
          Stats.incr(namePrefix+"operation-"+opName+"-total")
          Stats.addMetric(namePrefix+"operation-"+opName, duration.inMilliseconds.toInt)
        }

        val t = Stats.endTransaction()
        consumers.map { _(t) }
      }
    }
  }
}

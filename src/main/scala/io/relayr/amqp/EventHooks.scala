package io.relayr.amqp

import java.net.InetAddress

import scala.concurrent.duration.FiniteDuration

object EventHooks {
  def apply(f: Event ⇒ Unit): EventHooks = new EventHooks {
    override def event(event: Event): Unit = f(event)
  }

  def apply(pf: PartialFunction[Event, Unit]): EventHooks = new EventHooks() {
    override def event(e: Event) =
      if (pf.isDefinedAt(e))
        pf(e)
  }

  def apply(): EventHooks = new EventHooks {
    override def event(event: Event): Unit = ()
  }
}

trait EventHooks {
  private[amqp] def event(event: Event): Unit

}

sealed trait Event

object Event {

  trait ConnectionEvent extends Event

  object ConnectionEvent {

    case class ConnectionEstablished(address: InetAddress, port: Int, heartbeatInterval: FiniteDuration) extends ConnectionEvent

    object ConnectionShutdown extends ConnectionEvent
  }

  trait ChannelEvent extends Event

  object ChannelEvent {

    case class ChannelOpened(channelNumber: Int, qos: Option[Int]) extends ChannelEvent

    case class DeliveryFailed(replyCode: Int, replyText: String, exchange: String, routingKey: String) extends ChannelEvent
  }
}

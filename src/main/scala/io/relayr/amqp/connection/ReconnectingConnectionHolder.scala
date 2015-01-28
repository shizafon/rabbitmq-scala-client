package io.relayr.amqp.connection

import com.rabbitmq.client._
import io.relayr.amqp.Event.{ ChannelEvent, ConnectionEvent }
import io.relayr.amqp._

import scala.concurrent.{ ExecutionContext, blocking }
import scala.concurrent.duration._

private[connection] class ReconnectingConnectionHolder(factory: ConnectionFactory, reconnectionStrategy: Option[ReconnectionStrategy], eventConsumer: Event ⇒ Unit, implicit private val executionContext: ExecutionContext, channelFactory: ChannelFactory) extends ConnectionHolder {

  private var currentConnection: CurrentConnection = new CurrentConnection(None, Map())

  reconnect()

  private def reconnect(): Unit = this.synchronized {
    blocking {
      val conn = factory.newConnection()
      eventConsumer(ConnectionEvent.ConnectionEstablished(conn.getAddress, conn.getPort, conn.getHeartbeat seconds))
      conn.addShutdownListener(new ShutdownListener {
        override def shutdownCompleted(cause: ShutdownSignalException): Unit = {
          eventConsumer(ConnectionEvent.ConnectionShutdown)
          reconnectionStrategy.foreach(r ⇒ r.scheduleReconnection { reconnect() })
        }
      })
      def recreateChannels(channelKeys: Iterable[ChannelKey]): Map[ChannelKey, Channel] = {
        channelKeys.map(channelKey ⇒
          (channelKey, createChannel(conn, channelKey.qos))
        ).toMap
      }
      currentConnection = new CurrentConnection(Some(conn), recreateChannels(currentConnection.channelMappings.keys))
    }
  }

  private def createChannel(conn: Connection, qos: Option[Int]): Channel = blocking {
    val channel = conn.createChannel()
    // NOTE there may be other parameters possible to set up on the connection at the start
    qos.foreach(channel.basicQos)
    eventConsumer(ChannelEvent.ChannelOpened(channel.getChannelNumber, qos))
    channel.addReturnListener(new ReturnListener {
      override def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: AMQP.BasicProperties, body: Array[Byte]): Unit =
        eventConsumer(ChannelEvent.DeliveryFailed(replyCode, replyText, exchange, routingKey))
    })
    channel
  }

  /**
   * Create a new channel multiplexed over this connection.
   * @note Blocks on creation of the underlying channel
   */
  override def newChannel(qos: Int): ChannelOwner = newChannel(Some(qos))

  override def newChannel(): ChannelOwner = newChannel(None)

  /**
   * Create a new channel multiplexed over this connection.
   * @note Blocks on creation of the underlying channel
   */
  def newChannel(qos: Option[Int] = None): ChannelOwner = this.synchronized {
    ensuringConnection { c ⇒
      val key: ChannelKey = new ChannelKey(qos)
      currentConnection.channelMappings = currentConnection.channelMappings + (key -> createChannel(c, qos))
      channelFactory(new InternalChannelSessionProvider(key), executionContext)
    }
  }

  /** Close the connection. */
  override def close(): Unit = this.synchronized {
    currentConnection.connection.foreach(c ⇒ blocking { c.close() })
  }

  class InternalChannelSessionProvider(key: ChannelKey) extends ChannelSessionProvider {
    override def withChannel[T](f: (Channel) ⇒ T): T =
      f(currentConnection.channelMappings(key))
  }

  private def ensuringConnection[T](function: (Connection) ⇒ T): T = currentConnection.connection match {
    case Some(con) ⇒ function(con)
    // TODO explain and discuss this state exception
    case None      ⇒ throw new IllegalStateException("Not connected")
  }
}

private class CurrentConnection(var connection: Option[Connection], var channelMappings: Map[ChannelKey, Channel])

private class ChannelKey(val qos: Option[Int])

package io.relayr.amqp

/** The queue server found nowhere to route the message */
case class UndeliveredException() extends Exception

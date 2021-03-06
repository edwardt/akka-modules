/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.amqp

import collection.JavaConversions

import akka.event.EventHandler

import com.rabbitmq.client.AMQP.BasicProperties
import com.rabbitmq.client.{Channel, Envelope, DefaultConsumer}
import akka.amqp.AMQP._

private[amqp] class ConsumerActor(consumerParameters: ConsumerParameters)
        extends FaultTolerantChannelActor(
          consumerParameters.exchangeParameters, consumerParameters.channelParameters) {
  import consumerParameters._

  var listenerTag: Option[String] = None

  def specificMessageHandler = {
    case Acknowledge(deliveryTag) => acknowledgeDeliveryTag(deliveryTag, true)
    case Reject(deliveryTag) => rejectDeliveryTag(deliveryTag, true)
    case message: Message =>
      handleIllegalMessage("%s can't be used to send messages, ignoring message [%s]".format(this, message))
    case unknown =>
      handleIllegalMessage("Unknown message [%s] to %s".format(unknown, this))
  }

  protected def setupChannel(ch: Channel) = {

    channelParameters.foreach(params => ch.basicQos(params.prefetchSize))

    val exchangeName = exchangeParameters.flatMap(params => Some(params.exchangeName))
    val consumingQueue = exchangeName match {
      case Some(exchange) =>
        val queueDeclare: com.rabbitmq.client.AMQP.Queue.DeclareOk = {
          queueName match {
            case Some(name) =>
              declareQueue(ch, name, queueDeclaration)
            case None =>
              ch.queueDeclare
          }
        }
        ch.queueBind(queueDeclare.getQueue, exchange, routingKey)
        queueDeclare.getQueue
      case None =>
        // no exchange, use routing key as queuename
        declareQueue(ch, routingKey, queueDeclaration)
        routingKey
    }


    val tag = ch.basicConsume(consumingQueue, false, new DefaultConsumer(ch) {
      override def handleDelivery(tag: String, envelope: Envelope, properties: BasicProperties, payload: Array[Byte]) {
        try {
          val deliveryTag = envelope.getDeliveryTag
          import envelope._
          deliveryHandler ! Delivery(payload, getRoutingKey, getDeliveryTag, isRedeliver, properties, someSelf)

          if (selfAcknowledging) {
            acknowledgeDeliveryTag(deliveryTag, false)
          }
        } catch {
          case cause =>
            EventHandler notifyListeners EventHandler.Error(cause, this, "Delivery of message to %s failed" format toString)
            self ! Failure(cause) // pass on and re-throw exception in consumer actor to trigger restart and connect
        }
      }
    })
    listenerTag = Some(tag)
  }

  private def declareQueue(ch: Channel, queueName: String, queueDeclaration: Declaration): com.rabbitmq.client.AMQP.Queue.DeclareOk = {
    queueDeclaration match {
      case PassiveDeclaration =>
        ch.queueDeclarePassive(queueName)
      case ActiveDeclaration(durable, autoDelete, exclusive) =>
        val configurationArguments = exchangeParameters match {
          case Some(params) => params.configurationArguments
          case _ => Map.empty
        }
        ch.queueDeclare(queueName, durable, exclusive, autoDelete, JavaConversions.mapAsJavaMap(configurationArguments.toMap))
      case NoActionDeclaration => new com.rabbitmq.client.impl.AMQImpl.Queue.DeclareOk(queueName, 0, 0) // do nothing here
    }
  }

  private def acknowledgeDeliveryTag(deliveryTag: Long, remoteAcknowledgement: Boolean) = {
    channel.foreach {
      ch =>
        ch.basicAck(deliveryTag, false)
        if (remoteAcknowledgement) {
          deliveryHandler ! Acknowledged(deliveryTag)
        }
    }
  }

  private def rejectDeliveryTag(deliveryTag: Long, remoteAcknowledgement: Boolean) = {
    // FIXME: when rabbitmq 1.9 arrives, basicReject should be available on the API and implemented instead of this
    val message = ("Consumer is rejecting delivery with tag [%s] -" +
                   "for now this means we have to self terminate and kill the channel - see you in a second." format deliveryTag)
    EventHandler notifyListeners EventHandler.Warning(this, message)
    channel.foreach {
      ch =>
        if (remoteAcknowledgement) {
          deliveryHandler ! Rejected(deliveryTag)
        }
    }
    throw new RejectionException(deliveryTag)
  }

  private def handleIllegalMessage(errorMessage: String) = {
    EventHandler notifyListeners EventHandler.Error(null, this, errorMessage)
    throw new IllegalArgumentException(errorMessage)
  }

  override def preRestart(reason: Throwable) = {
    listenerTag = None
    super.preRestart(reason)
  }

  override def postStop = {
    listenerTag.foreach(tag => channel.foreach(_.basicCancel(tag)))
    val i = self.linkedActors.values.iterator
    while(i.hasNext) {
      val ref = i.next
      ref.stop
      self.unlink(ref)
    }
    super.postStop
  }

  override def toString =
    "AMQP.Consumer[address= " + self.address +
            ", exchangeParameters=" + exchangeParameters +
            ", queueDeclaration=" + queueDeclaration + "]"
}


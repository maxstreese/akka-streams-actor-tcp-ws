package com.streese.akka

import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.Sink
import akka.stream.typed.scaladsl.ActorSink
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors

object ConnectionHandler {

  sealed trait Request
  object Request {
    final case class  UpstreamInit(replyTo: ActorRef[Ack.type])                  extends Request
    final case class  UpstreamMessage(replyTo: ActorRef[Ack.type], elem: String) extends Request
    final case object UpstreamComplete                                           extends Request
    final case class  UpstreamFailure(ex: Throwable)                             extends Request
    final case class  DownstreamSource(replyTo: ActorRef[Response])              extends Request
  }

  final case object Ack

  sealed trait Response
  object Response {
    final case class  Message(elem: String) extends Response
    final case object Complete              extends Response
    final case class  Fail(ex: Exception)   extends Response
  }

  final case class State()

  def apply(): Behavior[Request] = initialBehavior

  private def initialBehavior: Behavior[Request] = Behaviors.receiveMessage {
    case msg: Request.UpstreamInit         => handleUpstreamInit(msg)
    case msg: Request.UpstreamMessage      => handleUpstreamMessage(msg, None)
    case Request.UpstreamComplete          => Behaviors.stopped
    case Request.UpstreamFailure(ex)       => Behaviors.stopped
    case Request.DownstreamSource(replyTo) => downstreamInitializedBehavior(replyTo)
  }

  private def downstreamInitializedBehavior(
    replyTo: ActorRef[Response]
  ): Behavior[Request] = Behaviors.receiveMessage {
    case msg: Request.UpstreamInit         => handleUpstreamInit(msg)
    case msg: Request.UpstreamMessage      => handleUpstreamMessage(msg, Some(replyTo))
    case Request.UpstreamComplete          => Behaviors.stopped
    case Request.UpstreamFailure(ex)       => Behaviors.stopped
    case Request.DownstreamSource(replyTo) => Behaviors.same
  }

  private def handleUpstreamInit(msg: Request.UpstreamInit): Behavior[Request] = {
    msg.replyTo ! Ack
    Behaviors.same
  }

  private def handleUpstreamMessage(
    msg: Request.UpstreamMessage,
    replyTo: Option[ActorRef[Response]]
  ): Behavior[Request] = {
    replyTo.foreach(r => r ! Response.Message(msg.elem + " !!!"))
    msg.replyTo ! Ack
    Behaviors.same
  }

  def sinkActorRefWithBackpressure(ref: ActorRef[Request]): Sink[String, NotUsed] =
    ActorSink.actorRefWithBackpressure(
      ref               = ref,
      messageAdapter    = (replyTo: ActorRef[Ack.type], elem: String) => Request.UpstreamMessage(replyTo, elem),
      onInitMessage     = (replyTo: ActorRef[Ack.type]) => Request.UpstreamInit(replyTo),
      ackMessage        = Ack,
      onCompleteMessage = Request.UpstreamComplete,
      onFailureMessage  = ex => Request.UpstreamFailure(ex)
    )

}

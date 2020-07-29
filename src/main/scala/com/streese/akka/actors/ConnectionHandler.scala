package com.streese.akka.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.{Sink, Source, Flow}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.CompletionStrategy

import scala.concurrent.duration._

object ConnectionHandler {

  sealed trait Request
  final object Request {
    final case class  SinkInit(replyTo: ActorRef[SinkAck.type])                  extends Request
    final case class  SinkMessage(replyTo: ActorRef[SinkAck.type], elem: String) extends Request
    final case object SinkCompleted                                              extends Request
    final case class  SinkFailed(ex: Throwable)                                  extends Request
    final case class  SourceInit(replyTo: ActorRef[Response])                    extends Request
    private[ConnectionHandler] final case object SourceAck                       extends Request
    private[ConnectionHandler] final case object Tick                            extends Request
  }
  final case object SinkAck

  sealed trait Response
  final object Response {
    final case class  SourceMessage(elem: String) extends Response
    final case object SourceCompleted             extends Response
    final case class  SourceFailed(ex: Throwable) extends Response
  }

  private[ConnectionHandler] sealed trait ClientRequest
  private[ConnectionHandler] final object ClientRequest {
    final case object Start         extends ClientRequest
    final case object Stop          extends ClientRequest
    final case object Reset         extends ClientRequest
    final case class  Inc(inc: Int) extends ClientRequest
  }

  final case class State(n: Int, inc: Int) {
    def applyInc(): State = this.copy(n = this.n + this.inc)
  }
  private val defaultState = State(0, 1)

  def apply(): Behavior[Request] = uninitializedBehavior(false, None)

  private def uninitializedBehavior(
    sinkInitialized: Boolean,
    sourceRef: Option[ActorRef[Response]]
  ): Behavior[Request] = Behaviors.withTimers { timers =>
    Behaviors.receiveMessagePartial {
      case Request.SinkInit(sinkRef) => {
        sinkRef ! SinkAck
        sourceRef.foreach(_ => timers.startTimerWithFixedDelay(Request.Tick, 1.second))
        sourceRef
          .map(r => initializedBehavior(r, None, false, false, defaultState))
          .getOrElse(uninitializedBehavior(true, None))
      }
      case Request.SourceInit(sourceRef) => {
        if (sinkInitialized) {
          timers.startTimerWithFixedDelay(Request.Tick, 1.second)
          initializedBehavior(sourceRef, None, false, false, defaultState)
        } else uninitializedBehavior(false, Some(sourceRef))
      }
    }
  }

  private def initializedBehavior(
    sourceRef: ActorRef[Response],
    sinkRefForMsg: Option[ActorRef[SinkAck.type]],
    waitingForSourceAck: Boolean,
    started: Boolean,
    state: State
  ): Behavior[Request] = Behaviors.receiveMessagePartial {
    case req: Request.SinkMessage => handleSinkMessage(sourceRef, waitingForSourceAck, started, state, req)
    case Request.SinkCompleted    => handleSinkCompleted(sourceRef)
    case req: Request.SinkFailed  => handleSinkFailed(sourceRef, req)
    case Request.SourceAck        => handleSourceAck(sourceRef, sinkRefForMsg, waitingForSourceAck, started, state)
    case Request.Tick             => handleTick(sourceRef, sinkRefForMsg, waitingForSourceAck, started, state)
  }

  private def handleSinkMessage(
    sourceRef: ActorRef[Response],
    waitingForSourceAck: Boolean,
    started: Boolean,
    state: State,
    msg: Request.SinkMessage
  ): Behavior[Request] = {
    val sinkRefForMsg =
      if (waitingForSourceAck) Some(msg.replyTo)
      else {
        msg.replyTo ! SinkAck
        None
      }
    parseClientMessage(msg.elem) match {
      case Some(ClientRequest.Start) => initializedBehavior(sourceRef, sinkRefForMsg, waitingForSourceAck, true, state)
      case Some(ClientRequest.Stop) => initializedBehavior(sourceRef, sinkRefForMsg, waitingForSourceAck, false, state)
      case Some(ClientRequest.Reset) => initializedBehavior(sourceRef, sinkRefForMsg, waitingForSourceAck, started, defaultState)
      case Some(ClientRequest.Inc(inc)) => initializedBehavior(sourceRef, sinkRefForMsg, waitingForSourceAck, started, state.copy(inc = inc))
      case None => Behaviors.same
    }
  }

  private def handleSinkCompleted(
    sourceRef: ActorRef[Response]
  ): Behavior[Request] = {
    sourceRef ! Response.SourceCompleted
    Behaviors.stopped
  }

  private def handleSinkFailed(
    sourceRef: ActorRef[Response],
    msg: Request.SinkFailed
  ): Behavior[Request] = {
    sourceRef ! Response.SourceFailed(msg.ex)
    Behaviors.stopped
  }

  private def handleSourceAck(
    sourceRef: ActorRef[Response],
    sinkRefForMsg: Option[ActorRef[SinkAck.type]],
    waitingForSourceAck: Boolean,
    started: Boolean,
    state: State,
  ): Behavior[Request] = {
    sinkRefForMsg.foreach(_ ! SinkAck)
    initializedBehavior(sourceRef, None, false, started, state)
  }

  private def handleTick(
    sourceRef: ActorRef[Response],
    sinkRefForMsg: Option[ActorRef[SinkAck.type]],
    waitingForSourceAck: Boolean,
    started: Boolean,
    state: State
  ): Behavior[Request] = {
    if (!waitingForSourceAck && started) {
      val newState = state.applyInc()
      sourceRef ! Response.SourceMessage(s"$newState")
      initializedBehavior(sourceRef, sinkRefForMsg, true, started, newState)
    } else {
      Behaviors.same
    }
  }

  def sinkAndSourceCoupledFlow(ref: ActorRef[Request]): Flow[String, String, NotUsed] = {
    val sink = ActorSink.actorRefWithBackpressure(
      ref               = ref,
      messageAdapter    = (replyTo: ActorRef[SinkAck.type], elem: String) => Request.SinkMessage(replyTo, elem),
      onInitMessage     = (replyTo: ActorRef[SinkAck.type]) => Request.SinkInit(replyTo),
      ackMessage        = SinkAck,
      onCompleteMessage = Request.SinkCompleted,
      onFailureMessage  = ex => Request.SinkFailed(ex)
    )
    val source = ActorSource.actorRefWithBackpressure[Response, Request.SourceAck.type](
      ackTo             = ref,
      ackMessage        = Request.SourceAck,
      completionMatcher = { case Response.SourceCompleted => CompletionStrategy.immediately },
      failureMatcher    = { case Response.SourceFailed(ex)  => ex }
    ).collect { case Response.SourceMessage(elem) =>
      elem
    }.mapMaterializedValue { sourceRef =>
      ref ! Request.SourceInit(sourceRef)
    }
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

  private def parseClientMessage(s: String): Option[ClientRequest] = s match {
    case "start" => Some(ClientRequest.Start)
    case "stop"  => Some(ClientRequest.Stop)
    case "reset" => Some(ClientRequest.Reset)
    case _       => None
  }

}

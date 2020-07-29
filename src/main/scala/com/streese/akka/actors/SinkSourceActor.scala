package com.streese.akka.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.Flow
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.OverflowStrategy
import akka.NotUsed

trait SinkSourceActor[I, O] {

  sealed trait Request
  object Request {
    case class                           SinkMessage(msg: I)                extends Request
    case object                          SinkCompleted                      extends Request
    case class                           SinkFailed(ex: Throwable)          extends Request
    private[SinkSourceActor] case class  SourceRef(ref: ActorRef[Response]) extends Request
  }

  sealed trait Response
  object Response {
    case class  SourceMessage(msg: O)       extends Response
    case object SourceCompleted             extends Response
    case class  SourceFailed(ex: Throwable) extends Response
  }

  final def apply(): Behavior[Request] = Behaviors.receiveMessagePartial {
    case Request.SourceRef(ref) => behavior(ref)
  }

  protected def behavior(sourceRef: ActorRef[Response]): Behavior[Request]

  final def sinkAndSourceCoupledFlow(
    ref: ActorRef[Request],
    bufferSize: Int,
    overflowStrategy: OverflowStrategy
  ): Flow[I, O, NotUsed] = {
    val sink =
      ActorSink.actorRef[Request](
        ref               = ref,
        onCompleteMessage = Request.SinkCompleted,
        onFailureMessage  = ex => Request.SinkFailed(ex)
      ).contramap(msg => Request.SinkMessage(msg))

    val source =
      ActorSource.actorRef[Response](
        completionMatcher = { case Response.SourceCompleted  => () },
        failureMatcher    = { case Response.SourceFailed(ex) => ex },
        bufferSize        = bufferSize,
        overflowStrategy  = overflowStrategy
      )
      .collect { case Response.SourceMessage(msg) => msg}
      .mapMaterializedValue(sourceRef => ref ! Request.SourceRef(sourceRef))
    Flow.fromSinkAndSourceCoupled(sink, source)
  }

}

package com.streese.akka.actors

import akka.NotUsed
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Flow
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}

object AkkaStreamsUtils {
  sealed trait StreamEvent[+T]
  object StreamEvent {
    case class Ready[T](control: StreamControl[T]) extends StreamEvent[T]
    case object Completed extends StreamEvent[Nothing]
    case class Failed(ex: Throwable) extends StreamEvent[Nothing]
  }

  trait StreamControl[T] {
    def push(msg: T): Unit
    def complete(): Unit
    def fail(ex: Throwable): Unit
  }

  sealed trait StreamControlCommand
  object StreamControlCommand {
    case object Complete extends StreamControlCommand
    case class Fail(ex: Throwable) extends StreamControlCommand
  }

  def sinkAndSourceCoupledFlow[I,O](ref: ActorRef[I],
                                              adaptEvent: StreamEvent[O] => I,
                                              bufferSize: Int,
                                              overflowStrategy: OverflowStrategy): Flow[I, O, NotUsed] = {
    val sink = ActorSink
      .actorRef[I](
        ref,
        onCompleteMessage = adaptEvent(StreamEvent.Completed),
        onFailureMessage  = ex => adaptEvent(StreamEvent.Failed(ex))
      )

    val source = ActorSource
      .actorRef[Any](
        completionMatcher = { case StreamControlCommand.Complete => () },
        failureMatcher    = { case StreamControlCommand.Fail(ex) => ex },
        bufferSize        = bufferSize,
        overflowStrategy  = overflowStrategy
      )
      .mapMaterializedValue { sourceRef =>
        val control = new StreamControl[O] {
          override def push(msg: O): Unit = sourceRef ! msg
          override def complete(): Unit = sourceRef.toClassic ! StreamControlCommand.Complete
          override def fail(ex: Throwable): Unit = sourceRef.toClassic ! StreamControlCommand.Fail(ex)
        }
        ref ! adaptEvent(StreamEvent.Ready(control))
      }
      .map(_.asInstanceOf[O]) //it is safe because all other messages will be caught by completion and failure matchers

    Flow.fromSinkAndSourceCoupled(sink, source)
  }

}

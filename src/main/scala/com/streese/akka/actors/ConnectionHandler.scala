package com.streese.akka.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}

import scala.concurrent.duration._

object ConnectionHandler {

  sealed trait TickRequest
  object TickRequest {
    case object Start         extends TickRequest
    case object Stop          extends TickRequest
    case object Reset         extends TickRequest
    case object Tick          extends TickRequest
    case class  Inc(inc: Int) extends TickRequest
  }

  case class TickResponse(n: Int)

  case class State(n: Int, inc: Int) {
    def applyInc(): State = this.copy(n = this.n + this.inc)
  }
  private val defaultState = State(0, 1)

  object Actor extends SinkSourceActor[TickRequest, TickResponse] {

    protected def behavior(sourceRef: ActorRef[Response]): Behavior[Request] = Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(Request.SinkMessage(TickRequest.Tick), 3.seconds)
      Behaviors.receiveMessagePartial(handleCompletions orElse handleMessagesWhenStopped(sourceRef, defaultState))
    }

    private val handleCompletions: PartialFunction[Request, Behavior[Request]] = {
        case Request.SinkCompleted    => Behaviors.stopped
        case Request.SinkFailed(ex)   => Behaviors.stopped
    }

    private def handleMessagesWhenStopped(
      sourceRef: ActorRef[Response],
      state: State
    ): PartialFunction[Request, Behavior[Request]] = {
      case Request.SinkMessage(msg) => msg match {
        case TickRequest.Start =>
          Behaviors.receiveMessagePartial(handleCompletions orElse handleMessagesWhenStarted(sourceRef, state))
        case TickRequest.Stop =>
          Behaviors.same
        case TickRequest.Reset =>
          Behaviors.receiveMessagePartial(handleCompletions orElse handleMessagesWhenStopped(sourceRef, defaultState)) 
        case TickRequest.Tick =>
          Behaviors.same
        case TickRequest.Inc(inc) =>
          Behaviors.receiveMessagePartial(
            handleCompletions orElse handleMessagesWhenStopped(sourceRef, state.copy(inc = inc))
          )
      }
    }

    private def handleMessagesWhenStarted(
      sourceRef: ActorRef[Response],
      state: State
    ): PartialFunction[Request, Behavior[Request]] = {
      case Request.SinkMessage(msg) => msg match {
        case TickRequest.Start =>
          Behaviors.same
        case TickRequest.Stop =>
          Behaviors.receiveMessagePartial(handleCompletions orElse handleMessagesWhenStopped(sourceRef, state))
        case TickRequest.Reset =>
          Behaviors.receiveMessagePartial(handleCompletions orElse handleMessagesWhenStarted(sourceRef, defaultState))  
        case TickRequest.Tick =>
          {
            sourceRef ! Response.SourceMessage(TickResponse(state.n))
            Behaviors.receiveMessagePartial(
              handleCompletions orElse handleMessagesWhenStarted(sourceRef, state.applyInc())
            )
          }
        case TickRequest.Inc(inc) =>
          Behaviors.receiveMessagePartial(
            handleCompletions orElse handleMessagesWhenStarted(sourceRef, state.copy(inc = inc))
          )
      }
    }

  }

}

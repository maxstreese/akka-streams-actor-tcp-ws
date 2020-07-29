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
      behaviorWhenStopped(sourceRef, defaultState)
    }

    private val handleCompletions: PartialFunction[Request, Behavior[Request]] = {
      case Request.SinkCompleted => Behaviors.stopped
      case Request.SinkFailed(_) => Behaviors.stopped
    }

    private def behaviorWhenStopped(
      sourceRef: ActorRef[Response],
      state: State
    ): Behavior[Request] = Behaviors.receiveMessagePartial {
      handleCompletions orElse {
        case Request.SinkMessage(msg) => msg match {
          case TickRequest.Start    => behaviorWhenStarted(sourceRef, state)
          case TickRequest.Stop     => Behaviors.same
          case TickRequest.Reset    => behaviorWhenStopped(sourceRef, defaultState)
          case TickRequest.Tick     => Behaviors.same
          case TickRequest.Inc(inc) => behaviorWhenStopped(sourceRef, state.copy(inc = inc))
        }
      }
    }

    private def behaviorWhenStarted(
      sourceRef: ActorRef[Response],
      state: State
    ): Behavior[Request] = Behaviors.receiveMessagePartial {
      handleCompletions orElse {
        case Request.SinkMessage(msg) => msg match {
          case TickRequest.Start    => Behaviors.same
          case TickRequest.Stop     => behaviorWhenStopped(sourceRef, state)
          case TickRequest.Reset    => behaviorWhenStarted(sourceRef, defaultState)
          case TickRequest.Tick     => handleTickWhenStarted(sourceRef, state)
          case TickRequest.Inc(inc) => behaviorWhenStarted(sourceRef, state.copy(inc = inc))
        }
      }
    }

    private def handleTickWhenStarted(sourceRef: ActorRef[Response], state: State): Behavior[Request] = {
      sourceRef ! Response.SourceMessage(TickResponse(state.n))
      behaviorWhenStarted(sourceRef, state.applyInc())
    }

  }

}

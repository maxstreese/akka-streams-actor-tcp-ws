package com.streese.akka.actors

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.streese.akka.actors.AkkaStreamsUtils.{StreamControl, StreamEvent}
import com.streese.akka.actors.ConnectionHandler2.TickRequest.HandleStreamEvent

import scala.concurrent.duration._

object ConnectionHandler2 {

  sealed trait TickRequest
  object TickRequest {
    case class HandleStreamEvent(event: StreamEvent[TickResponse]) extends TickRequest
    case object Start extends TickRequest
    case object Stop extends TickRequest
    case object Reset extends TickRequest
    case object Tick extends TickRequest
    case class Inc(inc: Int) extends TickRequest
  }

  case class TickResponse(n: Int)

  case class State(n: Int, inc: Int) {
    def applyInc(): State = this.copy(n = this.n + this.inc)
  }
  private val defaultState = State(0, 1)

  def apply(): Behavior[TickRequest] =
    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(TickRequest.Tick, 3.seconds)
      Behaviors.receiveMessagePartial {
        case HandleStreamEvent(StreamEvent.Ready(control)) =>
          behaviorWhenStopped(control, defaultState)
        case HandleStreamEvent(StreamEvent.Completed | _: StreamEvent.Failed) =>
          Behaviors.stopped
      }
    }

  private def behaviorWhenStopped(control: StreamControl[TickResponse],
                                  state: State): Behavior[TickRequest] =
    Behaviors.receiveMessagePartial {
      case TickRequest.Start => behaviorWhenStarted(control, state)
      case TickRequest.Stop  => Behaviors.same
      case TickRequest.Reset => behaviorWhenStopped(control, defaultState)
      case TickRequest.Tick  => Behaviors.same
      case TickRequest.Inc(inc) => behaviorWhenStopped(control, state.copy(inc = inc))
      case HandleStreamEvent(StreamEvent.Completed | _: StreamEvent.Failed) => Behaviors.stopped
    }

  private def behaviorWhenStarted(control: StreamControl[TickResponse],
                                  state: State): Behavior[TickRequest] =
    Behaviors.receiveMessagePartial {
      case TickRequest.Start => Behaviors.same
      case TickRequest.Stop  => behaviorWhenStopped(control, state)
      case TickRequest.Reset => behaviorWhenStarted(control, defaultState)
      case TickRequest.Tick  => handleTickWhenStarted(control, state)
      case TickRequest.Inc(inc) => behaviorWhenStarted(control, state.copy(inc = inc))
      case HandleStreamEvent(StreamEvent.Completed | _: StreamEvent.Failed) => Behaviors.stopped
    }

  private def handleTickWhenStarted(control: StreamControl[TickResponse],
                                    state: State): Behavior[TickRequest] = {
    control.push(TickResponse(state.n))
    behaviorWhenStarted(control, state.applyInc())
  }
}

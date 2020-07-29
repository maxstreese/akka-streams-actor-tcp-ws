package com.streese.akka.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

object TickActor {

  sealed trait Request
  object Request {
    final case object Start         extends Request
    final case object Stop          extends Request
    final case object Reset         extends Request
    final case object Tick          extends Request
    final case class  Inc(inc: Int) extends Request
  }

  final case class Response(n: Int)

  final case class State(n: Int, inc: Int) {
    def applyInc(): State = this.copy(n = this.n + this.inc)
  }

  private val defaultState = State(0, 1)

  def apply(replyTo: ActorRef[Response]): Behavior[Request] = Behaviors.withTimers { timers =>
    timers.startTimerWithFixedDelay(Request.Tick, 1.second)
    behaviorWhenStopped(replyTo, defaultState)
  }

  private def behaviorWhenStopped(
    replyTo: ActorRef[Response],
    state: State
  ): Behavior[Request] = Behaviors.receiveMessage {
    case Request.Start         => behaviorWhenStarted(replyTo, state)
    case Request.Stop          => Behaviors.same
    case Request.Reset         => behaviorWhenStopped(replyTo, defaultState)
    case Request.Tick          => Behaviors.same
    case Request.Inc(inc: Int) => behaviorWhenStopped(replyTo, state.copy(inc = inc))
  }

  private def behaviorWhenStarted(
    replyTo: ActorRef[Response],
    state: State
  ): Behavior[Request] = Behaviors.receiveMessage {
      case Request.Start         => Behaviors.same
      case Request.Stop          => behaviorWhenStopped(replyTo, state)
      case Request.Reset         => behaviorWhenStarted(replyTo, defaultState)
      case Request.Tick          => {replyTo ! Response(state.n); behaviorWhenStarted(replyTo, state.applyInc())}
      case Request.Inc(inc: Int) => behaviorWhenStarted(replyTo, state.copy(inc = inc))
  }

}

package com.streese.akka

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.streese.akka.ConnectionHandler.Start
import com.streese.akka.ConnectionHandler.Stop
import com.streese.akka.ConnectionHandler.Reset
import com.streese.akka.ConnectionHandler.Inc
import akka.actor.typed.ActorRef

object ConnectionHandler {

  sealed trait Request
  case object  Start                                  extends Request
  case object  Stop                                   extends Request
  case object  Reset                                  extends Request
  case class   Inc(n: Int)                            extends Request

  case class Response(n: Int)

  case class State(cur: Int, inc: Int)

  private val initState = State(0, 1)

  def apply(): Behavior[Request] = Behaviors.setup { context =>
    idle(initState)
  }

  private def idle(state: State): Behavior[Request] = Behaviors.receiveMessage {
    case Start  => streaming(state)
    case Stop   => Behaviors.same
    case Reset  => idle(initState)
    case Inc(n) => idle(state.copy(inc = n))
  }

  private def streaming(state: State): Behavior[Request] = Behaviors.receiveMessage {
    case Start  => Behaviors.same
    case Stop   => idle(state)
    case Reset  => streaming(initState)
    case Inc(n) => streaming(state.copy(inc = n))
  }

}

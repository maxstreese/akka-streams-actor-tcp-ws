package com.streese.akka

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

object EchoActor {

  final case class Request(msg: String, replyTo: ActorRef[Reply])
  final case class Reply(msg: String)

  def apply(i: Int): Behavior[Request] = Behaviors.receiveMessage { request =>
    request.replyTo ! Reply(request.msg + s" $i !!!\n")
    EchoActor(i+1)
  }

}

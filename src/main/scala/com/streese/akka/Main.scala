package com.streese.akka

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl._
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl.Framing
import akka.stream.typed.scaladsl._
import akka.util.ByteString
import com.streese.BuildInfo

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.typed.ActorRef

object Main extends App {

  implicit val system = ActorSystem(BuildInfo.name)
  val echoActor = system.spawn(EchoActor(), "hi")

  implicit val timeout: akka.util.Timeout = 1.second

  val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("127.0.0.1", 8888)

  connections.runForeach { connection =>

    println(s"New connection from: ${connection.remoteAddress}")

    val echoActorFlow = ActorFlow.ask(echoActor)(
      makeMessage = (msg: String, replyTo: ActorRef[EchoActor.Reply]) => EchoActor.Request(msg, replyTo)
    )

    val echo = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))
      .via(echoActorFlow)
      .map(_.msg)
      .map(ByteString(_))

    connection.handleWith(echo)

  }

}

/*
  - the client can send `start`, `stop`, `inc <n>` and `reset`
  - upon receiving `start` the server will start streaming integers starting from 0 and incremented
    by some inc value that by default is set to 1
  - upon receiving `stop` the server will stop streaming the integers again
  - receiving either `start` while the stream is alredy running or `stop` while it is stopped will
    be ignored
  - upon receiving `inc <n>` the server will set the inc value for the connection to <n>
  - upon receiving `reset` the server will reset the integer value for the connection to 0 and the
    inc value to 1
*/

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
import akka.stream.OverflowStrategy

object Main extends App {

  implicit val system = ActorSystem(BuildInfo.name)

  implicit val timeout: akka.util.Timeout = 1.second

  val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("127.0.0.1", 8888)

  connections.runForeach { connection =>

    val connectionHandler = system.spawn(ConnectionHandler(),
      name = {
        val addr = connection.remoteAddress
        s"conn-${addr.getHostName()}-${addr.getPort()}"
      }
    )

    val sink = ConnectionHandler.sinkActorRefWithBackpressure(connectionHandler)

    val source = ActorSource
      .actorRef[ConnectionHandler.Response](
        completionMatcher = { case ConnectionHandler.Response.Complete => ()},
        failureMatcher    = { case ConnectionHandler.Response.Fail(ex) => ex},
        bufferSize        = 100,
        overflowStrategy  = OverflowStrategy.dropHead
      )
      .collect { case ConnectionHandler.Response.Message(elem) => elem}
      .mapMaterializedValue { sourceActorRef =>
        connectionHandler ! ConnectionHandler.Request.DownstreamSource(sourceActorRef)
      }

    val flow = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))
      .via(Flow.fromSinkAndSourceCoupled(sink, source))
      .map(_ + "\n")
      .map(ByteString(_))

    connection.handleWith(flow)

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

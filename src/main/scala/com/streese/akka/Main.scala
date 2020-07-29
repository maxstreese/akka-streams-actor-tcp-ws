package com.streese.akka

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.stream.scaladsl._
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import com.streese.BuildInfo
import com.streese.akka.actors._
import com.streese.akka.actors.ConnectionHandler.TickRequest

import scala.concurrent.Future
import akka.stream.OverflowStrategy

object Main extends App {

  implicit val system = ActorSystem(BuildInfo.name)

  val tcpConnections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("127.0.0.1", 8888)

  tcpConnections.runForeach { connection =>

    val connectionHandler =
      spawnConnectionHandler(connection.remoteAddress.getHostName(), connection.remoteAddress.getPort())

    val flow = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))
      .mapConcat(line => parseRequestLine(line).toSeq)
      .via(ConnectionHandler.Actor.sinkAndSourceCoupledFlow(connectionHandler, 100, OverflowStrategy.fail))
      .map(res => ByteString(s"${res.n}\n"))

    connection.handleWith(flow)

  }

  def spawnConnectionHandler(hostName: String, port: Int) =
    system.spawn(ConnectionHandler.Actor(), s"conn-$hostName-$port")

  def parseRequestLine(line: String): Option[TickRequest] = line match {
    case "start" => Some(TickRequest.Start)
    case "stop"  => Some(TickRequest.Stop)
    case "reset" => Some(TickRequest.Reset)
    case "tick"  => Some(TickRequest.Tick)
    case _       => None
  }

}

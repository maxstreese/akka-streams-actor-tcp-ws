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
import akka.http.scaladsl.Http
import java.net.InetSocketAddress
import akka.http.scaladsl.model.RemoteAddress

import scala.jdk.OptionConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object Main extends App {

  implicit val system = ActorSystem(BuildInfo.name)

  val tcpConnectionsSource: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("127.0.0.1", 8888)

  tcpConnectionsSource.runForeach { connection =>

    val connectionHandler =
      spawnConnectionHandler("tcp", connection.remoteAddress)

    val flow = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))
      .mapConcat(line => parseRequestLine(line).toSeq)
      .via(ConnectionHandler.Actor.sinkAndSourceCoupledFlow(connectionHandler, 100, OverflowStrategy.fail))
      .map(res => ByteString(s"${res.n}\n"))

    val connectionHandler2 =
      spawnConnectionHandler2("tcp", connection.remoteAddress)

    val flow2 = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String.stripSuffix("\r"))
      .mapConcat(line => parseRequestLine2(line).toSeq)
      .via(
        AkkaStreamsUtils.sinkAndSourceCoupledFlow[ConnectionHandler2.TickRequest, ConnectionHandler2.TickResponse](
          connectionHandler2,
          ConnectionHandler2.TickRequest.HandleStreamEvent,
          100,
          OverflowStrategy.fail
        )
      )
      .map(res => ByteString(s"${res.n}\n"))

    connection.handleWith(flow)

  }

  val wsRoute = {
    import akka.http.scaladsl.server.Directives._
    import akka.http.scaladsl.model.ws.{Message, BinaryMessage, TextMessage}
    path("tick") {
      extractClientIP { ip =>
        get {
          val connectionHandler = spawnConnectionHandler("ws", ip)
          val flow = Flow[Message]
            .mapAsync(1) {
              case msg: BinaryMessage => msg.toStrict(1.second).map(_.data.utf8String)
              case msg: TextMessage   => msg.toStrict(1.second).map(_.text)
            }
            .mapConcat(line => parseRequestLine(line).toSeq)
            .via(ConnectionHandler.Actor.sinkAndSourceCoupledFlow(connectionHandler, 100, OverflowStrategy.fail))
            .map(res => TextMessage.Strict(s"${res.n}"))
          handleWebSocketMessages(flow)
        }
      }
    }
  }

  val httpServerBinding = Http().bindAndHandle(wsRoute, "127.0.0.1", 8080)

  def spawnConnectionHandler(protocol: String, remoteAddress: InetSocketAddress) =
    system.spawn(ConnectionHandler.Actor(), s"conn-$protocol-${remoteAddress.getHostName()}-${remoteAddress.getPort()}")

  def spawnConnectionHandler2(protocol: String, remoteAddress: InetSocketAddress) =
    system.spawn(ConnectionHandler2(), s"conn-$protocol-${remoteAddress.getHostName()}-${remoteAddress.getPort()}")

  def spawnConnectionHandler(protocol: String, remoteAddress: RemoteAddress) = {
    val hostName = remoteAddress.getAddress().toScala.map(_.getHostAddress()).getOrElse(s"unknown-${System.nanoTime()}")
    system.spawn(ConnectionHandler.Actor(), s"conn-$protocol-$hostName-${remoteAddress.getPort()}")
  }

  def parseRequestLine(line: String): Option[TickRequest] = line match {
    case "start" => Some(TickRequest.Start)
    case "stop"  => Some(TickRequest.Stop)
    case "reset" => Some(TickRequest.Reset)
    case "tick"  => Some(TickRequest.Tick)
    case _       => None
  }

  def parseRequestLine2(line: String): Option[ConnectionHandler2.TickRequest] = {
    import ConnectionHandler2.TickRequest
    line match {
      case "start" => Some(TickRequest.Start)
      case "stop"  => Some(TickRequest.Stop)
      case "reset" => Some(TickRequest.Reset)
      case "tick"  => Some(TickRequest.Tick)
      case _       => None
    }
  }

}

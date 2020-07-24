package com.streese.akka

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.scaladsl.Tcp._
import akka.stream.scaladsl.Framing
import akka.util.ByteString
import com.streese.BuildInfo

import scala.concurrent.Future

object Main extends App {

  implicit val system = ActorSystem(BuildInfo.name)

  val connections: Source[IncomingConnection, Future[ServerBinding]] = Tcp().bind("127.0.0.1", 8888)

  connections.runForeach { connection =>

    println(s"New connection from: ${connection.remoteAddress}")

    val echo = Flow[ByteString]
      .via(Framing.delimiter(ByteString("\n"), maximumFrameLength = 256, allowTruncation = true))
      .map(_.utf8String)
      .map(_ + "!!!\n")
      .map(ByteString(_))

    connection.handleWith(echo)

  }

}

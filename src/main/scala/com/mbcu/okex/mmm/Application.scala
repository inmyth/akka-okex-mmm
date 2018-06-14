package com.mbcu.okex.mmm

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import com.mbcu.okex.mmm.actors.MainActor
import com.mbcu.okex.mmm.utils.{MyLogging, MyLoggingSingle, MyUtils}


object Application extends App with MyLogging {

  override def main(args: Array[String]) {
    implicit val system: ActorSystem = akka.actor.ActorSystem("mmm")
    implicit val materializer: ActorMaterializer = akka.stream.ActorMaterializer()

    if (args.length != 2){
      println("Requires two arguments : <config file path>  <log directory path>")
      System.exit(-1)
    }
    MyLoggingSingle.init(args(1))
    info(s"START UP ${MyUtils.date()}")

    val mainActor = system.actorOf(Props(new MainActor(args(0))), name = "main")
    mainActor ! "start"
  }

}

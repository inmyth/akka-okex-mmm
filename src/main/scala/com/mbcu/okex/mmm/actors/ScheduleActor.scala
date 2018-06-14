package com.mbcu.okex.mmm.actors

import akka.actor.Actor

class ScheduleActor extends Actor {




  override def receive: Receive = {

    case "log orderbooks" =>
      sender() ! "log orderbooks"

    case "breathe" =>
      println("breathe")
      sender() ! "heartbeat"
  }

}
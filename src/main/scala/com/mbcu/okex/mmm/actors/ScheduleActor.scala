package com.mbcu.okex.mmm.actors

import akka.actor.Actor

class SchedulerActor extends Actor {




  override def receive: Receive = {

    case "log orderbooks" =>
      sender() ! "log orderbooks"
  }

}
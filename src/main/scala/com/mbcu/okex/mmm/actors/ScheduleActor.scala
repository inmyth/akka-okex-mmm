package com.mbcu.okex.mmm.actors

import akka.actor.Actor
import com.mbcu.okex.mmm.actors.ScheduleActor.{Heartbeat, RegularOrderCheck}

object ScheduleActor {

  object Heartbeat

  object RegularOrderCheck
}

class ScheduleActor extends Actor {


  override def receive: Receive = {

    case "log orderbooks" => sender() ! "log orderbooks"

    case Heartbeat => sender() ! Heartbeat

    case RegularOrderCheck => sender() ! RegularOrderCheck
  }

}
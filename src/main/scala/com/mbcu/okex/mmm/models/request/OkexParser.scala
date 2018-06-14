package com.mbcu.okex.mmm.models.request

import akka.actor.ActorRef
import com.mbcu.okex.mmm.models.request.OkexChannels.OkexChannels
import com.mbcu.okex.mmm.models.request.OkexEvents.OkexEvents
import com.mbcu.okex.mmm.models.request.OkexParser.{LoggedIn, OkexError, Pong}
import play.api.libs.json.{JsArray, JsValue, Json}

import scala.util.Try

object OkexParser{

  object Pong

  object LoggedIn

  case class OkexError(code : Int, msg :String)
}

class OkexParser(ref : ActorRef) {


  def parse(raw : String): Try[Unit] = {

      Try{
          val array : JsArray = Json.parse(raw).as[JsArray]
          val root = array.head.as[JsValue]

          if((root \ "event").isDefined) {
            (root \ "event").as[OkexEvents] match {
              case OkexEvents.pong => ref ! Pong
            }
          }
          else {
            val channel = (root \ "channel").as[String]
            val data = root \ "data"
            if ((data \ "error_msg").isDefined && (data \ "error_code").isDefined) {
              ref ! OkexError((data \ "error_code").as[Int], (data \ "error_msg").as[String])
            }
            else {
              channel match  {
                case c if c == OkexChannels.login.toString => ref ! LoggedIn
                case c if c == OkexChannels.ok_spot_order.toString =>
                case c if c == OkexChannels.ok_spot_orderinfo.toString =>
                case c if c == OkexChannels.ok_spot_cancel_order.toString =>
                case c if c.endsWith("_balance") => println("User Account Info")
                case c if c.endsWith("_order") => println("Trade Record")
                case _ => println(s"Unknown : $channel")

              }
            }
          }
      }

  }
}


package com.mbcu.okex.mmm.models.request

import akka.actor.ActorRef
import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType
import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType.OkexRestType
import com.mbcu.okex.mmm.models.internal.Offer
import com.mbcu.okex.mmm.models.internal.OfferStatus.OfferStatus
import com.mbcu.okex.mmm.models.internal.Side.Side
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.mbcu.okex.mmm.models.request.OkexParser._
import com.mbcu.okex.mmm.utils.MyLogging
import play.api.libs.json.{JsArray, JsValue, Json}

import scala.util.Try

object OkexParser{

  object Pong

  object LoggedIn

  case class ErrorFatal(code : Int, msg :String)

  case class ErrorIgnore(code: Int, msg : String)

  case class GotStartPrice(symbol : String, price : Option[BigDecimal])

  case class GotOrderId(symbol : String, id : String)

  case class GotOrderInfo(symbol : String, offer : Offer)
}

class OkexParser(ref : ActorRef) extends MyLogging {

  def parse(raw : String): Try[Unit] = {
    println(raw)
      Try{
        if (raw == "{\"event\":\"pong\"}"){
          ref ! Pong
        }
        else {
          val array: JsArray = Json.parse(raw).as[JsArray]
          val root = array.head.as[JsValue]
          val channel = (root \ "channel").as[String]
          val data = root \ "data"
          if ((data \ "error_msg").isDefined && (data \ "error_code").isDefined){
            pipeErrors((data \ "error_code").as[Int], (data \ "error_msg").as[String], ref)
          }
          else {
            channel match {
              case c if c == OkexChannels.login.toString => ref ! LoggedIn
              case c if c == OkexChannels.ok_spot_order.toString => info(s"OkexParser#parse : do not send new order with ws, cannot associate symbol with order_id.  $raw ")
              case c if c == OkexChannels.ok_spot_orderinfo.toString =>
                val okexOrder = (data \ "orders").as[JsArray].head.as[JsValue]
                val offer = new Offer(
                  (okexOrder \ "order_id").as[Long].toString,
                  (okexOrder \ "symbol").as[String],
                  (okexOrder \ "type").as[Side],
                  (okexOrder \ "status").asOpt[OfferStatus],
                  (okexOrder \ "create_date").asOpt[Long],
                  None,
                  (okexOrder \ "amount").as[BigDecimal],
                  (okexOrder \ "price").as[BigDecimal],
                  (okexOrder \ "deal_amount").asOpt[BigDecimal]
                )
                ref ! GotOrderInfo(offer.symbol, offer)

                /*
                {
  "result": true,
  "orders": [
    {
      "symbol": "trx_eth",
      "amount": 10,
      "orders_id": 19978226,
      "price": 0.00008301,
      "avg_price": 0,
      "create_date": 1529329074000,
      "type": "buy",
      "deal_amount": 0,
      "order_id": 19978226,
      "status": 0
    }
  ]
}
                 */

              case c if c == OkexChannels.ok_spot_cancel_order.toString =>
              case c if c.endsWith("_balance") => println("OkexParser#parse User Account Info")
              case c if c.endsWith("_order") => println("OkexParser#parse Trade Record")
              case _ =>
                info(
                  s"""Response:
                     |${data.as[JsValue]}
                   """.stripMargin)
            }
          }
        }
      }
  }

  def parseRest(symbol: String, okexRestType: OkexRestType, raw : String) : Unit = {
    val js = Json.parse(raw)
    if ((js \ "error_code").isDefined){
      pipeErrors((js \ "error_code").as[Int], "REST error doesn't come with msg", ref)
    }
    else{
      okexRestType match  {
        case OkexRestType.ticker =>
          val lastTrade = (js \ "ticker" \ "last").as[BigDecimal]
          ref ! GotStartPrice(symbol, Some(lastTrade))
        case OkexRestType.ownHistory =>
          val trade = (js \ "orders").as[JsArray].head
          if (trade.isDefined) ref ! GotStartPrice(symbol, Some((trade \ "price").as[BigDecimal])) else ref ! GotStartPrice(symbol, None)
        case OkexRestType.newOrderId => ref ! GotOrderId(symbol, (js \ "order_id").as[Long].toString)

        //        if (isDataError(data)) {
        //          pipeErrors(data, ref)
        //        }
        //        else {
        //          ref ! GotOrderId(symbol, (data \ "order_id").as[Long].toString)
        //        }

        //          val responseOffer = new Offer(
        //            (trade \ "order_id").as[Long].toString,
        //            (trade \ "symbol").as[String],
        //            (trade \ "type").as[Side],
        //            (trade \ "status").asOpt[OfferStatus],
        //            (trade \ "create_date").asOpt[Long],
        //            None,
        //            (trade \ "amount").as[BigDecimal],
        //            (trade \ "price").as[BigDecimal],
        //            (trade \ "deal_amount").asOpt[BigDecimal]
        //          )
        case _ => info(s"Unkown OkexParser#parseRest : $raw")

      }
    }


  }


  private def pipeErrors(code : Int, msg : String,  ref : ActorRef): Unit = {

//    val code = (data \ "error_code").as[Int]
//    val msg = (data \ "error_msg").as[String]
    println(s"$code $msg")
    code match {
      case 10009 | 10010 | 10011 | 10014 | 10016 => ref ! ErrorIgnore(code, msg)
      case _ => ref ! ErrorFatal(code, msg)
    }

  }
}


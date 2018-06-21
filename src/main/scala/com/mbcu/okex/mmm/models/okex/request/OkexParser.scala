package com.mbcu.okex.mmm.models.okex.request

import akka.actor.ActorRef
import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType
import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType.OkexRestType
import com.mbcu.okex.mmm.models.common.Offer
import com.mbcu.okex.mmm.models.common.OfferStatus.OfferStatus
import com.mbcu.okex.mmm.models.common.Side.Side
import com.mbcu.okex.mmm.models.okex.request.OkexParser._
import com.mbcu.okex.mmm.utils.MyLogging
import play.api.libs.json._

import scala.util.Try

object OkexParser{

  object Pong

  object LoggedIn

  case class ErrorFatal(code : Int, msg :String)

  case class ErrorIgnore(code: Int, msg : String)

  case class ErrorRestRetry(code: Int, msg: String, symbol: String, okexRestType : OkexRestType, originalParams : Map[String, String])

  case class GotStartPrice(symbol : String, price : Option[BigDecimal])

  case class GotOrderId(symbol : String, id : String)

  case class GotOrderInfo(symbol : String, offer : Offer)

  case class GotOrderCancelled(symbol : String, id: String)

  case class GotOrderbook(symbol : String, offers : Seq[Offer], currentPage : Int, nextPage : Boolean)



  val OKEX_ERRORS = Map(
    1002 -> "The transaction amount exceed the balance",
    1003 -> "The transaction amount is less than the minimum",
    20100 -> "request time out",
    10009 -> "Order does not exist",
    10010 -> "Insufficient funds",
    10011 -> "Amount too low",
    10014 -> "Order price must be between 0 and 1,000,000",
    10016 -> "Insufficient coins balance",
    10024 -> "balance not sufficient"
  )

}



class OkexParser(ref : ActorRef) extends MyLogging {

  def parse(raw : String): Try[Unit] = {
    info(
      s"""Response:
         |$raw
       """.stripMargin)
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
            pipeErrors((data \ "error_code").as[Int], (data \ "error_msg").as[String], ref, None, None, None)
          }
          else {
            channel match {
              case c if c == OkexChannels.login.toString => ref ! LoggedIn
              case c if c == OkexChannels.ok_spot_order.toString => info(s"OkexParser#parse : do not send new order with ws, cannot associate symbol with order_id.  $raw ")
              case c if c == OkexChannels.ok_spot_orderinfo.toString =>
                val okexOrder = (data \ "orders").as[JsArray].head.as[JsValue]
                val offer = toOffer(okexOrder)
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
                val id = (data \ "order_id").as[Long]
                info(s"OkexParse#parse Order cancelled $id")
/*
                {
    "binary": 0,
    "channel": "ok_spot_cancel_order",
    "data": {
        "result": true,
        "order_id": "125433027"
    }
}
                 */
              case c if c.endsWith("_balance") => info(
                s"""OkexParser#parse User Account Info :
                   |$data
                 """.stripMargin)
              case c if c.startsWith("ok_sub_spot_") && c.endsWith("_order") =>
                val offer = toOffer(data.as[JsValue])
                ref ! GotOrderInfo(offer.symbol, offer)


/*
[
  {
    "binary": 0,
    "channel": "ok_sub_spot_trx_eth_order",
    "data": {
      "symbol": "trx_eth",
      "orderId": 20201923,
      "tradeUnitPrice": "0.00009016",
      "tradeAmount": "20.00",
      "createdDate": "1529407457926",
      "completedTradeAmount": "0.00",
      "averagePrice": "0",
      "tradePrice": "0.00000000",
      "tradeType": "buy",
      "status": 0
    }
  }
]
 */
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

  val toOffer :  JsValue => Offer = (data: JsValue) =>
  new Offer(
    (data \ "order_id").as[Long].toString,
    (data \ "symbol").as[String],
    (data \ "type").as[Side],
    (data \ "status").asOpt[OfferStatus],
    (data \ "create_date").asOpt[Long],
    None,
    (data \ "amount").as[BigDecimal],
    (data \ "price").as[BigDecimal],
    (data \ "deal_amount").asOpt[BigDecimal]
  )

  def parseRest(symbol: String, originalParams: Map[String, String], okexRestType: OkexRestType, raw : String) : Unit = {
    info(
      s"""Response:
         |$raw
       """.stripMargin)
    Try{
      val js = Json.parse(raw)
      if ((js \ "error_code").isDefined){
        val code = (js \ "error_code").as[Int]
        val msg = OKEX_ERRORS.getOrElse(code, " code list : https://github.com/okcoin-okex/API-docs-OKEx.com/blob/master/API-For-Spot-EN/Error%20Code%20For%20Spot.md")
        pipeErrors(code, msg, ref, Some(symbol), Some(okexRestType), Some(originalParams))
      }
      else{
        okexRestType match  {
          case OkexRestType.ticker =>
            val lastTrade = (js \ "ticker" \ "last").as[BigDecimal]
            ref ! GotStartPrice(symbol, Some(lastTrade))

          case OkexRestType.ownHistoryFilled =>
            val trade = (js \ "orders").as[JsArray].head
            if (trade.isDefined) ref ! GotStartPrice(symbol, Some((trade \ "price").as[BigDecimal])) else ref ! GotStartPrice(symbol, None)

          case OkexRestType.ownHistoryUnfilled =>
            val orders = (js \ "orders").as[List[JsValue]]
            val res = orders.map(toOffer)
            val currentPage = (js \ "currency_page").as[Int]
            val nextPage =  if ((js \ "page_length").as[Int] > 200) true else false
            ref ! GotOrderbook(symbol, res, currentPage, nextPage)

          case OkexRestType.newOrderId => ref ! GotOrderId(symbol, (js \ "order_id").as[Long].toString)

          case OkexRestType.cancelOrderSingle => ref ! GotOrderCancelled(symbol, (js \ "order_id").as[String])

          case OkexRestType.orderInfo =>
            val order = (js \ "orders").as[JsArray].head
            if (order.isDefined) ref ! GotOrderInfo(symbol, toOffer(order.as[JsValue])) else ref ! ErrorFatal(-10, s"OkexParser#parseRest Undefined orderInfo $raw")

          case _ => error(s"Unknown OkexParser#parseRest : $raw")
        }
      }
    }
  }


  private def pipeErrors(code : Int, msg : String,  ref : ActorRef, symbol: Option[String], okexRestType: Option[OkexRestType], originalParams : Option[Map[String, String]]): Unit = {
    code match {
      case 10009 | 10010 | 10011 | 10014 | 10016 | 10024 | 1002  => ref ! ErrorIgnore(code, msg)
      case 20100 => (symbol, okexRestType, originalParams) match {
        case (Some(sbl), Some(restType), Some(params)) => ref ! ErrorRestRetry(code, msg, sbl, restType, params)
        case _ => ref ! ErrorIgnore(code, s"$msg , WARNING: this request should not be done by websocket because the original params cannot be retrieved")
      }
      case _ => ref ! ErrorFatal(code, msg)
    }



  }
}


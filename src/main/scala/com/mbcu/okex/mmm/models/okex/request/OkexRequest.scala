package com.mbcu.okex.mmm.models.okex.request

import java.security.MessageDigest

import com.mbcu.okex.mmm.models.common.Side.Side
import com.mbcu.okex.mmm.models.common.{Credentials, Offer}
import com.mbcu.okex.mmm.models.okex.request.OkexChannels.OkexChannels
import com.mbcu.okex.mmm.models.okex.request.OkexEvents.OkexEvents
import com.mbcu.okex.mmm.models.okex.request.OkexStatus.OkexStatus
import play.api.libs.functional.syntax._
import play.api.libs.json._


object OkexEvents extends Enumeration {
  type OkexEvents = Value
  val addChannel, removeChannel, login, ping, pong = Value

  implicit val read = Reads.enumNameReads(OkexEvents)
  implicit val write = Writes.enumNameWrites

  def withNameOpt(s: String): Option[Value] = values.find(_.toString == s)
}

object OkexChannels extends Enumeration {
  type OkexChannels = Value
  val ok_spot_orderinfo, ok_spot_order, ok_spot_cancel_order, login   = Value

  implicit val read = Reads.enumNameReads(OkexChannels)
  implicit val write = Writes.enumNameWrites

  def withNameOpt(s: String): Option[Value] = values.find(_.toString == s)
}

object OkexRequest {
  private val md5: MessageDigest = MessageDigest.getInstance("MD5")

  implicit val jsonFormat = Json.format[OkexRequest]

  object Implicits {
    implicit val writes = new Writes[OkexRequest] {
      def writes(r: OkexRequest): JsValue = Json.obj(
        "event" -> r.event,
        "channel" -> r.channel,
        "parameters" -> r.parameters
      )
    }

    implicit val reads: Reads[OkexRequest] = (
      (JsPath \ "event").read[OkexEvents] and
        (JsPath \ "channel").readNullable[OkexChannels] and
        (JsPath \ "parameters").readNullable[OkexParameters]
      ) (OkexRequest.apply _)

  }

  def login(apiKey : String, secret : String) : OkexRequest = {
    val params = OkexParameters(None, apiKey, None, None, None, None, None)
    val signed = sign(secret, params)
    val p =  OkexParameters(Some(signed), apiKey, None, None, None, None, None)
    OkexRequest(OkexEvents.login, None, Some(p))
  }

  def newOrder(credentials: Credentials, offer: Offer): OkexRequest = newOrder(credentials.pKey, credentials.signature, offer.symbol, offer.side, offer.quantity, offer.price)


  def newOrder(apiKey : String, secret : String, symbol : String, `type` : Side, amount : BigDecimal, price : BigDecimal): OkexRequest = {
    val params = OkexParameters(None, apiKey, Some(symbol), None, Some(`type`), Some(price), Some(amount))
    val signed = sign(secret, params)
    val p =  OkexParameters(Some(signed), apiKey, Some(symbol), None, Some(`type`), Some(price), Some(amount))
    OkexRequest(OkexEvents.addChannel, Some(OkexChannels.ok_spot_order), Some(p))
  }

  def restCancelOrder(credentials: Credentials, symbol: String, orderId : String) : Map[String, String] =
    restCancelOrder(credentials.pKey, credentials.signature, symbol, orderId)


  def restCancelOrder(apiKey: String, secret : String, symbol: String, orderId : String) : Map[String, String] = {
    val params = OkexParameters(None, apiKey, Some(symbol), Some(orderId), None, None, None, None, None, None)
    val signed = sign(secret, params)
    val p = OkexParameters(Some(signed), apiKey, Some(symbol), Some(orderId), None, None, None, None, None, None )
    Json.toJson(p).as[JsObject].value.map(r => r._1 -> r._2.toString().replace("\"", "")).toMap
  }

  def cancelOrder(apiKey: String, secret : String, symbol : String, orderId : String) : OkexRequest = {
    val p = min3(apiKey, secret, symbol, orderId)
    OkexRequest(OkexEvents.addChannel, Some(OkexChannels.ok_spot_cancel_order), Some(p))
  }

  def infoOrder(apiKey : String, secret : String, symbol : String, orderId : String) : OkexRequest = {
    val p = min3(apiKey, secret, symbol, orderId)
    OkexRequest(OkexEvents.addChannel, Some(OkexChannels.ok_spot_orderinfo), Some(p))
  }

  def restNewOrder(credentials: Credentials, symbol: String, `type`: Side, price: BigDecimal, amount: BigDecimal) : Map[String, String] =
    restNewOrder(credentials.pKey, credentials.signature, symbol, `type`, price, amount)

  def restNewOrder(apiKey: String, secret: String, symbol: String, `type`: Side, price: BigDecimal, amount: BigDecimal) : Map[String, String] = {
    val params = OkexParameters(None, apiKey, Some(symbol), None, Some(`type`), Some(price), Some(amount), None, None, None)
    val signed = sign(secret, params)
    val p = OkexParameters(Some(signed), apiKey, Some(symbol), None, Some(`type`), Some(price), Some(amount), None, None, None)
    Json.toJson(p).as[JsObject].value.map(r => r._1 -> r._2.toString().replace("\"", "")).toMap
  }

  def restInfoOrder(credentials: Credentials, symbol: String, orderId: String) : Map[String, String] =
    restInfoOrder(credentials.pKey, credentials.signature, symbol : String, orderId: String)

  def restInfoOrder(apiKey : String, secret: String, symbol : String, orderId: String) : Map[String, String] = {
    val params = OkexParameters(None, apiKey, Some(symbol), Some(orderId), None, None, None, None, None, None)
    val signed = sign(secret, params)
    val p = OkexParameters(Some(signed), apiKey, Some(symbol), Some(orderId), None, None, None, None, None, None)
    Json.toJson(p).as[JsObject].value.map(r => r._1 -> r._2.toString().replace("\"", "")).toMap
  }

//  def trades(apiKey : String, secret : String, symbol : String) : OkexRequest = {
//    val params = OkexParameters(None, apiKey, Some(symbol), None, None, None, None)
//    val signed = sign(secret, params)
//    val p =  OkexParameters(Some(signed), apiKey, Some(symbol), None, None, None, None)
//    OkexRequest(OkexEvents.addChannel, Some(OkexChannels.o), Some(p))
//  }

  def restOwnTrades(apiKey:String, secret:String, symbol:String, status: OkexStatus, currentPage : Int) : Map[String, String] = {
    val pageLength = 200
    val params = OkexParameters(None, apiKey, Some(symbol), None, None, None, None, Some(status), Some(currentPage), Some(pageLength))
    val signed = sign(secret, params)
    val p = OkexParameters(Some(signed), apiKey, Some(symbol), None, None, None, None, Some(status), Some(currentPage), Some(pageLength))
    Json.toJson(p).as[JsObject].value.map(r => r._1 -> r._2.toString().replace("\"", "")).toMap
  }

  def restTicker(symbol: String) : Map[String, String] = Map("symbol" -> symbol)

  def ping() : OkexRequest = OkexRequest(OkexEvents.ping, None, None)


  private def min3(apiKey: String, secret: String, symbol: String, orderId: String ) : OkexParameters = {
    val params = OkexParameters(None, apiKey, Some(symbol), Some(orderId), None, None, None)
    OkexParameters(Some(sign(secret, params)), apiKey, Some(symbol), Some(orderId), None, None, None)
  }

  private def sign(secretKey : String, params : OkexParameters): String = {
    val a =  Json.toJson(params).as[JsObject]
    val b = a.fields.sortBy(_._1).map(c => s"""${c._1.toString}=${c._2}""").reduce((l, r) => s"""$l&$r""")
    val d = s"""$b&secret_key=$secretKey""".replace("\"", "")
    import java.math.BigInteger
    import java.nio.charset.StandardCharsets
    md5.update(StandardCharsets.UTF_8.encode(d))
    String.format("%032x", new BigInteger(1, md5.digest)).toUpperCase
  }
}

case class OkexRequest (event : OkexEvents, channel : Option[OkexChannels], parameters : Option[OkexParameters])

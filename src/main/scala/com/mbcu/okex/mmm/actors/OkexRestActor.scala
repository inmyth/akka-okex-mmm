package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef}
import akka.dispatch.ExecutionContexts.global
import akka.stream.ActorMaterializer
import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType.OkexRestType
import com.mbcu.okex.mmm.actors.OkexRestActor._
import play.api.libs.json.Json
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.ExecutionContextExecutor

object OkexRestActor{

  object GotTicker

  case class GetOwnHistory(symbol : String, params : Map[String, String])

  case class GetTicker(symbol : String, params : Map[String, String])

  case class NewOrder(symbol: String, params : Map[String, String])

  case class GotRestText(symbol : String, restType : OkexRestType, raw : String)


  object OkexRestType extends  Enumeration {
    type OkexRestType = Value
    val ownHistory, ticker, newOrderId = Value
  }
}

class OkexRestActor(url : String) extends Actor {
  import play.api.libs.ws.DefaultBodyReadables._
  import play.api.libs.ws.DefaultBodyWritables._
  private var main : Option[ActorRef] = None
  implicit val materializer = ActorMaterializer()
  private  implicit val ec: ExecutionContextExecutor = global


  private var ws : StandaloneAhcWSClient = _

//  def call(wsClient: StandaloneWSClient, url: String): Future[Unit] = {
//    wsClient.url(url).get().map { response =>
//      val statusText: String = response.statusText
//      val body = response.body[String]
//      println(s"Got a response $body")
//    }
//  }

  override def receive: Receive = {

    case "start" =>
      ws = StandaloneAhcWSClient()
      main = Some(sender())

    case NewOrder(symbol, p) =>
      ws.url(s"$url/trade.do")
        .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded")
        .post(stringifyXWWWForm(p))
        .map(response => {main.foreach(_ ! GotRestText(symbol, OkexRestType.newOrderId, response.body[String]))})

    case GetOwnHistory(symbol, p) =>
      ws.url(s"$url/order_history.do")
      .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded")
      .post(stringifyXWWWForm(p))
      .map(response => {main.foreach(_ ! GotRestText(symbol, OkexRestType.ownHistory, response.body[String]))})

    case GetTicker(symbol, p) =>
      ws.url(s"$url/ticker.do")
        .addQueryStringParameters(p.toSeq: _*)
        .get().map { response => main.foreach(_ ! GotRestText(symbol, OkexRestType.ticker, response.body[String]))
      }

    case "terminate" => ws.close()
  }


  private  def stringifyXWWWForm(params : Map[String, String]) : String = params.map(r => s"${r._1}=${r._2}").mkString("&")

}

package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props}
import akka.dispatch.ExecutionContexts.global
import akka.stream.ActorMaterializer
import com.mbcu.okex.mmm.actors.MainActor.ConfigReady
import com.mbcu.okex.mmm.actors.WsActor.{SendJs, WsConnected, WsGotText}
import com.mbcu.okex.mmm.models.internal.Config
import com.mbcu.okex.mmm.models.request.OkexParser.{LoggedIn, OkexError}
import com.mbcu.okex.mmm.models.request.{OkexParser, OkexRequest, OkexStatus}
import com.mbcu.okex.mmm.utils.MyLogging
import play.api.libs.json.Json
import play.api.libs.ws._
import play.api.libs.ws.ahc._

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

object MainActor{
  case class ConfigReady(config : Try[Config])

}

class MainActor(configPath: String) extends Actor with MyLogging{
  val WS_ENDPOINT = "wss://real.okex.com:10441/websocket"
  private var config: Option[Config] = None
  private var ws: Option[ActorRef] = None
  private var logCancellable : Option[Cancellable] = None
  private var state : Option[ActorRef] = None
  private var ses : Option[ActorRef] = None
  import DefaultBodyReadables._
  private  implicit val ec: ExecutionContextExecutor = global
  val parser : OkexParser = new OkexParser(self)
  implicit val materializer = ActorMaterializer()

  override def receive: Receive = {

    case "start" =>
      val fileActor = context.actorOf(Props(new FileActor(configPath)))
      fileActor ! "start"

    case ConfigReady(tcfg) =>
      tcfg match {
        case Failure(f) => println(
          s"""Config error
             |$f
          """.stripMargin)
          System.exit(-1)

        case Success(cfg) =>
          config = Some(cfg)
//          ses = Some(context.actorOf(Props(new SesActor(cfg.env.sesKey, cfg.env.sesSecret, cfg.env.emails)), name = "ses"))
//          ses foreach (_ ! "start")
//          state = Some(context.actorOf(Props(new StateActor(cfg)), name = "state"))
//          state foreach (_ ! "start")
//          val scheduleActor = context.actorOf(Props(classOf[ScheduleActor]))
//          logCancellable =Some(
//            context.system.scheduler.schedule(
//              10 second,
//              (if (cfg.env.logSeconds < 5) 5 else cfg.env.logSeconds) second,
//              scheduleActor,
//              "log orderbooks"))
            ws = Some(context.actorOf(Props(new WsActor(WS_ENDPOINT)), name = "ws"))
            ws.foreach(_ ! "start")
      }


    case WsConnected =>
      info(s"Connected to $WS_ENDPOINT")
//      parser = Some(context.actorOf(Props(new ParserActor(config))))
      self ! "login"

    case "login" => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.login(c.credentials.pKey, c.credentials.secret)))))

    case LoggedIn => println("login success")

    case "init heartbeat" =>



    case WsGotText(raw) => parser.parse(raw)

    case OkexError(code,msg) => info(s"Error: $msg, code:$code")

//    case "start" =>
//
//      val wsClient = StandaloneAhcWSClient()
//
//      val historyParams = OkexRequest.history("fde11386-3736-49a8-bce2-644a9712b071", "0E15C1C460767AA3141215F621612F35", "trx_eth", OkexStatus.unfilled, 1, 10)
//
//
//
////      postHistory(wsClient, "https://www.okex.com/api/v1/ticker.do?symbol=ltc_btc", historyParams )
//
//      getTicker(wsClient, OkexRequest.marketTicker("trx_eth"))
//
////      .andThen{case _ => wsClient.close()}
//
//
//
  }

  def stringifyXWWWForm(params : Map[String, String]) : String = params.map(r => s"${r._1}=${r._2}").mkString("&")


  def postHistory(wsClient : StandaloneWSClient, url: String, params : Map[String, String]) : Future[Unit] = {
    import play.api.libs.ws.DefaultBodyReadables._
    import play.api.libs.ws.DefaultBodyWritables._
    wsClient.url("https://www.okex.com/api/v1/order_history.do")
      .addHttpHeaders("Content-Type" -> "application/x-www-form-urlencoded")
      .post(stringifyXWWWForm(params))
      .map(response => {

        val statusText: String = response.statusText
        val body = response.body[String]
        println(
          s"""
             |$body
             ${response.statusText}
           """.stripMargin)
      })

  }

  def getTicker(ws: StandaloneWSClient, params: Map[String, String]): Future[Unit] = {
    ws.url("https://www.okex.com/api/v1/ticker.do")
      .addQueryStringParameters(params.toSeq: _*)
      .get().map { response =>
      val statusText: String = response.statusText
      val body = response.body[String]
      println(s"Got a response $body")
    }
  }

  def call(wsClient: StandaloneWSClient, url: String): Future[Unit] = {
    wsClient.url(url).get().map { response =>
      val statusText: String = response.statusText
      val body = response.body[String]
      println(s"Got a response $body")
    }
  }
}

package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.dispatch.ExecutionContexts.global
import com.mbcu.okex.mmm.actors.MainActor.{ConfigReady, SendRestOrders}
import com.mbcu.okex.mmm.actors.OkexRestActor._
import com.mbcu.okex.mmm.actors.OrderbookActor.OrderbookCreated
import com.mbcu.okex.mmm.actors.ScheduleActor.Heartbeat
import com.mbcu.okex.mmm.actors.WsActor.{SendJs, WsConnected, WsGotText}
import com.mbcu.okex.mmm.models.internal.Config
import com.mbcu.okex.mmm.models.request.OkexParser._
import com.mbcu.okex.mmm.models.request.{OkexParser, OkexRequest, OkexStatus}
import com.mbcu.okex.mmm.utils.MyLogging
import play.api.libs.json.Json

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object MainActor{
  case class ConfigReady(config : Try[Config])

  case class SendRestOrders(symbol : String, order : Seq[Map[String, String]])

}

class MainActor(configPath: String) extends Actor with MyLogging{
  val WS_ENDPOINT = "wss://real.okex.com:10441/websocket"
  val REST_ENDPOINT = "https://www.okex.com/api/v1"

  private var config: Option[Config] = None
  private var ws: Option[ActorRef] = None
  private var rest : Option[ActorRef] = None
  private var logCancellable : Option[Cancellable] = None
  private var heartbeatCancellable : Option[Cancellable] = None
  private var scheduleActor: ActorRef = context.actorOf(Props(classOf[ScheduleActor]))
  private var state : Option[ActorRef] = None
  private var ses : Option[ActorRef] = None
  private  implicit val ec: ExecutionContextExecutor = global
  val parser : OkexParser = new OkexParser(self)

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
//            scheduleActor = Some(context.actorOf(Props(classOf[ScheduleActor])))
//          logCancellable =Some(
//            context.system.scheduler.schedule(
//              10 second,
//              (if (cfg.env.logSeconds < 5) 5 else cfg.env.logSeconds) second,
//              scheduleActor,
//              "log orderbooks"))
            ws = Some(context.actorOf(Props(new WsActor(WS_ENDPOINT)), name = "ws"))
            ws.foreach(_ ! "start")
            rest = Some(context.actorOf(Props(new OkexRestActor(REST_ENDPOINT)), name = "rest"))
            rest.foreach(_ ! "start")
      }


    case WsConnected =>
      info(s"Connected to $WS_ENDPOINT")
      self ! "login"

    case "login" => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.login(c.credentials.pKey, c.credentials.signature)))))

    case LoggedIn =>
      info("Login Success")
      self ! "init orderbooks"
      self ! "init heartbeat"

    case "init orderbooks" => config.foreach(_.bots.foreach(bot => {
      config.foreach(cfg => {
        val book = context.actorOf(Props(new OrderbookActor(bot, cfg.credentials)), name = s"${bot.pair}")
        book ! "start"
      })
    }))

    case "init heartbeat" =>
        heartbeatCancellable = Some(
           context.system.scheduler.schedule(
          2 second,
          10 second,
           scheduleActor,
           Heartbeat))

    case OrderbookCreated(bot) =>
      bot.startingPrice match {
      case s if s contains "last" => s match {
        case m if m.toLowerCase == "lastown" => config.foreach(c => rest.foreach(_ ! GetOwnHistory(bot.pair, OkexRequest.restOwnTrades(c.credentials.pKey, c.credentials.signature, bot.pair, OkexStatus.filled, 1, 10))))
        case m if m.toLowerCase == "lastticker" => rest.foreach(_ ! GetTicker(bot.pair, OkexRequest.restTicker(bot.pair)))
      }
      case _ => self ! GotStartPrice(bot.pair, Some(BigDecimal(bot.startingPrice)))
    }

    case Heartbeat => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.ping())))

    case Pong => info("heartbeat")

    case GotStartPrice(symbol, price) => price match {
      case Some(p) => context.actorSelection(s"/user/main/$symbol") ! GotStartPrice(symbol, price)
      case _ =>
        error(s"MainActor#GotStartPrice : Starting price for $symbol not found. Try different startPrice in bot")
        System.exit(-1)
    }

    case SendRestOrders(symbol, pmaps) => pmaps.foreach(p => rest.foreach(_ ! NewOrder(symbol, p)))

    case GotOrderId(symbol, id) => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.infoOrder(c.credentials.pKey, c.credentials.signature, symbol, id )))))

    case GotOrderInfo(symbol, offer) => context.actorSelection(s"/user/main/$symbol") ! GotOrderInfo(symbol, offer)

    case WsGotText(raw) => parser.parse(raw)

    case GotRestText(symbol, restType, raw) => parser.parseRest(symbol, restType, raw)

    case ErrorFatal(code,msg) =>
      info(s"ErrorFatal: $msg, code:$code")
      System.exit(-1)

    case ErrorIgnore(code,msg) => info(s"ErrorNonFatal: $msg, code:$code")

    case "test ws" => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.infoOrder(c.credentials.pKey, c.credentials.signature, "trx_eth", "16543180")))))


    case "test rest" =>

//      val wsClient = StandaloneAhcWSClient()
//
//      val historyParams = OkexRequest.restOwnTrades("fde11386-3736-49a8-bce2-644a9712b071", "0E15C1C460767AA3141215F621612F35", "trx_eth", OkexStatus.unfilled, 1, 10)



//      postHistory(wsClient, "https://www.okex.com/api/v1/ticker.do?symbol=ltc_btc", historyParams )

//      getTicker(wsClient, OkexRequest.restTicker("bch_btc"))

//      .andThen{case _ => wsClient.close()}



  }

  def toWs(okexRequest: OkexRequest): Unit = ws.foreach(_ ! SendJs(Json.toJson(okexRequest)))





}

package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.dispatch.ExecutionContexts.global
import com.mbcu.okex.mmm.actors.MainActor._
import com.mbcu.okex.mmm.actors.OkexRestActor._
import com.mbcu.okex.mmm.actors.OrderbookActor.{OrderbookCreated, SendCheckOrderSeq, SendRestOrders}
import com.mbcu.okex.mmm.actors.ScheduleActor.{Heartbeat, RegularOrderCheck}
import com.mbcu.okex.mmm.actors.SesActor.{CacheMessages, MailSent, MailTimer}
import com.mbcu.okex.mmm.actors.WsActor.{SendJs, WsConnected, WsGotText}
import com.mbcu.okex.mmm.models.internal.Config
import com.mbcu.okex.mmm.models.request.OkexParser._
import com.mbcu.okex.mmm.models.request.{OkexParser, OkexRequest, OkexStatus}
import com.mbcu.okex.mmm.utils.MyLogging
import play.api.libs.json.Json

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

object MainActor{
  case class ConfigReady(config : Try[Config])

  case class HandleRPCError(shutdowncode : Option[Int], errorCode: Int, msg : String)

  case class Shutdown(code :Int)

  case class HandleError(msg :String, code : Option[Int] = None)

  case class SendCheckOrder(symbol : String, id : String)

  case class SendCheckOrderRest(symbol: String, params: Map[String, String])

}

class MainActor(configPath: String) extends Actor with MyLogging{
  val WS_ENDPOINT = "wss://real.okex.com:10441/websocket"
  val REST_ENDPOINT = "https://www.okex.com/api/v1"

  private var config: Option[Config] = None
  private var ws: Option[ActorRef] = None
  private var rest : Option[ActorRef] = None
  private var logCancellable : Option[Cancellable] = None
  private var heartbeatCancellable : Option[Cancellable] = None
  private var ordercheckCancellable : Option[Cancellable] = None
  private var scheduleActor: ActorRef = context.actorOf(Props(classOf[ScheduleActor]))
  private var ses : Option[ActorRef] = None
  private  implicit val ec: ExecutionContextExecutor = global
  val parser : OkexParser = new OkexParser(self)

  override def receive: Receive = {

    case "start" =>
      val fileActor = context.actorOf(Props(new FileActor(configPath)))
      fileActor ! "start"

    case ConfigReady(tcfg) =>
      tcfg match {
        case Failure(f) => error(
          s"""Config error
             |$f
          """.stripMargin)
          System.exit(-1)

        case Success(cfg) =>
            config = Some(cfg)
            ses = Some(context.actorOf(Props(new SesActor(cfg.env.sesKey, cfg.env.sesSecret, cfg.env.emails)), name = "ses"))
            ses foreach (_ ! "start")
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
      self ! "init regular order check"
      self ! "init log orderbooks"

    case "init orderbooks" => config.foreach(cfg => cfg.bots.foreach(bot => {
        val book = context.actorOf(Props(new OrderbookActor(bot, cfg.credentials)), name = s"${bot.pair}")
        book ! "start"
    }))

    case "init heartbeat" => heartbeatCancellable = Some(context.system.scheduler.schedule(2 second, 10 second, scheduleActor, Heartbeat))

    case "init regular order check" =>
      val totalOrders = config match {
        case Some(c) => (c.bots map (b => b.buyGridLevels + b.sellGridLevels)).sum
        case _ => 0
      }
      ordercheckCancellable = Some(context.system.scheduler.schedule(2 second, (totalOrders + 5) second, scheduleActor, RegularOrderCheck))

    case "init log orderbooks" => logCancellable = Some(context.system.scheduler.schedule(10 second, 30 second, scheduleActor, "log orderbooks"))

    case OrderbookCreated(bot) =>
      bot.startingPrice match {
      case s if s contains "last" => s match {
        case m if m.toLowerCase == "lastown" => config.foreach(c => rest.foreach(_ ! GetOwnHistory(bot.pair, OkexRequest.restOwnTrades(c.credentials.pKey, c.credentials.signature, bot.pair, OkexStatus.filled, 1, 10))))
        case m if m.toLowerCase == "lastticker" => rest.foreach(_ ! GetTicker(bot.pair, OkexRequest.restTicker(bot.pair)))
      }
      case _ => self ! GotStartPrice(bot.pair, Some(BigDecimal(bot.startingPrice)))
    }

    case Heartbeat => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.ping())))

    case Pong =>

    case RegularOrderCheck => config.foreach(c => c.bots foreach(b => context.actorSelection(s"/user/main/${b.pair}") ! RegularOrderCheck))

    case "log orderbooks" => config.foreach(c => c.bots foreach(b => context.actorSelection(s"/user/main/${b.pair}") ! "log orderbook"))

    case GotStartPrice(symbol, price) => price match {
      case Some(p) => context.actorSelection(s"/user/main/$symbol") ! GotStartPrice(symbol, price)
      case _ =>
        error(s"MainActor#GotStartPrice : Starting price for $symbol not found. Try different startPrice in bot")
        System.exit(-1)
    }

    case SendRestOrders(symbol, pMaps, as) =>
      pMaps.zipWithIndex.foreach{
        case (params, i) =>  {
          info(s"""MainACtor#SendRestOrders sending $as : $symbol
               |$params""".stripMargin)
          rest.foreach(r => context.system.scheduler.scheduleOnce((500 * i) milliseconds, r, NewOrder(symbol, params)))
        }
      }

    case SendCheckOrderSeq(symbol, ids) =>
      ids.zipWithIndex.foreach{
        case (id, i) =>
//          context.system.scheduler.scheduleOnce((1 * i) second, self, SendCheckOrder(symbol, id)) // REPLACED WITH REST
          config.foreach(c => context.system.scheduler.scheduleOnce((1 * i) second, self, SendCheckOrderRest(symbol, OkexRequest.restInfoOrder(c.credentials, symbol, id))))
      }

    case SendCheckOrder(symbol, id) => config.foreach(c => ws.foreach(_ ! SendJs(Json.toJson(OkexRequest.infoOrder(c.credentials.pKey, c.credentials.signature, symbol, id)))))

    case SendCheckOrderRest(symbol, params) =>  config.foreach(c => rest.foreach(_ ! GetOrderInfo(symbol, params)))

    case GotOrderId(symbol, id) => self ! SendCheckOrder(symbol, id)

    case GotOrderInfo(symbol, offer) => context.actorSelection(s"/user/main/$symbol") ! GotOrderInfo(symbol, offer)

    case WsGotText(raw) => parser.parse(raw)

    case GotRestText(symbol, originalParams, okexRestType, raw) => parser.parseRest(symbol, originalParams, okexRestType, raw)

    case ErrorFatal(code, msg) => self ! HandleRPCError(Some(-1), code, msg)

    case ErrorIgnore(code,msg) => self ! HandleRPCError(None, code, msg)

    case ErrorRestRetry(code, msg, symbol, okexRestType, originalParams) =>
      val delay = scala.util.Random.nextInt(5) * 500

      okexRestType match {
        case OkexRestType.newOrderId => rest.foreach(r => context.system.scheduler.scheduleOnce(delay milliseconds, r, NewOrder(symbol, originalParams)))
        case OkexRestType.orderInfo => rest.foreach(r => context.system.scheduler.scheduleOnce(delay milliseconds, r, SendCheckOrderRest(symbol, originalParams)))
        case _ => HandleRPCError(None, code, s"MainActor#ErrorRestRetry not implemented : $okexRestType")
      }

    case HandleRPCError(shutdowncode, code, msg) =>
      var s =s"""Error code $code
           |$msg
         """.stripMargin
      error(s)
      ses foreach(_ ! CacheMessages(s, shutdowncode))

    case MailTimer =>
      ses match {
        case Some(s) => context.system.scheduler.scheduleOnce(5 second, s, "execute send")
        case _ => warn("MainActor#MailTimer no ses actor")
      }


    case MailSent(t, shutdownCode) =>
      t match {
        case Success(_) => info("Email Sent")
        case Failure(c) => info(
          s"""Failed sending email
             |${c.getMessage}
          """.stripMargin)
      }
      shutdownCode match {
        case Some(sdCode) => self ! Shutdown(sdCode)
        case _ =>
      }

    case Shutdown(code) =>
      info(s"Stopping application, shutdown code $code")
      implicit val executionContext: ExecutionContext = context.system.dispatcher
      context.system.scheduler.scheduleOnce(Duration.Zero)(System.exit(code))

  }


}

package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef, Cancellable, Props}
import akka.dispatch.ExecutionContexts.global
import com.mbcu.okex.mmm.actors.MainActor._
import com.mbcu.okex.mmm.actors.OkexRestActor._
import com.mbcu.okex.mmm.actors.OrderbookActor._
import com.mbcu.okex.mmm.actors.ScheduleActor.RegularOrderCheck
import com.mbcu.okex.mmm.actors.SesActor.{CacheMessages, MailSent, MailTimer}
import com.mbcu.okex.mmm.actors.WsActor.WsGotText
import com.mbcu.okex.mmm.models.internal.{Config, StartingPrice}
import com.mbcu.okex.mmm.models.request.OkexParser._
import com.mbcu.okex.mmm.models.request.{OkexParser, OkexRequest, OkexStatus}
import com.mbcu.okex.mmm.utils.MyLogging

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
  private var rest : Option[ActorRef] = None
  private var logCancellable : Option[Cancellable] = None
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
            rest = Some(context.actorOf(Props(new OkexRestActor(REST_ENDPOINT)), name = "rest"))
            rest.foreach(_ ! "start")
            self ! "init orderbooks"
            self ! "init regular order check"
            self ! "init log orderbooks"
      }


    case "init orderbooks" => config.foreach(cfg => cfg.bots.foreach(bot => {
        val book = context.actorOf(Props(new OrderbookActor(bot, cfg.credentials)), name = s"${bot.pair}")
        book ! "start"
    }))

    case "init regular order check" =>
      val totalOrders = config match {
        case Some(c) => (c.bots map (b => b.buyGridLevels + b.sellGridLevels)).sum
        case _ => 0
      }
      ordercheckCancellable = Some(context.system.scheduler.schedule(2 second, (totalOrders + 5) second, scheduleActor, RegularOrderCheck))

    case "init log orderbooks" => logCancellable = Some(context.system.scheduler.schedule(10 second, 30 second, scheduleActor, "log orderbooks"))

    case GetOrderbook(symbol, page) => config.foreach(c => rest.foreach(_ ! GetOwnHistory(symbol, OkexRequest.restOwnTrades(c.credentials.pKey, c.credentials.signature, symbol, OkexStatus.unfilled, page), OkexRestType.ownHistoryUnfilled)))

    case GotOrderbook(symbol, offers, currentPage, nextPage) => context.actorSelection(s"/user/main/$symbol") ! GotOrderbook(symbol, offers, currentPage, nextPage)

    case ClearOrderbook(symbol, idList) =>
      idList.zipWithIndex.foreach {
        case (id, i) =>
          info(s"MainActor#ClearOrderbook : deleting $id - $symbol")
          config.foreach(c => context.system.scheduler.scheduleOnce((500 * i) millisecond, self, CancelOrder(symbol, OkexRequest.restCancelOrder(c.credentials, symbol, id))))
      }

    case CancelOrder(symbol, id) => rest.foreach(_ ! CancelOrder(symbol, id))

    case GotOrderCancelled(symbol, id) => context.actorSelection(s"/user/main/${symbol}") ! GotOrderCancelled(symbol, id)

    case GetLastTrade(bot) =>
      bot.startingPrice match {
        case s if s contains "last" => s match {
          case m if m.equalsIgnoreCase(StartingPrice.lastOwn.toString) => config.foreach(c => rest.foreach(_ ! GetOwnHistory(bot.pair, OkexRequest.restOwnTrades(c.credentials.pKey, c.credentials.signature, bot.pair, OkexStatus.filled, 1), OkexRestType.ownHistoryFilled)))
          case m if m.equalsIgnoreCase(StartingPrice.lastTicker.toString) => rest.foreach(_ ! GetTicker(bot.pair, OkexRequest.restTicker(bot.pair)))
        }
        case s if s contains "cont" => info("Cont strategy : continue from last orderbook as is")
        case _ => self ! GotStartPrice(bot.pair, Some(BigDecimal(bot.startingPrice)))
      }

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
          info(s"""MainActor#SendRestOrders sending $as : $symbol
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


    case SendCheckOrderRest(symbol, params) =>  config.foreach(c => rest.foreach(_ ! GetOrderInfo(symbol, params)))

    case GotOrderId(symbol, id) =>
      context.actorSelection(s"/user/main/$symbol") ! GotOrderId(symbol, id)
      self ! SendCheckOrder(symbol, id)

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

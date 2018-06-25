package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.dispatch.ExecutionContexts.global
import com.mbcu.okex.mmm.actors.OrderbookActor._
import com.mbcu.okex.mmm.models.common.Side.Side
import com.mbcu.okex.mmm.models.common._
import com.mbcu.okex.mmm.models.okex.OkexEnv
import com.mbcu.okex.mmm.models.okex.request.OkexParser.{GotOrderCancelled, GotOrderInfo, GotOrderbook, GotStartPrice}
import com.mbcu.okex.mmm.models.okex.request.OkexRequest
import com.mbcu.okex.mmm.sequences.Strategy
import com.mbcu.okex.mmm.sequences.Strategy.PingPong
import com.mbcu.okex.mmm.utils.MyLogging
import scala.language.postfixOps

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

object OrderbookActor {

  case class SendRestOrders(symbol : String, order : Seq[Map[String, String]], as : String)

  case class SendCheckOrderSeq(symbol : String, ids : Seq[String])

  case class ClearOrderbook(symbol : String, ids : Seq[String])

  case class GetOrderbook(symbol : String, page: Int)

  case class GetLastTrade(bot : Bot)

}

class OrderbookActor(bot : Bot, creds : Credentials) extends Actor with MyLogging {
  private  implicit val ec: ExecutionContextExecutor = global

  var sels : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var buys : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var sortedSels: scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  var sortedBuys : scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  var refreshCancellable : Option[Cancellable] = None
  private var main : Option[ActorRef] = None
  val toRestParams: (Offer, String, Credentials) => Map[String, String] = (o: Offer, symbol: String, cred: Credentials) => OkexRequest.restNewOrder(creds, symbol, o.side, o.price, o.quantity)

  override def receive: Receive = {

    case "start" =>
      main = Some(sender())
      sender ! GetOrderbook(bot.pair, 1)

    case GotOrderbook(symbol, offers, currentPage, nextPage) =>
      offers.foreach(self ! GotOrderInfo(symbol, _))
      if (nextPage) {
        main.foreach(_ ! GetOrderbook(symbol, currentPage + 1))
      } else {
        self ! "keep or clear orderbook"
      }

    case "keep or clear orderbook" =>
      bot.startingPrice match {
        case a if a.equalsIgnoreCase(StartingPrice.lastOwn.toString) | a.equalsIgnoreCase(StartingPrice.lastTicker.toString) =>
          if (sels.isEmpty && buys.isEmpty) self ! "init price" else main.foreach(_ ! ClearOrderbook(bot.pair, (buys ++ sels).toSeq.map(_._1)))
        case _ => self ! "init price"
      }

    case "init price" => main.foreach(_ ! GetLastTrade(bot))

    case "refresh orders" =>
      main.foreach(_ ! SendCheckOrderSeq(bot.pair, (buys ++ sels).toSeq.map(_._1)))
      self ! "balancer"

    case "balancer" =>
      val growth = grow(Side.buy) ++ grow(Side.sell)
      sendOrders(bot.pair, growth, "balancer")


    case GotOrderCancelled(symbol, id) =>
      remove(Side.sell, id)
      remove(Side.buy, id)
      if (sels.isEmpty && buys.isEmpty) self ! "init price"

    case GotStartPrice(symbol, price) =>
      price match {
        case Some(p) =>
          val seed = initialSeed(p, isHardReset = true)
          sendOrders(symbol, seed, "seed")
        case _ =>
      }

    case GotOrderInfo(symbol, offer) =>
      refreshCancellable.foreach(_.cancel())
      refreshCancellable = Some(context.system.scheduler.scheduleOnce(OkexEnv.waitLong.id millisecond, self, "refresh orders"))

      offer.status match {

        case Some(OfferStatus.unfilled) =>
          add(offer)
          sort(offer.side)

        case Some(OfferStatus.filled) =>
          remove(offer.side, offer.offerId)
          sortBoth()
          val counters = counter(offer)
          sendOrders(symbol, counters, "counter")


        case Some(OfferStatus.partialFilled) =>
          add(offer)
          sort(offer.side)

        case Some(OfferStatus.cancelled | OfferStatus.cancelInProcess) =>
          remove(offer.side, offer.offerId)
          sort(offer.side)

        case _ => info(s"OrderbookActor_${bot.pair}#GotOrderInfo : unrecognized offer status")

    }

    case "log orderbooks" => info(Offer.dump(sortedBuys, sortedSels))
  }

  def sendOrders(symbol: String, seed: Seq[Offer], as: String) : Unit = main.foreach(_ ! SendRestOrders(symbol, seed.map(toRestParams(_, symbol, creds)), as))

  def sortBoth() : Unit = {
    sort(Side.buy)
    sort(Side.sell)
  }

  def initialSeed(midPrice : BigDecimal, isHardReset : Boolean): Seq[Offer] = {
    var res : Seq[Offer] = Seq.empty[Offer]
    var buyQty = BigDecimal(0)
    var selQty = BigDecimal(0)
    var calcMidPrice = midPrice
    var buyLevels = 0
    var selLevels = 0

    def set(up : BigDecimal, bq : BigDecimal, sq : BigDecimal, bl : Int, sl : Int) : Unit = {
      buyQty = bq
      selQty = sq
      buyLevels = bl
      selLevels = sl
      calcMidPrice = up
    }

    (sortedBuys.size, sortedSels.size) match {
      case (a, s) if a == 0 && s == 0 => set(midPrice, bot.buyOrderQuantity, bot.sellOrderQuantity, bot.buyGridLevels, bot.sellGridLevels)

      case (a, s) if a != 0 && s == 0 =>
        val anyBuy  = sortedBuys.head
        val calcMid = Strategy.calcMid(anyBuy.price, anyBuy.quantity, bot.quantityPower, bot.gridSpace, bot.counterScale, Side.buy, midPrice, bot.strategy)
        val bl = if (isHardReset) bot.buyGridLevels else calcMid._3 - 1
        set(calcMid._1, calcMid._2, calcMid._2, bl, bot.sellGridLevels)

      case (a, s) if a == 0 && s != 0 =>
        val anySel  = sortedSels.head
        val calcMid = Strategy.calcMid(anySel.price, anySel.quantity, bot.quantityPower, bot.gridSpace, bot.counterScale, Side.sell, midPrice, bot.strategy)
        val sl = if (isHardReset) bot.sellGridLevels else calcMid._3 - 1
        set(calcMid._1, calcMid._2, calcMid._2, bot.buyGridLevels, sl)

      case (a, s) if a != 0 && s != 0 =>
        val anySel  = sortedSels.head
        val calcMidSel = Strategy.calcMid(anySel.price, anySel.quantity, bot.quantityPower, bot.gridSpace, bot.counterScale, Side.sell, midPrice, bot.strategy)
        val anyBuy  = sortedBuys.head
        val calcMidBuy = Strategy.calcMid(anyBuy.price, anyBuy.quantity, bot.quantityPower, bot.gridSpace, bot.counterScale, Side.buy, calcMidSel._1, bot.strategy)
        val bl = if (isHardReset) bot.buyGridLevels else calcMidBuy._3 - 1
        val sl = if (isHardReset) bot.sellGridLevels else calcMidSel._3 - 1
        set(calcMidSel._1, calcMidBuy._2, calcMidSel._2, bl, sl)
    }

    res ++= Strategy.seed(buyQty, calcMidPrice, bot.quantityPower, bot.counterScale, bot.baseScale, bot.pair, buyLevels, bot.gridSpace, Side.buy, PingPong.ping,false, bot.strategy, bot.isNoQtyCutoff, bot.maxPrice, bot.minPrice)
    res ++= Strategy.seed(selQty, calcMidPrice, bot.quantityPower, bot.counterScale, bot.baseScale, bot.pair, selLevels, bot.gridSpace, Side.sell, PingPong.ping,false, bot.strategy, bot.isNoQtyCutoff, bot.maxPrice, bot.minPrice)
    res
  }

  def counter(order : Offer) : Seq[Offer] = Strategy.counter(order.quantity, order.price, bot.quantityPower, bot.counterScale, bot.baseScale, bot.pair, bot.gridSpace, order.side, PingPong.pong, bot.strategy, bot.isNoQtyCutoff, bot.maxPrice, bot.minPrice)

  def grow(side : Side) : Seq[Offer] = {
    def matcher(side : Side) : Seq[Offer] = {
      val preSeed = getRuntimeSeedStart(side)
      Strategy.seed(preSeed._2, preSeed._3, bot.quantityPower, bot.counterScale, bot.baseScale, bot.pair, preSeed._1, bot.gridSpace, side, PingPong.ping, preSeed._4, bot.strategy, bot.isNoQtyCutoff, bot.maxPrice, bot.minPrice)
    }
    side match {
      case Side.buy  => matcher(Side.buy)
      case Side.sell => matcher(Side.sell)
      case _ =>  Seq.empty[Offer] // unsupported operation at runtime
    }
  }

  def getRuntimeSeedStart(side : Side) : (Int, BigDecimal, BigDecimal, Boolean) = {
    var isPulledFromOtherSide : Boolean = false
    var levels : Int = 0

    val q0p0 = side match {
      case Side.buy =>
        sortedBuys.size match {
          case 0 =>
            levels = bot.buyGridLevels
            isPulledFromOtherSide = true
            (getTopSel.quantity, getTopSel.price)
          case _ =>
            levels = bot.buyGridLevels - sortedBuys.size
            (getLowBuy.quantity, getLowBuy.price)
        }
      case Side.sell =>
        sortedSels.size match {
          case 0 =>
            levels = bot.sellGridLevels
            isPulledFromOtherSide = true
            (getTopBuy.quantity, getTopBuy.price)
          case _ =>
            levels = bot.sellGridLevels - sortedSels.size
            (getLowSel.quantity, getLowSel.price)
        }
      case _ => (BigDecimal("0"), BigDecimal("0"))
    }
    (levels, q0p0._1, q0p0._2, isPulledFromOtherSide)
  }

  def add(offer : Offer) : Unit = {
    var l = offer.side match {
      case Side.buy => buys
      case Side.sell => sels
      case _ => TrieMap.empty[String, Offer]
    }
    l += (offer.offerId -> offer)
  }

  def remove(side : Side, clientOrderId : String ) : Unit = {
    var l = side match {
      case Side.buy => buys
      case Side.sell => sels
      case _ => TrieMap.empty[String, Offer]
    }
    l -= clientOrderId
  }

  def sort(side : Side) : Unit = {
    side match {
      case Side.buy => sortedBuys = sortBuys(buys)
      case Side.sell => sortedSels = sortSels(sels)
      case _ =>
        sortedBuys = sortBuys(buys)
        sortedSels = sortSels(sels)
    }
  }

  def sortBuys(buys : TrieMap[String, Offer]) : scala.collection.immutable.Seq[Offer] =
    collection.immutable.Seq(buys.toSeq.map(_._2).sortWith(_.price > _.price) : _*)

  def sortSels(sels : TrieMap[String, Offer]): scala.collection.immutable.Seq[Offer] =
    collection.immutable.Seq(sels.toSeq.map(_._2).sortWith(_.price < _.price) : _*)

  def getTopSel: Offer = sortedSels.head

  def getLowSel: Offer = sortedSels.last

  def getTopBuy: Offer = sortedBuys.head

  def getLowBuy: Offer = sortedBuys.last
}

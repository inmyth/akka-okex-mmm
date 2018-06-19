package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef}
import com.mbcu.okex.mmm.actors.OrderbookActor.{OrderbookCreated, SendCheckOrderSeq, SendRestOrders}
import com.mbcu.okex.mmm.actors.ScheduleActor.RegularOrderCheck
import com.mbcu.okex.mmm.models.internal.Side.Side
import com.mbcu.okex.mmm.models.internal._
import com.mbcu.okex.mmm.models.request.OkexParser.{GotOrderInfo, GotStartPrice}
import com.mbcu.okex.mmm.models.request.OkexRequest
import com.mbcu.okex.mmm.sequences.Strategy
import com.mbcu.okex.mmm.sequences.Strategy.PingPong
import com.mbcu.okex.mmm.utils.MyLogging

import scala.collection.concurrent.TrieMap

object OrderbookActor {

  case class OrderbookCreated(bot : Bot)

  case class SendRestOrders(symbol : String, order : Seq[Map[String, String]], as : String)

  case class SendCheckOrderSeq(symbol : String, ids : Seq[String])

}

class OrderbookActor(bot : Bot, creds : Credentials) extends Actor with MyLogging {
  var sels : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var buys : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var selTrans : Int = 0
  var buyTrans : Int = 0
  var sortedSels: scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  var sortedBuys : scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  private var main : Option[ActorRef] = None

  val toRestParams: (Offer, String, Credentials) => Map[String, String] = (o: Offer, symbol: String, cred: Credentials) => OkexRequest.restNewOrder(creds, symbol, o.side, o.price, o.quantity)

  override def receive: Receive = {

    case "start" =>
      main = Some(sender())
      sender ! OrderbookCreated(bot)

    case GotStartPrice(symbol, price) =>
      price.foreach(p => {
        val seed = initialSeed(p, isHardReset = true).map(toRestParams(_, symbol, creds))
//        val seed = initialSeed(p, isHardReset = true).map(o => OkexRequest.restNewOrder(creds, symbol, o.side, o.price, o.quantity))
        main.foreach(_ ! SendRestOrders(symbol, seed, "seed"))

      })

    case GotOrderInfo(symbol, offer) =>
      offer.status match {

      case Some(OfferStatus.unfilled) =>
        add(offer)
        sort(offer.side)

      case Some(OfferStatus.filled) =>
        remove(offer.side, offer.offerId)
        val counters = counter(offer).map(toRestParams(_, offer.symbol, creds))
        sortBoth()

        val growth = grow(offer.side).map(toRestParams(_, offer.symbol, creds))

        main.foreach(_ ! SendRestOrders(symbol, counters, "counter"))
        main.foreach(_ ! SendRestOrders(symbol, growth, "balancer"))


      case Some(OfferStatus.partialFilled) =>
        add(offer)
        sort(offer.side)

      case Some(OfferStatus.cancelled | OfferStatus.cancelInProcess) =>
        remove(offer.side, offer.offerId)
        sort(offer.side)

      case _ => info(s"OrderbookActor_${bot.pair}#GotOrderInfo : unrecognized offer status")

    }

    case RegularOrderCheck => main.foreach(_ ! SendCheckOrderSeq(bot.pair, (buys ++ sels).toSeq.map(_._1)))

    case "log orderbook" => info(Offer.dump(sortedBuys, sortedSels))
  }

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
    var qty0 : BigDecimal = BigDecimal("0")
    var unitPrice0 : BigDecimal = BigDecimal("0")
    var isPulledFromOtherSide : Boolean = false
    var levels : Int = 0

    var order : Option[Offer] = None
    side match {
      case Side.buy =>
        sortedBuys.size match {
          case 0 =>
            levels = bot.buyGridLevels
            isPulledFromOtherSide = true
            order = Some(getTopSel)
          case _ =>
            levels = bot.buyGridLevels - sortedBuys.size
            order = Some(getLowBuy)
        }
      case Side.sell =>
        sortedSels.size match {
          case 0 =>
            levels = bot.sellGridLevels
            isPulledFromOtherSide = true
            order = Some(getTopBuy)
          case _ =>
            levels = bot.sellGridLevels - sortedSels.size
            order = Some(getLowSel)
        }
      case _ => println("Orderbookactor#getPreSeed : _")
    }
    order foreach (o => {
      qty0 = o.quantity
      unitPrice0 = o.price
    })
    (levels, qty0, unitPrice0, isPulledFromOtherSide)
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

package com.mbcu.okex.mmm.actors

import akka.actor.{Actor, ActorRef}
import com.mbcu.okex.mmm.actors.MainActor.SendRestOrders
import com.mbcu.okex.mmm.actors.OrderbookActor.OrderbookCreated
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

}

class OrderbookActor(bot : Bot, creds : Credentials) extends Actor with MyLogging {
  var sels : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var buys : TrieMap[String, Offer] = TrieMap.empty[String, Offer]
  var selTrans : Int = 0
  var buyTrans : Int = 0
  var sortedSels: scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  var sortedBuys : scala.collection.immutable.Seq[Offer] = scala.collection.immutable.Seq.empty[Offer]
  private var main : Option[ActorRef] = None


  override def receive: Receive = {

    case "start" =>
      main = Some(sender())
      sender ! OrderbookCreated(bot)

    case GotStartPrice(symbol, price) =>
      price.foreach(p => {
        val seed = initialSeed(p, isHardReset = true).map(o => OkexRequest.restNewOrder(creds, symbol, o.side, o.price, o.quantity))
        main.foreach(_ ! SendRestOrders(symbol, seed))

      })

    case GotOrderInfo(symbol, offer) =>
      println(offer)
      offer.status match {

      case OfferStatus.unfilled => add(offer)

      case OfferStatus.filled =>  remove(offer.side, offer.offerId)

      case OfferStatus.partialFilled => add(offer)

      case OfferStatus.cancelled | OfferStatus.cancelInProcess =>  remove(offer.side, offer.offerId)


    }

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
}

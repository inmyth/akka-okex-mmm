package com.mbcu.okex.mmm.sequences

import java.math.MathContext

import com.mbcu.okex.mmm.models.internal.Side.Side
import com.mbcu.okex.mmm.models.internal.{Offer, Side}
import com.mbcu.okex.mmm.sequences.Strategy.Movement.Movement
import com.mbcu.okex.mmm.sequences.Strategy.PingPong.PingPong
import com.mbcu.okex.mmm.sequences.Strategy.Strategies.Strategies
import play.api.libs.json.{Reads, Writes}

import scala.math.BigDecimal.RoundingMode

object Strategy {
  val mc : MathContext = MathContext.DECIMAL64
  val ZERO = BigDecimal("0")
  val ONE = BigDecimal("1")
  val CENT = BigDecimal("100")
  val INFINITY = BigDecimal("1e60")

  object Movement extends Enumeration {
    type Movement = Value
    val UP, DOWN  = Value
  }

  object Strategies extends Enumeration {
    type Strategies = Value
    val ppt, fullfixed = Value

    implicit val strategiesReads = Reads.enumNameReads(Strategies)
    implicit val strategiesWrites = Writes.enumNameWrites
  }

  object PingPong extends Enumeration {
    type PingPong = Value
    val ping, pong = Value

    def reverse (a : PingPong) : PingPong = {
      if (a == ping) pong else ping
    }

  }

  def seed (qty0 : BigDecimal, unitPrice0 : BigDecimal, amtPwr : Int,
            ctrScale : Int, basScale : Int, symbol : String, levels : Int, gridSpace : BigDecimal,
            side: Side, act : PingPong,
            isPulledFromOtherSide : Boolean, strategy : Strategies, isNoQtyCutoff : Boolean,
            maxPrice : Option[BigDecimal] = None, minPrice : Option[BigDecimal] = None) : Seq[Offer] = {
    val range = strategy match {
      case Strategies.ppt => if(isPulledFromOtherSide) 2 else 1
      case Strategies.fullfixed => if(isPulledFromOtherSide) 2 else 1
      case _ => 0
    }
    val zero = PriceQuantity(unitPrice0, qty0)

    (range until (levels + range))
      .map(n => {
        val movement = if (side == Side.buy) Movement.DOWN else Movement.UP
        strategy match {
          case Strategies.ppt =>
            val mtp = ONE + gridSpace(mc) / CENT
            List.fill(n)(1)
              .foldLeft(zero)((z, i) => ppt(z.price, z.quantity, amtPwr, mtp, ctrScale, movement))

          case Strategies.fullfixed =>
            List.fill(n)(1)
              .foldLeft(zero)((z, i) => step(z.price, z.quantity, gridSpace, ctrScale, movement))

          case _ => PriceQuantity(ZERO, ZERO)
        }
      }
      )
      .filter(_.price > 0)
      .map(p1q1 => if (p1q1.quantity <= 0 && isNoQtyCutoff) PriceQuantity(p1q1.price, calcMinAmount(ctrScale)) else p1q1)
      .filter(_.quantity > 0)
      .filter(minPrice match {
        case Some(mp) => _.price >= mp
        case _ => _.price > ZERO
      })
      .filter(maxPrice match {
        case Some(mp) => _.price <= mp
        case None => _.price < INFINITY
      })
      .map(p1q1 => {
        Offer.newOffer(symbol, side, p1q1.price, p1q1.quantity.setScale(basScale, RoundingMode.HALF_EVEN))
      })
  }

  case class PriceQuantity(price : BigDecimal, quantity: BigDecimal)


  def counter(qty0 : BigDecimal, unitPrice0 : BigDecimal, amtPwr : Int, ctrScale : Int, basScale : Int, symbol : String, gridSpace : BigDecimal, side : Side,
              oldAct : PingPong, strategy : Strategies, isNoQtyCutoff : Boolean,
              maxPrice : Option[BigDecimal] = None, minPrice : Option[BigDecimal] = None)
  : Seq[Offer] = {
    val newSide = Side.reverse(side)
    val newAct  = PingPong.reverse(oldAct)
    seed(qty0, unitPrice0, amtPwr, ctrScale, basScale, symbol, 1, gridSpace, newSide, newAct, isPulledFromOtherSide = false, strategy, isNoQtyCutoff, maxPrice, minPrice)
  }

  def ppt(unitPrice0 : BigDecimal, qty0 : BigDecimal, amtPower : Int, rate : BigDecimal, ctrScale : Int, movement: Movement): PriceQuantity ={
    val unitPrice1 = if (movement == Movement.DOWN) unitPrice0(mc) / rate else unitPrice0 * rate
    val mtpBoost = sqrt(rate) pow amtPower
    var qty1 = if (movement == Movement.DOWN) roundCeil(qty0 * mtpBoost, ctrScale) else roundFloor(qty0(mc) / mtpBoost, ctrScale)
    PriceQuantity(unitPrice1, qty1)
  }

  def step(unitPrice0 : BigDecimal, qty0 : BigDecimal, rate : BigDecimal, ctrScale : Int, movement: Movement ): PriceQuantity ={
    val unitPrice1 = if (movement == Movement.DOWN) unitPrice0(mc) - rate else unitPrice0 + rate
    val qty1 = qty0
    PriceQuantity(unitPrice1, qty1)
  }

  def calcMinAmount(ctrScale : Int) : BigDecimal = {
    ONE.setScale(ctrScale, BigDecimal.RoundingMode.CEILING)
  }

  def calcMid(unitPrice0 : BigDecimal, qty0 : BigDecimal, amtPower : Int, gridSpace : BigDecimal, ctrScale : Int, side: Side, marketMidPrice : BigDecimal, strategy : Strategies)
  : (BigDecimal, BigDecimal, Int) = {
    var base = PriceQuantity(unitPrice0, qty0)
    var levels = 0
    val mtp = ONE + gridSpace(mc) / CENT

    side match {
      case Side.buy =>
        while (base.price < marketMidPrice) {
          levels = levels + 1
          strategy match  {
            case Strategies.ppt => base = ppt(base.price, base.quantity, amtPower, mtp, ctrScale, Movement.UP)
            case _ => base = step(base.price, base.quantity, gridSpace, ctrScale, Movement.UP)
          }
        }
      case _ =>
        while (base.price > marketMidPrice) {
          levels = levels + 1
          strategy match  {
            case Strategies.ppt => base = ppt(base.price, base.quantity, amtPower, mtp, ctrScale, Movement.DOWN)
            case _ => base = step(base.price, base.quantity, gridSpace, ctrScale, Movement.DOWN)
          }
        }
    }
    (base.price, base.quantity, levels)
  }

  private def sqrt(a: BigDecimal, scale: Int = 16): BigDecimal = {
    val mc = MathContext.DECIMAL64
    var x = BigDecimal( Math.sqrt(a.doubleValue()), mc )

    if (scale < 17) {
      return x
    }

    var tempScale = 16
    while(tempScale < scale){
      x = x - (x * x - a)(mc) / (2 * x)
      tempScale *= 2
    }
    x
  }

  private def roundCeil(a : BigDecimal, scale : Int): BigDecimal =a.setScale(scale, RoundingMode.CEILING)

  private  def roundFloor(a : BigDecimal, scale : Int): BigDecimal =a.setScale(scale, RoundingMode.FLOOR)

  private def roundHalfDown(a : BigDecimal, scale : Int) : BigDecimal = a.setScale(scale, RoundingMode.HALF_DOWN)

}
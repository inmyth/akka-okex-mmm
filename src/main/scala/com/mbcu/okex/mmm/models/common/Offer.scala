package com.mbcu.okex.mmm.models.common

import com.mbcu.okex.mmm.models.common.Side.Side
import com.mbcu.okex.mmm.models.common.OfferStatus.OfferStatus
import play.api.libs.json._
import play.api.libs.functional.syntax._

object Side extends  Enumeration {
  type Side = Value
  val buy, sell = Value

  implicit val read = Reads.enumNameReads(Side)
  implicit val write = Writes.enumNameWrites

  def reverse(a : Side) : Side = {
    if (a == Side.buy) sell else buy
  }
}

object OfferStatus extends Enumeration {
  type OfferStatus = Value
  val filled = Value(2)
  val partialFilled = Value(1)
  val unfilled = Value(0)
  val cancelled = Value(-1)
  val cancelInProcess = Value(4)



  implicit val enumFormat = new Format[OfferStatus] {
    override def reads(json: JsValue): JsResult[OfferStatus] = json.validate[Int].map(OfferStatus(_))
    override def writes(enum: OfferStatus) = JsNumber(enum.id)
  }
}

object Offer {
  implicit val jsonFormat = Json.format[Offer]

  object Implicits {
    implicit val writes = new Writes[Offer] {
      def writes(o: Offer): JsValue = Json.obj(
        "offerId" -> o.offerId,
        "symbol" -> o.symbol,
        "side" -> o.side,
        "status" -> o.status,
        "createdAt" -> o.createdAt,
        "updatedAt" -> o.updatedAt,
        "quantity" -> o.quantity,
        "price" -> o.price,
        "cumQuantity" -> o.cumQuantity
      )
    }

    implicit val reads: Reads[Offer] = (
      (JsPath \ "offerId").read[String] and
        (JsPath \ "symbol").read[String] and
        (JsPath \ "side").read[Side] and
        (JsPath \ "status").readNullable[OfferStatus] and
        (JsPath \ "createdAt").readNullable[Long] and
        (JsPath \ "updatedAt").readNullable[Long] and
        (JsPath \ "quantity").read[BigDecimal] and
        (JsPath \ "price").read[BigDecimal] and
        (JsPath \ "cumQuantity").readNullable[BigDecimal]
      ) (Offer.apply _)
  }

  def newOffer(symbol : String, side: Side, price : BigDecimal, quantity: BigDecimal) : Offer = Offer("unused", symbol, side, None, None, None, quantity, price, None)

  def dump(sortedBuys: Seq[Offer], sortedSels: Seq[Offer]) : String = {
    val builder = StringBuilder.newBuilder
    builder.append(System.getProperty("line.separator"))
//    builder.append(self.path.name)
    builder.append(System.getProperty("line.separator"))
    builder.append(s"buys : ${sortedBuys.size}")
    builder.append(System.getProperty("line.separator"))
    sortedBuys.foreach(b => {
      builder.append(s"id:${b.offerId} quantity:${b.quantity} price:${b.price.toString} filled:${b.cumQuantity.get}")
      builder.append(System.getProperty("line.separator"))
    })
    builder.append(s"sells : ${sortedSels.size}")
    builder.append(System.getProperty("line.separator"))
    sortedSels.foreach(s => {
      builder.append(s"id:${s.offerId} quantity:${s.quantity} price:${s.price.toString} filled:${s.cumQuantity.get}")
      builder.append(System.getProperty("line.separator"))
    })
    builder.toString()
  }


}

case class Offer (
                 offerId : String,
                 symbol: String,
                 side : Side,
                 status : Option[OfferStatus],
                 createdAt : Option[Long],
                 updatedAt : Option[Long],
                 quantity: BigDecimal,
                 price : BigDecimal,
                 cumQuantity : Option[BigDecimal]
                 )

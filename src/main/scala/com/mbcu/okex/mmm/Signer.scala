package com.mbcu.okex.mmm

import com.mbcu.okex.mmm.models.okex.request.{OkexRequest, OkexStatus}
import play.api.libs.json.Json

object Signer extends App {

  val orderCheck = OkexRequest.restOwnTrades(args(0), args(1), "trx_eth", OkexStatus.unfilled, 1)
  println(orderCheck)

}

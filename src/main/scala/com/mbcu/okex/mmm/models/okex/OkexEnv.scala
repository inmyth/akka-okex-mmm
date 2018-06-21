package com.mbcu.okex.mmm.models.okex

import com.mbcu.okex.mmm.actors.OkexRestActor.OkexRestType.Value
import com.mbcu.okex.mmm.models.okex

object OkexEnv  extends  Enumeration {
  type OkexEnv = Value
  val waitLong: okex.OkexEnv.Value = Value(5000)
  val wait500: okex.OkexEnv.Value = Value(500)
  val wait300: okex.OkexEnv.Value = Value(300)
  val wait1000: okex.OkexEnv.Value = Value(1000)

}

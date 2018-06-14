package com.mbcu.okex.mmm.models.request

import org.scalatest.FunSuite
import play.api.libs.json.Json




class OkexParametersTest extends FunSuite {
  val secretKey = "abrtrtsPQper8789"


  test ("enum value") {
    val a = OkexParameters(None, "", None, None, None, None, None, Some(OkexStatus.unfilled), None, None)
    val json = Json.toJson(a)
    println(json)

    val b = """{"api_key":"","status":1}"""
    val obj = Json.parse(b).as[OkexParameters]
    println(obj.status)
  }



  test ("sign test symbol") {


  }

}

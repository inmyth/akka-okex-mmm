package com.mbcu.okex.mmm.actors

import com.mbcu.okex.mmm.models.internal.Offer
import org.scalatest.FunSuite

import scala.collection.concurrent.TrieMap

class OrderbookActorTest extends FunSuite {

  test("map to ids") {
    var sels : TrieMap[String, Int] = TrieMap.empty[String, Int]
    var buys : TrieMap[String, Int] = TrieMap.empty[String, Int]

    sels += ("a" -> 1)
    sels += ("b" -> 2)
    sels += ("c" -> 3)

    buys += ("r" -> 5)
    buys += ("t" -> 6)
    buys += ("y" -> 9)
    val d = (sels ++ buys).toSeq.map(_._1)
    assert(d === Seq("c", "r", "y", "a", "b", "t"))

  }

}

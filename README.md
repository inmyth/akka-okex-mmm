# OkEx MMM Market Maker

A market making bot similar to HitBTC one. Except Okex sucks ass so
- Websocket has to be baited to stay open with heartbeat. Idleness will cause the connection to be *irresponsive* so the bot has to disconnect and reconnect it.
- No broadcast. Bot cannot know when an order is taken.
- Order cannot be set with custom id. So we cannot know which order corresponds to which response.
- Send order will return just order id. We cannot know if it's filled, unfilled, ...
- Order has to be checked with BOTH symbol and id. Ws method only returns order id.
- *This means that websocket methods are almost useless*. Unlike rest, ws doesn't associate request with response so we can't even obtain the request info.
- Critical methods (send order, cancel order) CANNOT be sent to websocket. It may raise 20100 time out error and as I said, we cannot retrieve the original request.
- Somehow sending a ws order info request returns more orderinfo response and trade response.
- Many ws methods don't work i.e trade
- `POST /api/v1/order_history Only the most recent 2 days are returned`
-- All unfilled orders are returned -> Active orders
-- Filled orders returned are only from the past 2 days


This bot is a modified [HitBTC bot](https://github.com/inmyth/akka-hitbtc-mmm) with
Bot sends order request with REST. All these ids are verified with REST order info.
Once verified, they info is stored in orderbook .
A scheduler checks these orders for status. Depending on the status, the bot will counter or do something else.


## Config and Installation

Look at [HitBTC config](https://github.com/inmyth/akka-hitbtc-mmm)

Replace `signature ` with API secret and set "nonce" with empty string.


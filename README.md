# OkEx MMM Market Maker

A market making bot similar to HitBTC one. Except Okex sucks ass so
- Websocket has to be baited to stay open with heartbeat. Idleness will cause the connection to be *irresponsive* so the bot has to disconnect and reconnect it.
- No broadcast. Bot cannot know when an order is taken.
- No manual id assignment. So we cannot know which order corresponds to which response.
- overall shitty api
- `POST /api/v1/order_history Only the most recent 2 days are returned`
-- All unfilled orders are returned -> Active orders
-- Filled orders returned are only from the past 2 days

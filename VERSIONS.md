1.0.2
- support current orderbook retrieval and clear depending on starting price
- support pagination in order retrieval

1.0.1
- minor fixes

1.0.0
- basic version

0.0.0
- initial commit


TODO
- [x] implement add
- [x] implement delete
- [x] implement match on orderInfo arrival
- [x] implement sort
- [x] if filled : delete, counter
- [x] if unfilled : add
- [x] if cancelled : delete
- [x] add order check scheduler
- [x] triggers orderInfo on order check
- ~[] replace transient with counter~
- ~[] count orders in transit with the counter~
- [x] check amount levels
- ~[x] delete all orders pre-start~
- [x] retry rest error 20010
- [x] grow depends on at least offer on the other side which is pushed by counter. For this to work, counter has to be inserted in orderbook.
Because REST process is slow, grow may start before counter arrives and causes no head error in OrderbookActor.
~Use crude check isNotEmpty on the orderbooks to trigger grow~ Impossible because errors don't return order id
- [x] turn off websocket
- [x] initiate orders
- [x] "last" starting price will delete current orderbook then reseed it




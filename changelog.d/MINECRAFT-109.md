bump: minor

### Added
- Dispatcher offer UI (MINECRAFT-109): interacting with a Dispatcher villager now opens a
  trade-list-style screen, one row per destination contract, instead of the vanilla "no
  trades" unhappy response. Each row shows distance in kilometres and danger as both a
  number and a colour gauge, laid out as visibly independent columns. The server is the
  only source of offers: it calls `DispatcherOfferService.generateOffers` once per opened
  screen (never per tick, never for every loaded Dispatcher) and sends the results to the
  client over a dedicated menu; the client only renders what it was sent. Selecting a row
  sends a server-side accept request that is currently a stub — it validates the selection
  and tells the player, but creates no contract state; the delivery-lifecycle slice owns
  that. An empty offer list shows a plain message instead of crashing.

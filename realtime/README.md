# io.github.supabase-community/realtime

WebSocket-based Realtime integration for Supabase. Subscribe to Postgres
changes, exchange broadcast messages, and track presence — all over a
single multiplexed connection.

## Installation

```clojure
;; deps.edn
{:deps
 {io.github.supabase-community/core {:mvn/version "1.0.0"}
  io.github.supabase-community/realtime {:mvn/version "1.0.0"}}} ;; x-release-please-version
```

## Quick Start

```clojure
(require '[supabase.core.client :as client]
         '[supabase.realtime :as rt])

(def c    (client/make-client "https://abc.supabase.co" "anon-key"))
(def conn (rt/connect c {:on-error println}))

(def ch (rt/channel conn "room:lobby"
                    {:config {:broadcast {:self true}
                              :presence  {:key "user:42"}}}))

;; Listen for INSERTs on public.messages
(rt/on ch :postgres-changes
       {:event :insert :schema "public" :table "messages"}
       (fn [payload] (println "row" payload)))

;; Listen for "typing" broadcasts
(rt/on ch :broadcast {:event "typing"}
       (fn [payload] (println "typing" payload)))

;; React to presence sync
(rt/on ch :presence {:event :sync}
       (fn [_] (println "presence" (rt/presence-state ch))))

;; Open the channel
(rt/subscribe ch)

;; Domain ops (buffered until :joined arrives async)
(rt/broadcast ch "typing" {:user "alice"})
(rt/track     ch {:online_at (System/currentTimeMillis)})
(rt/untrack   ch)

;; Refresh JWT on this conn (fans out to all joined channels)
(rt/set-auth conn new-token)

(rt/unsubscribe ch)
(rt/disconnect conn)
```

## API

| Function                     | Purpose                                                       |
| ---------------------------- | ------------------------------------------------------------- |
| `connect`/`disconnect`       | Open/close a WebSocket connection.                            |
| `channel`                    | Build a channel value (no I/O).                               |
| `on`                         | Register a binding (`:postgres-changes`, `:broadcast`, `:presence`). |
| `subscribe`/`unsubscribe`    | Send `phx_join` / `phx_leave`.                                |
| `broadcast`                  | Send a broadcast message.                                     |
| `track`/`untrack`            | Send a presence track/untrack.                                |
| `presence-state`             | Read the latest presence snapshot.                            |
| `set-auth`                   | Swap the JWT on a channel or every joined channel.            |

All functions either return the input value (channel or connection) or a
cognitect-anomaly map. Async transport / server errors are delivered to
the `:on-error` callback passed to `connect`.

## v0.1.0 scope

**In:** postgres_changes, broadcast send/receive, basic presence, manual
`set-auth`, heartbeat, multi-channel per connection.

**Deferred to v0.2+:** auto-reconnect, broadcast ack / `wait_for_ack`,
HTTP fallback for broadcasts, binary v2 protocol, exponential backoff,
auto token refresh tied to the auth module.

A dropped WebSocket stays dropped — the caller is responsible for noticing
via `:on-error` and re-`connect`ing if needed.

## License

MIT

# Architecture decisions: pagination, caching, CQRS

This documents *why* the API is built the way it is and *how to reconfigure it*. For
"how do I run/build this" see [README.md](README.md).

## Why

`GET /api/items` originally loaded the entire `items` table on every call, and every read
went straight to Postgres. That doesn't hold up under high request volume: unbounded
result sets get slower (and heavier on the network/client) as the table grows, and every
read competing with writes for the same database connection pool caps how far the service
scales. The three changes below target that:

- **Pagination** bounds the size and cost of every list response, regardless of table
  size.
- **Caching** takes repeat reads off the database entirely.
- **CQRS** (Command Query Responsibility Segregation) splits the write path from the read
  path so they can be optimized, scaled, and (if needed) hosted independently — a read
  spike doesn't compete with writes for the same query path.

Note: this covers the application-level strategies. Actually serving millions of requests
in production also needs deployment-level work this repo's code doesn't provide —
running multiple app instances behind a load balancer, tuning the JDBC connection pool,
etc. The design here (stateless app, externalized cache, event-driven read refresh) is
what makes that deployment-level scaling *possible*, not a substitute for it.

## Transport: REST and gRPC, side by side

The item API is served over both REST (`controller/ItemController.java`, port 8080) and
gRPC (`src/main/proto/item.proto`, `grpc/ItemGrpcService.java`, port 9090), both calling
the same `ItemCommandService`/`ItemReadStore` — transport is purely how a request arrives,
not a difference in behavior, consistency, or which datastore is authoritative. Actuator
(health/info/metrics, port 8080) stays HTTP regardless, since it's infrastructure rather
than part of the item API.

REST's embedded Tomcat connector has HTTP/2 cleartext (h2c) enabled
(`config/Http2Config.java`), so a client that requests HTTP/2 gets the same
persistent-connection, multiplexed-stream transport characteristics gRPC has by default,
rather than being handicapped by plain HTTP/1.1. This is what makes a fair latency/throughput
comparison between the two possible — see "Load testing" below.

`db.write.duration` (the only stage where transport can matter — everything downstream,
CDC/Kafka/Redis, doesn't know or care which API a row came through) carries a `transport`
tag (`grpc`/`rest`) precisely so that comparison can be made directly in Grafana instead of
by eyeballing separate time windows.

## Pagination

`GET /api/items?page=0&size=20&sort=id,desc` — Spring Data `Pageable`, default page size
20, capped at 100 (`config/PaginationConfig.java`) so a client can't request an unbounded
result set. Response shape is `dto/PagedResult.java`, not Spring's `Page`/`PageImpl`
directly — `PageImpl` doesn't have a usable no-arg constructor, which breaks Jackson
serialization the moment the same object needs to be cache-serialized (Redis) as well as
returned as an HTTP response.

## Caching

Two cache regions, `items-by-id` and `items-list`, backed by whichever `CacheManager`
`app.cache.type` selects (`config/CacheConfig.java`):

| `app.cache.type` | Backend | Tradeoff |
|---|---|---|
| `redis` (default) | Redis, shared across app instances | One network hop per cache hit, but every instance behind a load balancer sees the same cache — required once you run more than one instance, which "millions of requests" implies. |
| `caffeine` | In-process (Caffeine) | Lowest possible latency (no network hop), but each instance has its own cache — entries can be inconsistent across instances until each independently expires/refreshes. Simplest option for a single instance or local dev without Redis running. |

TTL is `app.cache.items-ttl-seconds` (default 300), applied by both backends.

Redis values are serialized as JSON (`GenericJackson2JsonRedisSerializer`), so cached
objects are plain DTOs (`dto/ItemDto.java`, `dto/PagedResult.java`), never JPA entities —
a Hibernate-proxied `Item` doesn't serialize/deserialize reliably.

## CQRS

Writes and reads go through separate components:

- **Command** (`service/ItemCommandService.java`) — `create`/`delete` write to the
  `items` table (source of truth). PostgreSQL's WAL is captured by Debezium (Kafka
  Connect) and published to the `mydb.public.items` Kafka topic — the command path
  doesn't publish anything itself; the database's own commit is what drives the event.
- **Query** (`readmodel/ItemReadStore.java` / `RedisCacheItemReadStore.java`) — serves all
  reads from Redis. `cdc/CdcEventConsumer.java` consumes the Debezium topic and calls
  `onItemCreated`/`onItemUpdated`/`onItemDeleted`, refreshing the cache **asynchronously**,
  off the request path.

**Consistency tradeoff**: because the read side refreshes from CDC asynchronously,
there's a window after a write where a read can still miss or return stale state until the
consumer processes the corresponding event. Under normal load this is milliseconds; under
backlog (e.g. a large bulk load competing for the same WAL/Kafka pipeline) it can be much
longer — this repo's own telemetry (`cdc.capture.lag.duration`, `cdc.consume.lag.duration`)
measured minutes of lag during a 50M-row bulk seed. This is deliberate — it's what keeps
the write path's latency independent of how much read infrastructure exists behind it —
but it is not appropriate for use cases needing strict read-your-writes consistency.

### Fast path: synchronous read-model write alongside CDC

`ItemCommandService` also pushes each write directly into the Redis read model
(`fastpath.cache.write.duration`), synchronously, in addition to the CDC path above —
added specifically because the CDC-only lag measured above means a client that creates an
item and immediately reads it back can see a cache miss for anywhere from milliseconds to
minutes. This trades a small, bounded amount of write-path latency (one extra Redis round
trip normally well under a millisecond) for read-your-writes consistency in the common
case, while CDC keeps running unchanged as the durable, replayable reconciliation source —
both paths write identical derived state, so CDC's later (redundant) write is harmless.
The fast-path write is best-effort: a Redis failure is caught and logged, never propagated
to the client, since CDC remains the backstop that will eventually populate the cache
regardless. This reintroduces a dependency from the command side onto the read model that
the original CQRS write-up above deliberately avoided — accepted here because the
observed latency cost of *not* having it (a multi-minute window during backlog) outweighs
the coupling cost for this workload.

## Configuration reference

All in `src/main/resources/application.yml` (env var overrides in parentheses, as used
by `docker-compose.yml`):

| Property | Values | Default | Purpose |
|---|---|---|---|
| `app.cache.type` (`APP_CACHE_TYPE`) | `redis`, `caffeine` | `redis` | Cache backend, see above. |
| `app.cache.items-ttl-seconds` | integer | `300` | Cache entry TTL, both backends. |
| `app.cqrs.read-mode` (`APP_CQRS_READMODE`) | `cache`, `materialized-table` | `cache` | Read-model depth, see above. |
| `spring.data.redis.host`/`.port` (`SPRING_DATA_REDIS_HOST`/`PORT`) | host/port | `localhost`/`6379` | Only used when `app.cache.type=redis`. |

Spring's relaxed environment-variable binding drops hyphens, so `read-mode` becomes
`READMODE` in the env var name, not `READ-MODE`.

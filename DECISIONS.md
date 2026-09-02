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

### Write-behind: in-memory buffer, batched async flush to Postgres

This branch replaces the command path's per-request `INSERT` with
`service/WriteBehindBuffer.java`: `create` assigns an id
(`service/SnowflakeIdGenerator.java` — app-generated, since a DB-`IDENTITY` id doesn't
exist until the row is actually inserted), enqueues the item in memory, updates Redis
directly from that same layer, and returns — Postgres is not on the request's critical
path at all. A `@Scheduled` task (every 25ms) drains whatever has queued up and executes
one batched `INSERT` (`JdbcTemplate.batchUpdate`, `OVERRIDING SYSTEM VALUE` to supply the
app-generated id into the identity column) instead of one commit per row.

**Why**: this session's own bulk-seed script proved batching is the real throughput lever
— ~72K rows/sec batched vs. ~2K rows/sec one-row-per-request (measured via the gRPC/REST
load clients earlier in this session). Coalescing many concurrent requests' rows into one
INSERT amortizes the commit/WAL-flush cost across all of them instead of paying it per row.

**The tradeoff, stated plainly**: a write is acknowledged to the client (and visible in
Redis) *before* it is durably in PostgreSQL. If the process crashes with rows still in the
in-memory queue, those rows are gone — not delayed, not recoverable, gone. This is not a
free performance win; it's an explicit choice to accept a bounded window (currently up to
~25ms, one flush cycle) of "acknowledged but not yet durable" in exchange for
throughput/latency. That is a legitimate, common pattern for high-volume ingestion
pipelines where an individual row's loss on a rare crash is acceptable — it is **not**
appropriate for anything where a success response must mean "durably stored" (financial
transactions, anything audited). CDC continues to run against whatever actually lands in
Postgres, so once a batch flushes, that portion of the data reconciles into Redis exactly
as before — the tradeoff is scoped to the pre-flush window, not the whole system's
consistency model.

`delete` is unchanged from the synchronous write-through variant — deletes aren't the
throughput bottleneck this strategy targets.

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

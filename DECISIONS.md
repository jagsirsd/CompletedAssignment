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
  `items` table (source of truth) and publish a `CREATED`/`DELETED` event to the
  `item-events` Kafka topic (`kafka/ItemEventProducer.java`). It never touches a cache
  or read model directly, so write latency doesn't depend on how much read
  infrastructure exists behind it.
- **Query** (`readmodel/ItemReadStore.java`) — serves all `GET` traffic. `kafka/ItemEventConsumer.java`
  (which previously just logged) now parses each event and calls
  `onItemCreated`/`onItemDeleted` on the read store, refreshing it **asynchronously**,
  off the request path entirely.

`app.cqrs.read-mode` picks how deep that separation goes:

| `app.cqrs.read-mode` | Implementation | What it reads from |
|---|---|---|
| `cache` (default) | `readmodel/CacheBackedItemReadStore.java` | The same `items` table the write path uses, fronted by the cache above (refreshed by events, not invalidated synchronously on write). |
| `materialized-table` | `readmodel/MaterializedItemReadStore.java` | A separate `items_read` table (`readmodel/ItemReadModel.java`), populated only by the Kafka consumer — never written to directly by the command path. Still cached in front (the two settings compose). Closer to physically separate read/write stores; more moving parts (extra table, consumer does a DB write per event) in exchange for a read path that could later move to its own datastore without touching the write path at all. |

**Consistency tradeoff**: because the read side refreshes from Kafka asynchronously,
there's a brief window after a write where a read can still return the old state (e.g. a
just-deleted item still appearing in a cached list page) until the consumer processes the
event. This is deliberate — it's what keeps writes fast — and is standard for CQRS. It is
not appropriate for use cases needing strict read-your-writes consistency.

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

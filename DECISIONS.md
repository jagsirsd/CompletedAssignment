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
the Scylla write and Redis update, doesn't know or care which API a row came through)
carries a `transport` tag (`grpc`/`rest`) precisely so that comparison can be made directly
in Grafana instead of by eyeballing separate time windows.

## Datastore: ScyllaDB, not PostgreSQL — and no CDC

This branch replaces PostgreSQL with ScyllaDB (`entity/Item.java` now Cassandra-mapped,
`repository/ItemRepository.java` a `CassandraRepository`) as one of three parallel
experiments into lower write-path latency (the other two, on separate branches, keep
Postgres and instead add a synchronous or buffered fast-path write into Redis — see their
own DECISIONS.md for that writeup).

**The gap, stated plainly**: this branch has no change-data-capture pipeline at all. The
other branches' CDC path (Postgres WAL → Debezium → Kafka → consumer → Redis) has no
equivalent here — Scylla does have its own native CDC feature, but integrating it means a
completely different Kafka Connect plugin (`scylla-cdc-source-connector`) and a rewritten
event/envelope parser, not a drop-in swap of `CdcEventConsumer`. That was out of scope to
build and validate properly in this pass, so it wasn't attempted or faked. The practical
consequence: `ItemCommandService`'s synchronous write into the Redis read model
(`fastpath.cache.write.duration`) is not a latency optimization *alongside* a durable
reconciliation path here — it is the **only** thing populating the read model. If that
write fails, or if Redis is ever flushed, there is currently nothing that will replay
Scylla's data back into it. A real Scylla-backed version of this service would need that
CDC integration built before being trusted the way the Postgres branches can be.

**Why ScyllaDB is still a legitimate answer to "lower write latency"**: unlike the
Postgres branches (single-writer, WAL-bound), Scylla is built for high-throughput,
low-latency writes at scale (shard-per-core architecture, tunable consistency, no
CDC-pipeline hop required for the write itself to complete). Every write still needs an
app-generated id (`service/SnowflakeIdGenerator.java`) since Cassandra/Scylla has no
auto-increment concept — the datastore doesn't generate keys, ever, for any write.

**Ids and app generation**: because there's no CDC path deriving the read model from a
separate source of truth, `id` has no natural "the database decides eventually" story here
either. This is a smaller, self-contained instance of the same problem the write-behind
branch solves for a different reason (there, the id must exist *before* the DB write
happens; here, it must exist because the DB never generates one at all).

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

Writes and reads go through separate components, though on this branch "separate" means
something narrower than on the Postgres branches:

- **Command** (`service/ItemCommandService.java`) — `create`/`delete` write to ScyllaDB
  (source of truth), then synchronously push the same change into Redis directly (see
  "Datastore: ScyllaDB" above for why this is the *only* read-model update mechanism here).
- **Query** (`readmodel/ItemReadStore.java` / `RedisCacheItemReadStore.java`) — serves all
  reads from Redis.

**Consistency**: unlike the Postgres/CDC branches, there is no asynchronous refresh window
here — the Redis write happens synchronously, in the same request, before the client gets
a response. That's stronger read-your-writes consistency than CDC-based CQRS gives you,
but it comes at the cost described above: no durable, replayable path back to the read
model if that synchronous write is ever lost. This is a different point on the
consistency/resilience tradeoff curve than the other two branches, not simply "better."

## Configuration reference

All in `src/main/resources/application.yml` (env var overrides in parentheses, as used
by `docker-compose.yml`):

| Property | Values | Default | Purpose |
|---|---|---|---|
| `app.cache.items-ttl-seconds` | integer | `300` | Redis entry TTL. |
| `spring.cassandra.contact-points`/`.port` (`SPRING_CASSANDRA_CONTACT_POINTS`/`PORT`) | host/port | `localhost`/`9042` | Scylla connection. |
| `spring.cassandra.local-datacenter` (`SPRING_CASSANDRA_LOCAL_DATACENTER`) | string | `datacenter1` | Required by the Cassandra driver even for a single-node dev cluster. |
| `spring.cassandra.keyspace-name` (`SPRING_CASSANDRA_KEYSPACE_NAME`) | string | `demo` | Must already exist — see `scylla-init` in docker-compose.yml. |
| `spring.data.redis.host`/`.port` (`SPRING_DATA_REDIS_HOST`/`PORT`) | host/port | `localhost`/`6379` | Redis connection. |

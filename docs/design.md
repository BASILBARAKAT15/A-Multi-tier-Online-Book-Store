# Bazar.com design document

Distributed and Operating Systems, Fall 2026, Lab 1

## 1. Overview

Bazar.com is a two-tier book store built from three microservices written in
Java with the Spark micro web framework. Each service is its own process (its
own Docker container) and the services talk to each other only through HTTP
REST calls that return JSON.

```
                     browser / curl
                           |
                    +------v------+
                    |  frontend   |  :4568   search, info, purchase
                    +--+-------+--+
          search/info  |       | purchase
           +-----------v+     +v-----------+
           |  catalog   |<----+   order     |  :4569
           |   :4567    |purchase|          |
           +-----+------+     +--+---------+
                 |               |
            books.json       orders.json
```

* **Front-end tier**: `FrontendService` accepts client requests, validates
  them, forwards them to the right back-end service and relays the answer.
  It also serves a small web page (`index.html`) so the store can be used from
  a browser.
* **Back-end tier**: `CatalogService` owns the catalog (title, topic, price,
  stock for the four books) and `OrderService` owns the list of orders.

## 2. How it works

**search(topic)**: `GET /search/<topic>` on the front-end is forwarded to
`GET /search/<topic>` on the catalog, which returns `[{id, title}]` for every
book of that topic. The catalog also prints the matches as a table in its log.

**info(item)**: `GET /info/<id>` returns `{title, quantity, price}` (and also
`id`, `topic`, `stock`, which the web page uses).

**purchase(item)**: `POST /purchase/<id>` goes to the order service. It calls
`POST /catalog/books/<id>/purchase` on the catalog. The catalog checks the
stock and decrements it inside one lock, so a purchase can never see a stale
stock value. If the book is out of stock the catalog answers `409`, the order
service passes that back and records nothing. On success the order service
appends the order to `orders.json`, prints `bought book <title>` and answers
`201`.

**update**: the catalog can change a price (`{"price": n}`) and change stock
either absolutely (`{"stock": n}`) or relatively (`{"delta": n}`, positive to
restock, negative to remove). Negative prices and negative stock are rejected.

**Persistence**: the catalog keeps `data/books.json`, the order service keeps
`data/orders.json`. Both are plain JSON text files that are loaded at start-up
and rewritten after every change. In docker compose `./data` is mounted into
the containers, so data survives restarts (verified by restarting the catalog
and order containers).

**Concurrency**: Spark serves each request on its own thread, so concurrency
needs no thread code in the handlers. Shared state is protected by
`synchronized` blocks: the catalog list is locked while a book is read or
changed (check + decrement + save are one critical section) and the order list
is locked while an order is appended and saved. The smoke test fires 20
parallel purchases at a book with 5 copies and checks that exactly 5 succeed
and stock ends at 0.

**Distribution**: every address and port comes from environment variables
(`PORT`, `CATALOG_URL`, `ORDER_URL`, `DATA_DIR`), so the three services can run
on different machines. Docker compose runs them as three containers on one
network and uses the service names (`catalog`, `order`) as host names.

## 3. Design decisions and trade-offs

| Decision | Why | Cost |
|----------|-----|------|
| Spark + Gson, one jar, three entry classes | Very small framework, REST in a few lines, one Docker image for all three services (`SERVICE_CLASS` picks which one runs). | The three services share a code base and build, so they are not independently versioned. |
| JSON files instead of a database | The lab allows text files; trivial to inspect and to reset with `git checkout data`. | The whole file is rewritten on every change, which is fine for four books but would not scale. |
| Purchase = one atomic catalog call (check + decrement) | The lab describes "query the catalog, then decrement". Doing both in one call under one lock avoids the race where two buyers both see the last copy. | The order service does not do a separate verification query. |
| Catalog is the single owner of stock | One place to lock, one source of truth. | The catalog is a single point of failure and bottleneck. |
| Two URL styles for search | `/search/<topic>` follows the lab appendix, `/search?topic=` is what the web page uses. | Two routes to maintain. |
| `/info` returns extra fields | Matches the appendix (`title`, `quantity`, `price`) while still serving the web page. | Slightly larger reply than the appendix example. |
| Plain HTTP, no authentication | Keeps the lab focused on the architecture. | The update endpoints are open to anyone who can reach the front-end. |

## 4. Known limitations (cases that do not work correctly)

* **Stock and order log can disagree.** The catalog decrements stock first and
  the order is saved afterwards. If saving the order fails (disk full) the
  customer gets a `500` but the copy is already gone from stock. A fix is a
  compensating call that restores the stock, or writing the order first with a
  `PENDING` status.
* **A crash between the two steps** has the same effect.
* **If a back-end service is down**, the front-end returns a generic `500`
  rather than a clear "service unavailable" message, and there are no retries
  or timeouts.
* **Files are not safe for several writers.** Only one catalog process and one
  order process may use a data directory. Running two catalogs on the same
  `books.json` would lose updates.
* **Order ids** are positions in the file, there is no unique order id and no
  customer information.
* Topic search is an exact (case-insensitive) match, not a partial one.

## 5. Possible improvements

* Replace the JSON files with SQLite (still allowed by the lab) to get
  transactions and per-row updates, and give each order a unique id.
* Add timeouts, retries and a `503` answer in the front-end for unreachable
  services, plus health-check endpoints.
* Cache `info`/`search` results in the front-end and invalidate them on
  update; add replicas of the catalog behind a load balancer (the read paths
  are already stateless apart from the file).
* Add authentication for the update endpoints.
* Return the new stock level in the purchase reply.

## 6. How to run and test

See `README.md` in the repository root. In short:

```bash
docker compose up --build        # start the three services
bash tests/smoke-test.sh         # 22 checks against the running system
docker compose down
git checkout data                # restore the original catalog
```

`docs/client-output.txt` and `docs/server-logs.txt` are the output of a full
run: the first is what the client printed, the second what the three
containers logged (including the `bought book ...` lines and the formatted
search/info results).

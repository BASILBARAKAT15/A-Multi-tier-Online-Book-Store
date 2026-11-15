#!/bin/bash
# Smoke test for Bazar.com. Run it against a running system (docker compose
# up, or the three services started by hand):
#
#   bash tests/smoke-test.sh
#
# Override the addresses to test services running on other machines:
#   FRONTEND=http://10.0.0.1:4568 CATALOG=http://10.0.0.2:4567 \
#   ORDER=http://10.0.0.3:4569 bash tests/smoke-test.sh
#
# NOTE: it buys books and changes stock/price, so reset data/ afterwards
# (git checkout data) if you want the original catalog back.

FRONTEND=${FRONTEND:-http://localhost:4568}
CATALOG=${CATALOG:-http://localhost:4567}
ORDER=${ORDER:-http://localhost:4569}

PASS=0
FAIL=0
BODY=""
CODE=""

# call METHOD URL [JSON body]: stores response body in $BODY, status in $CODE
call() {
    local method=$1 url=$2 data=$3
    local out
    if [ -n "$data" ]; then
        out=$(curl -s -w $'\n%{http_code}' -X "$method" \
            -H 'Content-Type: application/json' -d "$data" "$url")
    else
        out=$(curl -s -w $'\n%{http_code}' -X "$method" "$url")
    fi
    CODE=${out##*$'\n'}
    BODY=${out%$'\n'*}
    # Keep the log readable: show at most the first 200 characters, one line.
    echo "  $method $url -> $CODE $(echo "$BODY" | tr '\n' ' ' | cut -c1-200)"
}

# check DESCRIPTION EXPECTED_CODE [TEXT that must appear in the body]
check() {
    local desc=$1 code=$2 text=$3
    if [ "$CODE" = "$code" ] && { [ -z "$text" ] || [[ "$BODY" == *"$text"* ]]; }; then
        echo "PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "FAIL: $desc (expected $code containing '$text')"
        FAIL=$((FAIL + 1))
    fi
}

echo "== Services are up"
call GET "$CATALOG/";  check "catalog service responds" 200 "Catalog"
call GET "$ORDER/";    check "order service responds" 200 "Order"
call GET "$FRONTEND/"; check "frontend serves the web page" 200 "html"

echo "== search(topic)"
call GET "$FRONTEND/search/distributed%20systems"
check "search by topic (path style) finds the 2 DOS books" 200 "RPCs for Noobs"
call GET "$FRONTEND/search?topic=undergraduate%20school"
check "search by topic (query style) finds the 2 school books" 200 "Cooking for the Impatient Undergrad"
call GET "$FRONTEND/search/no%20such%20topic"
check "unknown topic returns an empty list" 200 "[]"

echo "== info(item_number)"
call GET "$FRONTEND/info/2"
check "info returns title, quantity and price" 200 '"quantity"'
call GET "$FRONTEND/info/99"
check "info on unknown item returns 404" 404 "not found"
call GET "$FRONTEND/info/abc"
check "info with a bad id returns 400" 400 "Invalid"

echo "== purchase(item_number)"
call GET "$CATALOG/info/1"
BEFORE=$(echo "$BODY" | sed -E 's/.*"quantity":([0-9]+).*/\1/')
call POST "$FRONTEND/purchase/1"
check "purchase succeeds" 201 "SUCCESS"
call GET "$CATALOG/info/1"
AFTER=$(echo "$BODY" | sed -E 's/.*"quantity":([0-9]+).*/\1/')
if [ "$AFTER" = "$((BEFORE - 1))" ]; then
    echo "PASS: stock dropped by one ($BEFORE -> $AFTER)"; PASS=$((PASS + 1))
else
    echo "FAIL: stock should drop by one ($BEFORE -> $AFTER)"; FAIL=$((FAIL + 1))
fi
call GET "$ORDER/orders"
check "order is recorded in the order log" 200 "SUCCESS"
call POST "$FRONTEND/purchase/99"
check "purchase of unknown book returns 404" 404 "not found"

echo "== catalog update (restock, then sell out)"
call PUT "$CATALOG/catalog/books/4/stock" '{"stock":1}'
check "set stock of book 4 to 1" 200 '"stock":1'
call POST "$FRONTEND/purchase/4"
check "buying the last copy works" 201 "SUCCESS"
call POST "$FRONTEND/purchase/4"
check "buying when out of stock fails with 409" 409 "out of stock"
call PUT "$FRONTEND/catalog/books/4/stock" '{"delta":5}'
check "increase stock by 5 with delta" 200 '"stock":5'
call PUT "$FRONTEND/catalog/books/4/stock" '{"delta":-100}'
check "decreasing below zero is rejected" 400 "negative"
call PUT "$FRONTEND/catalog/books/4/stock" '{}'
check "update without stock or delta is rejected" 400 "Invalid"
call PUT "$FRONTEND/catalog/books/3/price" '{"price":42.5}'
check "update price" 200 '"price":42.5'
call PUT "$FRONTEND/catalog/books/3/price" '{"price":-1}'
check "negative price is rejected" 400 "Invalid price"

echo "== concurrency: 20 parallel purchases of a book with 5 copies"
call PUT "$CATALOG/catalog/books/2/stock" '{"stock":5}'
tmp=$(mktemp -d)
for i in $(seq 1 20); do
    # one result file per request, so parallel writers cannot interleave
    curl -s -o /dev/null -w '%{http_code}' -X POST "$FRONTEND/purchase/2" > "$tmp/$i" &
done
wait
ok=$(cat "$tmp"/* | grep -o '201' | wc -l)
rm -rf "$tmp"
call GET "$CATALOG/info/2"
if [ "$ok" = "5" ] && [[ "$BODY" == *'"quantity":0'* ]]; then
    echo "PASS: exactly 5 of 20 parallel purchases succeeded, stock is 0"
    PASS=$((PASS + 1))
else
    echo "FAIL: $ok purchases succeeded (expected 5); body $BODY"
    FAIL=$((FAIL + 1))
fi

echo
echo "Result: $PASS passed, $FAIL failed"
[ "$FAIL" = "0" ]

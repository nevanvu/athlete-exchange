#!/usr/bin/env python3
"""
End-to-end walkthrough of the Athlete Exchange API against a running server.

    ./gradlew :app:bootRun --args='--athletex.demo-seed.ipo-opens-in=15s --athletex.demo-seed.ipo-duration=60s --athletex.scheduler.fixed-delay-ms=2000'
    python3 scripts/demo.py

Registers three paper-money traders, bids in the demo slate's IPO, waits for the
auction to clear, then trades on the open market and shows the resulting P&L.
Standard library only.
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"


# ---------------------------------------------------------------- helpers

def api(method, path, token=None, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, (json.loads(raw) if raw else None)


def money(cents):
    sign = "-" if cents < 0 else ""
    return f"{sign}${abs(cents) / 100:,.2f}"


def banner(title):
    print()
    print("=" * 78)
    print(f"  {title}")
    print("=" * 78)


def step(text):
    print(f"\n--> {text}")


def wait_for_server():
    step("waiting for the server at " + BASE)
    for _ in range(120):
        try:
            status, _ = api("GET", "/actuator/health")
            if status == 200:
                print("    server is up")
                return
        except Exception:
            pass
        time.sleep(2)
    sys.exit("server did not come up")


def login(name):
    body = {"username": name, "email": f"{name}@demo.local", "password": "paper-money-only"}
    status, resp = api("POST", "/api/auth/register", body=body)
    if status == 409:
        status, resp = api("POST", "/api/auth/login", body={"username": name, "password": body["password"]})
    assert status in (200, 201), (status, resp)
    return resp["token"]


def wallet(token):
    _, w = api("GET", "/api/wallet", token)
    return w


def portfolio(token):
    _, p = api("GET", "/api/portfolio", token)
    return p


def show_wallets(traders):
    for name, token in traders.items():
        w = wallet(token)
        print(f"    {name:<6} cash {money(w['cashCents']):>12}   escrow {money(w['escrowCents']):>12}")


def show_portfolio(name, token, names):
    p = portfolio(token)
    print(f"    {name}: cash {money(p['cashCents'])}, escrow {money(p['escrowCents'])}, "
          f"positions {money(p['positionsValueCents'])}, equity {money(p['totalEquityCents'])}")
    for pos in p["positions"]:
        print(f"        {names.get(pos['marketId'], '?'):<18} {pos['quantity']:>4} sh "
              f"({pos['lockedQuantity']} locked)  avg {money(pos['averageCostCents']):>8}  "
              f"last {money(pos['lastPriceCents'] or 0):>8}  "
              f"unrealized {money(pos['unrealizedPnlCents']):>9}  realized {money(pos['realizedPnlCents']):>9}")


def slate_detail(token, slate_id):
    _, d = api("GET", f"/api/slates/{slate_id}", token)
    return d


PHASES = ["SCHEDULED", "IPO", "OPEN_MARKET", "CLOSED", "SETTLED"]


def wait_for_status(token, slate_id, wanted):
    step(f"waiting for slate to reach {wanted}")
    last = None
    while True:
        d = slate_detail(token, slate_id)
        if d["status"] != last:
            print(f"    slate status: {d['status']}")
            last = d["status"]
        if d["status"] == wanted:
            return d
        if d["status"] not in PHASES or PHASES.index(d["status"]) > PHASES.index(wanted):
            sys.exit(f"slate is already {d['status']}; restart the server with a fresh database "
                     f"(docker compose down) to replay the demo from the IPO")
        time.sleep(2)


def show_book(token, market_id, label):
    _, b = api("GET", f"/api/markets/{market_id}/book", token)
    print(f"    book for {label}: last {money(b['lastTradePriceCents'] or 0)}")
    asks = sorted(b["asks"], key=lambda l: -l["priceCents"])
    for lvl in asks:
        print(f"        ASK {money(lvl['priceCents']):>8} x {lvl['quantity']}")
    print("        ---------------")
    for lvl in b["bids"]:
        print(f"        BID {money(lvl['priceCents']):>8} x {lvl['quantity']}")
    if not asks and not b["bids"]:
        print("        (empty)")


# ---------------------------------------------------------------- demo

def main():
    banner("ATHLETE EXCHANGE — paper money demo")
    wait_for_server()

    step("registering three traders (each seeded with $10,000 of paper money)")
    traders = {name: login(name + str(int(time.time()) % 100000)) for name in ("alice", "bob", "carol")}
    alice, bob, carol = traders["alice"], traders["bob"], traders["carol"]
    show_wallets(traders)

    step("finding the demo slate")
    _, slates = api("GET", "/api/slates", alice)
    slate = slates[0]
    print(f"    {slate['name']}  [{slate['status']}]  IPO {slate['ipoOpensAt']} -> {slate['ipoClosesAt']}")
    detail = slate_detail(alice, slate["id"])
    markets = detail["markets"]
    names = {m["id"]: m["athleteName"] for m in markets}
    print(f"\n    {'athlete':<18} {'team':<5} {'pos':<4} {'proj fp':>8} {'reserve':>9}")
    for m in markets:
        print(f"    {m['athleteName']:<18} {m['teamCode']:<5} {m['position']:<4} "
              f"{float(m['projectedFantasyPoints']):>8.1f} {money(m['reservePriceCents']):>9}")
    print("\n    reserve price = projected fantasy points x $0.25 (the settlement formula, clamped to $1..$100)")

    # ------------------------------------------------------------ IPO
    banner("PHASE 1 — IPO auction")
    wait_for_status(alice, slate["id"], "IPO")

    m1, m2, m3 = markets[0], markets[1], markets[2]
    r1, r2, r3 = m1["reservePriceCents"], m2["reservePriceCents"], m3["reservePriceCents"]

    step(f"bidding on {m1['athleteName']} (1000 shares on offer, reserve {money(r1)})")
    bids = [
        ("alice", alice, m1, r1 + 150, 200),
        ("bob", bob, m1, r1 + 100, 850),
        ("carol", carol, m1, r1 - 50, 200),   # below reserve: will be refunded
    ]
    for name, token, m, price, qty in bids:
        status, resp = api("POST", "/api/ipo/bids", token,
                           {"marketId": m["id"], "maxPriceCents": price, "quantity": qty})
        outcome = resp.get("status") or resp.get("detail") or resp
        print(f"    {name:<6} bids {qty:>4} x {money(price):>8}  -> {status} {outcome}")
    print("    demand at or above reserve = 200 + 850 = 1050 > 1000, so the auction is oversubscribed")

    step(f"bidding on {m2['athleteName']} and {m3['athleteName']} (undersubscribed)")
    for name, token, m, price, qty in [("alice", alice, m2, r2 + 50, 50), ("carol", carol, m3, r3 + 20, 300)]:
        status, resp = api("POST", "/api/ipo/bids", token,
                           {"marketId": m["id"], "maxPriceCents": price, "quantity": qty})
        print(f"    {name:<6} bids {qty:>4} x {money(price):>8} on {m['athleteName']:<18} -> {status}")

    step("escrow: max price x quantity is locked while bids are open")
    show_wallets(traders)

    status, resp = api("POST", "/api/ipo/bids", alice, {"marketId": m1["id"], "maxPriceCents": 10_000, "quantity": 5000})
    print(f"\n    alice tries to bid $50,000,000 more than she has -> {status}: {resp['detail']}")

    # ------------------------------------------------------------ clearing
    banner("PHASE 2 — auction clears, market opens")
    detail = wait_for_status(alice, slate["id"], "OPEN_MARKET")
    by_id = {m["id"]: m for m in detail["markets"]}
    for m in (m1, m2, m3):
        cleared = by_id[m["id"]]
        print(f"    {m['athleteName']:<18} reserve {money(m['reservePriceCents']):>8}  "
              f"cleared at {money(cleared['ipoClearingPriceCents']):>8}")
    print(f"\n    {m1['athleteName']}: alice's higher bid fills in full, bob is the marginal bidder and sets the")
    print(f"    price ({money(r1 + 100)}); both pay that price, alice's extra escrow is refunded, carol was below reserve.")
    print(f"    {m2['athleteName']} / {m3['athleteName']}: demand < supply, so they clear at the reserve and the")
    print(f"    house keeps the unsold shares.")

    step("bid outcomes")
    for name, token in traders.items():
        _, mine = api("GET", "/api/ipo/bids", token)
        for b in mine:
            print(f"    {name:<6} {names[b['marketId']]:<18} {b['quantity']:>4} @ max {money(b['maxPriceCents']):>8} "
                  f"-> {b['status']:<9} allocated {b['allocatedQuantity']:>4} @ {money(b['clearingPriceCents'] or 0)}")

    step("portfolios after the IPO (escrow is back to zero)")
    for name, token in traders.items():
        show_portfolio(name, token, names)

    # ------------------------------------------------------------ trading
    banner("PHASE 3 — open-market trading")
    clearing = by_id[m1["id"]]["ipoClearingPriceCents"]
    ask = clearing + 30

    step(f"alice offers 50 {m1['athleteName']} at {money(ask)} (shares are locked in her position while the order rests)")
    status, sell = api("POST", "/api/orders", alice,
                       {"marketId": m1["id"], "side": "SELL", "type": "LIMIT", "priceCents": ask, "quantity": 50})
    print(f"    -> {status} order {sell['order']['status']}, {len(sell['trades'])} trades")
    show_book(alice, m1["id"], m1["athleteName"])

    step(f"bob bids for 30 at {money(ask + 10)} — he crosses the spread, but pays the resting ask price")
    status, buy = api("POST", "/api/orders", bob,
                      {"marketId": m1["id"], "side": "BUY", "type": "LIMIT", "priceCents": ask + 10, "quantity": 30})
    for t in buy["trades"]:
        print(f"    -> TRADE {t['quantity']} x {money(t['priceCents'])}   (bob's extra {money(10 * 30)} of escrow is refunded)")
    print(f"    bob's order: {buy['order']['status']}, escrow left on it {money(buy['order']['escrowCents'])}")
    show_book(alice, m1["id"], m1["athleteName"])

    step("carol sends a MARKET buy for 10 — escrow is sized to the exact sweep cost of the book")
    status, mkt = api("POST", "/api/orders", carol,
                      {"marketId": m1["id"], "side": "BUY", "type": "MARKET", "quantity": 10})
    for t in mkt["trades"]:
        print(f"    -> TRADE {t['quantity']} x {money(t['priceCents'])}")
    show_book(alice, m1["id"], m1["athleteName"])

    step("alice cancels the rest of her offer — the 10 locked shares are released")
    status, cancelled = api("DELETE", f"/api/orders/{sell['order']['id']}", alice)
    print(f"    -> {status} order {cancelled['status']}, filled {cancelled['filledQuantity']} of {cancelled['quantity']}")
    show_book(alice, m1["id"], m1["athleteName"])

    step("a sell for more shares than you hold is rejected and leaves no trace")
    status, resp = api("POST", "/api/orders", carol,
                       {"marketId": m1["id"], "side": "SELL", "type": "LIMIT", "priceCents": ask, "quantity": 500})
    print(f"    -> {status}: {resp['detail']}")

    step("recent trades")
    _, trades = api("GET", f"/api/markets/{m1['id']}/trades", alice)
    for t in trades:
        print(f"    {t['executedAt']}  {t['quantity']:>3} x {money(t['priceCents'])}")

    banner("RESULT — portfolios and P&L")
    for name, token in traders.items():
        show_portfolio(name, token, names)
    print(f"\n    alice sold 40 of the shares she bought at {money(clearing)} for {money(ask)}: realized P&L "
          f"{money(40 * (ask - clearing))}. Everyone else's P&L is unrealized at the last trade price.")
    print("    Every cent above moved through the double-entry ledger; nothing was created or destroyed.")
    print()


if __name__ == "__main__":
    main()

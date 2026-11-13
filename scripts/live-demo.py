#!/usr/bin/env python3
"""
Drives a live demo: plays every game in a slate through the simulator, and injects injuries
or news on demand. Standard library only.

    python3 scripts/live-demo.py start                 # newest slate, 30x speed
    python3 scripts/live-demo.py start --speed 60 --seed 7
    python3 scripts/live-demo.py injure "Avery Stone"  # rules the athlete OUT mid-game
    python3 scripts/live-demo.py news "Kai Stone" 0.12 # sentiment nudge (impact -0.15..0.15)
    python3 scripts/live-demo.py status

Needs the exchange on :8080 and the simulator on :8081 (./gradlew :app:bootRun, ./gradlew :simulator:bootRun).
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

EXCHANGE = "http://localhost:8080"
SIMULATOR = "http://localhost:8081"


def call(method, url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        sys.exit(f"{method} {url} failed with {e.code}: {detail}")
    except urllib.error.URLError as e:
        sys.exit(f"could not reach {url}: {e.reason}")


def pick_slate(slate_id):
    if slate_id:
        return call("GET", f"{EXCHANGE}/api/slates/{slate_id}")
    slates = call("GET", f"{EXCHANGE}/api/slates")
    if not slates:
        sys.exit("no slates yet; start the exchange with the demo seed enabled")
    return call("GET", f"{EXCHANGE}/api/slates/{slates[0]['id']}")


def find_athlete(slate, name):
    matches = [m for m in slate["markets"] if name.lower() in m["athleteName"].lower()]
    if not matches:
        sys.exit(f"no athlete matching {name!r} in {slate['name']}")
    if len(matches) > 1:
        sys.exit(f"{name!r} matches several athletes: " + ", ".join(m["athleteName"] for m in matches))
    return matches[0]


def start(args):
    slate = pick_slate(args.slate)
    if slate["status"] in ("SCHEDULED", "IPO") and args.wait:
        print(f"{slate['name']} is {slate['status']}; waiting for trading to open (Ctrl-C to stop)…")
        while slate["status"] in ("SCHEDULED", "IPO"):
            time.sleep(2)
            slate = pick_slate(slate["id"])
    for game in slate["games"]:
        athletes = [
            {"athleteId": m["athleteId"], "projectedFantasyPoints": float(m["projectedFantasyPoints"]),
             "projectedMinutes": float(m["projectedMinutes"])}
            for m in slate["markets"] if m["gameId"] == game["id"]
        ]
        body = {"gameId": game["id"], "slateId": slate["id"], "speed": args.speed, "athletes": athletes,
                "injuriesPerGame": args.injuries}
        if args.seed is not None:
            body["seed"] = args.seed
        session = call("POST", f"{SIMULATOR}/simulations", body)
        minutes = 48 / args.speed
        print(f"started {game['awayTeamCode']} at {game['homeTeamCode']}: {len(athletes)} athletes, "
              f"{args.speed:g}x speed (about {minutes:.1f} minutes of real time)")
    print("Watch it at http://localhost:5173")


def injure(args):
    slate = pick_slate(args.slate)
    market = find_athlete(slate, args.athlete)
    call("POST", f"{SIMULATOR}/simulations/{market['gameId']}/injury",
         {"athleteId": market["athleteId"], "status": args.status, "detail": args.detail,
          "returningThisGame": False})
    print(f"{market['athleteName']} ruled {args.status}. A big enough move halts the market for 30 seconds.")


def news(args):
    slate = pick_slate(args.slate)
    market = find_athlete(slate, args.athlete)
    call("POST", f"{SIMULATOR}/simulations/{market['gameId']}/news",
         {"athleteId": market["athleteId"], "headline": args.headline, "impact": args.impact, "confidence": 1.0})
    print(f"news on {market['athleteName']}: impact {args.impact:+.2f}")


def status(_args):
    for s in call("GET", f"{SIMULATOR}/simulations"):
        state = "final" if s["finished"] else f"{s['elapsedSeconds'] // 60} game minutes in"
        print(f"game {s['gameId']}: {state}, {s['eventsPublished']} events published")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--slate", help="slate id (default: newest)")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("start", help="simulate every game in the slate")
    p.add_argument("--speed", type=float, default=30.0, help="game speed multiple (60 = a game in 48 seconds)")
    p.add_argument("--seed", type=int, help="fixed seed for a reproducible game")
    p.add_argument("--injuries", type=float, default=0.0, help="expected random injuries per game")
    p.add_argument("--no-wait", dest="wait", action="store_false", help="start even before trading opens")
    p.set_defaults(func=start)

    p = sub.add_parser("injure", help="injure an athlete mid-game")
    p.add_argument("athlete", help="part of the athlete's name")
    p.add_argument("--status", default="OUT", choices=["OUT", "DOUBTFUL", "QUESTIONABLE", "ACTIVE"])
    p.add_argument("--detail", default="ankle, will not return")
    p.set_defaults(func=injure)

    p = sub.add_parser("news", help="publish a news item about an athlete")
    p.add_argument("athlete")
    p.add_argument("impact", type=float, help="-0.15 to 0.15")
    p.add_argument("--headline", default="Coach expects heavy minutes")
    p.set_defaults(func=news)

    sub.add_parser("status", help="show running simulations").set_defaults(func=status)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

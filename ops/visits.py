#!/usr/bin/env python3
"""Summarize who visited the public demos, from Caddy's access logs on the server.

Runs on your own machine. It reads /data/access/*.log (and rolled .gz files) from the Caddy
container over SSH, keeps real page views (no bots, no static assets, no API calls, not your own
IP), and looks up which network each visitor IP belongs to through public RDAP (whois) data.

    python3 ops/visits.py                 # last 7 days
    python3 ops/visits.py --days 1        # today and yesterday's tail
    python3 ops/visits.py --raw           # also list every page view

Environment: VISITS_SSH_HOST (default "lightsail"), VISITS_CADDY_CONTAINER
(default "dev-productivity-signals-caddy-1"). Standard library only.

IP addresses are personal data: the server keeps them for 30 days, and this script only reads them.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

SSH_HOST = os.environ.get("VISITS_SSH_HOST", "lightsail")
CONTAINER = os.environ.get("VISITS_CADDY_CONTAINER", "dev-productivity-signals-caddy-1")
JST = timezone(timedelta(hours=9))
CACHE = Path.home() / ".cache" / "signals-visits" / "rdap.json"

BOT_UA = re.compile(
    r"bot|crawl|spider|slurp|curl|wget|python|httpx|aiohttp|go-http|java/|okhttp|headless|"
    r"preview|monitor|uptime|scan|facebookexternalhit|embedly|discord|slack|whatsapp|telegram|"
    r"deploy-check|site-check|zgrab|masscan|nmap|censys|expanse|l9explore",
    re.I,
)
ASSET = re.compile(
    r"^/(_next/|static/|assets/|api/|favicon|robots\.txt|sitemap|apple-touch|site\.webmanifest|\.well-known/)"
    r"|\.(js|mjs|css|map|png|jpe?g|gif|svg|ico|webp|avif|woff2?|ttf|json|txt|xml)(\?|$)",
    re.I,
)


def ssh(cmd: str) -> str:
    out = subprocess.run(["ssh", "-o", "BatchMode=yes", SSH_HOST, cmd], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"ssh {SSH_HOST} failed: {out.stderr.strip()}")
    return out.stdout


def fetch_lines() -> list[str]:
    script = (
        "for f in /data/access/*; do case \"$f\" in *.gz) gunzip -c \"$f\";; *.log) cat \"$f\";; esac; done"
    )
    return ssh(f"docker exec {CONTAINER} sh -c '{script}'").splitlines()


def my_ip() -> str | None:
    # The server sees this SSH session coming from your public IP; no third-party lookup needed.
    parts = ssh("echo $SSH_CLIENT").split()
    return parts[0] if parts else None


def load_cache() -> dict:
    try:
        return json.loads(CACHE.read_text())
    except (OSError, ValueError):
        return {}


def save_cache(cache: dict) -> None:
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(cache, ensure_ascii=False, indent=1))


def vcard_names(entity: dict) -> list[str]:
    names = []
    vcard = entity.get("vcardArray")
    if isinstance(vcard, list) and len(vcard) > 1:
        for item in vcard[1]:
            if isinstance(item, list) and len(item) >= 4 and item[0] in ("fn", "org"):
                val = item[3]
                if isinstance(val, str) and val.strip():
                    names.append(val.strip())
    for sub in entity.get("entities") or []:
        names += vcard_names(sub)
    return names


def jpnic_org(ip: str) -> str:
    """Japanese addresses: RDAP stops at JPNIC, so read [Organization] from JPNIC's whois."""
    try:
        out = subprocess.run(["whois", "-h", "whois.nic.ad.jp", f"{ip}/e"], capture_output=True,
                             text=True, timeout=20).stdout
    except (OSError, subprocess.TimeoutExpired):
        return ""
    m = re.search(r"\[Organization\]\s+(.+)", out)
    return m.group(1).strip() if m else ""


def org_of(ip: str, cache: dict) -> str:
    if ip in cache:
        return cache[ip]
    label = "?"
    try:
        req = urllib.request.Request(f"https://rdap.org/ip/{ip}", headers={"Accept": "application/rdap+json"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.load(resp)
        names = []
        for ent in data.get("entities") or []:
            if {"registrant", "administrative"} & set(ent.get("roles") or []) or not names:
                names += vcard_names(ent)
        net = data.get("name") or ""
        remarks = " ".join(
            " ".join(r.get("description") or []) for r in data.get("remarks") or [] if isinstance(r, dict)
        )
        registry = re.compile(r"network information cent|^apnic|^ripe|^arin|^lacnic|^afrinic|^abuse|^noc|^hostmaster", re.I)
        best = next((n for n in names if n and not registry.search(n)), "")
        if not best and any("Japan Network Information Center" in n for n in names):
            best = jpnic_org(ip)  # JPNIC keeps the end-user organization in its own whois
        label = " / ".join(x for x in (best, net) if x) or remarks[:60] or "?"
        time.sleep(0.5)  # be polite to the public RDAP service
    except Exception as exc:  # network errors, rate limits: keep going
        label = f"? ({type(exc).__name__})"
    cache[ip] = label
    return label


def short_ua(ua: str) -> str:
    os_ = next((o for o in ("iPhone", "iPad", "Android", "Windows", "Mac OS X", "Linux") if o in ua), "?")
    br = next((b for b in ("Edg/", "Chrome/", "Firefox/", "Safari/") if b in ua), "?")
    return f"{os_.replace(' OS X', '')}/{br.rstrip('/')}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--days", type=int, default=7, help="look back N days (default 7)")
    ap.add_argument("--raw", action="store_true", help="also list every page view")
    ap.add_argument("--include-me", action="store_true", help="do not exclude your own IP")
    args = ap.parse_args()

    since = datetime.now(JST) - timedelta(days=args.days)
    me = None if args.include_me else my_ip()
    views = []
    skipped = defaultdict(int)
    for line in fetch_lines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        r = e.get("request") or {}
        ts = datetime.fromtimestamp(e.get("ts", 0), JST)
        if ts < since:
            continue
        h = r.get("headers") or {}
        ua = (h.get("User-Agent") or [""])[0]
        ip = r.get("client_ip") or r.get("remote_ip") or "?"
        uri = r.get("uri") or "/"
        if r.get("method") != "GET" or e.get("status") not in (200, 304):
            skipped["not a page view"] += 1
        elif ASSET.search(uri):
            skipped["asset/api"] += 1
        elif not ua or BOT_UA.search(ua):
            skipped["bot/tool"] += 1
        elif me and ip == me:
            skipped["you"] += 1
        else:
            views.append({"ts": ts, "ip": ip, "host": r.get("host", "?"), "uri": uri, "ua": ua,
                          "ref": (h.get("Referer") or [""])[0]})

    print(f"Page views since {since:%Y-%m-%d %H:%M} JST: {len(views)}"
          f"  (skipped: {', '.join(f'{k} {v}' for k, v in sorted(skipped.items())) or 'none'})")
    if not views:
        return 0

    per_day = defaultdict(lambda: defaultdict(set))
    for v in views:
        per_day[v["ts"].date()][v["host"]].add(v["ip"])
    print("\nVisitors per day (unique IPs):")
    for day in sorted(per_day):
        cells = ", ".join(f"{host.split('.')[0]} {len(ips)}" for host, ips in sorted(per_day[day].items()))
        print(f"  {day}  {cells}")

    cache = load_cache()
    by_ip = defaultdict(list)
    for v in views:
        by_ip[v["ip"]].append(v)
    print("\nVisitors (most recent first):")
    for ip, vs in sorted(by_ip.items(), key=lambda kv: max(v["ts"] for v in kv[1]), reverse=True):
        last = max(v["ts"] for v in vs)
        sites = sorted({v["host"].split(".")[0] for v in vs})
        refs = sorted({re.sub(r"^https?://([^/]+).*", r"\1", v["ref"]) for v in vs if v["ref"]})
        print(f"  {last:%m-%d %H:%M}  {ip:<39} {org_of(ip, cache)}")
        print(f"      {len(vs)} views on {', '.join(sites)}; {short_ua(vs[0]['ua'])}"
              + (f"; from {', '.join(refs)}" if refs else ""))
    save_cache(cache)

    if args.raw:
        print("\nAll page views:")
        for v in sorted(views, key=lambda v: v["ts"]):
            print(f"  {v['ts']:%m-%d %H:%M}  {v['ip']:<39} {v['host']}{v['uri']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

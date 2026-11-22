#!/usr/bin/env python3
"""Download the complete Eurovision dataset and export it as raw JSON and CSV.

Usage: python scripts/fetch_eurovision.py --output output/data
Only the Python standard library is required.
"""
from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

BASE_URL = "https://eurovisionapi.runasp.net/api"
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}

COUNTRY_NAMES_PL = {
    "AD": "Andorra", "AL": "Albania", "AM": "Armenia", "AT": "Austria",
    "AU": "Australia", "AZ": "Azerbaijan", "BA": "Bosnia and Herzegovina",
    "BE": "Belgium", "BG": "Bulgaria", "BY": "Belarus", "CH": "Switzerland",
    "CS": "Serbia and Montenegro", "CY": "Cyprus", "CZ": "Czech Republic", "DE": "Germany",
    "DK": "Denmark", "EE": "Estonia", "ES": "Spain", "FI": "Finland",
    "FR": "France", "GB": "United Kingdom", "GB-WLS": "Wales", "GE": "Georgia",
    "GR": "Greece", "HR": "Croatia", "HU": "Hungary", "IE": "Ireland",
    "IL": "Israel", "IS": "Iceland", "IT": "Italy", "KZ": "Kazakhstan",
    "LT": "Lithuania", "LU": "Luxembourg", "LV": "Latvia", "MA": "Morocco",
    "MC": "Monaco", "MD": "Moldova", "ME": "Montenegro",
    "MK": "North Macedonia", "MT": "Malta", "NL": "Netherlands", "NO": "Norway",
    "PL": "Poland", "PT": "Portugal", "RO": "Romania", "RS": "Serbia",
    "RU": "Russia", "SE": "Sweden", "SI": "Slovenia", "SK": "Slovakia",
    "SM": "San Marino", "TR": "Turkey", "UA": "Ukraine", "YU": "Yugoslavia",
}
# Voting-only codes that the /countries endpoint does not list.
EXTRA_COUNTRIES = {"WLD": ("Rest of the World", "Rest of the World")}


def fetch_json(url: str, retries: int = 6, timeout: int = 60) -> Any:
    request = urllib.request.Request(
        url, headers={"Accept": "application/json", "User-Agent": "eurovision-data-export"}
    )
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in RETRYABLE_STATUS or attempt == retries - 1:
                raise RuntimeError(f"HTTP {error.code} for {url}") from error
        except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError) as error:
            if attempt == retries - 1:
                raise RuntimeError(f"Failed to fetch {url}: {error}") from error
        time.sleep(2**attempt)
    raise RuntimeError(f"Failed to fetch {url}")


def cell(value: Any) -> Any:
    """Keep scalars; store lists and objects as JSON so no value is lost."""
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    return value


def flat(item: dict[str, Any], *skip: str) -> dict[str, Any]:
    return {key: cell(value) for key, value in item.items() if key not in skip}


def write_csv(path: Path, rows: list[dict[str, Any]], leading: list[str]) -> None:
    columns = list(leading)
    for row in rows:
        columns.extend(key for key in row if key not in columns)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fetch_contest(base: str, year: int, pool: ThreadPoolExecutor) -> tuple[dict, list[dict]]:
    contest = fetch_json(f"{base}/contests/{year}")
    urls = [f"{base}/contests/{year}/contestants/{c['id']}" for c in contest.get("contestants") or []]
    return contest, list(pool.map(fetch_json, urls))


def build_tables(contests: list[tuple[dict, list[dict]]]) -> tuple[dict[str, list[dict]], list[str], set[str]]:
    tables: dict[str, list[dict]] = {
        name: [] for name in ("contests", "contestants", "lyrics", "rounds", "performances", "scores", "votes")
    }
    problems: list[str] = []
    codes: set[str] = set()

    for contest, contestants in contests:
        year = contest["year"]
        tables["contests"].append(flat(contest, "contestants", "rounds"))
        codes.update(c for c in (contest.get("country"), contest.get("intendedCountry")) if c)
        ids = {c["id"] for c in contestants}
        by_id = {c["id"]: c for c in contestants}

        for person in contestants:
            codes.add(person["country"])
            tables["contestants"].append({"year": year, "contestant_id": person["id"], **flat(person, "id", "lyrics")})
            for index, lyric in enumerate(person.get("lyrics") or []):
                tables["lyrics"].append({"year": year, "contestant_id": person["id"], "lyric_index": index, **flat(lyric)})

        for round_ in contest.get("rounds") or []:
            round_name = round_["name"]
            tables["rounds"].append({"year": year, "round": round_name, **flat(round_, "name", "performances")})
            for performance in round_.get("performances") or []:
                contestant_id = performance["contestantId"]
                if contestant_id not in ids:
                    problems.append(f"{year}/{round_name}: unknown contestant {contestant_id}")
                    continue
                country = by_id[contestant_id]["country"]
                key = {"year": year, "round": round_name, "contestant_id": contestant_id}
                tables["performances"].append({**key, "country": country, **flat(performance, "contestantId", "scores")})
                for score in performance.get("scores") or []:
                    score_key = {**key, "score_type": score["name"]}
                    tables["scores"].append({**score_key, "country": country, **flat(score, "name", "votes")})
                    for voter, points in (score.get("votes") or {}).items():
                        codes.add(voter)
                        tables["votes"].append({**score_key, "from_country": voter, "to_country": country, "points": points})
    return tables, problems, codes


def country_rows(api_countries: dict[str, str], codes: set[str]) -> tuple[list[dict], list[str]]:
    names = {code: (name, COUNTRY_NAMES_PL.get(code)) for code, name in api_countries.items()}
    names.update(EXTRA_COUNTRIES)
    missing = sorted(code for code in codes | set(names) if code not in names or not names[code][1])
    rows = [
        {"code": code, "name_en": names[code][0], "name_pl": names[code][1]}
        for code in sorted(names)
        if names[code][1]
    ]
    return rows, missing


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("output/data"))
    parser.add_argument("--competition", choices=("senior", "junior"), default="senior")
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--years", type=int, nargs="*", help="Limit the export to these years (for testing)")
    args = parser.parse_args()

    base = f"{BASE_URL}/{args.competition}"
    years = sorted(args.years or fetch_json(f"{base}/contests/years"))
    api_countries = fetch_json(f"{BASE_URL}/countries")

    contests = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for number, year in enumerate(years, 1):
            contests.append(fetch_contest(base, year, pool))
            print(f"[{number}/{len(years)}] {year}: {len(contests[-1][1])} contestants", flush=True)

    tables, problems, codes = build_tables(contests)
    countries, missing = country_rows(api_countries, codes)
    if missing:
        problems.append(f"country codes without a mapping: {', '.join(missing)}")
    if problems:
        print("Validation failed:\n  " + "\n  ".join(problems), file=sys.stderr)
        return 1

    temp = args.output.with_name(args.output.name + ".tmp")
    shutil.rmtree(temp, ignore_errors=True)
    (temp / "csv").mkdir(parents=True)
    for contest, contestants in contests:
        write_json(temp / "raw" / "contests" / f"{contest['year']}.json", contest)
        write_json(temp / "raw" / "contestants" / f"{contest['year']}.json", contestants)
    write_json(temp / "raw" / "countries.json", api_countries)
    write_json(temp / "countries.json", {row["code"]: row["name_pl"] for row in countries})

    leading = {
        "contests": ["year"],
        "contestants": ["year", "contestant_id"],
        "lyrics": ["year", "contestant_id", "lyric_index"],
        "rounds": ["year", "round"],
        "performances": ["year", "round", "contestant_id", "country"],
        "scores": ["year", "round", "contestant_id", "score_type", "country"],
        "votes": ["year", "round", "contestant_id", "score_type", "from_country", "to_country", "points"],
    }
    for name, rows in tables.items():
        write_csv(temp / "csv" / f"{name}.csv", rows, leading[name])
    write_csv(temp / "csv" / "countries.csv", countries, ["code", "name_en", "name_pl"])

    counts = {name: len(rows) for name, rows in tables.items()} | {"countries": len(countries)}
    write_json(temp / "manifest.json", {
        "source": base, "competition": args.competition,
        "first_year": years[0], "last_year": years[-1], "row_counts": counts,
    })

    shutil.rmtree(args.output, ignore_errors=True)
    temp.replace(args.output)
    print(json.dumps(counts, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())

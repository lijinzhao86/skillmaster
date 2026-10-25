#!/usr/bin/env python3
"""v19's retrieval evaluation: Recall@10 over the checked-in labelled set.

    DASHSCOPE_API_KEY=... python3 scripts/retrieval-eval.py

What it does
------------
Embeds the 33 fixture skills and the 99 labelled queries with the same model and the same
text composition the server uses, scores every query against every skill by cosine, and
reports:

  * ``Recall@10`` overall and split by the two query kinds (semantic-phrased, name-style);
  * the **score distribution of the correct answer**;
  * a **threshold sweep**: at each candidate cut, how many correct answers fall below it (the
    rows the product would silently withhold) and how many unmatched queries would still return
    something.

Both sides are needed. The threshold exists for exactly one case — a query nothing here answers —
and a cut chosen only from the "correct answers are high" side runs the risk of dropping real
answers to make imaginary noise go away. ``unmatched.tsv`` (one out-of-domain query per line) is
the other side.

Why a script and not a JUnit test
---------------------------------
It costs money to run (one embedding call per text) and it is deliberately reproducible
rather than automatic: a test suite that calls a paid API on every build is a test suite
people disable. ``--cache`` makes the second run free.

The vectors are cached by the sha256 of the *exact text* that was embedded, so editing a
fixture invalidates only that entry.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sys
import urllib.error
import urllib.request

ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"
MODEL = "text-embedding-v4"
DIMENSIONS = 1024
# The provider refuses more than ten inputs per request; measured, not assumed.
BATCH = 10


def compose(name: str, title: str, description: str, when_to_use: str) -> str:
    """The server's composition, `SkillEmbeddingTextSource.compose`, kept in step by hand.

    Labelled lines rather than bare concatenation: without the labels a title and a
    description run together into a phrase nobody wrote, and the vector starts answering for
    text that does not exist. A field that is empty is dropped, label and all.
    """
    parts = []
    for label, value in (("name", name), ("title", title), ("description", description),
                         ("when to use", when_to_use)):
        if value and value.strip():
            parts.append(f"{label}: {value.strip()}")
    return "\n".join(parts)


def embed_all(texts: list[str], cache_path: str, api_key: str) -> list[list[float]]:
    cache: dict[str, list[float]] = {}
    if os.path.exists(cache_path):
        with open(cache_path, encoding="utf-8") as handle:
            cache = json.load(handle)

    # **The model and the width are part of the key.** Keying on the text alone means a run after a
    # model change mixes vectors from two models into one score table, silently — and a width change
    # would not even be visible, because `cosine` would zip two lists of different lengths.
    key_of = lambda text: hashlib.sha256(
        f"{MODEL}:{DIMENSIONS}:{text}".encode("utf-8")).hexdigest()
    missing = [text for text in dict.fromkeys(texts) if key_of(text) not in cache]

    for start in range(0, len(missing), BATCH):
        chunk = missing[start:start + BATCH]
        body = json.dumps({"model": MODEL, "input": chunk, "dimensions": DIMENSIONS}).encode()
        request = urllib.request.Request(ENDPOINT, data=body, headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        })
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.loads(response.read())
        except urllib.error.HTTPError as error:
            sys.exit(f"the provider answered {error.code}: {error.read()[:200]!r}")
        for item in payload["data"]:
            cache[key_of(chunk[item["index"]])] = item["embedding"]
        print(f"  embedded {min(start + BATCH, len(missing))}/{len(missing)}", file=sys.stderr)

    if missing:
        with open(cache_path, "w", encoding="utf-8") as handle:
            json.dump(cache, handle)

    return [cache[key_of(text)] for text in texts]


def cosine(left: list[float], right: list[float]) -> float:
    # `zip` stops at the shorter list: two widths would otherwise produce a plausible, wrong number
    # instead of an error, and every number on this page would be wrong with no sign of it.
    if len(left) != len(right):
        sys.exit(f"vectors of different widths: {len(left)} vs {len(right)}")
    dot = sum(a * b for a, b in zip(left, right))
    norm = math.sqrt(sum(a * a for a in left)) * math.sqrt(sum(b * b for b in right))
    return dot / norm if norm else 0.0


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return float("nan")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def main() -> None:
    here = os.path.dirname(os.path.abspath(__file__))
    fixtures = os.path.join(here, "..", "src", "test", "resources", "retrieval")
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixtures", default=os.path.normpath(fixtures))
    parser.add_argument("--cache", default=os.path.join(os.path.dirname(here), ".retrieval-vectors.json"))
    parser.add_argument("--top", type=int, default=10)
    args = parser.parse_args()

    api_key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not api_key:
        sys.exit("DASHSCOPE_API_KEY is not set")

    with open(os.path.join(args.fixtures, "skills.json"), encoding="utf-8") as handle:
        skills = json.load(handle)
    with open(os.path.join(args.fixtures, "labeled.tsv"), encoding="utf-8") as handle:
        rows = [line.rstrip("\n").split("\t") for line in handle
                if line.strip() and not line.startswith("#")]
    rows = [(query, name) for query, name in rows]

    unmatched_path = os.path.join(args.fixtures, "unmatched.tsv")
    unmatched: list[str] = []
    if os.path.exists(unmatched_path):
        with open(unmatched_path, encoding="utf-8") as handle:
            unmatched = [line.strip() for line in handle
                         if line.strip() and not line.startswith("#")]

    names = [skill["name"] for skill in skills]
    unknown = sorted({target for _, target in rows} - set(names))
    if unknown:
        sys.exit(f"labeled.tsv points at skills that are not in skills.json: {unknown}")
    skill_texts = [compose(skill["name"], skill.get("title", ""), skill.get("description", ""),
                           skill.get("when_to_use", "")) for skill in skills]
    query_texts = [query for query, _ in rows]

    print(f"embedding {len(skill_texts)} skills, {len(query_texts)} labelled queries "
          f"and {len(unmatched)} unmatched queries…", file=sys.stderr)
    skill_vectors = embed_all(skill_texts, args.cache, api_key)
    query_vectors = embed_all(query_texts, args.cache, api_key)
    unmatched_vectors = embed_all(unmatched, args.cache, api_key)

    hits = {"semantic": 0, "name": 0}
    totals = {"semantic": 0, "name": 0}
    correct_scores: list[float] = []
    misses: list[tuple[str, str, int, float]] = []
    # (was the right skill in top-10, its own score, every top-10 row's score) per query —
    # the sweep needs all three and recomputing them would score everything twice.
    per_query: list[tuple[bool, float, list[float]]] = []

    for (query, target), vector in zip(rows, query_vectors):
        scored = sorted(((cosine(vector, skill_vector), name)
                         for name, skill_vector in zip(names, skill_vectors)), reverse=True)
        rank = next((position for position, (_, name) in enumerate(scored[:args.top], start=1)
                     if name == target), None)
        own = next(score for score, name in scored if name == target)
        per_query.append((rank is not None, own, [score for score, _ in scored[:args.top]]))
        # A name-style query is recognised by being **exactly** the skill's name; everything else is
        # phrased. A near-miss ("飞书 lark", a synonym) would therefore count as semantic and quietly
        # move the recall of that group — the printed n per group is what makes that visible, and
        # `labeled.tsv`'s header says which lines are which.
        group = "name" if query.strip() == target else "semantic"
        totals[group] += 1
        if rank:
            hits[group] += 1
            correct_scores.append(own)
        else:
            misses.append((query, target, len(names), own))

    def report(label: str) -> None:
        if not totals[label]:
            return
        print(f"Recall@{args.top} ({label}, n={totals[label]}): "
              f"{hits[label] / totals[label]:.3f}")

    print()
    for label in ("semantic", "name"):
        report(label)
    overall = sum(hits.values()) / max(1, sum(totals.values()))
    print(f"Recall@{args.top} (all, n={sum(totals.values())}): {overall:.3f}")

    print()
    print("score of the correct answer (top-10 hits only):")
    for label, fraction in (("p5", 0.05), ("p25", 0.25), ("p50", 0.50), ("p95", 0.95)):
        print(f"  {label}: {percentile(correct_scores, fraction):.3f}")

    unmatched_tops = [max(cosine(vector, s) for s in skill_vectors) for vector in unmatched_vectors]

    print()
    print("threshold sweep — what each cut costs on the labelled set:")
    print("  cut   hit row dropped   result list emptied   unmatched that return rows")
    for cut in (0.35, 0.38, 0.40, 0.41, 0.42, 0.43, 0.45, 0.50, 0.55):
        # Two different costs, and only the second is serious. A dropped hit row still leaves
        # the rest of the list; the list going empty is the user being told nothing matched —
        # the exact promise ADR 0095 §决定 4 makes. Counted over top-10 rows only: a correct
        # answer already outside top-10 is not something a cut can take away.
        dropped = sum(1 for hit, own, _ in per_query if hit and own < cut)
        emptied = sum(1 for _, _, scores in per_query if all(score < cut for score in scores))
        through = sum(1 for top in unmatched_tops if top >= cut)
        print(f"  {cut:.2f}  {dropped:>3} / {len(rows):<3}        "
              f"{emptied:>3} / {len(rows):<3}             {through:>3} / {len(unmatched_tops):<3}")

    if unmatched_tops:
        print()
        print("best score any skill reached for an unmatched query "
              "(these should all be low; the highest is what a cut has to clear):")
        for query, top in sorted(zip(unmatched, unmatched_tops), key=lambda pair: -pair[1])[:5]:
            print(f"  {top:.3f}  {query}")

    if misses:
        print()
        print(f"missed ({len(misses)}), with the correct answer's own score:")
        for query, target, _, best in misses:
            print(f"  {best:.3f}  {target:<28} {query}")


if __name__ == "__main__":
    main()

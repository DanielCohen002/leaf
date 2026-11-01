#!/usr/bin/env python3
"""Build app/src/main/assets/dict.db from a WordNet 3.0 `dict` directory.

Usage:  python tools/build_dictionary.py <path-to>/WordNet-3.0/dict

Download WordNet 3.0 from https://wordnet.princeton.edu/download/current-version (the full
WordNet-3.0.tar.gz, which contains the *.exc exception lists). WordNet is (c) Princeton University
and is redistributable under its own licence, which must accompany the data: copy the LICENSE file
from the tarball to app/src/main/assets/WORDNET-LICENSE.txt.

Tables:
  w(word PRIMARY KEY, def)   def = one line per sense: "<pos>\\t<gloss>", pos in n/v/a/r, WordNet order
  x(form PRIMARY KEY, base)  irregular forms ("saw" -> "see"), pos-independent, first mapping wins
"""
import os
import sqlite3
import sys

POS_FILES = [("noun", "n"), ("verb", "v"), ("adj", "a"), ("adv", "r")]


def load_glosses(path):
    glosses = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("  "):
                continue
            head, _, gloss = line.partition("|")
            glosses[int(head.split(" ", 1)[0])] = gloss.strip()
    return glosses


def main(dict_dir, out_path):
    words = {}  # lemma -> list of (pos, gloss)
    for fname, pos in POS_FILES:
        glosses = load_glosses(os.path.join(dict_dir, "data." + fname))
        with open(os.path.join(dict_dir, "index." + fname), encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("  "):
                    continue
                t = line.split()
                lemma, synsets = t[0], int(t[2])
                for off in t[-synsets:]:
                    words.setdefault(lemma, []).append((pos, glosses[int(off)]))

    exc = {}
    for fname, _ in POS_FILES:
        with open(os.path.join(dict_dir, fname + ".exc"), encoding="utf-8") as f:
            for line in f:
                t = line.split()
                if len(t) >= 2:
                    exc.setdefault(t[0], t[1])

    if os.path.exists(out_path):
        os.remove(out_path)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    db = sqlite3.connect(out_path)
    db.execute("CREATE TABLE w (word TEXT PRIMARY KEY, def TEXT NOT NULL) WITHOUT ROWID")
    db.execute("CREATE TABLE x (form TEXT PRIMARY KEY, base TEXT NOT NULL) WITHOUT ROWID")
    db.executemany(
        "INSERT INTO w VALUES (?, ?)",
        ((w, "\n".join(f"{p}\t{g}" for p, g in senses)) for w, senses in words.items()),
    )
    db.executemany("INSERT INTO x VALUES (?, ?)", exc.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"{len(words)} words, {len(exc)} irregular forms, {os.path.getsize(out_path) / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    here = os.path.dirname(os.path.abspath(__file__))
    main(sys.argv[1], os.path.join(here, "..", "app", "src", "main", "assets", "dict.db"))

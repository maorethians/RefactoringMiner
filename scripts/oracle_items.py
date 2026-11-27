#!/usr/bin/env python3
"""Notes and todos of the narrator oracles (representations.json, chapters.json), for choosing a subset to work on.

Notes are the strings of a case's "notes" list; todos are the "todo" fields of its expectations. Item ids:
  <R|C>:<case>:n<index in notes>   or   <R|C>:<case>:t<index in expectations>
R = representations.json, C = chapters.json; <case> is the short id the code TODOs cite (es_123926, netty_14031,
rocketmq_8916, ...), with @<commit prefix> for a dataset the oracle holds at several commits. The long dataset name is accepted wherever a case is given.

Index lines: a note shows its first sentence; a todo shows [expectation kind], its label (the "mechanism (...):" prefix,
when it has one) and the expectation description, which summarises it more briefly than the todo's opening.
A case header counts its notes, todos and the code TODO items citing it.

Usage:
  oracle_items.py                           index of every item
  oracle_items.py --oracle C --kind todos   only the todos of chapters.json
  oracle_items.py --case netty_14031 --grep 'forward extension'   dataset substring / text regex filters
  oracle_items.py --counts                  per-case counts only
  oracle_items.py --show C:es_123926:t16 R:netty_14031:n2   full text of chosen items
  oracle_items.py --grep 'forward extension' --full         full text of every matched item
  oracle_items.py --code [--case es_113613] code TODOs of the graph package, split into sub-items, with the cases each
                                            cites (the mechanisms several cases wait on: the payoff of one fix)
"""
import argparse
import json
import re
import signal
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ORACLE_DIR = REPO / "src" / "test" / "resources" / "narrator"
CODE_DIR = REPO / "src" / "main" / "java" / "org" / "refactoringminer" / "astDiff" / "graph"
ORACLES = {"R": "representations.json", "C": "chapters.json"}
REPO_SHORT = {"elasticsearch": "es", "spring-boot": "spring", "spring-framework": "spring"}
CASE_ID = re.compile(r"\b[a-z]+_\d{4,6}\b")
LABEL = re.compile(r"^([^:]{1,110}\)):\s*")


def short_case(dataset):
    org_repo, number = dataset.rsplit("_", 1)
    repo = org_repo.split("_", 1)[1] if "_" in org_repo else org_repo
    return f"{REPO_SHORT.get(repo, repo.lower())}_{number}"


def items(oracle):
    cases = json.loads((ORACLE_DIR / ORACLES[oracle]).read_text(encoding="utf-8"))
    repeated = {d for d in (c["dataset"] for c in cases) if sum(c["dataset"] == d for c in cases) > 1}
    for case in cases:
        dataset, short = case["dataset"], short_case(case["dataset"])
        if dataset in repeated:  # one dataset at several commits (es_115624): the commit tells them apart
            dataset, short = f"{dataset}@{case['commit'][:7]}", f"{short}@{case['commit'][:7]}"
        for i, note in enumerate(case.get("notes", [])):
            yield {"id": f"{oracle}:{short}:n{i}", "case": short, "dataset": dataset, "kind": "note", "text": note}
        for i, expectation in enumerate(case["expectations"]):
            if "todo" in expectation:
                yield {"id": f"{oracle}:{short}:t{i}", "case": short, "dataset": dataset, "kind": "todo",
                       "text": expectation["todo"], "expectation": expectation}


def code_todos():
    """// TODO blocks of the graph package, split at their "- " and "1. " sub-items, with the case ids each cites."""
    result = []
    for path in sorted(CODE_DIR.rglob("*.java")):
        lines = path.read_text(encoding="utf-8").splitlines()
        i = 0
        while i < len(lines):
            match = re.match(r"\s*// TODO:?\s*(.*)", lines[i])
            if not match:
                i += 1
                continue
            start, block = i + 1, [(i + 1, match.group(1))]
            i += 1
            while i < len(lines) and re.match(r"\s*//\s{2,}\S", lines[i]):
                block.append((i + 1, lines[i].strip()[2:].strip()))
                i += 1
            parts = []
            for number, text in block:
                if not parts or re.match(r"(-|\d+\.)\s", text):
                    parts.append([number, text])
                else:
                    parts[-1][1] += " " + text
            head, *subs = parts
            result.append({"where": f"{path.name}:{start}", "head": head[1],
                           "subs": [{"line": n, "text": t} for n, t in subs],
                           "cases": sorted(set(CASE_ID.findall(" ".join(t for _, t in block))))})
    for todo in result:
        todo["head_cases"] = sorted(set(CASE_ID.findall(todo["head"])))
        for sub in todo["subs"]:
            sub["cases"] = sorted(set(CASE_ID.findall(sub["text"])))
    return result


def one_line(text, width):
    text = " ".join(text.split())
    return text if width <= 0 or len(text) <= width else text[:width - 1] + "…"


def first_sentence(text):
    match = re.match(r"(.+?[.;])(\s+[A-Z(]|$)", " ".join(text.split()))
    return match.group(1) if match else text


def summary(item):
    if item["kind"] == "note":
        return first_sentence(item["text"])
    expectation = item["expectation"]
    label = LABEL.match(item["text"])
    return f"[{expectation['kind']}] {label.group(1) + ' | ' if label else ''}{expectation['description']}"


def wanted_case(case_args, *names):
    return not case_args or any(c in name for c in case_args for name in names)


def print_code(case_args, width):
    for todo in code_todos():
        if case_args and not any(wanted_case(case_args, c) for c in todo["cases"]):
            continue
        cases = todo["head_cases"]
        print(f"{todo['where']}  [{len(todo['cases'])} cases] {one_line(todo['head'], width)}"
              + (f"  ({', '.join(cases)})" if cases and width > 0 else ""))
        for sub in todo["subs"]:
            if case_args and not any(wanted_case(case_args, c) for c in sub["cases"]):
                continue
            print(f"    :{sub['line']} [{len(sub['cases'])}] {one_line(sub['text'], width)}"
                  + (f"  ({', '.join(sub['cases'])})" if sub["cases"] and width > 0 else ""))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--oracle", choices=["R", "C", "both"], default="both")
    parser.add_argument("--kind", choices=["notes", "todos", "both"], default="both")
    parser.add_argument("--case", action="append", default=[], help="case substring, short or long (repeatable)")
    parser.add_argument("--grep", help="case-insensitive regex on the full item text")
    parser.add_argument("--width", type=int, default=160, help="characters per line, 0 for untruncated lines")
    parser.add_argument("--counts", action="store_true", help="per-case counts of the matched items only")
    parser.add_argument("--code", action="store_true", help="code TODOs with the cases they cite")
    parser.add_argument("--full", action="store_true", help="full text of every matched item instead of the index")
    parser.add_argument("--show", nargs="+", metavar="ID", help="print the full text of these items")
    args = parser.parse_args()

    if args.code:
        print_code(args.case, args.width)
        return

    oracles = ["R", "C"] if args.oracle == "both" else [args.oracle]
    if args.show:
        oracles = sorted({i.split(":", 1)[0] for i in args.show} & set(ORACLES))
    all_items = [item for oracle in oracles for item in items(oracle)]

    def print_full(item):
        print(f"## {item['id']} ({item['kind']})")
        if item["kind"] == "todo":
            e = item["expectation"]
            print(f"expectation: [{e['kind']}] {e['description']}")
        print(item["text"] + "\n")

    if args.show:
        by_id = {}
        for item in all_items:
            oracle, _, index = item["id"].split(":")
            by_id[item["id"]] = by_id[f"{oracle}:{item['dataset']}:{index}"] = item
        for wanted in args.show:
            item = by_id.get(wanted)
            if item is None:
                print(f"## {wanted}: no such item\n")
            else:
                print_full(item)
        return

    code_cites = {}
    for todo in code_todos():
        for case in todo["head_cases"] + [c for sub in todo["subs"] for c in sub["cases"]]:
            code_cites[case] = code_cites.get(case, 0) + 1

    grep = re.compile(args.grep, re.IGNORECASE) if args.grep else None
    matched = [item for item in all_items
               if (args.kind == "both" or item["kind"] + "s" == args.kind)
               and wanted_case(args.case, item["case"], item["dataset"])
               and (grep is None or grep.search(item["text"]))]
    if args.full:
        for item in matched:
            print_full(item)
        return

    current = None
    for item in matched:
        oracle_case = item["id"].rsplit(":", 1)[0]
        if oracle_case != current:
            current = oracle_case
            in_case = [m for m in matched if m["id"].startswith(oracle_case + ":")]
            notes = sum(m["kind"] == "note" for m in in_case)
            print(f"{oracle_case}  notes {notes}  todos {len(in_case) - notes}  code {code_cites.get(item['case'].split('@')[0], 0)}")
        if not args.counts:
            print(f"  {item['id'].rsplit(':', 1)[1]:>4} {one_line(summary(item), args.width)}")
    notes = sum(m["kind"] == "note" for m in matched)
    print(f"total: notes {notes}  todos {len(matched) - notes}")


if __name__ == "__main__":
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)
    main()

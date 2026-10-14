"""LLM-as-a-judge relevance of generated comments to ground truth review comments,
with Claude as the judge, in the SWR-Bench style.

Every ground truth is paired with each generated comment matched to it by line overlap
(groundTruthGeneratedCommentsNodes). Each pair is judged in full isolation by `judge`: one
fresh `claude -p` process per pair, started in an empty temporary directory with no tools,
no MCP servers, no skills and no session persistence, given only RUBRIC and that pair's
information (the ground truth's path, diff hunk tail and reviewer thread, and the one
generated comment), never other pairs or their verdicts.

Verdicts are cached in relevance_verdicts.jsonl next to this script (or --cache PATH), keyed
by (file, ground truth id, comment text), so identical texts from any run reuse one verdict.
For repeated trials, judge each trial into its own --cache file, then `vote` writes the
majority verdict of every pair into the cache.

Metrics (same definitions as sum_ground_truths.py / calculate_precision.py):
  recall    -- ground truths with at least one matched comment judged Y / ground truths
  precision -- generated comments (text + target node ids) judged Y for at least one
               ground truth / generated comments; unmatched comments are not relevant
  F1        -- from pooled precision and recall; per file, paired per file
  pairwise  -- ground truths hit by only one of two runs, two-sided exact sign test

Usage:
  python judge_relevance.py judge  [results_dir] [--items-from dir] [--limit n] [--model claude-opus-5-5]
      judge every unjudged pair (or the first n) in its own claude process, recording
      each verdict as it arrives
  python judge_relevance.py show   [results_dir] [--items-from dir] [--limit 1]
      print unjudged pairs, one self-contained block each, with stable ids
  python judge_relevance.py record [results_dir] [--items-from dir] SPEC...
      SPEC is pi:Y / pi:N, the verdict of unjudged pair pi
  python judge_relevance.py report [results_dir ...] [--items-from dir]
      metrics per run, and pairwise sign tests when several runs are given
  python judge_relevance.py vote TRIAL_CACHE...
      majority verdict of each pair over the trial caches (odd count, every pair in each
      trial), appended to --cache for pairs it does not have yet
"""

import json
import subprocess
import sys
import tempfile
from math import comb
from pathlib import Path

DEFAULT_DIR = Path('results/ContextCRBench')
CACHE = Path(__file__).with_name('relevance_verdicts.jsonl')
DEFAULT_LIMIT = 1
HUNK_TAIL = 10
DEFAULT_MODEL = 'claude-opus-5-5'

RUBRIC = """You judge one pair: a reviewer's comment (with its thread) on a code change, and one
generated review comment on the same code. Answer one question with Y or N:

  Does the generated comment raise the same issue, concern or point as the reviewer's
  comment, read together with its thread?

Rubric (apply exactly, every pair, no skipping):
  - The reviewer's first comment is the point; replies only clarify what it means.
  - Y needs the specific point, substantially and correctly. Wording, tone and any extra
    points in the generated comment do not matter.
  - Generic hedges ("ensure this works", "should be tested", "verify all references")
    are N unless they name the reviewer's specific concern.
  - Praise ("thanks for the test", "nice rename"): Y only if the generated comment
    approves the same change.
  - Author's note explaining their own change ("extracted from X", "this avoids a sleep"):
    Y if the generated comment captures the same point or purpose.
  - Question about a change ("why is this removed?"): Y if the generated comment raises
    or answers the same question about the same code.

Reply with your reasoning if you need it, then a last line holding only Y or N."""


def pop_option(args, name, default):
    if name in args:
        i = args.index(name)
        value = args[i + 1]
        del args[i:i + 2]
        return value
    return default


def load_cache(path=None):
    path = path or CACHE
    cache = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            if line.strip():
                r = json.loads(line)
                cache[(r['file'], r['gt_id'], r['comment'])] = r['verdict'] == 'Y'
    return cache


def load(results_dir, items):
    """[(file name, data)] sorted by name; optionally only files also in `items`."""
    out = []
    for json_file in sorted(results_dir.glob('*.json')):
        if items is not None and json_file.name not in items:
            continue
        with open(json_file, encoding='utf-8') as f:
            out.append((json_file.name, json.load(f)))
    return out


def pairs(files):
    """Stable list of judgeable pairs: (file, entry, thread, matched text), one per unique text."""
    out = []
    for name, data in files:
        for e in data.get('groundTruthGeneratedCommentsNodes', []):
            thread = [c for c in e['groundTruth'].get('review_comment', []) if c.strip()]
            if not thread:
                continue
            texts = dict.fromkeys(c['comment'] for c in e.get('generatedCommentsNodes', []) if c['comment'].strip())
            out.extend((name, e, thread, t) for t in texts)
    return out


def pair_key(pair):
    name, e, _, text = pair
    return (name, e['groundTruth']['id'], text)


def pair_block(pair):
    name, e, thread, text = pair
    lines = [f"path: {e['groundTruth'].get('path', name)}", '-- diff hunk (tail):']
    lines += ['   ' + line for line in e['groundTruth'].get('diff_hunk', '').splitlines()[-HUNK_TAIL:]]
    lines.append('-- reviewer thread:')
    lines += [('  GT: ' if j == 0 else '  reply: ') + c.replace('\n', ' ') for j, c in enumerate(thread)]
    lines.append('-- generated comment: ' + text.replace('\n', ' '))
    return '\n'.join(lines)


def show(prs, cache, limit):
    todo = [(pi, pr) for pi, pr in enumerate(prs) if pair_key(pr) not in cache]
    print(f"{len(todo)} unjudged pairs; showing {min(limit, len(todo))}")
    for pi, pr in todo[:limit]:
        print('=' * 100)
        print(f"PAIR {pi}")
        print(pair_block(pr))


def judge(prs, cache, limit, model):
    todo = [(pi, pr) for pi, pr in enumerate(prs) if pair_key(pr) not in cache]
    todo = todo[:limit] if limit is not None else todo
    print(f"{len(todo)} pairs to judge with {model}")
    for pi, pr in todo:
        with tempfile.TemporaryDirectory() as empty:
            out = subprocess.run(
                ['claude', '-p', '--model', model, '--system-prompt', RUBRIC, '--tools', '',
                 '--strict-mcp-config', '--disable-slash-commands', '--no-session-persistence'],
                input=pair_block(pr), capture_output=True, text=True, cwd=empty, check=True).stdout
        lines = out.strip().splitlines()
        v = lines[-1].strip() if lines else ''
        if v not in ('Y', 'N'):
            sys.exit(f"pair {pi}: no Y/N verdict in judge output:\n{out}")
        k = pair_key(pr)
        cache[k] = v == 'Y'
        with open(CACHE, 'a', encoding='utf-8') as f:
            f.write(json.dumps({'file': k[0], 'gt_id': k[1], 'comment': k[2], 'verdict': v}, ensure_ascii=False) + '\n')
        print(f"pair {pi}: {v}")


def record(prs, cache, specs):
    rows = []
    for spec in specs:
        pi, v = spec.split(':')
        if v not in ('Y', 'N'):
            sys.exit(f"pair {pi}: verdict must be Y or N, got {v!r}")
        k = pair_key(prs[int(pi)])
        if k in cache:
            sys.exit(f"pair {pi} is already judged")
        cache[k] = v == 'Y'
        rows.append({'file': k[0], 'gt_id': k[1], 'comment': k[2], 'verdict': v})
    with open(CACHE, 'a', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    print(f"{len(rows)} verdicts recorded")


def key(c):
    return (c['comment'], frozenset(n['id'] for n in c['nodes']))


def score(files, cache):
    """Per-run totals and per-file lists, plus the set of hit ground truth keys."""
    s = {'files': 0, 'gt': 0, 'hit': 0, 'gen': 0, 'rel': 0, 'rec': [], 'prec': [], 'f1': [],
         'hits': set(), 'keys': set(), 'missing': 0}
    for name, data in files:
        generated = {key(c) for c in data.get('generatedCommentsNodes', []) if c['comment'].strip()}
        entries = data.get('groundTruthGeneratedCommentsNodes', [])
        relevant, hits = set(), 0
        for e in entries:
            gid = e['groundTruth']['id']
            s['keys'].add((name, gid))
            hit = False
            for c in e.get('generatedCommentsNodes', []):
                if not c['comment'].strip():
                    continue
                v = cache.get((name, gid, c['comment']))
                if v is None:
                    s['missing'] += 1
                elif v:
                    hit = True
                    relevant.add(key(c))
            if hit:
                hits += 1
                s['hits'].add((name, gid))
        relevant &= generated
        s['files'] += 1
        s['gt'] += len(entries)
        s['hit'] += hits
        s['gen'] += len(generated)
        s['rel'] += len(relevant)
        r = hits / len(entries) if entries else 0.0
        p = len(relevant) / len(generated) if generated else 0.0
        if entries:
            s['rec'].append(r)
        if generated:
            s['prec'].append(p)
            s['f1'].append(2 * p * r / (p + r) if p + r else 0.0)
    return s


def sign_p(x, y):
    n = x + y
    if n == 0:
        return 1.0
    return min(1.0, 2 * sum(comb(n, i) for i in range(min(x, y) + 1)) / 2 ** n)


def report(dirs, items, cache):
    runs = {d.name: score(load(d, items), cache) for d in dirs}
    missing = {r: s['missing'] for r, s in runs.items() if s['missing']}
    if missing:
        sys.exit(f"unjudged matched comments: {missing}; run `show` and `record` first")
    pct = lambda a, b: a / b * 100 if b else 0.0
    avg = lambda xs: sum(xs) / len(xs) * 100 if xs else 0.0
    w = max(len(r) for r in runs) + 2
    print(f"{'run':<{w}}{'files':>6}{'GT':>6}{'hit':>6}{'recall':>9}{'rec/file':>10}"
          f"{'generated':>11}{'relevant':>10}{'precision':>11}{'prec/file':>11}{'F1':>8}{'F1/file':>9}")
    for r, s in runs.items():
        rec, prec = pct(s['hit'], s['gt']), pct(s['rel'], s['gen'])
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        print(f"{r:<{w}}{s['files']:>6}{s['gt']:>6}{s['hit']:>6}{rec:>8.2f}%{avg(s['rec']):>9.2f}%"
              f"{s['gen']:>11}{s['rel']:>10}{prec:>10.2f}%{avg(s['prec']):>10.2f}%{f1:>7.2f}%{avg(s['f1']):>8.2f}%")
    names = list(runs)
    if len(names) > 1:
        print('\npairwise on ground truths (only first hit / only second hit / both, sign test):')
        for i in range(len(names)):
            for j in range(i + 1, len(names)):
                a, b = runs[names[i]]['hits'], runs[names[j]]['hits']
                x, y = len(a - b), len(b - a)
                print(f"  {names[i]} vs {names[j]}: {x} / {y} / {len(a & b)}  p={sign_p(x, y):.3f}")


def vote(trial_paths, cache):
    if len(trial_paths) % 2 == 0:
        sys.exit("vote needs an odd number of trial caches")
    trials = [load_cache(Path(t)) for t in trial_paths]
    keys = set().union(*trials)
    partial = [k for k in keys if any(k not in t for t in trials)]
    if partial:
        sys.exit(f"{len(partial)} pairs are not judged in every trial")
    rows, split = [], 0
    for k in sorted(keys):
        votes = sum(t[k] for t in trials)
        split += 0 < votes < len(trials)
        if k not in cache:
            rows.append({'file': k[0], 'gt_id': k[1], 'comment': k[2],
                         'verdict': 'Y' if votes * 2 > len(trials) else 'N'})
    with open(CACHE, 'a', encoding='utf-8') as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + '\n')
    print(f"{len(keys)} pairs, {split} without unanimous trials; {len(rows)} majority verdicts recorded")


def main():
    global CACHE
    args = sys.argv[1:]
    if not args or args[0] not in ('judge', 'show', 'record', 'report', 'vote'):
        sys.exit(__doc__)
    cmd = args.pop(0)
    CACHE = Path(pop_option(args, '--cache', CACHE))
    items_from = pop_option(args, '--items-from', None)
    items = {f.name for f in Path(items_from).glob('*.json')} if items_from else None
    limit = pop_option(args, '--limit', None)
    model = pop_option(args, '--model', DEFAULT_MODEL)
    cache = load_cache()
    if cmd == 'vote':
        vote(args, cache)
        return
    if cmd == 'report':
        report([Path(a) for a in args] or [DEFAULT_DIR], items, cache)
        return
    results_dir = Path(args.pop(0)) if args and ':' not in args[0] else DEFAULT_DIR
    prs = pairs(load(results_dir, items))
    if cmd == 'judge':
        judge(prs, cache, int(limit) if limit else None, model)
    elif cmd == 'show':
        show(prs, cache, int(limit or DEFAULT_LIMIT))
    else:
        record(prs, cache, args)


if __name__ == '__main__':
    main()

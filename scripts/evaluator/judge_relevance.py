"""LLM-as-a-judge relevance of generated comments to ground truth review comments,
with Claude (the assistant running this script) as the judge, in the SWR-Bench style.

Every ground truth is paired with each generated comment matched to it by line overlap
(groundTruthGeneratedCommentsNodes). Each pair gets one verdict, Y or N, for:

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
  - Consistency: `show` lists comments already judged for the same ground truth (any run).
    A new comment making the same point as one judged Y is Y, as one judged N is N.

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
  python judge_relevance.py show   [results_dir] [--items-from dir] [--limit 30]
      print ground truths that still have unjudged comments, with stable ids
  python judge_relevance.py record [results_dir] [--items-from dir] SPEC...
      SPEC is gi:Y / gi:N (every pending comment of ground truth gi) or
      gi:c2=Y,c5=Y (those Y, the other pending ones N)
  python judge_relevance.py report [results_dir ...] [--items-from dir]
      metrics per run, and pairwise sign tests when several runs are given
  python judge_relevance.py vote TRIAL_CACHE...
      majority verdict of each pair over the trial caches (odd count, every pair in each
      trial), appended to --cache for pairs it does not have yet
"""

import json
import sys
from math import comb
from pathlib import Path

DEFAULT_DIR = Path('results/ContextCRBench')
CACHE = Path(__file__).with_name('relevance_verdicts.jsonl')
DEFAULT_LIMIT = 30
HUNK_TAIL = 10


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


def ground_truths(files):
    """Stable list of judgeable ground truths: (file, entry, thread, unique matched texts)."""
    gts = []
    for name, data in files:
        for e in data.get('groundTruthGeneratedCommentsNodes', []):
            thread = [c for c in e['groundTruth'].get('review_comment', []) if c.strip()]
            if not thread:
                continue
            texts = list(dict.fromkeys(c['comment'] for c in e.get('generatedCommentsNodes', []) if c['comment'].strip()))
            gts.append((name, e, thread, texts))
    return gts


def pending(gt, cache):
    name, e, _, texts = gt
    return [(f'c{j + 1}', t) for j, t in enumerate(texts) if (name, e['groundTruth']['id'], t) not in cache]


def show(gts, cache, limit):
    todo = [(gi, gt) for gi, gt in enumerate(gts) if pending(gt, cache)]
    print(f"{len(todo)} ground truths with unjudged comments; showing {min(limit, len(todo))}")
    judged = {}
    for (f, gid, t), v in cache.items():
        judged.setdefault((f, gid), []).append((t, v))
    for gi, gt in todo[:limit]:
        name, e, thread, _ = gt
        print('=' * 100)
        print(f"GT {gi}  {e['groundTruth'].get('path', name)}")
        print('-- diff hunk (tail):')
        for line in e['groundTruth'].get('diff_hunk', '').splitlines()[-HUNK_TAIL:]:
            print('   ' + line)
        print('-- reviewer thread:')
        for j, c in enumerate(thread):
            print(('  GT: ' if j == 0 else '  reply: ') + c.replace('\n', ' '))
        for t, v in judged.get((name, e['groundTruth']['id']), []):
            print(f"-- already judged {'Y' if v else 'N'}: " + t.replace('\n', ' '))
        for cid, t in pending(gt, cache):
            print(f"-- {cid}: " + t.replace('\n', ' '))


def record(gts, cache, specs):
    rows = []
    for spec in specs:
        gi, rest = spec.split(':')
        gt = gts[int(gi)]
        todo = dict(pending(gt, cache))
        default, yes = 'N', set()
        for part in rest.split(','):
            if part in ('Y', 'N'):
                default = part
            else:
                cid, v = part.split('=')
                if cid not in todo:
                    sys.exit(f"GT {gi}: {cid} is not a pending comment ({sorted(todo)})")
                if v == 'Y':
                    yes.add(cid)
        name, e, _, _ = gt
        for cid, t in todo.items():
            rows.append({'file': name, 'gt_id': e['groundTruth']['id'], 'comment': t,
                         'verdict': 'Y' if cid in yes else default})
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
    if not args or args[0] not in ('show', 'record', 'report', 'vote'):
        sys.exit(__doc__)
    cmd = args.pop(0)
    CACHE = Path(pop_option(args, '--cache', CACHE))
    items_from = pop_option(args, '--items-from', None)
    items = {f.name for f in Path(items_from).glob('*.json')} if items_from else None
    limit = int(pop_option(args, '--limit', DEFAULT_LIMIT))
    cache = load_cache()
    if cmd == 'vote':
        vote(args, cache)
        return
    if cmd == 'report':
        report([Path(a) for a in args] or [DEFAULT_DIR], items, cache)
        return
    results_dir = Path(args.pop(0)) if args and ':' not in args[0] else DEFAULT_DIR
    gts = ground_truths(load(results_dir, items))
    if cmd == 'show':
        show(gts, cache, limit)
    else:
        record(gts, cache, args)


if __name__ == '__main__':
    main()

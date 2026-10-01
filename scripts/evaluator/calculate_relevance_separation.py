"""How well an embedding model's cosine similarity separates relevant from irrelevant
(ground truth, matched generated comment) pairs, using the verdicts judged with
judge_relevance.py (relevance_verdicts.jsonl).

Only pairs from the given runs that have a verdict are used. As in
calculate_embedding_recall.py, the reference is the first comment of the ground truth
thread. Per model it reports min / max / median / mean cosine of relevant and of
irrelevant pairs, and the AUC: the probability that a relevant pair scores higher than
an irrelevant one (ties count half), which does not depend on the model's scale.

Usage: python calculate_relevance_separation.py results_dir... [--models a,b,c] [--url url]
"""

import json
import sys
from pathlib import Path

import numpy as np

from calculate_embedding_recall import DEFAULT_URL, embed_all, pop_option
from judge_relevance import load_cache

DEFAULT_MODELS = 'nomic-embed-text:latest,mxbai-embed-large:latest,qwen3-embedding:latest'


def pairs(results_dirs, cache):
    """[(reference, generated comment, relevant)], one per judged pair."""
    out = []
    for results_dir in results_dirs:
        for json_file in sorted(results_dir.glob('*.json')):
            with open(json_file, encoding='utf-8') as f:
                data = json.load(f)
            for e in data.get('groundTruthGeneratedCommentsNodes', []):
                reference = next((c for c in e['groundTruth'].get('review_comment', []) if c.strip()), None)
                if reference is None:
                    continue
                texts = dict.fromkeys(c['comment'] for c in e.get('generatedCommentsNodes', []) if c['comment'].strip())
                for t in texts:
                    v = cache.get((json_file.name, e['groundTruth']['id'], t))
                    if v is not None:
                        out.append((reference, t, v))
    return out


def auc(pos, neg):
    neg = np.sort(neg)
    below = np.searchsorted(neg, pos, side='left')
    ties = np.searchsorted(neg, pos, side='right') - below
    return float((below + ties / 2).sum() / (len(pos) * len(neg)))


def main():
    args = sys.argv[1:]
    models = pop_option(args, '--models', DEFAULT_MODELS).split(',')
    url = pop_option(args, '--url', DEFAULT_URL)
    ps = pairs([Path(a) for a in args], load_cache())
    print(f"{len(ps)} judged pairs: {sum(v for _, _, v in ps)} relevant, {sum(not v for _, _, v in ps)} irrelevant\n")
    w = max(len(m) for m in models) + 2
    print(f"{'model':<{w}}{'class':<12}{'min':>8}{'max':>8}{'median':>8}{'mean':>8}{'AUC':>8}")
    for model in models:
        vectors = embed_all([r for r, _, _ in ps] + [t for _, t, _ in ps], model, url)
        sims = np.array([vectors[r] @ vectors[t] for r, t, _ in ps])
        rel = np.array([v for _, _, v in ps])
        a = auc(sims[rel], sims[~rel])
        for label, s in (('relevant', sims[rel]), ('irrelevant', sims[~rel])):
            print(f"{model if label == 'relevant' else '':<{w}}{label:<12}{s.min():>8.4f}{s.max():>8.4f}"
                  f"{np.median(s):>8.4f}{s.mean():>8.4f}" + (f"{a:>8.3f}" if label == 'relevant' else ''))


if __name__ == '__main__':
    main()

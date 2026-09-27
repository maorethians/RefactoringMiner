"""Cosine similarity (Ollama embeddings) between ground truth review comments and
the generated comments matched to them, and ground truth recall when a match also
has to clear a similarity threshold.

As in calculate_similarity.py, only the first comment of a ground truth thread is
the reference, and aggregation is per file:
  similarity -- per ground truth avg and max over its matched generated comments;
                per file the average of those; overall the average over files
  recall     -- per file, the share of its ground truths whose best matched
                generated comment reaches the threshold (a ground truth with no
                matched comment is a miss); overall the average over files.
                The 'overlap' row is recall from line overlap alone.

Each embedding model has its own similarity scale, so besides the fixed thresholds
recall is also reported at the p90/p95 of an unrelated baseline: ground truths paired
with generated comments of *other* files, a fixed-seed sample of BASELINE_SAMPLE of
each. A threshold at the baseline p95 is one that unrelated comments reach 5% of the time.

Usage: python calculate_embedding_recall.py [results_dir] [--items-from other_dir]
                                            [--model name] [--url url]
"""

import json
import random
import sys
from pathlib import Path

import numpy as np
import requests

DEFAULT_DIR = Path('results/ContextCRBench')
DEFAULT_MODEL = 'qwen3-embedding:latest'
DEFAULT_URL = 'http://localhost:11434'

THRESHOLDS = [0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70, 0.75, 0.80]
BASELINE_PERCENTILES = [90, 95]
BASELINE_SAMPLE = 200

BATCH = 64


def mean(values):
    return sum(values) / len(values)


def pop_option(args, name, default):
    if name not in args:
        return default
    k = args.index(name)
    value = args[k + 1]
    del args[k:k + 2]
    return value


def embed(texts, model, url):
    """Unit-normalized embeddings, one row per text."""
    response = requests.post(f'{url}/api/embed', json={'model': model, 'input': texts})
    response.raise_for_status()
    vectors = np.array(response.json()['embeddings'], dtype=np.float64)
    return vectors / np.linalg.norm(vectors, axis=1, keepdims=True)


def embed_all(texts, model, url):
    texts = sorted(set(texts))
    vectors = {}
    for i in range(0, len(texts), BATCH):
        batch = texts[i:i + BATCH]
        vectors.update(zip(batch, embed(batch, model, url)))
    return vectors


def load(results_dir, items):
    """Per file: a list of (reference, [matched generated comments]) per ground truth."""
    files = {}
    for json_file in sorted(results_dir.glob('*.json')):
        if items is not None and json_file.name not in items:
            continue
        with open(json_file, 'r', encoding='utf-8') as f:
            try:
                data = json.load(f)
            except (json.JSONDecodeError, IOError) as e:
                print(f"Error reading {json_file}: {e}")
                continue

        entries = []
        for entry in data.get('groundTruthGeneratedCommentsNodes', []):
            reference = next((c for c in entry['groundTruth'].get('review_comment', []) if c.strip()), None)
            if reference is None:
                continue
            candidates = [c['comment'] for c in entry.get('generatedCommentsNodes', []) if c['comment'].strip()]
            entries.append((reference, candidates))
        if entries:
            files[json_file.stem] = entries
    return files


def baseline_thresholds(files, vectors):
    """Percentiles of the similarity between ground truths and other files' generated comments."""
    rng = random.Random(0)
    references = [(name, r) for name, entries in files.items() for r, _ in entries]
    candidates = [(name, c) for name, entries in files.items() for _, cs in entries for c in cs]
    references = rng.sample(references, min(BASELINE_SAMPLE, len(references)))
    candidates = rng.sample(candidates, min(BASELINE_SAMPLE, len(candidates)))
    sims = [float(vectors[r] @ vectors[c]) for rf, r in references for cf, c in candidates if rf != cf]
    return {p: float(np.percentile(sims, p)) for p in BASELINE_PERCENTILES}, float(np.median(sims))


def main():
    args = sys.argv[1:]
    model = pop_option(args, '--model', DEFAULT_MODEL)
    url = pop_option(args, '--url', DEFAULT_URL)
    items_from = pop_option(args, '--items-from', None)
    items = {f.name for f in Path(items_from).glob('*.json')} if items_from else None
    results_dir = Path(args[0]) if args else DEFAULT_DIR

    files = load(results_dir, items)
    if not files:
        print(f"No ground truth comments found in {results_dir}")
        return

    vectors = embed_all([t for entries in files.values() for ref, cands in entries for t in [ref, *cands]],
                        model, url)
    baseline, baseline_median = baseline_thresholds(files, vectors)

    thresholds = [('overlap', None)]
    thresholds += [(f">= {t:.2f}", t) for t in THRESHOLDS]
    thresholds += [(f"p{p} ({t:.3f})", t) for p, t in baseline.items()]

    file_avg, file_max = [], []
    file_recall = {label: [] for label, _ in thresholds}
    ground_truths = matched = pairs = 0

    for entries in files.values():
        gt_avg, gt_max = [], []
        best = []
        for reference, candidates in entries:
            ground_truths += 1
            if not candidates:
                best.append(None)
                continue
            sims = [float(vectors[reference] @ vectors[c]) for c in candidates]
            matched += 1
            pairs += len(sims)
            gt_avg.append(mean(sims))
            gt_max.append(max(sims))
            best.append(max(sims))

        if gt_avg:
            file_avg.append(mean(gt_avg))
            file_max.append(mean(gt_max))
        for label, t in thresholds:
            hits = sum(b is not None and (t is None or b >= t) for b in best)
            file_recall[label].append(hits / len(best))

    print(f"Directory: {results_dir}   model: {model}")
    print(f"Files: {len(files)} (with a matched ground truth: {len(file_avg)})")
    print(f"Ground truths: {ground_truths} (matched by overlap: {matched})  "
          f"(ground truth, generated) pairs: {pairs}")
    print()
    print(f"cosine avg-of-avg: {mean(file_avg):.4f}   avg-of-max: {mean(file_max):.4f}   "
          f"(over files with a matched ground truth)")
    print(f"unrelated baseline: median {baseline_median:.3f}  "
          + '  '.join(f"p{p} {t:.3f}" for p, t in baseline.items()))
    print()
    print(f"{'threshold':<16}{'avg recall per file':>20}")
    for label, recalls in file_recall.items():
        print(f"{label:<16}{mean(recalls) * 100:>19.2f}%")


if __name__ == '__main__':
    main()

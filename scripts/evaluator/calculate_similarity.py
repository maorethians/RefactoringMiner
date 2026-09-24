"""ROUGE-1 / ROUGE-L / edit similarity between ground truth review comments and
the generated comments matched to them.

Only the first comment of a ground truth thread -- the one the reviewer wrote
on first seeing the code -- is used as the reference; later replies are ignored.

Aggregation (three levels):
  1. pair   -- the first ground truth comment x every generated comment
               matched to that ground truth
  2. per ground truth -- average and maximum over its pairs
  3. per file -- average of the per-ground-truth averages, and average of the
               per-ground-truth maximums
  4. overall -- average of the per-file averages, and average of the per-file
               maximums
"""

import json
import sys
from pathlib import Path

import numpy as np
from rouge_score import rouge_scorer

DEFAULT_DIR = Path('results/ContextCRBench')

SCORER = rouge_scorer.RougeScorer(['rouge1', 'rougeL'], use_stemmer=True)

METRICS = ('rouge1', 'rougeL', 'edit')


def levenshtein(a, b):
    """Exact character-level Levenshtein distance, one numpy row per char of `a`.

    The insertion term cur[j] = min(cur[j], cur[j-1] + 1) unrolls to
    j + min_{k<=j}(raw[k] - k), so it is a prefix minimum rather than a loop.
    """
    if not a:
        return len(b)
    if not b:
        return len(a)

    bb = np.frombuffer(b.encode('utf-32-le'), dtype=np.uint32)
    idx = np.arange(len(b) + 1)
    prev = idx.copy()

    for i, ch in enumerate(a, start=1):
        cost = (bb != ord(ch)).astype(np.int64)
        raw = np.empty(len(b) + 1, dtype=np.int64)
        raw[0] = i
        np.minimum(prev[:-1] + cost, prev[1:] + 1, out=raw[1:])
        prev = np.minimum.accumulate(raw - idx) + idx

    return int(prev[-1])


def edit_similarity(reference, candidate):
    """1 - normalized edit distance, in [0, 1]."""
    longest = max(len(reference), len(candidate))
    if longest == 0:
        return 1.0
    return 1.0 - levenshtein(reference, candidate) / longest


def pair_scores(reference, candidate):
    rouge = SCORER.score(reference, candidate)
    return {
        'rouge1': rouge['rouge1'].fmeasure,
        'rougeL': rouge['rougeL'].fmeasure,
        'edit': edit_similarity(reference, candidate),
    }


def mean(values):
    return sum(values) / len(values)


def main():
    results_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DIR

    file_avg = {m: [] for m in METRICS}
    file_max = {m: [] for m in METRICS}
    files_scored = 0
    files_skipped = 0
    ground_truths_scored = 0
    ground_truths_unmatched = 0
    pairs = 0

    for json_file in sorted(results_dir.glob('*.json')):
        with open(json_file, 'r', encoding='utf-8') as f:
            try:
                data = json.load(f)
            except (json.JSONDecodeError, IOError) as e:
                print(f"Error reading {json_file}: {e}")
                continue

        gt_avg = {m: [] for m in METRICS}
        gt_max = {m: [] for m in METRICS}

        for entry in data.get('groundTruthGeneratedCommentsNodes', []):
            reference = next((c for c in entry['groundTruth'].get('review_comment', []) if c.strip()), None)
            candidates = [c['comment'] for c in entry.get('generatedCommentsNodes', []) if c['comment'].strip()]
            if reference is None or not candidates:
                ground_truths_unmatched += 1
                continue

            scores = {m: [] for m in METRICS}
            for candidate in candidates:
                for metric, value in pair_scores(reference, candidate).items():
                    scores[metric].append(value)
            pairs += len(candidates)
            ground_truths_scored += 1

            for m in METRICS:
                gt_avg[m].append(mean(scores[m]))
                gt_max[m].append(max(scores[m]))

        if not gt_avg[METRICS[0]]:
            files_skipped += 1
            continue

        files_scored += 1
        for m in METRICS:
            file_avg[m].append(mean(gt_avg[m]))
            file_max[m].append(mean(gt_max[m]))

    if not files_scored:
        print(f"No matched ground truth comments found in {results_dir}")
        return

    print(f"Directory: {results_dir}")
    print(f"Files scored: {files_scored} (skipped, no matched ground truth: {files_skipped})")
    print(f"Ground truths scored: {ground_truths_scored} (unmatched: {ground_truths_unmatched})")
    print(f"(ground truth comment, generated comment) pairs: {pairs}")
    print()
    print(f"{'metric':<10}{'avg-of-avg':>14}{'avg-of-max':>14}")
    for m in METRICS:
        print(f"{m:<10}{mean(file_avg[m]) * 100:>13.2f}%{mean(file_max[m]) * 100:>13.2f}%")


if __name__ == '__main__':
    main()

import json
from pathlib import Path

RESULTS_DIR = Path('results/ContextCRBench')


def key(c):
    """Identity of a generated comment: its text plus the set of node ids it targets."""
    return (c['comment'], frozenset(n['id'] for n in c['nodes']))


def main():
    precisions = []
    f1s = []
    total_generated = 0
    total_matched = 0
    zero_precision = 0

    for json_file in sorted(RESULTS_DIR.glob('*.json')):
        with open(json_file, 'r', encoding='utf-8') as f:
            try:
                data = json.load(f)
            except (json.JSONDecodeError, IOError) as e:
                print(f"Error reading {json_file}: {e}")
                continue

        generated = {key(c) for c in data.get('generatedCommentsNodes', [])}
        if not generated:
            continue

        matched = set()
        for gt in data.get('groundTruthGeneratedCommentsNodes', []):
            for c in gt.get('generatedCommentsNodes', []):
                matched.add(key(c))
        matched &= generated

        precision = len(matched) / len(generated)
        precisions.append(precision)
        if precision == 0:
            zero_precision += 1
        total_generated += len(generated)
        total_matched += len(matched)

        covered = data.get('coveredGroundTruth', 0)
        uncovered = data.get('uncoveredGroundTruth', 0)
        recall = covered / (covered + uncovered) if covered + uncovered else 0.0
        f1s.append(2 * precision * recall / (precision + recall) if precision + recall else 0.0)

    if not precisions:
        print("No valid data found to calculate precision.")
        return

    print(f"Processed {len(precisions)} files.")
    print(f"Total Generated Comments: {total_generated}")
    print(f"Matched (share >=1 hunk with a ground truth): {total_matched}")
    print(f"Unmatched (target hunks no human commented on): {total_generated - total_matched}")
    print(f"Average Precision (matched / generated, per file): {sum(precisions) / len(precisions) * 100:.2f}%")
    print(f"Average F1 (paired per file with recall): {sum(f1s) / len(f1s) * 100:.2f}%")
    print(f"Files with zero matched comments: {zero_precision}")
    print(f"Pooled precision over all comments (for reference): {total_matched / total_generated * 100:.2f}%")


if __name__ == '__main__':
    main()

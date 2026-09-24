import os
import json
from pathlib import Path

def main():
    results_dir = Path('results/ContextCRBench')
    total_covered = 0
    total_uncovered = 0
    recalls = []

    for json_file in results_dir.glob('*.json'):
        with open(json_file, 'r', encoding='utf-8') as f:
            try:
                data = json.load(f)
                covered = data.get('coveredGroundTruth', 0)
                uncovered = data.get('uncoveredGroundTruth', 0)
                total_covered += covered
                total_uncovered += uncovered
                total = covered + uncovered
                recalls.append(covered / total if total else 0.0)
            except (json.JSONDecodeError, IOError) as e:
                print(f"Error reading {json_file}: {e}")

    if not recalls:
        print("No valid data found to calculate average recall.")
        return

    avg_recall = sum(recalls) / len(recalls)
    print(f"Processed {len(recalls)} files.")
    print(f"Total Covered Ground Truths: {total_covered}")
    print(f"Total Uncovered Ground Truths: {total_uncovered}")
    print(f"Overall Total: {total_covered + total_uncovered}")
    print(f"Average Recall (coveredGroundTruth / (coveredGroundTruth + uncoveredGroundTruth)): {avg_recall * 100:.2f}%")

if __name__ == "__main__":
    main()

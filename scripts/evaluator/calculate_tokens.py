import json
import os
import glob
import sys


def calculate_tokens(directory='ContextCRBench'):
    path = os.path.join('results', directory, '*.json')
    files = glob.glob(path)

    if not files:
        print(f"No JSON files found in results/{directory}")
        return

    tokens_in = []
    tokens_out = []

    for file_path in files:
        try:
            with open(file_path, 'r') as f:
                data = json.load(f)

            if 'tokensIn' in data:
                tokens_in.append(data['tokensIn'])
            if 'tokensOut' in data:
                tokens_out.append(data['tokensOut'])
        except Exception as e:
            print(f"Error reading {file_path}: {e}")

    if not tokens_in and not tokens_out:
        print("No valid data found to calculate average.")
        return

    print(f"Processed {len(files)} files.")

    if tokens_in:
        print(f"Average tokensIn over {len(tokens_in)} items: "
              f"{sum(tokens_in) / len(tokens_in):.2f} (total {sum(tokens_in)})")
    if tokens_out:
        print(f"Average tokensOut over {len(tokens_out)} items: "
              f"{sum(tokens_out) / len(tokens_out):.2f} (total {sum(tokens_out)})")


if __name__ == '__main__':
    calculate_tokens(sys.argv[1] if len(sys.argv) > 1 else 'ContextCRBench')

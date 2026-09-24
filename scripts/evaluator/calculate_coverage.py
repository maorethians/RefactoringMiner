import json
import os
import glob
import sys

# Node types that represent an actual change; nodes carrying any other
# nodeType (e.g. SEMANTIC_CONTEXT, LOCATION_CONTEXT, EXTENSION) are skipped.
CHANGE_NODE_TYPES = {
    'DELETION', 'SRC_MOVE', 'SRC_UPDATE',
    'ADDITION', 'DST_MOVE', 'DST_UPDATE',
}

def node_lines(node):
    """Total lines the node spans, over whichever ranges its shape carries.

    AST nodes carry one range; raw nodes carry a src and/or dst range; diff nodes carry
    no range of their own, only a list of changes per side.
    """
    total = 0

    for start_key, end_key in (('srcStartLine', 'srcEndLine'),
                               ('dstStartLine', 'dstEndLine'),
                               ('startLine', 'endLine')):
        start, end = node.get(start_key), node.get(end_key)
        if start is not None and end is not None:
            total += end - start + 1

    for side in ('srcChanges', 'dstChanges'):
        for change in node.get(side, []):
            total += change['endLine'] - change['startLine'] + 1

    return total


def calculate_metrics(directory='ContextCRBench'):
    path = os.path.join('results', directory, '*.json')
    files = glob.glob(path)

    if not files:
        print(f"No JSON files found in results/{directory}")
        return

    total_ratios = []
    total_line_counts = []

    for file_path in files:
        try:
            with open(file_path, 'r') as f:
                data = json.load(f)
                unique_nodes = {}
                for comment in data.get('generatedCommentsNodes', []):
                    for node in comment.get('nodes', []):
                        # Nodes that carry a nodeType only count when they
                        # represent an actual change (context nodes are skipped).
                        if 'nodeType' in node and node['nodeType'] not in CHANGE_NODE_TYPES:
                            continue
                        unique_nodes[node.get('id')] = node

                total_line_counts.append(sum(node_lines(node) for node in unique_nodes.values()))

                covered = data.get('coveredMatchable', 0)
                uncovered = data.get('uncoveredMatchable', 0)

                denominator = covered + uncovered
                if denominator > 0:
                    ratio = covered / denominator
                    total_ratios.append(ratio)
                else:
                    # If both are 0, we might want to skip or treat as 0.
                    # Usually, if there were no matchable nodes, it's an edge case.
                    # I'll treat it as 0 for now but maybe print a warning.
                    total_ratios.append(0.0)
        except Exception as e:
            print(f"Error reading {file_path}: {e}")

    if not total_ratios:
        print("No valid data found to calculate average.")
        return

    avg_ratio = sum(total_ratios) / len(total_ratios)
    print(f"Processed {len(total_ratios)} files.")
    print(f"Average Percentage (coveredMatchable / (coveredMatchable + uncoveredMatchable)): {avg_ratio * 100:.2f}%")

    avg_lines = sum(total_line_counts) / len(total_line_counts)
    print(f"Average line range of unique nodes associated with generated comments: {avg_lines:.2f}")

if __name__ == '__main__':
    calculate_metrics(sys.argv[1] if len(sys.argv) > 1 else 'ContextCRBench')

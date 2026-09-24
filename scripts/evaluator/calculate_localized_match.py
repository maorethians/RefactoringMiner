"""Width-insensitive matching between generated comments and human review comments.

Benchmark scores a hit whenever a generated comment has a node whose line span
overlaps the human comment's line. That rewards wide anchors: a hunk spanning 100
lines overlaps a reviewer's line far more easily than a 5-line AST node, so the two
representations are not comparable on hit counts alone.

This re-runs the same matching from the stored results, but caps how wide the
anchoring node may be. At a given cap both representations must localize to the
same precision to earn a hit, so the comparison measures aim rather than reach.
"""

import json
import glob
import os
import sys

CAPS = [None, 100, 40, 20, 10, 5]


def node_path(node, side):
    """The node's path on the requested side, or None if it has none.

    AST and raw nodes carry a single path; diff nodes carry one per side, either of
    which is absent when the file was added or deleted.
    """
    if 'srcPath' in node or 'dstPath' in node:
        return node.get('srcPath' if side == 'LEFT' else 'dstPath')

    return node.get('path')


def node_spans(node, side):
    """The node's (start, end) ranges on the requested side.

    Raw nodes carry a src and/or dst range; AST nodes carry one range plus the side
    it belongs to; diff nodes carry a list of changes per side, each of which anchors
    on its own -- the hull would be far wider than what the node localizes to. Mirrors
    RawNode.overlapLine, Node.overlapLine and DiffNode.overlapLine.
    """
    if 'srcChanges' in node or 'dstChanges' in node:
        changes = node.get('srcChanges' if side == 'LEFT' else 'dstChanges', [])
        return [(change['startLine'], change['endLine']) for change in changes]

    if 'srcDst' in node:
        if node['srcDst'] != ('SRC' if side == 'LEFT' else 'DST'):
            return []
        start, end = node.get('startLine'), node.get('endLine')
    elif side == 'LEFT':
        start, end = node.get('srcStartLine'), node.get('srcEndLine')
    else:
        start, end = node.get('dstStartLine'), node.get('dstEndLine')

    return [] if start is None or end is None else [(start, end)]


def hits(comment, gt, cap):
    """Does this generated comment anchor on the ground truth's line within the cap?"""
    path = gt['path']
    side = gt['side']
    line = gt['submitted_line']
    start_line = gt.get('submitted_start_line')

    # Benchmark matches against every anchored node, with no node type filtered out.
    for node in comment['nodes']:
        if node_path(node, side) != path:
            continue

        for start, end in node_spans(node, side):
            overlaps = (start <= line <= end) if start_line is None \
                else (start_line <= end and start <= line)
            if overlaps and (cap is None or end - start + 1 <= cap):
                return True

    return False


def collect(directory):
    """{ground truth id: {cap: hit}} plus the stored verdict, per dataset."""
    out = {}
    for file_path in glob.glob(os.path.join('results', directory, '*.json')):
        data = json.load(open(file_path, encoding='utf-8'))
        comments = data.get('generatedCommentsNodes') or []

        for entry in data.get('groundTruthGeneratedCommentsNodes') or []:
            gt = entry['groundTruth']
            if 'submitted_line' not in gt:
                continue
            out[(os.path.basename(file_path), gt['id'])] = {
                'stored': bool(entry.get('generatedCommentsNodes')),
                'caps': {cap: any(hits(c, gt, cap) for c in comments) for cap in CAPS},
            }
    return out


def main(dirs):
    datasets = {d: collect(d) for d in dirs}

    for d, gts in datasets.items():
        agree = sum(1 for g in gts.values() if g['caps'][None] == g['stored'])
        print(f'{d}: {len(gts)} ground truths, uncapped re-match agrees with stored verdict '
              f'on {agree}/{len(gts)} ({agree / len(gts) * 100:.1f}%)')

    common = set.intersection(*(set(g) for g in datasets.values()))
    print(f'\nGround truths present in every dataset: {len(common)}\n')

    header = 'anchor width cap'.ljust(18) + ''.join(f'{d[:20]:>22}' for d in dirs)
    print(header)
    for cap in CAPS:
        label = 'none (as scored)' if cap is None else f'<= {cap} lines'
        row = label.ljust(18)
        for d in dirs:
            hit = sum(1 for k in common if datasets[d][k]['caps'][cap])
            row += f'{hit:>10} {hit / len(common) * 100:10.1f}%'
        print(row)


if __name__ == '__main__':
    main(sys.argv[1:] or ['ContextCRBench'])

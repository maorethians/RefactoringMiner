package narrator.graph.cluster.traverse;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.Comparator;
import narrator.graph.Node;
import narrator.graph.NodeType;
import narrator.graph.Edge;
import narrator.graph.EdgeType;
import narrator.graph.cluster.Cluster;
import org.jgrapht.Graph;
import org.refactoringminer.astDiff.utils.Constants;

public class Narrator {
    private final TraversalPattern rootPattern;
    private final Map<GrainLevel, List<TraversalPattern>> cache = new HashMap<>();
    private final Map<GrainLevel, Integer> progressMap = new HashMap<>();

    private static final Map<GrainLevel, Set<String>> GRAIN_LEVEL_TYPES = new HashMap<>();
    static {
        Constants c = new Constants("");
        GRAIN_LEVEL_TYPES.put(GrainLevel.METHOD, Set.of(c.METHOD_DECLARATION));
        GRAIN_LEVEL_TYPES.put(GrainLevel.CLASS, Set.of(c.TYPE_DECLARATION, c.INTERFACE_DECLARATION, c.ENUM_DECLARATION, c.RECORD_DECLARATION));
        GRAIN_LEVEL_TYPES.put(GrainLevel.FILE, Set.of(c.COMPILATION_UNIT));
    }

    public Narrator(TraversalPattern rootPattern) {
        this.rootPattern = rootPattern;
    }

    public List<TraversalPattern> getNarrative(GrainLevel grainLevel) {
        return cache.computeIfAbsent(grainLevel, this::narrate);
    }

    public int getProgress(GrainLevel grainLevel) {
        return progressMap.getOrDefault(grainLevel, 0);
    }

    public void incrementProgress(GrainLevel grainLevel) {
        progressMap.put(grainLevel, getProgress(grainLevel) + 1);
    }

    private List<TraversalPattern> narrate(GrainLevel grainLevel) {
        if (rootPattern == null) {
            return Collections.emptyList();
        }

        List<TraversalPattern> result = new ArrayList<>();
        Set<TraversalPattern> visited = new HashSet<>();

        switch (grainLevel) {
            case LEAF -> traverse(rootPattern, visited, result, pp -> false, pp -> pp instanceof Leaf);
            case USAGE_CHAIN_ROOT -> {
                Set<UsagePattern> roots = findUsageRoots(rootPattern);
                traverse(rootPattern, visited, result,
                    pp -> pp instanceof UsagePattern u && roots.contains(u),
                    pp -> pp instanceof Leaf && (!(pp instanceof UsagePattern) || roots.contains((UsagePattern) pp)));
            }
            case SEMANTIC_LEAF -> traverse(rootPattern, visited, result,
                pp -> pp instanceof TraversalComponent tc && isSemanticLeaf(tc),
                pp -> pp instanceof Leaf);
            case SEMANTIC_ROOT -> traverse(rootPattern, visited, result,
                pp -> pp instanceof TraversalComponent tc && isSemanticRoot(tc),
                pp -> pp instanceof Leaf);
            case METHOD, CLASS, FILE -> traverse(rootPattern, visited, result,
                pp -> pp instanceof TraversalComponent tc && matchesGrain(tc, grainLevel),
                pp -> pp instanceof Leaf);
        }

        return sortPatterns(result);
    }

    private Set<UsagePattern> findUsageRoots(TraversalPattern root) {
        Set<UsagePattern> allUsages = new HashSet<>();
        collectUsages(root, allUsages);

        Set<UsagePattern> roots = new HashSet<>();
        for (UsagePattern usage : allUsages) {
            if (!isDescendantOfUsage(usage, allUsages)) {
                roots.add(usage);
            }
        }
        return roots;
    }

    private void collectUsages(TraversalPattern p, Set<UsagePattern> usages) {
        if (p instanceof UsagePattern usage) {
            usages.add(usage);
        }
        if (p instanceof AggregatorPattern agg) {
            for (TraversalPattern sub : agg.subs) {
                collectUsages(sub, usages);
            }
        }
    }

    private boolean isDescendantOfUsage(UsagePattern p, Set<UsagePattern> allUsages) {
        for (UsagePattern other : allUsages) {
            if (p == other) continue;
            if (p.dependsOn(other)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isSemanticLeaf(TraversalComponent tc) {
        if (tc.getMergeContexts() == null || tc.getMergeContexts().isEmpty()) return false;

        boolean hasSemanticContext = false;
        for (Node node : tc.getMergeContexts()) {
            if (node.getNodeType() == NodeType.SEMANTIC_CONTEXT) {
                hasSemanticContext = true;
                break;
            }
        }

        if (!hasSemanticContext) return false;

        for (TraversalPattern sub : tc.subs) {
            if (!(sub instanceof Leaf)) return false;
        }

        return true;
    }

    public static boolean isSemanticRoot(TraversalComponent tc) {
        if (tc.getMergeContexts() == null || tc.getMergeContexts().isEmpty()) return false;

        for (Node node : tc.getMergeContexts()) {
            if (node.getNodeType() != NodeType.SEMANTIC_CONTEXT) {
                return false;
            }
        }

        return true;
    }

    public static boolean matchesGrain(TraversalComponent tc, GrainLevel grainLevel) {
        Set<String> allowedTypes = GRAIN_LEVEL_TYPES.get(grainLevel);
        if (allowedTypes == null || tc.getMergeContexts() == null) return false;

        for (Node contextNode : tc.getMergeContexts()) {
            var tree = contextNode.getTree();
            if (allowedTypes.contains(tree.getType().name)) return true;
            for (var parent : tree.getParents()) {
                if (allowedTypes.contains(parent.getType().name)) return true;
            }
        }
        return false;
    }

    public static boolean isChapter(TraversalPattern p, GrainLevel level) {
        return switch (level) {
            case LEAF -> p instanceof Leaf;
            case USAGE_CHAIN_ROOT -> p instanceof UsagePattern;
            case SEMANTIC_LEAF -> p instanceof TraversalComponent tc && isSemanticLeaf(tc);
            case SEMANTIC_ROOT -> p instanceof TraversalComponent tc && isSemanticRoot(tc);
            case METHOD, CLASS, FILE -> p instanceof TraversalComponent tc && matchesGrain(tc, level);
        };
    }

    public List<TraversalPattern> sortPatterns(List<TraversalPattern> patterns) {
        List<TraversalPattern> result = new ArrayList<>();
        Set<TraversalPattern> visited = new HashSet<>();
        for (TraversalPattern pattern : patterns) {
            visit(pattern, visited, result, patterns);
        }
        return result;
    }

    private void visit(TraversalPattern pattern, Set<TraversalPattern> visited, List<TraversalPattern> result, Collection<TraversalPattern> candidates) {
        if (!visited.add(pattern)) return;
        for (TraversalPattern other : candidates) {
            if (pattern.dependsOn(other)) {
                visit(other, visited, result, candidates);
            }
        }
        result.add(pattern);
    }

    public static void traverse(TraversalPattern p, Set<TraversalPattern> visited, List<TraversalPattern> result, Predicate<TraversalPattern> stopPredicate, Predicate<TraversalPattern> leafPredicate) {
        if (visited.contains(p)) return;
        visited.add(p);

        if (stopPredicate.test(p)) {
            result.add(p);
            return;
        }

        if (p instanceof AggregatorPattern agg) {
            List<TraversalPattern> sortedSubs = new ArrayList<>(agg.subs);
            sortedSubs.sort(Comparator.comparingInt(TraversalPattern::getDepth).reversed());
            for (TraversalPattern sub : sortedSubs) {
                traverse(sub, visited, result, stopPredicate, leafPredicate);
            }
        }

        if (leafPredicate.test(p)) {
            result.add(p);
        }
    }
}

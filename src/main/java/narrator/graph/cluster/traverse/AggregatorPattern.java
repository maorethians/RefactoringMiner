package narrator.graph.cluster.traverse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.SrcDst;
import narrator.graph.cluster.Cluster;
import org.jgrapht.Graph;

public class AggregatorPattern extends TraversalPattern {

    @Override
    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        List<Node> allMains = getMains(graph);
        List<Node> allSides = getSides(graph);

        Set<Node> nodesToFilter = new HashSet<>();
        if (filterPatterns != null) {
            for (TraversalPattern p : filterPatterns) {
                nodesToFilter.addAll(p.getMains(graph));
                nodesToFilter.addAll(p.getSides(graph));
            }
        }

        List<Node> allNodes = new ArrayList<>();
        allNodes.addAll(allMains);
        allNodes.addAll(allSides);

        List<Node> filteredNodes = allNodes.stream()
                .filter(n -> !nodesToFilter.contains(n))
                .filter(n -> {
                    List<Node> partners = (n.getSrcDst() == SrcDst.SRC) ? n.getMappingTargets(graph) : n.getMappingSources(graph);
                    return !partners.isEmpty();
                })
                .toList();

        List<TraversalPattern.MappingGroup> groups = TraversalPattern.aggregateByMapping(graph, filteredNodes);
        List<String> hunks = new ArrayList<>();
        for (TraversalPattern.MappingGroup mg : groups) {
            hunks.add(buildMappingHunk(mg.group, mg.partners, graph));
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append(String.join("\n---\n", hunks));

        return prompt.toString();
    }

    private String buildMappingHunk(List<Node> group, List<Node> partners, Graph<Node, Edge> graph) {
        Set<String> allOps = new HashSet<>();
        for (Node n : group) {
            allOps.addAll(n.getOperations(graph));
        }
        String ops = String.join(" and ", allOps.stream().map(op -> op + "d").toList());

        Node rep = group.get(0);
        Collection<Node> from = (rep.getSrcDst() == SrcDst.SRC) ? group : partners;
        Collection<Node> to = (rep.getSrcDst() == SrcDst.SRC) ? partners : group;

        StringBuilder hunk = new StringBuilder();
        hunk.append(String.join("\n", from.stream().map(n -> n.base(graph)).toList()))
            .append("\n\n").append(ops).append(" to:\n\n")
            .append(String.join("\n", to.stream().map(n -> n.base(graph)).toList()));
        return hunk.toString();
    }

    @Override
    public List<Node> getMains(Graph<Node, Edge> graph) {
        List<TraversalPattern> leaves = this.getNarrator().getNarrative(GrainLevel.LEAF);
        if (leaves.isEmpty()) {
            return List.of();
        }

        Set<Node> mainsOrdered = new LinkedHashSet<>();
        for (TraversalPattern leaf : leaves) {
            for (Node main : leaf.getMains(graph)) {
                mainsOrdered.add(main);
            }
        }

        return new ArrayList<>(mainsOrdered);
    }

    @Override
    public List<Node> getSides(Graph<Node, Edge> graph) {
        Set<Node> mainsSet = new HashSet<>(getMains(graph));
        List<TraversalPattern> leaves = this.getNarrator().getNarrative(GrainLevel.LEAF);
        if (leaves.isEmpty()) {
            return List.of();
        }

        Set<Node> sidesOrdered = new LinkedHashSet<>();
        for (TraversalPattern leaf : leaves) {
            for (Node side : leaf.getSides(graph)) {
                if (!mainsSet.contains(side)) {
                    sidesOrdered.add(side);
                }
            }
        }

        return new ArrayList<>(sidesOrdered);
    }

    Set<TraversalPattern> subs = new HashSet<>();

    protected boolean containsNode(Node node, Set<TraversalPattern> visited) {
        if (!visited.add(this)) {
            return false;
        }

        boolean isRootNode = getGraph().vertexSet().stream()
                .anyMatch(coreNode -> coreNode.equals(node));
        if (isRootNode) {
            return true;
        }

        for (TraversalPattern sub : subs) {
            if (sub instanceof AggregatorPattern) {
                if (((AggregatorPattern) sub).containsNode(node, visited)) {
                    return true;
                }
            } else {
                if (!visited.add(this)) {
                    continue;
                }
                if (sub.containsNode(node)) {
                    return true;
                }
            }
        }

        return false;
    }

    protected Set<Node> vertexSet(Set<AggregatorPattern> visited) {
        if (!visited.add(this)) {
            return new HashSet<>();
        }

        Set<Node> result = new HashSet<>(getGraph().vertexSet());
        for (TraversalPattern sub : subs) {
            if (sub instanceof AggregatorPattern) {
                result.addAll(((AggregatorPattern) sub).vertexSet(visited));
            } else {
                if (!visited.add(this)) {
                    continue;
                }
                result.addAll(sub.vertexSet());
            }
        }
        return result;
    }

    // TODO: test and validate
    protected void breakCircularDependencies(List<AggregatorPattern> path) {
        List<AggregatorPattern> acceptableSubs = subs.stream()
                .filter(sub -> sub instanceof AggregatorPattern).map(sub -> (AggregatorPattern) sub)
                .toList();
        if (acceptableSubs.isEmpty()) {
            return;
        }

        List<AggregatorPattern> newPath = new ArrayList<>(path);
        newPath.add(this);

        if (this instanceof UsagePattern thisUsage) {
            List<AggregatorPattern> circularSubs = acceptableSubs.stream().filter(newPath::contains)
                    .toList();
            for (AggregatorPattern circularSub : circularSubs) {
                subs.remove(circularSub);

                List<Node> requirementNodes = thisUsage.getRequirements().entrySet().stream()
                        .filter(entry -> entry.getValue().equals(circularSub)).map(
                                Entry::getKey).toList();
                if (requirementNodes.size() > 1) {
                    System.out.println("Requirement Breaking Failure");
                    continue;
                }
                for (Node requirementNode : requirementNodes) {
                    thisUsage.breakRequirement(requirementNode);
                }
            }
        }

        for (AggregatorPattern sub : acceptableSubs) {
            sub.breakCircularDependencies(newPath);
        }
    }

}

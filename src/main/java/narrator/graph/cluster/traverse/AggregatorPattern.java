package narrator.graph.cluster.traverse;

import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.SrcDst;
import org.jgrapht.Graph;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class AggregatorPattern extends TraversalPattern {

    Set<TraversalPattern> subs = new HashSet<>();

    @Override
    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        Set<Node> nodesTracker = new HashSet<>();
        nodesTracker.addAll(getMains(graph));
        nodesTracker.addAll(getSides(graph));

//        Set<Node> nodesToFilter = new HashSet<>();
//        if (filterPatterns != null) {
//            for (TraversalPattern p : filterPatterns) {
//                nodesToFilter.addAll(p.getMains(graph));
//                nodesToFilter.addAll(p.getSides(graph));
//            }
//        }
//        nodesTracker = nodesTracker.stream().filter(node -> !nodesToFilter.contains(node)).collect(Collectors.toSet());

        Set<String> promptSections = new HashSet<>();

        List<TraversalComponent> semanticLeaves = this.getNarrator().getNarrative(GrainLevel.SEMANTIC_LEAF).stream()
                .filter(chapter -> chapter instanceof TraversalComponent
                        && Narrator.isSemanticLeaf((TraversalComponent) chapter))
                .map(chapter -> (TraversalComponent) chapter).toList();
        if (!semanticLeaves.isEmpty()) {
            for (TraversalComponent semanticLeaf : semanticLeaves) {
                List<Node> allLeafNodes = new ArrayList<>();
                allLeafNodes.addAll(semanticLeaf.getMains(graph));
                allLeafNodes.addAll(semanticLeaf.getSides(graph));

                StringBuilder leafPrompt = new StringBuilder();

                List<String> leafPromptSections = new ArrayList<>();
                for (MappingGroup leafMappingGroup : TraversalPattern.aggregateByMapping(graph, allLeafNodes)) {
                    if (leafMappingGroup.partners.isEmpty()) {
                        leafPromptSections.add(String.join("\n", leafMappingGroup.group.stream().map(n -> n.base(graph)).toList()));
                    } else {
                        leafPromptSections.add(buildMappingHunk(leafMappingGroup.group, leafMappingGroup.partners, graph));
                    }
                }
                leafPrompt.append(String.join("\n---\n", leafPromptSections));

                Set<Node> mergeContexts = semanticLeaf.getMergeContexts();
                Node firstMergeContext = mergeContexts.iterator().next();
                leafPrompt.append("\n\nwithin:\n\n").append(firstMergeContext.mapping(graph));

                promptSections.add(leafPrompt.toString());
                nodesTracker = nodesTracker.stream().filter(n -> !allLeafNodes.contains(n)).collect(Collectors.toSet());
            }
        }

        if (nodesTracker.isEmpty()) {
            // TODO: sort
            return String.join("\n```\n", promptSections);
        }

        List<MappingGroup> mappingGroups = TraversalPattern.aggregateByMapping(graph, nodesTracker);
        List<MappingGroup> groupsWithMapping = mappingGroups.stream().filter(g -> !g.partners.isEmpty()).toList();
        for (MappingGroup mg : groupsWithMapping) {
            promptSections.add(buildMappingHunk(mg.group, mg.partners, graph));
        }

        // TODO: sort
        return String.join("\n```\n", promptSections);
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

        String hunk = String.join("\n", from.stream().map(n -> n.base(graph)).toList()) +
                "\n\n" + ops + " to:\n\n" +
                String.join("\n", to.stream().map(n -> n.base(graph)).toList());
        return hunk;
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

package narrator.graph.cluster.traverse;

import narrator.graph.Edge;
import narrator.graph.Node;
import org.jgrapht.Graph;

import java.util.*;
import java.util.Map.Entry;

public class AggregatorPattern extends TraversalPattern {

    Set<TraversalPattern> subs = new HashSet<>();

    @Override
    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        Set<String> nodesSubChapters = new HashSet<>();

        List<MappingGroup> mappingGroups = TraversalPattern.aggregateByMapping(graph, getMains(graph));

        Map<MappingGroup, Set<Node>> mappingGroupContexts = new HashMap<>();
        for (MappingGroup mg : mappingGroups) {
            Set<Node> contexts = new HashSet<>();

            for (Node target : mg.targets()) {
                List<Node> semanticContexts = target.getSemanticContexts(graph);
                if (semanticContexts.isEmpty()) {
                    continue;
                }

                Node semanticContext = semanticContexts.get(0);
                contexts.add(semanticContext);

                List<Node> contextSources = semanticContext.getMappingSources(graph);
                contexts.addAll(contextSources);
            }
            for (Node source : mg.sources()) {
                List<Node> semanticContexts = source.getSemanticContexts(graph);
                if (semanticContexts.isEmpty()) {
                    continue;
                }

                Node semanticContext = semanticContexts.get(0);
                contexts.add(semanticContext);

                List<Node> contextSources = semanticContext.getMappingTargets(graph);
                contexts.addAll(contextSources);
            }

            Set<Node> filteredContexts = contexts.stream()
                    .filter(ctx -> ctx.getSemanticContexts(graph).stream()
                            .noneMatch(sc -> sc != ctx && contexts.contains(sc)))
                    .collect(java.util.stream.Collectors.toSet());
            mappingGroupContexts.put(mg, filteredContexts);
        }

        List<Map.Entry<MappingGroup, Set<Node>>> sortedEntries = new ArrayList<>(mappingGroupContexts.entrySet());
        sortedEntries.sort((e1, e2) -> Integer.compare(e2.getValue().size(), e1.getValue().size()));

        List<MergedGroup> mergedGroups = new ArrayList<>();
        for (Map.Entry<MappingGroup, Set<Node>> entry : sortedEntries) {
            MappingGroup mg = entry.getKey();
            Set<Node> context = entry.getValue();

            boolean merged = false;
            for (MergedGroup mergedGroup : mergedGroups) {
                if (mergedGroup.context.containsAll(context)) {
                    mergedGroup.groups.add(mg);
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                MergedGroup newGroup = new MergedGroup(context);
                newGroup.groups.add(mg);
                mergedGroups.add(newGroup);
            }
        }

        for (MergedGroup mergedGroup : mergedGroups) {
            StringBuilder sb = new StringBuilder();
            sb.append("<SUB_CHAPTER>");

            Set<String> contextsText = new HashSet<>();
            for (Node context : mergedGroup.context) {
                contextsText.add(context.mappingXml(graph));
            }
            if (!contextsText.isEmpty()) {
                sb.append("\n    <CONTEXT>\n        ");
                sb.append(String.join("\n        ", contextsText.stream().map(contextText -> contextText.replace("\n", "\n        ")).toList()));
                sb.append("\n    </CONTEXT>");
            }

            for (MappingGroup mg : mergedGroup.groups) {
                sb.append("\n    ").append(buildXmlMappingHunk(mg.sources(), mg.targets(), graph).replace("\n", "\n    "));
            }

            sb.append("\n</SUB_CHAPTER>");
            nodesSubChapters.add(sb.toString());
        }

        return String.join("\n", nodesSubChapters);
    }

    private String buildXmlMappingHunk(List<Node> sources, List<Node> targets, Graph<Node, Edge> graph) {
        StringBuilder xmlOutput = new StringBuilder();

        xmlOutput.append("<CHANGE>");

        // TODO: ordering before and after in an interleaved manner?
        if (!sources.isEmpty()) {
            xmlOutput.append("\n    ");
            xmlOutput.append(String.join("\n    ", sources.stream().map(n -> n.baseXml(graph).replace("\n", "\n    ")).toList()));
        }
        if (!targets.isEmpty()) {
            xmlOutput.append("\n    ");
            xmlOutput.append(String.join("\n    ", targets.stream().map(n -> n.baseXml(graph).replace("\n", "\n    ")).toList()));
        }

        xmlOutput.append("\n</CHANGE>");

        return xmlOutput.toString();
    }

    @Override
    public List<Node> getMains(Graph<Node, Edge> graph) {
        List<TraversalPattern> leaves = this.getNarrator().getNarrative(GrainLevel.LEAF);
        Set<Node> superMainsOrdered = new LinkedHashSet<>();
        for (TraversalPattern leaf : leaves) {
            superMainsOrdered.addAll(leaf.getMains(graph));
        }

        Set<Node> subsMains = new HashSet<>();
        for (TraversalPattern sub : subs) {
            subsMains.addAll(sub.getMains(graph));
        }

        return superMainsOrdered.stream().filter(subsMains::contains).toList();
    }

    @Override
    public List<Node> getSides(Graph<Node, Edge> graph) {
        List<TraversalPattern> leaves = this.getNarrator().getNarrative(GrainLevel.LEAF);
        Set<Node> superSidesOrdered = new LinkedHashSet<>();
        for (TraversalPattern leaf : leaves) {
            superSidesOrdered.addAll(leaf.getSides(graph));
        }

        List<Node> mains = this.getMains(graph);
        return superSidesOrdered.stream().filter(side -> !mains.contains(side)).toList();
    }

    protected boolean containsNode(Node node, Set<TraversalPattern> visited) {
        if (!visited.add(this)) {
            return false;
        }

        boolean isRootNode = getGraph().vertexSet().stream().anyMatch(coreNode -> coreNode.equals(node));
        if (isRootNode) {
            return true;
        }

        for (TraversalPattern sub : subs) {
            if (sub instanceof AggregatorPattern) {
                if (((AggregatorPattern) sub).containsNode(node, visited)) {
                    return true;
                }
            } else {
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
                result.addAll(sub.vertexSet());
            }
        }
        return result;
    }

    // TODO: test and validate
    protected void breakCircularDependencies(List<AggregatorPattern> path) {
        List<AggregatorPattern> acceptableSubs = subs.stream().filter(sub -> sub instanceof AggregatorPattern).map(sub -> (AggregatorPattern) sub).toList();
        if (acceptableSubs.isEmpty()) {
            return;
        }

        List<AggregatorPattern> newPath = new ArrayList<>(path);
        newPath.add(this);

        if (this instanceof UsagePattern thisUsage) {
            List<AggregatorPattern> circularSubs = acceptableSubs.stream().filter(newPath::contains).toList();
            for (AggregatorPattern circularSub : circularSubs) {
                subs.remove(circularSub);

                List<Node> requirementNodes = thisUsage.getRequirements().entrySet().stream().filter(entry -> entry.getValue().equals(circularSub)).map(Entry::getKey).toList();
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

    private static class MergedGroup {
        Set<Node> context;
        List<MappingGroup> groups = new ArrayList<>();

        MergedGroup(Set<Node> context) {
            this.context = context;
        }
    }
}

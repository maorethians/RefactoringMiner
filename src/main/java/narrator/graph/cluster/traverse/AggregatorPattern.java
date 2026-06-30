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
//        Set<Node> nodesToFilter = new HashSet<>();
//        if (filterPatterns != null) {
//            for (TraversalPattern p : filterPatterns) {
//                nodesToFilter.addAll(p.getMains(graph));
//                nodesToFilter.addAll(p.getSides(graph));
//            }
//        }
//        nodesTracker = nodesTracker.stream().filter(node -> !nodesToFilter.contains(node)).collect(Collectors.toSet());

        // Group by Semantic Mapping
//    List<TraversalComponent> semanticLeaves = this.getNarrator()
//        .getNarrative(GrainLevel.SEMANTIC_LEAF).stream().filter(
//            chapter -> chapter instanceof TraversalComponent && Narrator.isSemanticLeaf(
//                (TraversalComponent) chapter)).map(chapter -> (TraversalComponent) chapter)
//        .filter(chapter -> chapter.getMergeContexts().size() > 1).toList();
//    for (TraversalComponent semanticLeaf : semanticLeaves) {
//      StringBuilder subChapter = new StringBuilder();
//
//      subChapter.append("<SUB_CHAPTER>\n");
//
//      subChapter.append("    <CONTEXT>\n        ").append(
//          semanticLeaf.getMergeContexts().iterator().next().mappingXml(graph)
//              .replace("\n", "\n        ")).append("\n    </CONTEXT>\n");
//
//      List<Node> leafMains = semanticLeaf.getMains(graph);
//      for (MappingGroup leafMappingGroup : TraversalPattern.aggregateByMapping(graph, leafMains)) {
//        subChapter.append("    ").append(
//            buildXmlMappingHunk(leafMappingGroup.group, leafMappingGroup.partners, graph).replace(
//                "\n", "\n    ")).append("\n");
//      }
//
//      List<Node> leafSides = semanticLeaf.getSides(graph);
//      Map<Boolean, List<Node>> sidesMainsExtensions = leafSides.stream()
//          .collect(Collectors.partitioningBy(n -> n.getNodeType().equals(NodeType.EXTENSION)));
//      // TODO: show their relations
////        List<Node> leafSidesMains = sidesMainsExtensions.get(false);
//      List<Node> leafSidesExtensions = sidesMainsExtensions.get(true);
//      if (!leafSidesExtensions.isEmpty()) {
//        subChapter.append("    <DEPENDENCIES>\n        ").append(String.join("\n        ",
//            leafSidesExtensions.stream().map(e -> e.baseXml(graph).replace("\n", "\n        "))
//                .toList())).append("\n    </DEPENDENCIES>").append("\n");
//      }
//      subChapter.append("</SUB_CHAPTER>\n");
//      allNodes = allNodes.stream().filter(n -> !leafMains.contains(n))
//          .collect(Collectors.toSet());
//    }
//
//    if (!allNodes.isEmpty()) {
//      return xmlOutput.toString();
//    }

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

            mappingGroupContexts.put(mg, contexts);
        }

        Map<Set<Node>, List<MappingGroup>> groupsByContext = new HashMap<>();
        for (Map.Entry<MappingGroup, Set<Node>> entry : mappingGroupContexts.entrySet()) {
            groupsByContext.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        for (Entry<Set<Node>, List<MappingGroup>> contextsGroups : groupsByContext.entrySet()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<SUB_CHAPTER>");

            Set<String> contextsText = new HashSet<>();
            for (Node context : contextsGroups.getKey()) {
                contextsText.add(context.mappingXml(graph));
            }
            if (!contextsText.isEmpty()) {
                sb.append("\n    <CONTEXT>\n        ");
                sb.append(String.join("\n        ", contextsText.stream().map(contextText -> contextText.replace("\n", "\n        ")).toList()));
                sb.append("\n    </CONTEXT>");
            }

            List<MappingGroup> groups = contextsGroups.getValue();
            for (MappingGroup mg : groups) {
                sb.append("\n    ").append(buildXmlMappingHunk(mg.sources(), mg.targets(), graph).replace("\n", "\n    ")).append("\n");
            }

            sb.append("</SUB_CHAPTER>");
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

}

package narrator.graph.cluster.traverse;

import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.NodeType;
import org.jgrapht.Graph;

import java.util.*;
import java.util.Map.Entry;

public class AggregatorPattern extends TraversalPattern {

    Set<TraversalPattern> subs = new HashSet<>();

    public List<NarrativeElement> getElements(Graph<Node, Edge> graph, List<TraversalPattern> filterPatterns) {
        List<Node> aggMains = getMains(graph);

        // Merge Mains by Mapping
        List<MappingGroup> mappingGroups = TraversalPattern.aggregateByMapping(graph, aggMains);

        // Get Group Contexts
        Map<MappingGroup, Set<Node>> mappingGroupContexts = new HashMap<>();
        for (MappingGroup mg : mappingGroups) {
            Set<Node> contexts = new HashSet<>();
            for (Node target : mg.targets()) {
                List<Node> semanticContexts = target.getSemanticContexts(graph);
                if (!semanticContexts.isEmpty()) {
                    Node semanticContext = semanticContexts.get(0);
                    contexts.add(semanticContext);
                    contexts.addAll(semanticContext.getMappingSources(graph));
                }
            }
            for (Node source : mg.sources()) {
                List<Node> semanticContexts = source.getSemanticContexts(graph);
                if (!semanticContexts.isEmpty()) {
                    Node semanticContext = semanticContexts.get(0);
                    contexts.add(semanticContext);
                    contexts.addAll(semanticContext.getMappingTargets(graph));
                }
            }
            mappingGroupContexts.put(mg, filterLargest(contexts, graph));
        }

        // Merge Groups by Context
        List<MergedGroup> mergedGroups = new ArrayList<>();
        for (Map.Entry<MappingGroup, Set<Node>> entry : mappingGroupContexts.entrySet()) {
            MappingGroup mg = entry.getKey();
            Set<Node> context = entry.getValue();
            boolean merged = false;
            for (MergedGroup mergedGroup : mergedGroups) {
                if (isCompatible(context, mergedGroup.context, graph)) {
                    mergedGroup.groups.add(mg);
                    Set<Node> union = new HashSet<>(mergedGroup.context);
                    union.addAll(context);
                    mergedGroup.context = filterLargest(union, graph);
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

        List<TraversalPattern> leaves = this.getNarrator().getNarrative(GrainLevel.LEAF);

        // Side to Mains Mappings
        Set<Node> allMains = new HashSet<>(aggMains);
        if (filterPatterns != null) {
            for (TraversalPattern fp : filterPatterns) {
                allMains.addAll(fp.getMains(graph));
            }
        }
        Map<Node, Set<Node>> sideToMains = new HashMap<>();
        for (TraversalPattern leaf : leaves) {
            List<Node> leafMains = leaf.getMains(graph);
            List<Node> leafSides = leaf.getSides(graph);
            for (Node side : leafSides) {
                if ((side.isContext() && !side.getNodeType().equals(NodeType.SEMANTIC_CONTEXT)) || allMains.contains(side)) {
                    continue;
                }

                Set<Node> relyingMains = sideToMains.computeIfAbsent(side, k -> new HashSet<>());
                for (Node m : leafMains) {
                    if (allMains.contains(m)) {
                        relyingMains.add(m);
                    }
                }
            }
        }

        // Main to Group Mappings (Each Main Maps to Only 1 Group)
        Map<Node, MergedGroup> mainToGroup = new HashMap<>();
        for (MergedGroup mg : mergedGroups) {
            for (MappingGroup group : mg.groups) {
                for (Node n : group.sources()) mainToGroup.put(n, mg);
                for (Node n : group.targets()) mainToGroup.put(n, mg);
            }
        }

        // TODO: Sort Must be Done By Dependency, Not Latest Index
        Map<MergedGroup, Integer> groupLatestIndex = new HashMap<>();
        for (MergedGroup mg : mergedGroups) {
            int maxIdx = -1;
            for (MappingGroup group : mg.groups) {
                for (Node n : group.sources()) {
                    for (int i = leaves.size() - 1; i >= 0; i--) {
                        if (leaves.get(i).getMains(graph).contains(n)) {
                            maxIdx = Math.max(maxIdx, i);
                        }
                    }
                }
                for (Node n : group.targets()) {
                    for (int i = leaves.size() - 1; i >= 0; i--) {
                        if (leaves.get(i).getMains(graph).contains(n)) {
                            maxIdx = Math.max(maxIdx, i);
                        }
                    }
                }
            }
            groupLatestIndex.put(mg, maxIdx);
        }

        // Identify Group Dependencies
        Set<Node> globalSides = new HashSet<>();
        Map<MergedGroup, List<Node>> localSidesMap = new HashMap<>();
        for (Map.Entry<Node, Set<Node>> entry : sideToMains.entrySet()) {
            Node side = entry.getKey();
            Set<Node> mains = entry.getValue();
            Set<MergedGroup> chapters = new HashSet<>();
            for (Node m : mains) {
                MergedGroup mg = mainToGroup.get(m);
                if (mg != null) chapters.add(mg);
            }

            if (chapters.size() > 1) {
                globalSides.add(side);
            } else if (chapters.size() == 1) {
                MergedGroup mg = chapters.iterator().next();
                localSidesMap.computeIfAbsent(mg, k -> new ArrayList<>()).add(side);
            }
        }

        // Produce Elements
        List<NarrativeElement> elements = new ArrayList<>();
        Set<Node> outputtedSides = new HashSet<>();
        Set<MergedGroup> outputtedGroups = new HashSet<>();
        for (int i = 0; i < leaves.size(); i++) {
            TraversalPattern leaf = leaves.get(i);
            for (Node s : leaf.getSides(graph)) {
                if (globalSides.contains(s) && !outputtedSides.contains(s)) {
                    String content = "<DEPENDENCY>\n    " + s.baseXml(graph).replace("\n", "\n    ") + "\n</DEPENDENCY>";
                    elements.add(new NarrativeElement(content, content.split("\n").length, NarrativeElement.ElementType.DEPENDENCY));
                    outputtedSides.add(s);
                }
            }
            for (MergedGroup mg : mergedGroups) {
                if (groupLatestIndex.get(mg) == i && !outputtedGroups.contains(mg)) {
                    String content = buildSubChapterXml(mg, graph, leaves, localSidesMap);
                    elements.add(new NarrativeElement(content, content.split("\n").length, NarrativeElement.ElementType.SUB_CHAPTER));
                    outputtedGroups.add(mg);
                }
            }
        }

        return elements;
    }

    @Override
    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        return String.join("\n", getElements(graph, filterPatterns).stream().map(NarrativeElement::content).toList());
    }

    private String buildSubChapterXml(MergedGroup mergedGroup, Graph<Node, Edge> graph, List<TraversalPattern> leaves, Map<MergedGroup, List<Node>> localSidesMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("<SUB_CHAPTER>");

        for (MappingGroup mg : mergedGroup.groups) {
            sb.append("\n    ").append(buildXmlMappingHunk(mg.sources(), mg.targets(), graph).replace("\n", "\n    "));
        }

        if (!mergedGroup.context.isEmpty()) {
            sb.append("\n    <CONTEXT>");
            Set<String> contextsText = new HashSet<>();
            for (Node context : mergedGroup.context) {
                contextsText.add(context.mappingXml(graph));
            }
            sb.append("\n        ").append(String.join("\n        ", contextsText.stream().map(text -> text.replace("\n", "\n        ")).toList()));
            sb.append("\n    </CONTEXT>");
        }

        List<Node> localSides = localSidesMap.getOrDefault(mergedGroup, List.of());
        if (!localSides.isEmpty()) {
            sb.append("\n    <DEPENDENCIES>");
            for (Node side : localSides) {
                sb.append("\n        ").append(side.baseXml(graph).replace("\n", "\n        "));
            }
            sb.append("\n    </DEPENDENCIES>");
        }

        sb.append("\n</SUB_CHAPTER>");
        return sb.toString();
    }

    private String buildXmlMappingHunk(List<Node> sources, List<Node> targets, Graph<Node, Edge> graph) {
        StringBuilder xmlOutput = new StringBuilder();
        xmlOutput.append("<CHANGE>");
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

    private boolean isCompatible(Set<Node> setA, Set<Node> setB, Graph<Node, Edge> graph) {
        if (setA.isEmpty() || setB.isEmpty()) return false;

        boolean aCoveredByB = true;
        for (Node a : setA) {
            boolean matched = false;
            for (Node b : setB) {
                if (a.equals(b) || a.getSemanticContexts(graph).contains(b) || b.getSemanticContexts(graph).contains(a)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                aCoveredByB = false;
                break;
            }
        }

        boolean bCoveredByA = true;
        for (Node b : setB) {
            boolean matched = false;
            for (Node a : setA) {
                if (a.equals(b) || a.getSemanticContexts(graph).contains(b) || b.getSemanticContexts(graph).contains(a)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                bCoveredByA = false;
                break;
            }
        }

        return aCoveredByB || bCoveredByA;
    }

    private Set<Node> filterLargest(Set<Node> contexts, Graph<Node, Edge> graph) {
        return contexts.stream().filter(ctx -> ctx.getSemanticContexts(graph).stream().noneMatch(sc -> sc != ctx && contexts.contains(sc))).collect(java.util.stream.Collectors.toSet());
    }

    private static class MergedGroup {
        Set<Node> context;
        List<MappingGroup> groups = new ArrayList<>();

        MergedGroup(Set<Node> context) {
            this.context = context;
        }
    }
}

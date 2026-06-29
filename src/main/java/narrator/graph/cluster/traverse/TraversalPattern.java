package narrator.graph.cluster.traverse;

import com.github.gumtreediff.tree.Tree;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.NodeType;
import narrator.graph.SrcDst;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.GraphWrapper;
import org.jgrapht.Graph;

public class TraversalPattern extends GraphWrapper {
    public static class MappingGroup {
        public final List<Node> group;
        public final List<Node> partners;

        public MappingGroup(List<Node> group, List<Node> partners) {
            this.group = group;
            this.partners = partners;
        }
    }

    public static List<MappingGroup> aggregateByMapping(Graph<Node, Edge> graph, Collection<Node> nodes) {
        Map<HashSet<Node>, LinkedHashSet<Node>> partnerMap = new HashMap<>();

        for (Node n : nodes) {
            List<Node> partners = (n.getSrcDst() == SrcDst.SRC) ? n.getMappingTargets(graph) : n.getMappingSources(graph);
            HashSet<Node> partnersSet = new HashSet<>(partners);
            if (!partnerMap.containsKey(partnersSet)) {
                partnerMap.put(partnersSet, new LinkedHashSet<>());
            }
            partnerMap.get(partnersSet).add(n);
        }

        List<MappingGroup> result = new ArrayList<>();
        for (Entry<HashSet<Node>, LinkedHashSet<Node>> partnerGroup : partnerMap.entrySet()) {
            LinkedHashSet<Node> group = partnerGroup.getValue();
            HashSet<Node> partners = partnerGroup.getKey();
            if (partners.isEmpty()) {
                for (Node node : group) {
                    ArrayList<Node> singleGroup = new ArrayList<>();
                    singleGroup.add(node);
                    result.add(new MappingGroup(singleGroup, new ArrayList<>()));
                }
                continue;
            }

            result.add(new MappingGroup(new ArrayList<>(group), new ArrayList<>(partners)));
        }

        return result;
    }

    private final Narrator narrator = new Narrator(this);

    public Narrator getNarrator() {
        return narrator;
    }

    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        return "";
    }

    protected final Util util = new Util(getGraph());
    protected final Set<String> identifiers = new HashSet<>();
    protected Node cachedLead = null;
    protected NodeType nodeType;
    private List<TraversalPattern> cachedFlatten = null;
    private Map<TraversalPattern, Boolean> dependsOnCache = new HashMap<>();

    public Node getLead() {
        if (cachedLead == null) {
            cachedLead = getGraph().vertexSet().iterator().next();
        }

        return cachedLead;
    }

    public String getId() {
        Tree tree = getLead().getTree();
        return getClass().getSimpleName() + "-" + tree.getPos() + '-' + tree.getEndPos() + '-'
                + System.identityHashCode(this);
    }

    public JsonObject stringify() {
        JsonObject nodeObj = new JsonObject();

        nodeObj.addProperty("id", getId());
        nodeObj.addProperty("nodeType", nodeType.name());

        if (!identifiers.isEmpty()) {
            JsonArray identifiersArr = new JsonArray();
            for (String identifier : identifiers) {
                identifiersArr.add(identifier);
            }

            nodeObj.add("identifiers", identifiersArr);
        }

        return nodeObj;
    }

    public Set<Node> vertexSet() {
        return getGraph().vertexSet();
    }

    public int getDepth() {
        if (this instanceof AggregatorPattern aggregator) {
            return aggregator.subs.stream()
                    .mapToInt(TraversalPattern::getDepth)
                    .max()
                    .orElse(0) + 1;
        }
        return 0;
    }

    public void addIdentifier(String identifier) {
        this.identifiers.add(identifier);
    }

    public List<Node> getMains(Graph<Node, Edge> graph) {
        return List.of(getLead());
    }

    public List<Node> getSides(Graph<Node, Edge> graph) {
        return List.of();
    }

    public boolean dependsOn(TraversalPattern p) {
        if (dependsOnCache.containsKey(p)) {
            return dependsOnCache.get(p);
        }

        List<TraversalPattern> pNarrative = p.flatten();
        boolean result = false;
        for (TraversalPattern thisP : this.flatten()) {
            if (thisP instanceof UsagePattern usage) {
                for (TraversalPattern sub : usage.subs) {
                    if (pNarrative.contains(sub)) {
                        result = true;
                        break;
                    }
                }
            }
            if (result) break;
        }
        dependsOnCache.put(p, result);
        return result;
    }


    private List<TraversalPattern> flatten() {
        if (cachedFlatten == null) {
            List<TraversalPattern> result = new ArrayList<>();
            Set<TraversalPattern> visited = new HashSet<>();
            flattenRecursive(this, visited, result);
            cachedFlatten = List.copyOf(result);
        }
        return cachedFlatten;
    }

    private void flattenRecursive(TraversalPattern p, Set<TraversalPattern> visited, List<TraversalPattern> result) {
        if (visited.contains(p)) return;
        visited.add(p);

        if (p instanceof AggregatorPattern agg) {
            for (TraversalPattern sub : agg.subs) {
                flattenRecursive(sub, visited, result);
            }
        }

        result.add(p);
    }
}

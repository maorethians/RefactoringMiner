package narrator.graph.cluster.traverse;

import com.github.gumtreediff.tree.Tree;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import narrator.graph.Node;
import narrator.graph.NodeType;
import narrator.graph.cluster.Cluster;
import narrator.graph.cluster.GraphWrapper;

public class TraversalPattern extends GraphWrapper {
    private final Narrator narrator = new Narrator(this);

    public Narrator getNarrator() {
        return narrator;
    }

    public String extended(Cluster cluster, GrainLevel level) {
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

    public List<Node> getMains(Cluster cluster) {
        return List.of(getLead());
    }

    public List<Node> getSides(Cluster cluster) {
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

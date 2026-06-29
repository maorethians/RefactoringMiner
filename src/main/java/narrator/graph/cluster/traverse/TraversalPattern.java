package narrator.graph.cluster.traverse;

import com.github.gumtreediff.tree.Tree;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import narrator.graph.Edge;
import narrator.graph.Node;
import narrator.graph.NodeType;
import narrator.graph.cluster.GraphWrapper;
import org.jgrapht.Graph;

import java.util.*;

public class TraversalPattern extends GraphWrapper {
    protected final Util util = new Util(getGraph());
    protected final Set<String> identifiers = new HashSet<>();
    private final Narrator narrator = new Narrator(this);
    private final Map<TraversalPattern, Boolean> dependsOnCache = new HashMap<>();
    protected Node cachedLead = null;
    protected NodeType nodeType;
    private List<TraversalPattern> cachedFlatten = null;

    public static List<MappingGroup> aggregateByMapping(Graph<Node, Edge> graph, Collection<Node> nodes) {
        Set<Node> visited = new HashSet<>();
        List<MappingGroup> result = new ArrayList<>();

        for (Node node : nodes) {
            if (visited.contains(node)) continue;

            Set<Node> component = new HashSet<>();
            Queue<Node> queue = new LinkedList<>();
            queue.add(node);
            visited.add(node);

            while (!queue.isEmpty()) {
                Node curr = queue.poll();
                component.add(curr);

                for (Node src : curr.getMappingSources(graph)) {
                    if (!visited.contains(src)) {
                        visited.add(src);
                        queue.add(src);
                    }
                }
                for (Node dst : curr.getMappingTargets(graph)) {
                    if (!visited.contains(dst)) {
                        visited.add(dst);
                        queue.add(dst);
                    }
                }
            }

            List<Node> srcNodes = new ArrayList<>();
            List<Node> dstNodes = new ArrayList<>();
            for (Node n : component) {
                if (n.isSrc()) srcNodes.add(n);
                else dstNodes.add(n);
            }

            Comparator<Node> nodeComparator = Comparator.comparing(Node::getPath)
                    .thenComparingInt(n -> n.getTree().getPos());
            srcNodes.sort(nodeComparator);
            dstNodes.sort(nodeComparator);


            Set<Node> inputSet = new HashSet<>(nodes);
            List<Node> srcInInput = srcNodes.stream().filter(inputSet::contains).toList();
            List<Node> dstInInput = dstNodes.stream().filter(inputSet::contains).toList();

            if (!srcInInput.isEmpty()) {
                result.add(new MappingGroup(srcNodes, dstNodes));
            } else if (!dstInInput.isEmpty()) {
                result.add(new MappingGroup(dstNodes, srcNodes));
            }
        }
        return result;
    }

    public Narrator getNarrator() {
        return narrator;
    }

    public String extended(Graph<Node, Edge> graph, GrainLevel level, List<TraversalPattern> filterPatterns) {
        return "";
    }

    public Node getLead() {
        if (cachedLead == null) {
            cachedLead = getGraph().vertexSet().iterator().next();
        }

        return cachedLead;
    }

    public String getId() {
        Tree tree = getLead().getTree();
        return getClass().getSimpleName() + "-" + tree.getPos() + '-' + tree.getEndPos() + '-' + System.identityHashCode(this);
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
            return aggregator.subs.stream().mapToInt(TraversalPattern::getDepth).max().orElse(0) + 1;
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

    public record MappingGroup(List<Node> group, List<Node> partners) {
    }
}

package narrator.langchain;

import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;

public class NarrativeRequest {
    private final String url;
    private final GrainLevel grainLevel;

    public NarrativeRequest(String url, GrainLevel grainLevel) {
        this.url = url;
        this.grainLevel = grainLevel;
    }

    public String getUrl() {
        return url;
    }

    public GrainLevel getGrainLevel() {
        return grainLevel;
    }
}

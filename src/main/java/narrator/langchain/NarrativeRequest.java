package narrator.langchain;

import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;

public class NarrativeRequest {
    private final String url;
    private final GrainLevel grainLevel;
    private final String task;

    public NarrativeRequest(String url, GrainLevel grainLevel, String task) {
        this.url = url;
        this.grainLevel = grainLevel;
        this.task = task;
    }

    public String getUrl() {
        return url;
    }

    public GrainLevel getGrainLevel() {
        return grainLevel;
    }

    public String getTask() {
        return task;
    }
}

package narrator.langchain;

public class NarrativeState {
    private final String understanding;

    public NarrativeState(String understanding) {
        this.understanding = understanding;
    }

    public String getUnderstanding() {
        return understanding;
    }

    public static NarrativeState empty() {
        return new NarrativeState("No understanding yet.");
    }
}

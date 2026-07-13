package narrator.langchain;

public class NarrativeResponse {
    private final String finalResult;

    public NarrativeResponse(String finalResult) {
        this.finalResult = finalResult;
    }

    public String getFinalResult() {
        return finalResult;
    }
}

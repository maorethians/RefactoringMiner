package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;

import java.util.List;

public class NarrativeRunner {
    private static final GrainLevel DEFAULT_LEVEL = GrainLevel.SINGLE;

    public static void main(String[] args) throws Exception {
        NarrativeProcessor.NarrativeProcessResult response = run("https://github.com/spring-projects/spring-boot/commit/f28caee30d2ea48a0b19405dc604090e4b2f9b3d");
        System.out.println(response.content());
    }

    public static NarrativeProcessor.NarrativeProcessResult run(String url) throws Exception {
        NarrativeService narrativeService = new NarrativeService();
        NarrativeProcessor processor = new NarrativeProcessor(narrativeService);
        return processor.process(new NarrativeRequest(url, DEFAULT_LEVEL));
    }
}
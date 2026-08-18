package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;

import java.util.List;

public class NarrativeRunner {
    private static final GrainLevel DEFAULT_LEVEL = GrainLevel.FILE;

    public static void main(String[] args) {
        System.out.println(run("https://github.com/spring-projects/spring-boot/commit/f28caee30d2ea48a0b19405dc604090e4b2f9b3d"));
    }

    public static String run(String url) {
        try {
            NarrativeService narrativeService = new NarrativeService();
            NarrativeProcessor processor = new NarrativeProcessor(narrativeService);
            List<ReviewPrompt.ReviewComment> response = processor.process(new NarrativeRequest(url, DEFAULT_LEVEL));
            return String.join("\n\n", response.stream()
                    .map(res -> String.join(", ", res.hunkIds()) + ": " + res.text()).toList());
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }
}
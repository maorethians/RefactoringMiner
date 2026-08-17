package narrator.langchain;

import narrator.service.NarrativeService;
import org.refactoringminer.astDiff.graph.cluster.traverse.GrainLevel;

public class NarrativeRunner {
    private static final GrainLevel DEFAULT_LEVEL = GrainLevel.FILE;

    public static void main(String[] args) {
        System.out.println(run("https://github.com/TeamNewPipe/NewPipe/pull/10018"));
    }

    public static String run(String url) {
        try {
            NarrativeService narrativeService = new NarrativeService();
            NarrativeProcessor processor = new NarrativeProcessor(narrativeService);
            NarrativeResponse response = processor.process(new NarrativeRequest(url, DEFAULT_LEVEL));
            return response.getFinalResult();
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }
}
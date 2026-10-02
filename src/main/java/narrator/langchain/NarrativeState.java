package narrator.langchain;

import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.*;

public class NarrativeState {
    // chapters are ordered by insertion
    private final Map<Narrator.ChapterUnit, String> chapterResult = new LinkedHashMap<>();

    public void setResult(Narrator.ChapterUnit chapter, String result) {
        chapterResult.put(chapter, result);
    }

    public List<String> getResults() {
        return chapterResult.values().stream().toList();
    }
}

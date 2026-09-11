package narrator.langchain;

import narrator.langchain.prompt.ReviewPrompt;
import org.refactoringminer.astDiff.graph.cluster.traverse.Narrator;

import java.util.*;

public class NarrativeState {
    // chapters are ordered by insertion
    private final Map<Narrator.ChapterUnit, List<ReviewPrompt.Identifier>> chapterIdentifiers = new LinkedHashMap<>();
    private final Map<Narrator.ChapterUnit, String> chapterResult = new LinkedHashMap<>();

    public void setIdentifiers(Narrator.ChapterUnit chapter, List<ReviewPrompt.Identifier> identifiers) {
        chapterIdentifiers.put(chapter, identifiers);
    }

    public void setResult(Narrator.ChapterUnit chapter, String result) {
        chapterResult.put(chapter, result);
    }

    public boolean hasIdentifiers(Narrator.ChapterUnit chapter) {
        return chapterIdentifiers.containsKey(chapter);
    }

    public List<ReviewPrompt.Identifier> getIdentifiers(Narrator.ChapterUnit chapter) {
        return chapterIdentifiers.get(chapter);
    }

    public List<String> getResults() {
        return chapterResult.values().stream().toList();
    }
}

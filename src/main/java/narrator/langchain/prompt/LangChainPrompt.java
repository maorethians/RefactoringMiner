package narrator.langchain.prompt;

import java.util.List;

public interface LangChainPrompt {
  String chapter(String content, List<String> understandings);
  String understanding(List<StringIndex> understandings);
  String result(List<StringIndex> results, String understanding, boolean isFinal);

  record StringIndex(String str, int index) {}
}

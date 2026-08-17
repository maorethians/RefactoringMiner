package narrator.langchain.prompt;

import java.util.List;
import java.util.Set;

public interface LangChainPrompt {
  String chapter(String content, List<String> understandings);
  String understanding(List<String> understandings);
  String result(List<String> results, String understanding);
}

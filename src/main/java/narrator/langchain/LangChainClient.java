package narrator.langchain;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import narrator.langchain.prompt.ReviewPrompt;
import org.refactoringminer.astDiff.graph.cluster.traverse.Splitter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class LangChainClient {
    private final ChatLanguageModel model;
    private final ReviewPrompt prompt;
    private static final double temperature = 0.0;

    public LangChainClient(ChatLanguageModel model) {
        this.model = model;
        this.prompt = new ReviewPrompt();
    }

    public static LangChainClient create() {
        String provider = "ollama";
        String apiKey = "";
        String modelName = "qwen2.5-coder:32b";
        String baseUrl = "http://localhost:11435";

        ChatLanguageModel model;
        if ("anthropic".equalsIgnoreCase(provider)) {
            model = AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("openai".equalsIgnoreCase(provider)) {
            model = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else if ("ollama".equalsIgnoreCase(provider)) {
            model = OllamaChatModel.builder()
                    .baseUrl(baseUrl != null ? baseUrl : "http://localhost:11434")
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(999)) // Increase timeout for local large models
                    .temperature(temperature)
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return model.generate(prompt);
    }

    public ReviewPrompt.ParsedResponse processChapter(String content, List<String> understandings) {
        String understanding = generate(this.prompt.chapterUnderstanding(content, understandings));
        String result = generate(this.prompt.chapterResult(content, understanding));
        return new ReviewPrompt.ParsedResponse(understanding, result);
    }

    public List<ReviewPrompt.ReviewComment> compileResults(List<String> results, List<String> understandings) throws Exception {
        List<ReviewPrompt.ReviewComment> finalResult = new ArrayList<>();

        String aggregatedUnderstanding = null;
        List<List<Integer>> splits = Splitter.createBalancedSplits(understandings);
        for (int i = 0; i < splits.size(); i++) {
            List<Integer> split = splits.get(i);

            List<ReviewPrompt.StringIndex> splitUnderstandings = new ArrayList<>();
            if (aggregatedUnderstanding != null) {
                splitUnderstandings.add(new ReviewPrompt.StringIndex(aggregatedUnderstanding, split.get(0) - 1));
            }
            splitUnderstandings.addAll(split.stream()
                    .map(index -> new ReviewPrompt.StringIndex(understandings.get(index), index)).toList());
            aggregatedUnderstanding = model.generate(this.prompt.understanding(splitUnderstandings));

            List<ReviewPrompt.ReviewComment> parsedSplitResult = null;
            while (parsedSplitResult == null) {
                System.out.println("Synthesizing final result part " + (i + 1) + " of " + splits.size());
                String splitResult = model.generate(this.prompt.result(split.stream().map(results::get).toList(), aggregatedUnderstanding));
                parsedSplitResult = ReviewPrompt.parseResult(splitResult);
            }

            finalResult.addAll(parsedSplitResult);
        }

        return finalResult;
    }
}

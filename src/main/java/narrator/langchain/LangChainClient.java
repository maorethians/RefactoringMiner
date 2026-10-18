package narrator.langchain;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import narrator.langchain.prompt.ReviewPrompt;
import org.refactoringminer.astDiff.graph.cluster.traverse.Splitter;

import java.io.InterruptedIOException;
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
                    .timeout(Duration.ofSeconds(400)) // Increase timeout for local large models
                    .temperature(temperature)
                    .maxRetries(1) // A timed-out request is not resent
                    .build();
        } else {
            throw new IllegalArgumentException("Unsupported provider: " + provider);
        }
        return new LangChainClient(model);
    }

    public String generate(String prompt) {
        return call(prompt);
    }

    private String call(String prompt) {
        try {
            return model.generate(prompt);
        } catch (RuntimeException e) {
            if (e.getCause() instanceof InterruptedIOException) {
                throw new RequestTimeoutException(e.getCause().getMessage());
            }
            throw e;
        }
    }

    public String reviewChapter(String content, boolean rawDiff) {
        System.out.println("result");
        String result = call(this.prompt.chapterResult(content, rawDiff));
        return result.replace(ReviewPrompt.END_OF_AUDIT, "").trim();
    }

    public List<ReviewPrompt.ReviewComment> compileResults(List<String> results) throws SynthesisFailedException {
        List<ReviewPrompt.ReviewComment> finalResult = new ArrayList<>();

        List<List<Integer>> splits = Splitter.createBalancedSplits(results);
        for (int i = 0; i < splits.size(); i++) {
            List<Integer> split = splits.get(i);

            System.out.println("Synthesizing final result part " + (i + 1) + " of " + splits.size());
            String splitResult = call(this.prompt.result(split.stream().map(results::get).toList()));
            List<ReviewPrompt.ReviewComment> parsedSplitResult = ReviewPrompt.parseResult(splitResult);
            if (parsedSplitResult == null) {
                throw new SynthesisFailedException("Failed to parse final result part " + (i + 1) + " of " + splits.size());
            }

            finalResult.addAll(parsedSplitResult);
        }

        return finalResult;
    }

    public static class SynthesisFailedException extends Exception {
        public SynthesisFailedException(String message) {
            super(message);
        }
    }

    public static class RequestTimeoutException extends RuntimeException {
        public RequestTimeoutException(String message) {
            super(message);
        }
    }
}

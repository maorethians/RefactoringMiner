package narrator.langchain.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.NotNull;
import org.refactoringminer.astDiff.graph.Node;

public class ReviewPrompt {
  // This is necessary for terminating the agent and preventing it from falling in a loop
  public static String END_OF_AUDIT = "### END OF AUDIT";
  public static String END_OF_ENTRIES = "### END OF ENTRIES";

  private static final Pattern ALPHANUMERIC_PATTERN = Pattern.compile("[\\p{Alnum}]");

  private static final Pattern OPTIONAL_PREFIX_ID_PATTERN = Pattern.compile(
          "(?<![\\p{Alnum}#])" + Pattern.quote(Node.PROMPT_ID_PREFIX) + "?" + Node.PROMPT_ID_BODY_REGEX);

  private static final List<String> IDENTIFIER_FIELDS = List.of("IDENTIFIER", "KIND", "BEFORE", "AFTER", "CHANGE");

  private static final Pattern IDENTIFIER_FIELD_PATTERN = Pattern.compile(
          "^[\\s\\-*]*(" + String.join("|", IDENTIFIER_FIELDS) + ")\\**\\s*:\\s*(.*)$", Pattern.CASE_INSENSITIVE);

  private String specification(boolean rawDiff) {
    return rawDiff ? rawDiffSpecification() : chapterSpecification();
  }

  private String rawDiffSpecification() {
    StringBuilder spec = new StringBuilder();

    spec.append("### CHANGE REPRESENTATION\n")
            .append("The chapter is a set of single diffs taken from the unified diff of the two revisions. ")
            .append("Each diff is one hunk: a contiguous region of one file that changed, together with the surrounding lines the diff carries for context.\n")
            .append("- Every <diff> element holds exactly one hunk, verbatim, starting with its `@@ -<src>,<len> +<dst>,<len> @@` header, ")
            .append("which gives the line numbers the hunk covers in each revision.\n")
            .append("- Inside a hunk, a line starting with `-` was removed from the source revision, a line starting with `+` was added in the destination revision, ")
            .append("and a line starting with a space is unchanged context. Context is orientation, not additional change.\n")
            .append("- Each <diff> carries file=\"<path>\", the file it belongs to, which the hunk itself does not name, ")
            .append("and id=\"#XXXXX\", which identifies it uniquely within the pull request.\n")
            .append("The diffs, and the chapters that group them, appear in the order the unified diff lists them.\n\n");

    return spec.toString();
  }

  private String chapterSpecification() {
    StringBuilder spec = new StringBuilder();

    spec.append("### CHANGE REPRESENTATION\n")
            .append("The chapter is not a textual diff. It is a structured view derived from an AST comparison of the two revisions, ")
            .append("in which related edits have already been grouped.\n")
            .append("- <sub_chapter> groups edits that share an enclosing construct; it is one coherent unit of work.\n")
            .append("- <change> holds one edit. A before_* element paired with an after_* element is the same code before and after that edit—one change, not two. ")
            .append("A lone <added> or <deleted> element is an insertion or a removal with no counterpart.\n")
            .append("- Element tags name the operation: <added>, <deleted>, <unchanged>, and the paired forms before_change/after_change (edited in place), ")
            .append("before_move/after_move (relocated intact), and before_move_and_change/after_move_and_change (both). ")
            .append("- <context> shows the enclosing construct before and after the edit. It restates, in situ, the same code that the <change> elements isolate. ")
            .append("It is orientation, not additional change.\n")
            .append("- <dependencies> and <dependency> contain code that the edits in this chapter depend on, supplied so that identifiers resolve.\n")
            .append("- Every element carries location=\"<file>::<Type>#<member>\", the construct enclosing it, and id=\"#XXXXX\", which identifies it uniquely within the pull request.\n")
            .append("Edits are captured at expression and statement granularity, so a single logical change is routinely spread across several <change> blocks inside one <sub_chapter>. ")
            .append("The blocks carry no line numbers, and the order in which they appear is not the order of the code in the file; the code inside <context> is where the real sequence is visible.\n\n");

    return spec.toString();
  }

  public String chapterIdentifiers(String content, boolean rawDiff) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a Software Engineer building a symbol-level index of a code change. ")
            .append(rawDiff ? "The changes in a pull request have been decomposed into a sequence of chapters. "
                    : "The changes in a pull request have been decomposed into a sequence of chapters, ordered by their dependency graph. ")
            .append("Later chapters reference the identifiers this chapter changes, but never see its code. ")
            .append("Your index stands in for that code: for every identifier it must say what that identifier was before this chapter, what it is after, and how it changed.\n\n");

    prompt.append(specification(rawDiff));

    prompt.append("### CURRENT CHAPTER\n")
            .append("The changes that make up the current chapter, in the representation described above. Every entry you write comes from here:\n")
            .append(content).append("\n\n");

    prompt.append("### YOUR TASK\n")
            .append("Index every identifier this chapter changes—types, methods, fields, parameters, and variables. ")
            .append("An identifier is changed when this chapter introduces it, removes it, moves it, or alters what it does or what it offers. ")
            .append("Altered behaviour counts even when the declaration itself is untouched. An identifier the chapter only mentions is not changed and gets no entry.\n")
            .append("Read the change blocks one by one, name the identifier each one changes, and write that identifier's entry.\n\n");

    prompt.append("### OUTPUT FORMAT\n")
            .append("Write each entry as exactly these five lines, in this order:\n")
            .append("IDENTIFIER: the name, alone\n")
            .append("KIND: type, method, field, parameter, or variable\n")
            .append("BEFORE: what it was and what it offered before this chapter, or `did not exist`\n")
            .append("AFTER: what it is and what it offers after this chapter, or `removed`\n")
            .append("CHANGE: the transition between the two—introduced, removed, renamed (give both names), moved (give where from and where to), or what specifically differs\n")
            .append("For a method, BEFORE and AFTER give what it accepts, what it returns, and what it does to state outside itself; ")
            .append("for a field or variable, what it holds and what a reader of it gets; for a type, what it models and what it exposes.\n")
            .append("Keep each field on one line. Separate entries with a blank line. Write nothing else: no headings, no numbering, no commentary.\n\n");

    prompt.append("Rules:\n")
            .append("- Write the specification, not the code. Later chapters read this in place of this chapter's code, so hand them the digest instead of what they would have to derive themselves.\n")
            .append("- Each entry stands on its own. It is read far from here, so it cannot lean on the change blocks, on the surrounding code, or on another entry.\n")
            .append("- Record only what the code shows. Do not infer motivation or intent, and do not judge whether the change is correct or an improvement.\n")
            .append("- Where several change blocks change one identifier, give it a single entry covering all of them.\n")
            .append("- Identify code by name. Change IDs refer to nothing in the chapters that read this index, so keep them out of your entries.\n")
            .append("- Index this chapter only. An identifier that appears only in the context surrounding the changes was changed elsewhere and gets no entry from you.\n")
            .append("- If this chapter changes no identifier, write no entries.\n")
            .append("- When you have written every entry, or if you have none, end your response with `").append(END_OF_ENTRIES).append("` on its own line and output nothing after it.\n\n");

    return prompt.toString();
  }

  private static String renderKnownIdentifiers(List<Identifier> identifiers) {
    return String.join("\n", identifiers.stream()
            .map(identifier -> "<known name=\"" + oneLine(identifier.name()) + "\" kind=\"" + oneLine(identifier.kind()) + "\">\n"
                    + "  was: " + oneLine(identifier.before()) + "\n"
                    + "  now: " + oneLine(identifier.after()) + "\n"
                    + "  changed: " + oneLine(identifier.change()) + "\n"
                    + "</known>").toList());
  }

  private static String oneLine(String value) {
    return value == null ? "" : value.replaceAll("\\s+", " ").trim();
  }

  public String chapterResult(String content, List<Identifier> dependencyIdentifiers, boolean rawDiff) {
    StringBuilder prompt = new StringBuilder();
    boolean hasDependencyIdentifiers = dependencyIdentifiers != null && !dependencyIdentifiers.isEmpty();

    prompt.append("## Role\n")
            .append("You are a code review assistant. You are responsible for producing professional review feedback on pull requests before they are merged. ")
            .append("The changes in a pull request have been decomposed into a sequence of chapters; you are shown one of them, together with the context needed to judge it.\n")
            .append("Please keep your responses concise and objective.\n\n");

    prompt.append(specification(rawDiff));

    prompt.append("## Capabilities\n")
            .append("- Think step by step progressively.\n")
            .append("- First understand the code changes to be reviewed, in the representation described above.\n")
            .append("- Be objective and neutral, make judgments based on facts and logic, avoid subjective assumptions. ")
            .append(rawDiff
                    ? "The unchanged lines each hunk carries are the context available to you; judge against them rather than against assumptions about code you cannot see.\n"
                    : hasDependencyIdentifiers ? "Dependency identifiers below record how identifiers this chapter references were changed elsewhere in this pull request. Judge against them rather than against assumptions about code you cannot see.\n" : "")
            .append("- For the current code changes, provide feedback opinions, pointing out areas for improvement or potential issues.\n")
            .append("- Avoid commenting on correct code or unchanged code.\n")
            .append("- Focus on clarity, practicality, and comprehensiveness.\n")
            .append("- Use developer-friendly terminology and analogies in explanations.\n")
            .append("- Focus primarily on the actual code logic and functionality. Avoid commenting on or providing feedback about non-functional elements ")
            .append("such as code comments, tool-generated indicators (like @Generated annotations), or other metadata.\n\n");

    prompt.append("## Strict Focus Rules\n")
            .append(rawDiff
                    ? "- The unchanged context lines are background information only. Your comments must address the changed lines — never produce comments targeting code outside this chapter.\n"
                    : (hasDependencyIdentifiers
                            ? "- <context>, <dependencies>, and the dependency identifiers are background information only. Your comments must address the <change> elements of this chapter — never produce comments targeting code outside it.\n"
                            : "- <context> and <dependencies> are background information only. Your comments must address the <change> elements of this chapter — never produce comments targeting code outside it.\n"))
            .append("\n");

    prompt.append("## Reply limit\n")
            .append(rawDiff
                    ? "- Before ending your response, confirm you have given every <diff> in the chapter one pass. "
                    : "- Before ending your response, confirm you have given every <sub_chapter> in the chapter one pass. ")
            .append("Reviewing an implementation does not cover its header, interface, or configuration counterpart—being the smaller or secondary member of the chapter is not a reason to skip it.\n")
            .append("- If a code issue has been identified and confirmed, write a review comment for it in the format given below.\n\n");

    prompt.append("### CURRENT CHAPTER\n")
            .append("The following block contains the changes that make up the current chapter, in the representation described above. It is the code under review:\n")
            .append(content).append("\n\n");

    if (hasDependencyIdentifiers) {
      prompt.append("### DEPENDENCY IDENTIFIERS\n")
              .append("Other chapters of this pull request changed identifiers that this chapter references. The entries below record what those identifiers were, what they are now, and how they changed. ")
              .append("They are background information: use them to resolve references leaving this chapter, and do not produce comments targeting them.\n")
              .append(renderKnownIdentifiers(dependencyIdentifiers))
              .append("\n\n");
    }

    prompt.append("### Review Checklist\n")
            .append("#### Correctness\n")
            .append("Is the logic correct? Are there missing boundary conditions?\n")
            .append("Are exceptions handled properly?\n")
            .append("Is it thread-safe in concurrent scenarios?\n\n")
            .append("#### Security\n")
            .append("Are there security vulnerabilities such as SQL injection or XSS?\n")
            .append("Is sensitive information handled correctly?\n")
            .append("Is permission validation complete?\n\n")
            .append("#### Performance\n")
            .append("Are there obvious performance issues (e.g., N+1 queries, unnecessary loops)?\n")
            .append("Are resources properly released?\n\n")
            .append("#### Maintainability\n")
            .append("Is the code clear and easy to understand?\n")
            .append("Do names accurately express intent?\n")
            .append("Does it follow the project’s existing code style and architecture patterns?\n\n")
            .append("#### Test Coverage\n")
            .append("Do critical logic paths have corresponding test cases?\n")
            .append("Do test cases cover boundary conditions?\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n")
            .append("Write each review comment as:\n")
            .append("- LINE 1: Only the comma-separated list of change IDs the comment addresses.\n")
            .append("- SUBSEQUENT LINES: The review text.\n")
            .append("Separate individual comments with one or more blank lines. Report each distinct issue once—if one issue repeats across several places, write it once and list every ID involved.\n")
            .append("When you have written every comment, or if you have none, end your response with `").append(END_OF_AUDIT).append("` on its own line and output nothing after it.\n\n");

    prompt.append("Now please review the code changes in the current chapter above.");

    return prompt.toString();
  }

  public String result(List<String> results) {
    StringBuilder prompt = new StringBuilder();

    prompt.append("You are a software engineer assembling the final review of a pull request. ")
            .append("The pull request was decomposed into chapters, and each chapter was reviewed independently, so the same problem may have been reported more than once and two chapters may disagree. ")
            .append("Your objective is to merge the duplicates and drop the disagreements. Nothing else.\n\n");

    prompt.append("You are given the findings only. You do not have the code they refer to, and you cannot re-check them. ")
            .append("Do not judge whether a finding is correct, and do not add findings of your own: you have no evidence for either. ")
            .append("Decide only what the findings themselves establish—whether two of them are the same finding, or whether they cannot both be true.\n\n");

    prompt.append("### FINDINGS BY CHAPTER\n");
    for (int i = 0; i < results.size(); i++) {
      prompt.append("<chapter_").append(i + 1).append(">\n");
      prompt.append(results.get(i)).append("\n");
      prompt.append("</chapter_").append(i + 1).append(">\n");
    }
    prompt.append("\n");

    prompt.append("### YOUR TASK\n")
            .append("Produce the final list of comments by applying these rules:\n")
            .append("1. MERGE DUPLICATES: Where two or more findings report the same problem—the same mistake in the same place, or one mistake repeated across places—emit one comment for it. ")
            .append("Keep the clearest of the wordings rather than writing a new one, and list the change IDs of every finding you merged.\n")
            .append("2. DROP CONTRADICTIONS: Where two findings cannot both be true—one says a value is always set and another says it can be absent, one says a branch is unreachable and another reports what happens inside it—discard both. ")
            .append("You have no way to tell which is right, and a confident wrong comment costs more than a missing one. Findings that merely differ, or address different aspects of the same code, are not contradictions: keep them.\n")
            .append("3. PASS EVERYTHING ELSE THROUGH: A finding that is neither duplicated nor contradicted is kept as it is. Do not reword it, soften it, expand it, or merge unrelated findings because they sit in the same file.\n")
            .append("4. PRESERVE IDS EXACTLY: Copy change IDs verbatim from the findings you keep. Never invent, alter, or drop one. A merged comment carries the IDs of every finding that went into it.\n\n");

    prompt.append("### OUTPUT FORMAT REQUIREMENTS\n")
            .append("Your response must be the final list of comments wrapped in <review_comments> tags.\n")
            .append("Each comment must have this structure:\n")
            .append("- LINE 1: Only the comma-separated list of change IDs.\n")
            .append("- SUBSEQUENT LINES: The review text.\n")
            .append("Separate individual comments with one or more blank lines.\n")
            .append("If every finding was dropped, or there were none to begin with, emit empty <review_comments> tags.");

    return prompt.toString();
  }

  public static List<Identifier> parseIdentifiers(String response) {
    List<Identifier> identifiers = new ArrayList<>();
    if (response == null || response.isEmpty()) {
      return identifiers;
    }

    String[] fields = new String[IDENTIFIER_FIELDS.size()];
    int openField = -1;

    for (String line : response.split("\\r?\\n")) {
      String trimmedLine = line.trim();

      Matcher matcher = IDENTIFIER_FIELD_PATTERN.matcher(trimmedLine);
      if (matcher.matches()) {
        int field = IDENTIFIER_FIELDS.indexOf(matcher.group(1).toUpperCase());
        // The name opens an entry; everything up to the next name belongs to it
        if (field == 0) {
          addIdentifier(identifiers, fields);
          fields = new String[IDENTIFIER_FIELDS.size()];
        }
        // The label may come wrapped in markdown emphasis, which is not part of the value
        fields[field] = matcher.group(2).replaceAll("^\\**\\s*|\\s*\\**$", "").trim();
        openField = field;
        continue;
      }

      // A field the model wrapped onto the following lines
      if (openField >= 0 && !trimmedLine.isEmpty()) {
        fields[openField] = (fields[openField] + " " + trimmedLine).trim();
      }
    }
    addIdentifier(identifiers, fields);

    return identifiers;
  }

  private static void addIdentifier(List<Identifier> identifiers, String[] fields) {
    if (fields[0] == null || fields[0].isEmpty()) {
      return;
    }

    identifiers.add(new Identifier(fields[0], fields[1], fields[2], fields[3], fields[4]));
  }

  public static List<ReviewComment> parseResult(String response) {
    if (response == null || response.isEmpty()) {
      return null;
    }

    List<ReviewComment> comments = new ArrayList<>();

    Pattern outerPattern = Pattern.compile("<review_comments>(.*?)</review_comments>", Pattern.DOTALL);
    Matcher outerMatcher = outerPattern.matcher(response);

    StringBuilder blocks = new StringBuilder();
    while (outerMatcher.find()) {
      String block = outerMatcher.group(1).trim();
      if (block.isEmpty()) continue;

      if (blocks.length() > 0) {
        blocks.append("\n\n");
      }
      blocks.append(block);
    }
    if (blocks.length() == 0) {
      return null;
    }

    String[] lines = blocks.toString().split("\\r?\\n");

    String currentHunksStr = null;
    StringBuilder currentText = new StringBuilder();

    for (String line : lines) {
      String trimmedLine = line.trim();
      if (trimmedLine.isEmpty()) continue;

      // A header line is one that consists only of valid hunk IDs and separators (commas, spaces)
      if (isHeaderLine(trimmedLine)) {
        // Commit previous comment if it exists
        if (currentHunksStr != null && currentText.length() > 0) {
          List<String> ids = extractHunkIds(currentHunksStr);
          if (!ids.isEmpty()) {
            comments.add(new ReviewComment(ids, currentText.toString().trim()));
          }
        }
        currentHunksStr = trimmedLine;
        currentText = new StringBuilder();
      } else {
        // Append to the current comment's text
        if (currentHunksStr != null) {
          if (currentText.length() > 0) {
            currentText.append("\n");
          }
          currentText.append(line);
        }
      }
    }

    // Commit the final comment
    if (currentHunksStr != null && currentText.length() > 0) {
      List<String> ids = extractHunkIds(currentHunksStr);
      if (!ids.isEmpty()) {
        comments.add(new ReviewComment(ids, currentText.toString().trim()));
      }
    }

    return comments.isEmpty() ? null : comments;
  }

  private static boolean isHeaderLine(String line) {
    if (line == null || line.isEmpty()) return false;
    // A line is a header if it contains at least one valid ID and NO other non-separator characters
    List<String> ids = extractHunkIds(line);
    if (ids.isEmpty()) return false;

    // The IDs may be separated in any way the agent chooses ("A, B", "A -> B", "A & B", "A + B / C", ...),
    // so anything that is not alphanumeric counts as a separator; only words or numbers disqualify the line
    String stripped = OPTIONAL_PREFIX_ID_PATTERN.matcher(line).replaceAll("");
    return !ALPHANUMERIC_PATTERN.matcher(stripped).find();
  }

  private static List<String> extractHunkIds(String hunksStr) {
    List<String> ids = new ArrayList<>();

    if (hunksStr == null) {
      return ids;
    }

    Matcher m = OPTIONAL_PREFIX_ID_PATTERN.matcher(hunksStr);
    while (m.find()) {
      String id = m.group();
      ids.add(id.startsWith(Node.PROMPT_ID_PREFIX) ? id : Node.PROMPT_ID_PREFIX + id);
    }

    return ids;
  }

  public record Identifier(String name, String kind, String before, String after, String change) {
  }

  public record ReviewComment(List<String> hunkIds, String text) {
    @NotNull
    @Override
    public String toString() {
      return String.join(",", hunkIds) + "\n" + text;
    }
  }
}

package dev.estap.compression;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeBlockExtractor {

    // Regex to match code blocks starting with ``` (with optional language) and ending with ```
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(\\w*)\\r?\\n([\\s\\S]*?)```");

    public ExtractionPair extract(String prompt) {
        if (prompt == null || prompt.isEmpty()) {
            return new ExtractionPair("", List.of());
        }

        List<CodeBlock> extractedBlocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(prompt);
        StringBuilder sanitized = new StringBuilder();
        int lastIndex = 0;
        int blockIndex = 0;

        while (matcher.find()) {
            // Append the text before the match
            sanitized.append(prompt, lastIndex, matcher.start());

            String placeholder = "{{CODE_BLOCK_" + blockIndex + "}}";
            String language = matcher.group(1);
            String fullBlock = matcher.group(0); // The entire block including backticks

            extractedBlocks.add(new CodeBlock(placeholder, language, fullBlock));
            sanitized.append(placeholder);

            lastIndex = matcher.end();
            blockIndex++;
        }

        // Append the remaining text
        sanitized.append(prompt, lastIndex, prompt.length());

        return new ExtractionPair(sanitized.toString(), extractedBlocks);
    }

    public String reintegrate(String sanitizedText, List<CodeBlock> extractedBlocks) {
        if (sanitizedText == null || sanitizedText.isEmpty()) {
            return "";
        }
        if (extractedBlocks == null || extractedBlocks.isEmpty()) {
            return sanitizedText;
        }

        String result = sanitizedText;
        for (CodeBlock block : extractedBlocks) {
            result = result.replace(block.placeholder(), block.fullBlockContent());
        }
        return result;
    }

    public record ExtractionPair(
        String sanitizedText,
        List<CodeBlock> extractedBlocks
    ) {}

    public record CodeBlock(
        String placeholder,
        String language,
        String fullBlockContent
    ) {}
}

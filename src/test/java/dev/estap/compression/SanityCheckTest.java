package dev.estap.compression;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SanityCheckTest {

    private final SanityCheck sanityCheck = new SanityCheck();

    @Test
    void shouldPassWhenCompressionIsSuccessfulAndReducesSize() {
        String originalPrompt = "Crie uma classe Java com um método main.";
        String originalSanitized = "Crie uma classe Java com um método main.";
        String compressed = "Create Java class with main method.";
        List<CodeBlockExtractor.CodeBlock> blocks = List.of();
        String recomposed = "Create Java class with main method.";

        SanityCheck.ValidationResult result = sanityCheck.validate(
            originalPrompt,
            originalSanitized,
            compressed,
            blocks,
            recomposed
        );

        assertThat(result.passed()).isTrue();
        assertThat(result.originalSizeBytes()).isEqualTo(41);
        assertThat(result.compressedSizeBytes()).isEqualTo(35);
        assertThat(result.compressionRatio()).isGreaterThan(0.0);
    }

    @Test
    void shouldFailWhenRecomposedIsEqualOrLargerThanOriginal() {
        String originalPrompt = "Soma";
        String originalSanitized = "Soma";
        String compressed = "Soma e imprime";
        List<CodeBlockExtractor.CodeBlock> blocks = List.of();
        String recomposed = "Soma e imprime";

        SanityCheck.ValidationResult result = sanityCheck.validate(
            originalPrompt,
            originalSanitized,
            compressed,
            blocks,
            recomposed
        );

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("maior ou igual ao original");
    }

    @Test
    void shouldFailWhenPlaceholderIsMissingFromCompressedText() {
        String originalPrompt = "Refatore:\n```java\nint x = 1;\n```";
        String originalSanitized = "Refatore:\n{{CODE_BLOCK_0}}";
        String compressed = "Refactor this code please."; // missing placeholder
        List<CodeBlockExtractor.CodeBlock> blocks = List.of(
            new CodeBlockExtractor.CodeBlock("{{CODE_BLOCK_0}}", "java", "```java\nint x = 1;\n```")
        );
        String recomposed = "Refactor this code please.";

        SanityCheck.ValidationResult result = sanityCheck.validate(
            originalPrompt,
            originalSanitized,
            compressed,
            blocks,
            recomposed
        );

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).contains("Placeholder corrompido ou ausente");
    }
}

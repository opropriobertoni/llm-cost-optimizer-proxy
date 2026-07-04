package dev.estap.compression;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class SanityCheck {

    public ValidationResult validate(
            String originalPrompt,
            String originalSanitizedText,
            String compressedText,
            List<CodeBlockExtractor.CodeBlock> originalBlocks,
            String recomposedText) {

        long originalSizeBytes = originalPrompt.getBytes(StandardCharsets.UTF_8).length;
        long recomposedSizeBytes = recomposedText.getBytes(StandardCharsets.UTF_8).length;

        // Camada 1: Validação de Integridade de Placeholders
        for (CodeBlockExtractor.CodeBlock block : originalBlocks) {
            if (!compressedText.contains(block.placeholder())) {
                return new ValidationResult(
                    false,
                    "Placeholder corrompido ou ausente no texto comprimido: " + block.placeholder(),
                    originalSizeBytes,
                    recomposedSizeBytes,
                    0.0
                );
            }
        }

        // Camada 2: Validação Matemática (a compressão deve de fato reduzir o payload)
        if (recomposedSizeBytes >= originalSizeBytes) {
            return new ValidationResult(
                false,
                "Texto comprimido é maior ou igual ao original (" + recomposedSizeBytes + "B >= " + originalSizeBytes + "B)",
                originalSizeBytes,
                recomposedSizeBytes,
                0.0
            );
        }

        double ratio = (1.0 - ((double) recomposedSizeBytes / originalSizeBytes)) * 100.0;

        return new ValidationResult(
            true,
            "Sucesso",
            originalSizeBytes,
            recomposedSizeBytes,
            ratio
        );
    }

    public record ValidationResult(
        boolean passed,
        String reason,
        long originalSizeBytes,
        long compressedSizeBytes,
        double compressionRatio
    ) {}
}

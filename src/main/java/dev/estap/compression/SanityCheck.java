package dev.estap.compression;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;

import java.util.List;

public class SanityCheck {

    private static final Encoding ENCODING =
        Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);

    public ValidationResult validate(
            String originalPrompt,
            String originalSanitizedText,
            String compressedText,
            List<CodeBlockExtractor.CodeBlock> originalBlocks,
            String recomposedText) {

        int originalTokens = ENCODING.countTokens(originalPrompt);
        int recomposedTokens = ENCODING.countTokens(recomposedText);

        // Camada 1: Validação de Integridade de Placeholders
        for (CodeBlockExtractor.CodeBlock block : originalBlocks) {
            if (!compressedText.contains(block.placeholder())) {
                return new ValidationResult(
                    false,
                    "Placeholder corrompido ou ausente no texto comprimido: " + block.placeholder(),
                    originalTokens,
                    recomposedTokens,
                    0.0,
                    0,
                    0,
                    0.0
                );
            }
        }

        // Camada 2: Validação Matemática em TOKENS reais (não bytes)
        // Tokens capturam o fenômeno cross-lingual PT→EN que bytes não enxergam.
        if (recomposedTokens >= originalTokens) {
            return new ValidationResult(
                false,
                "Texto comprimido tem tokens >= original (" + recomposedTokens + " >= " + originalTokens + ")",
                originalTokens,
                recomposedTokens,
                0.0,
                0,
                0,
                0.0
            );
        }

        double ratio = (1.0 - ((double) recomposedTokens / originalTokens)) * 100.0;

        String originalProse = originalSanitizedText.replaceAll("\\{\\{CODE_BLOCK_\\d+\\}\\}", "");
        String compressedProse = compressedText.replaceAll("\\{\\{CODE_BLOCK_\\d+\\}\\}", "");
        int proseOriginalTokens = ENCODING.countTokens(originalProse);
        int proseCompressedTokens = ENCODING.countTokens(compressedProse);
        double proseRatio = proseOriginalTokens > 0
            ? (1.0 - ((double) proseCompressedTokens / proseOriginalTokens)) * 100.0
            : 0.0;

        return new ValidationResult(
            true,
            "Sucesso",
            originalTokens,
            recomposedTokens,
            ratio,
            proseOriginalTokens,
            proseCompressedTokens,
            proseRatio
        );
    }

    public record ValidationResult(
        boolean passed,
        String reason,
        long originalTokens,
        long compressedTokens,
        double compressionRatio,
        long proseOriginalTokens,
        long proseCompressedTokens,
        double proseCompressionRatio
    ) {}
}

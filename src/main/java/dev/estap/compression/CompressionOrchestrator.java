package dev.estap.compression;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.estap.circuitbreaker.FailOpenCircuitBreaker;
import dev.estap.proxy.PromptExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public class CompressionOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(CompressionOrchestrator.class);

    private final PromptExtractor promptExtractor;
    private final CodeBlockExtractor codeBlockExtractor;
    private final GroqCompressor groqCompressor;
    private final SanityCheck sanityCheck;
    private final FailOpenCircuitBreaker circuitBreaker;

    public CompressionOrchestrator(
            PromptExtractor promptExtractor,
            CodeBlockExtractor codeBlockExtractor,
            GroqCompressor groqCompressor,
            SanityCheck sanityCheck,
            FailOpenCircuitBreaker circuitBreaker) {
        this.promptExtractor = promptExtractor;
        this.codeBlockExtractor = codeBlockExtractor;
        this.groqCompressor = groqCompressor;
        this.sanityCheck = sanityCheck;
        this.circuitBreaker = circuitBreaker;
    }

    public CompressionOutcome orchestrate(byte[] originalPayloadBytes) {
        Optional<PromptExtractor.ExtractionResult> extractionOpt = promptExtractor.extract(originalPayloadBytes);

        if (extractionOpt.isEmpty()) {
            LOG.debug("Prompt extraction skipped or failed. Passthrough original payload.");
            return new CompressionOutcome(
                originalPayloadBytes,
                false,
                false,
                FailOpenCircuitBreaker.FailOpenReason.NONE,
                0, 0, 0.0, 0, 0,
                0, 0, 0.0
            );
        }

        PromptExtractor.ExtractionResult extraction = extractionOpt.get();
        String originalPrompt = extraction.userPrompt();

        // Camada 1: Isolamento de blocos de código
        CodeBlockExtractor.ExtractionPair codeExtraction = codeBlockExtractor.extract(originalPrompt);
        String sanitizedPrompt = codeExtraction.sanitizedText();

        LOG.debug("Original prompt length: {} chars. Sanitized prompt length: {} chars. Extracted blocks: {}",
            originalPrompt.length(),
            sanitizedPrompt.length(),
            codeExtraction.extractedBlocks().size());

        // Executa compressão pelo Groq com proteção de Circuit Breaker
        FailOpenCircuitBreaker.ExecutionResult<String> groqResult = circuitBreaker.execute(
            () -> groqCompressor.compress(sanitizedPrompt),
            sanitizedPrompt
        );

        if (groqResult.failOpenTriggered()) {
            LOG.warn("Groq compression failed (fail-open triggered: {}). Passthrough original payload.", groqResult.reason());
            return new CompressionOutcome(
                originalPayloadBytes,
                false,
                true,
                groqResult.reason(),
                0, 0, 0.0,
                groqResult.latencyMs(),
                codeExtraction.extractedBlocks().size(),
                0, 0, 0.0
            );
        }

        String compressedSanitized = groqResult.value();

        // Reintegra os blocos de código no texto comprimido
        String recomposedPrompt = codeBlockExtractor.reintegrate(compressedSanitized, codeExtraction.extractedBlocks());

        // Camada 2: Sanity Check matemático e de integridade
        SanityCheck.ValidationResult validation = sanityCheck.validate(
            originalPrompt,
            sanitizedPrompt,
            compressedSanitized,
            codeExtraction.extractedBlocks(),
            recomposedPrompt
        );

        if (!validation.passed()) {
            LOG.warn("Sanity check failed: {}. Passthrough original payload.", validation.reason());
            return new CompressionOutcome(
                originalPayloadBytes,
                false,
                true,
                FailOpenCircuitBreaker.FailOpenReason.SANITY_CHECK_FAILED,
                validation.originalTokens(),
                0, 0.0,
                groqResult.latencyMs(),
                codeExtraction.extractedBlocks().size(),
                0, 0, 0.0
            );
        }

        // Modifica o payload original com o prompt recomposto e comprimido
        updatePayloadAtPath(extraction.originalPayload(), extraction.jsonPath(), recomposedPrompt);
        byte[] finalPayload = extraction.originalPayload().toString().getBytes(StandardCharsets.UTF_8);

        LOG.info("Compression successful! Saved {} tokens ({}% reduction).",
            validation.originalTokens() - validation.compressedTokens(),
            String.format("%.2f", validation.compressionRatio()));

        return new CompressionOutcome(
            finalPayload,
            true,
            false,
            FailOpenCircuitBreaker.FailOpenReason.NONE,
            validation.originalTokens(),
            validation.compressedTokens(),
            validation.compressionRatio(),
            groqResult.latencyMs(),
            codeExtraction.extractedBlocks().size(),
            validation.proseOriginalTokens(),
            validation.proseCompressedTokens(),
            validation.proseCompressionRatio()
        );
    }

    private void updatePayloadAtPath(ObjectNode payload, String[] path, String newValue) {
        JsonNode current = payload;
        for (int i = 0; i < path.length - 1; i++) {
            String segment = path[i];
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                current = current.get(Integer.parseInt(segment));
            }
        }
        String leafSegment = path[path.length - 1];
        if (current instanceof ObjectNode objectNode) {
            objectNode.put(leafSegment, newValue);
        }
    }

    public record CompressionOutcome(
        byte[] finalPayloadBody,
        boolean compressionApplied,
        boolean failOpenTriggered,
        FailOpenCircuitBreaker.FailOpenReason failOpenReason,
        long originalTokens,
        long compressedTokens,
        double compressionRatio,
        long groqLatencyMs,
        int codeBlocksCount,
        long proseOriginalTokens,
        long proseCompressedTokens,
        double proseCompressionRatio
    ) {}
}

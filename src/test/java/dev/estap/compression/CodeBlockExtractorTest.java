package dev.estap.compression;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CodeBlockExtractorTest {

    private final CodeBlockExtractor extractor = new CodeBlockExtractor();

    @Test
    void shouldReturnEmptyOnNullOrEmptyPrompt() {
        CodeBlockExtractor.ExtractionPair resultNull = extractor.extract(null);
        assertThat(resultNull.sanitizedText()).isEmpty();
        assertThat(resultNull.extractedBlocks()).isEmpty();

        CodeBlockExtractor.ExtractionPair resultEmpty = extractor.extract("");
        assertThat(resultEmpty.sanitizedText()).isEmpty();
        assertThat(resultEmpty.extractedBlocks()).isEmpty();
    }

    @Test
    void shouldNotModifyPromptWithoutCodeBlocks() {
        String prompt = "Por favor, explique a diferença entre classes e interfaces em Java.";
        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);

        assertThat(result.sanitizedText()).isEqualTo(prompt);
        assertThat(result.extractedBlocks()).isEmpty();
    }

    @Test
    void shouldExtractSingleCodeBlockWithLanguage() {
        String prompt = """
            Por favor refatore este código:
            ```java
            public class Main {
                public static void main(String[] args) {}
            }
            ```
            Adicione tratamento de exceções.
            """;

        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);

        assertThat(result.sanitizedText()).isEqualTo("""
            Por favor refatore este código:
            {{CODE_BLOCK_0}}
            Adicione tratamento de exceções.
            """);

        assertThat(result.extractedBlocks()).hasSize(1);
        CodeBlockExtractor.CodeBlock block = result.extractedBlocks().getFirst();
        assertThat(block.placeholder()).isEqualTo("{{CODE_BLOCK_0}}");
        assertThat(block.language()).isEqualTo("java");
        assertThat(block.fullBlockContent()).contains("public class Main");
        assertThat(block.fullBlockContent()).startsWith("```java").endsWith("```");
    }

    @Test
    void shouldExtractMultipleCodeBlocks() {
        String prompt = """
            Compare este arquivo python:
            ```python
            print("hello")
            ```
            Com este em javascript:
            ```javascript
            console.log("hello");
            ```
            Qual é melhor?
            """;

        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);

        assertThat(result.sanitizedText()).isEqualTo("""
            Compare este arquivo python:
            {{CODE_BLOCK_0}}
            Com este em javascript:
            {{CODE_BLOCK_1}}
            Qual é melhor?
            """);

        assertThat(result.extractedBlocks()).hasSize(2);
        assertThat(result.extractedBlocks().get(0).language()).isEqualTo("python");
        assertThat(result.extractedBlocks().get(1).language()).isEqualTo("javascript");
    }

    @Test
    void shouldExtractCodeBlockWithoutLanguage() {
        String prompt = """
            Veja este texto formatado:
            ```
            linha de log 1
            linha de log 2
            ```
            """;

        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);

        assertThat(result.sanitizedText()).isEqualTo("""
            Veja este texto formatado:
            {{CODE_BLOCK_0}}
            """);

        assertThat(result.extractedBlocks()).hasSize(1);
        assertThat(result.extractedBlocks().getFirst().language()).isEmpty();
    }

    @Test
    void shouldNotExtractInlineBackticks() {
        String prompt = "Use a anotação `@Override` no método `toString()`.";
        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);

        assertThat(result.sanitizedText()).isEqualTo(prompt);
        assertThat(result.extractedBlocks()).isEmpty();
    }

    @Test
    void shouldReintegrateByteToByteIdentical() {
        String prompt = """
            Antes do código.
            ```java
            System.out.println("hello \\n world");
            ```
            Depois do código.
            ```python
            # python comment
            x = 42
            ```
            Fim.
            """;

        CodeBlockExtractor.ExtractionPair result = extractor.extract(prompt);
        String reintegrated = extractor.reintegrate(result.sanitizedText(), result.extractedBlocks());

        assertThat(reintegrated).isEqualTo(prompt);
    }
}

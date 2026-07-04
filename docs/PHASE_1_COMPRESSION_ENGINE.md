# Fase 1: Interceptação e Arbitragem Cross-Lingual

> **Referência:** [ESTAP.md — Seções 3 (Fase 1), 5 e 6](../ESTAP.md)
>
> **Pré-requisito:** Fase 0 concluída com todos os critérios de aceite validados. O mapeamento de payload (Seção 0.4.3) deve estar documentado com a localização exata do nó JSON contendo o prompt do usuário.
>
> **Objetivo:** Ativar o motor de inteligência de borda — extrair texto natural do prompt, comprimir via Groq/Llama 3, reintegrar blocos de código intactos, e despachar o payload otimizado para a nuvem.

---

## 1.1 — Novas Dependências

Nenhuma dependência externa adicional é necessária. A comunicação com a API Groq será feita via `java.net.http.HttpClient` (já presente). Novas variáveis de ambiente:

```dotenv
# === Motor de Compressão (Groq) ===
GROQ_API_KEY=gsk_xxx
GROQ_MODEL=llama3-70b-8192
GROQ_API_URL=https://api.groq.com/openai/v1/chat/completions

# Timeout do Circuit Breaker (em ms).
# Valor final = 800ms - overhead estimado de rede (validado empiricamente na Fase 0).
GROQ_TIMEOUT_MS=600

# Modo dry-run: imprime antes/depois sem enviar para a nuvem
ESTAP_DRY_RUN=false
```

Atualizar `EnvironmentConfig.java` para incluir os novos campos.

---

## 1.2 — Estrutura de Diretórios (Adições)

```
src/main/java/dev/estap/
├── compression/
│   ├── CompressionOrchestrator.java
│   ├── CodeBlockExtractor.java
│   ├── GroqCompressor.java
│   ├── SanityCheck.java
│   └── CompressionResult.java
├── circuitbreaker/
│   └── FailOpenCircuitBreaker.java
├── telemetry/
│   ├── RequestMetrics.java       (existente — expandir)
│   ├── MetricsLogger.java        (existente — expandir)
│   └── CompressionMetrics.java   (novo)
└── proxy/
    ├── ProxyController.java      (existente — integrar compressão)
    ├── StreamingRelay.java       (existente — sem alteração)
    └── PromptExtractor.java      (novo)
```

---

## 1.3 — Extração do Prompt (`PromptExtractor.java`)

Responsabilidade: localizar e extrair o texto do prompt do usuário dentro do payload JSON, baseado no mapeamento empírico produzido na Fase 0.

**Contrato:**

```java
public record ExtractionResult(
    String userPrompt,              // Texto extraído do prompt
    String[] jsonPath,              // Caminho JSON até o nó (ex: ["messages", "1", "content"])
    ObjectNode originalPayload      // Payload original parseado
) {}
```

**Algoritmo:**
1. Parsear o body JSON com Jackson.
2. Navegar até o nó do prompt do usuário (path determinado empiricamente).
3. Se o nó não for encontrado (payload com formato inesperado), retornar `Optional.empty()` — o proxy faz passthrough sem compressão.
4. Extrair o texto e retornar o `ExtractionResult`.

> **Decisão de design:** A estratégia de localização do prompt deve ser configurável ou pelo menos facilmente adaptável, pois diferentes APIs de LLM posicionam o prompt em nós JSON distintos. Na v1, codificar os paths mais comuns (Anthropic Messages API, OpenAI Chat Completions API) e selecionar pelo formato detectado.

---

## 1.4 — Allowlist de Imutabilidade — Camada 1 (`CodeBlockExtractor.java`)

> **Referência:** [ESTAP.md — Seção 6, Sanity Check Camada 1](../ESTAP.md)

Responsabilidade: extrair todos os blocos de código Markdown do texto do prompt e substituí-los por placeholders, garantindo que o LLM de borda processe apenas linguagem natural.

**Contrato:**

```java
public record ExtractionPair(
    String sanitizedText,                   // Texto com placeholders no lugar dos blocos
    List<CodeBlock> extractedBlocks         // Blocos extraídos com seus placeholders
) {}

public record CodeBlock(
    String placeholder,    // Ex: "{{CODE_BLOCK_0}}"
    String language,       // Ex: "java", "python", "" (sem especificação)
    String content         // Conteúdo integral do bloco
) {}
```

**Algoritmo:**

1. Aplicar regex para capturar todos os blocos delimitados por `` ``` ``:
   - Pattern: `` ```(\w*)\n([\s\S]*?)``` `` (com flags MULTILINE e DOTALL).
2. Para cada bloco encontrado:
   - Gerar um placeholder único no formato `{{CODE_BLOCK_N}}` (onde N é o índice sequencial).
   - Armazenar o bloco original com seu placeholder no `List<CodeBlock>`.
   - Substituir o bloco inteiro (incluindo delimitadores) pelo placeholder no texto.
3. Retornar o `ExtractionPair`.

**Invariantes obrigatórias:**
- O número de placeholders no `sanitizedText` deve ser igual ao tamanho de `extractedBlocks`.
- A reinserção dos blocos deve produzir um texto **byte-a-byte idêntico** ao original nos trechos de código.

---

## 1.5 — Cliente Groq (`GroqCompressor.java`)

Responsabilidade: enviar o texto sanitizado (sem blocos de código) para a API Groq para tradução e compressão semântica.

### 1.5.1 API Groq — Formato de Requisição

A API Groq segue o formato OpenAI Chat Completions:

```
POST https://api.groq.com/openai/v1/chat/completions
Authorization: Bearer {GROQ_API_KEY}
Content-Type: application/json

{
  "model": "llama3-70b-8192",
  "messages": [
    {
      "role": "system",
      "content": "<SYSTEM_PROMPT_DE_COMPRESSÃO>"
    },
    {
      "role": "user",
      "content": "<TEXTO_SANITIZADO>"
    }
  ],
  "temperature": 0.1,
  "max_tokens": 4096,
  "stream": false
}
```

> **Nota:** A chamada ao Groq é **síncrona e não-streaming** (`"stream": false`). O objetivo é obter a resposta completa rapidamente para reintegrar no payload antes de despachar para a nuvem. Streaming aqui adicionaria complexidade desnecessária.

### 1.5.2 System Prompt de Compressão

O system prompt é o componente mais crítico para a qualidade da compressão. Deve ser iterado e refinado via dry-run. Diretriz inicial:

```
You are a lossless semantic compressor for software engineering instructions.

RULES:
1. Translate the input from Portuguese to English.
2. Remove all filler words, redundancies, and politeness markers.
3. Preserve every technical term, identifier, file path, class name, and variable name EXACTLY as written.
4. Preserve all placeholders in the format {{CODE_BLOCK_N}} EXACTLY as they appear — do not translate, modify, or remove them.
5. Compress verbose descriptions into minimal, imperative instructions.
6. Output ONLY the compressed text. No preambles, no explanations.
```

> **Aviso:** Este prompt é ponto de partida. A calibração final depende de testes empíricos via dry-run (Seção 1.8).

### 1.5.3 Implementação do Cliente

**Algoritmo:**

1. Construir o body JSON com o system prompt e o texto sanitizado.
2. Criar o `HttpRequest` com:
   - Timeout de conexão: valor de `GROQ_TIMEOUT_MS`.
   - Headers: `Authorization`, `Content-Type`.
3. Executar via `HttpClient.send()` síncrono.
4. Parsear a resposta JSON e extrair `choices[0].message.content`.
5. Retornar o texto comprimido.

**Tratamento de Erros:**
- `HttpTimeoutException` → propagar para o Circuit Breaker.
- HTTP 429 (Rate Limit) → propagar para o Circuit Breaker (tratar como indisponibilidade).
- HTTP 4xx/5xx → propagar para o Circuit Breaker.

---

## 1.6 — Sanity Check — Camada 2 (`SanityCheck.java`)

> **Referência:** [ESTAP.md — Seção 6, Sanity Check Camada 2](../ESTAP.md)

Responsabilidade: validar que a compressão de fato reduziu o payload e que os blocos de código estão intactos.

**Contrato:**

```java
public record ValidationResult(
    boolean passed,
    String reason,              // Motivo da falha (se aplicável)
    long originalSizeBytes,
    long compressedSizeBytes,
    double compressionRatio     // (1 - compressed/original) * 100
) {}
```

**Algoritmo:**

1. **Validação de Integridade dos Placeholders:**
   - Verificar que todos os placeholders `{{CODE_BLOCK_N}}` presentes no texto original sanitizado também existem no texto comprimido.
   - Se algum placeholder foi removido ou alterado → **FALHA**.

2. **Reinserção dos Blocos de Código:**
   - Substituir cada placeholder no texto comprimido pelo bloco de código original.
   - Produzir o texto final recomposto.

3. **Validação Matemática:**
   - Comparar `tamanho_em_bytes(texto_final_recomposto)` vs `tamanho_em_bytes(prompt_original)`.
   - Se `texto_final >= prompt_original` → **FALHA** (compressão não reduziu o payload).

4. Retornar `ValidationResult` com os dados para métricas.

---

## 1.7 — Circuit Breaker Fail-Open (`FailOpenCircuitBreaker.java`)

> **Referência:** [ESTAP.md — Seção 6, Circuit Breaker](../ESTAP.md)

Responsabilidade: encapsular a chamada ao Groq com proteção de timeout. Em caso de falha, o payload original segue intocado.

**Comportamento:**

```
┌────────────────────┐
│  Prompt do Usuário  │
└─────────┬──────────┘
          │
          ▼
┌─────────────────────────────────┐
│  FailOpenCircuitBreaker.execute │
│                                 │
│  try:                           │
│    compressedText = groq.call() │     ← Timeout: GROQ_TIMEOUT_MS
│    result = sanityCheck(...)    │
│    if result.passed:            │
│      return compressedPayload   │     ← Sucesso: payload comprimido
│    else:                        │
│      log(FAIL_OPEN, SANITY)     │
│      return originalPayload     │     ← Fail-open: sanity check falhou
│  catch TimeoutException:        │
│    log(FAIL_OPEN, TIMEOUT)      │
│    return originalPayload       │     ← Fail-open: Groq não respondeu
│  catch Exception:               │
│    log(FAIL_OPEN, ERROR)        │
│    return originalPayload       │     ← Fail-open: erro inesperado
└─────────────────────────────────┘
```

**Cálculo do Timeout:**

Conforme ESTAP.md: `timeout = 800ms - overhead_estimado_de_rede`.

O overhead de rede é a variável empírica medida na Fase 0 (telemetria de latência do proxy). Exemplo: se o proxy adiciona ~50ms de overhead, `GROQ_TIMEOUT_MS = 750`.

---

## 1.8 — Orquestrador de Compressão (`CompressionOrchestrator.java`)

Responsabilidade: coordenar todo o pipeline de compressão como uma unidade atômica.

**Contrato:**

```java
public record CompressionOutcome(
    String finalPayloadBody,        // Body JSON final (comprimido ou original)
    boolean compressionApplied,     // Se a compressão foi de fato usada
    CompressionMetrics metrics      // Métricas para logging
) {}
```

**Pipeline:**

```
Prompt Original
    │
    ▼
PromptExtractor.extract(payload)
    │
    ├── Prompt não encontrado? → Passthrough (payload original)
    │
    ▼
CodeBlockExtractor.extract(prompt)
    │
    ▼
FailOpenCircuitBreaker.execute(sanitizedText)
    │
    ├── Timeout/Erro? → Passthrough (payload original) + Métrica FAIL_OPEN
    │
    ▼
SanityCheck.validate(compressed, originalBlocks, originalPrompt)
    │
    ├── Falhou? → Passthrough (payload original) + Métrica FAIL_OPEN
    │
    ▼
Reintegrar blocos de código no texto comprimido
    │
    ▼
Substituir prompt original no payload JSON pelo texto comprimido
    │
    ▼
Payload otimizado → ProxyController → StreamingRelay → Nuvem
```

---

## 1.9 — Modo Dry-Run

> **Referência:** [ESTAP.md — Seção 6, Auditoria e Dry-Run](../ESTAP.md)

Quando `ESTAP_DRY_RUN=true`:

1. O pipeline de compressão executa normalmente (extração, Groq, sanity check).
2. **O payload NÃO é enviado para a nuvem.**
3. O console imprime:

```
╔══════════════════════════════════════╗
║         ESTAP DRY-RUN REPORT         ║
╠══════════════════════════════════════╣
║ ORIGINAL (PT):                       ║
╠══════════════════════════════════════╣
  <texto original do prompt>
╠══════════════════════════════════════╣
║ COMPRESSED (EN):                     ║
╠══════════════════════════════════════╣
  <texto comprimido>
╠══════════════════════════════════════╣
║ CODE BLOCKS PRESERVED: 3            ║
║ ORIGINAL SIZE: 2,847 bytes           ║
║ COMPRESSED SIZE: 1,203 bytes         ║
║ COMPRESSION RATIO: 57.7%            ║
║ SANITY CHECK: PASSED                ║
╚══════════════════════════════════════╝
```

4. Retornar `200 OK` com body: `{ "dryRun": true, "compressionRatio": 57.7 }`.

Este modo é **mandatório** antes de liberar compressão em produção.

---

## 1.10 — Métricas de Compressão (`CompressionMetrics.java`)

> **Referência:** [ESTAP.md — Seção 6, Rastreamento Obrigatório de Fail-Open](../ESTAP.md)

Record para capturar métricas específicas de compressão, **segregadas** das métricas de rede:

| Campo | Tipo | Descrição |
|---|---|---|
| `requestId` | `String` | Mesmo UUID da `RequestMetrics` |
| `compressionApplied` | `boolean` | Se a compressão foi usada no payload final |
| `failOpenTriggered` | `boolean` | Se houve fallback para o original |
| `failOpenReason` | `enum` | `NONE`, `TIMEOUT`, `SANITY_CHECK_FAILED`, `ERROR` |
| `originalSizeBytes` | `long` | Tamanho do prompt original |
| `compressedSizeBytes` | `long` | Tamanho do prompt comprimido (0 se fail-open) |
| `compressionRatio` | `double` | Percentual de redução |
| `groqLatencyMs` | `long` | Tempo da chamada ao Groq |
| `codeBlocksExtracted` | `int` | Quantidade de blocos de código protegidos |

**Formato de Log (separado da telemetria de rede):**

```json
{"type":"COMPRESSION","requestId":"uuid","applied":true,"failOpen":false,"originalBytes":2847,"compressedBytes":1203,"ratio":57.7,"groqMs":234,"codeBlocks":3}
```

```json
{"type":"COMPRESSION","requestId":"uuid","applied":false,"failOpen":true,"reason":"TIMEOUT","originalBytes":2847,"compressedBytes":0,"ratio":0.0,"groqMs":750,"codeBlocks":0}
```

---

## 1.11 — Integração no `ProxyController.java`

Alterar o fluxo do controller para incluir a compressão:

```
Requisição chega
    │
    ▼
Telemetria: capturar timestamp de início
    │
    ▼
if (ESTAP_DRY_RUN):
    CompressionOrchestrator.compress(body)
    Imprimir dry-run report
    Retornar 200 com relatório
    FIM
    │
    ▼
CompressionOrchestrator.compress(body)
    │
    ▼
StreamingRelay.forward(payloadFinal)
    │
    ▼
Telemetria: registrar RequestMetrics + CompressionMetrics
```

---

## 1.12 — Critérios de Aceite da Fase 1

| # | Critério | Validação |
|---|---|---|
| 1 | Blocos de código em Markdown são extraídos e reintegrados com fidelidade byte-a-byte | Testes unitários com diversos formatos de código |
| 2 | Placeholders não são adulterados pelo Groq/Llama 3 | Testes via dry-run com prompts reais contendo código |
| 3 | Compressão reduz > 30% em prompts conversacionais extensos | Medição via dry-run em 20+ prompts representativos |
| 4 | Circuit Breaker aciona fail-open em < `GROQ_TIMEOUT_MS` | Teste com WireMock simulando delay excessivo no Groq |
| 5 | Sanity Check rejeita compressão que resulta maior que original | Teste unitário com texto comprimido artificialmente maior |
| 6 | Métricas de compressão e fail-open são logadas separadamente | Inspeção dos logs após testes de integração |
| 7 | Dry-run imprime before/after sem enviar para a nuvem | Teste manual + verificação de ausência de chamada upstream |
| 8 | Prompts sem blocos de código são comprimidos normalmente | Teste unitário |
| 9 | Payloads com formato JSON inesperado fazem passthrough | Teste unitário |
| 10 | Nenhuma chave de API aparece hardcoded no código | Revisão de código + grep automatizado |

---

## 1.13 — Checklist de Implementação (Ordem de Execução)

- [ ] **1.13.1** Atualizar `EnvironmentConfig.java` com variáveis do Groq e dry-run
- [ ] **1.13.2** Implementar `PromptExtractor.java`
- [ ] **1.13.3** Implementar `CodeBlockExtractor.java`
- [ ] **1.13.4** Escrever testes unitários do `CodeBlockExtractor` (cobertura de edge cases)
- [ ] **1.13.5** Implementar `GroqCompressor.java`
- [ ] **1.13.6** Implementar `SanityCheck.java`
- [ ] **1.13.7** Escrever testes unitários do `SanityCheck`
- [ ] **1.13.8** Implementar `FailOpenCircuitBreaker.java`
- [ ] **1.13.9** Escrever testes do Circuit Breaker (timeout, erro, sucesso)
- [ ] **1.13.10** Implementar `CompressionMetrics.java`
- [ ] **1.13.11** Implementar `CompressionOrchestrator.java`
- [ ] **1.13.12** Implementar modo Dry-Run
- [ ] **1.13.13** Integrar compressão no `ProxyController.java`
- [ ] **1.13.14** Escrever testes de integração do pipeline completo
- [ ] **1.13.15** Sessão de calibração do system prompt via dry-run (20+ prompts reais)
- [ ] **1.13.16** Ajustar `GROQ_TIMEOUT_MS` baseado na telemetria da Fase 0

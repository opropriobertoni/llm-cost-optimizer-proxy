# Estratégia de Testes

> **Referência:** [ESTAP.md — Seção 6 (Governança Operacional)](../ESTAP.md)
>
> **Princípio:** Cada componente do ESTAP deve ser testável de forma isolada. Testes de integração validam o pipeline completo. Nenhum teste deve depender de APIs externas reais — todo I/O de rede é mockado.

---

## T.1 — Stack de Testes

| Ferramenta | Propósito |
|---|---|
| **JUnit 5** | Framework de testes unitários e de integração |
| **AssertJ** | Asserções fluentes e expressivas |
| **Mockito** | Mocking de dependências internas |
| **WireMock** | Simulação de APIs HTTP externas (upstream, Groq) |
| **OkHttp + OkHttp-SSE** | Cliente HTTP para testes de integração (incluindo consumo de SSE) |

---

## T.2 — Testes Unitários

### T.2.1 `EnvironmentConfigTest`

| Caso | Entrada | Resultado Esperado |
|---|---|---|
| Todas as variáveis presentes | `.env` completo | Record criado com valores corretos |
| Variável obrigatória ausente | `UPSTREAM_BASE_URL` faltando | `IllegalStateException` com nome da variável |
| Porta inválida (não-numérica) | `ESTAP_PORT=abc` | `IllegalStateException` |
| Dev mode como boolean | `ESTAP_DEV_MODE=false` | `devMode = false` |

### T.2.2 `CodeBlockExtractorTest`

| Caso | Entrada | Resultado Esperado |
|---|---|---|
| Prompt sem blocos de código | `"Crie uma função que soma dois números"` | `sanitizedText` idêntico ao original, `extractedBlocks` vazio |
| Um bloco de código simples | Texto com um bloco ` ```java ... ``` ` | Placeholder `{{CODE_BLOCK_0}}` no lugar do bloco |
| Múltiplos blocos de código | Texto com 3 blocos em linguagens diferentes | 3 placeholders sequenciais, 3 blocos extraídos |
| Bloco sem especificação de linguagem | ` ``` ... ``` ` (sem `java`, `python`, etc.) | Bloco extraído com `language = ""` |
| Blocos aninhados (edge case) | Bloco de código que contém ` ``` ` no conteúdo | Extração correta sem quebra de delimitadores |
| Inline code (backtick simples) | `` `variavel` `` no texto | **NÃO extrair** — apenas blocos de 3+ backticks |
| Reinserção byte-a-byte | Extrair e reintegrar | Texto final == texto original |

### T.2.3 `PromptExtractorTest`

| Caso | Entrada | Resultado Esperado |
|---|---|---|
| Formato Anthropic Messages API | `{"messages":[{"role":"user","content":"..."}]}` | Prompt extraído corretamente |
| Formato OpenAI Chat Completions | `{"messages":[{"role":"user","content":"..."}]}` | Prompt extraído corretamente |
| Content como array (multimodal) | `{"content":[{"type":"text","text":"..."}]}` | Texto extraído do elemento `type=text` |
| Payload sem mensagens do usuário | `{"messages":[{"role":"system","content":"..."}]}` | `Optional.empty()` |
| JSON malformado | `"não é JSON"` | `Optional.empty()` |
| Payload vazio | `""` | `Optional.empty()` |

### T.2.4 `SanityCheckTest`

| Caso | Entrada | Resultado Esperado |
|---|---|---|
| Compressão bem-sucedida | Comprimido < original, todos placeholders intactos | `passed = true`, ratio calculado |
| Comprimido maior que original | Comprimido >= original | `passed = false`, reason descritivo |
| Placeholder removido pelo Groq | `{{CODE_BLOCK_1}}` ausente no texto comprimido | `passed = false`, reason descritivo |
| Comprimido vazio | String vazia | `passed = false` |
| Comprimido igual ao original | Mesmo tamanho | `passed = false` (>=, não apenas >) |

### T.2.5 `FailOpenCircuitBreakerTest`

| Caso | Setup | Resultado Esperado |
|---|---|---|
| Compressão bem-sucedida | Groq retorna em tempo, sanity check OK | Payload comprimido retornado |
| Timeout do Groq | Groq demora > `GROQ_TIMEOUT_MS` | Payload original retornado + métrica TIMEOUT |
| Erro HTTP do Groq | Groq retorna 500 | Payload original retornado + métrica ERROR |
| Sanity check falha | Groq retorna, mas compressão é maior | Payload original retornado + métrica SANITY_CHECK_FAILED |
| Exceção inesperada | Groq lança `IOException` | Payload original retornado + métrica ERROR |

### T.2.6 `MetricsLoggerTest`

| Caso | Validação |
|---|---|
| Log de telemetria de rede | JSON one-line com campos obrigatórios, sem conteúdo de prompt |
| Log de compressão bem-sucedida | `type: COMPRESSION`, `failOpen: false` |
| Log de fail-open | `type: COMPRESSION`, `failOpen: true`, `reason` preenchido |
| Segregação de logs | Métricas de compressão e de rede são entradas de log distintas |

---

## T.3 — Testes de Integração

### T.3.1 Setup Compartilhado

Todos os testes de integração seguem o padrão:

1. **Iniciar WireMock** na porta dinâmica para simular o upstream (nuvem) e/ou o Groq.
2. **Iniciar o proxy ESTAP** apontando para o WireMock como upstream.
3. **Usar OkHttp** como cliente para enviar requisições ao proxy.
4. **Validar** a resposta recebida pelo cliente e os logs gerados.
5. **Tear down** tudo ao final.

```java
@BeforeEach → WireMock.start(), EstapApplication.start(testConfig)
@AfterEach  → EstapApplication.stop(), WireMock.stop()
```

### T.3.2 Testes de Integração — Fase 0

| Teste | Descrição | Validação |
|---|---|---|
| `proxyForwardsRequestIntact` | POST com body JSON ao proxy | WireMock recebe o mesmo body, headers essenciais preservados |
| `proxyForwardsResponseIntact` | WireMock retorna 200 com body | Cliente recebe o mesmo status, headers, body |
| `proxyStreamsSSE` | WireMock retorna SSE (múltiplos chunks com delay) | Cliente recebe chunks incrementais na ordem correta |
| `proxyHandlesUpstreamError` | WireMock retorna 500 | Cliente recebe 500 com body de erro original |
| `proxyHandlesUpstreamTimeout` | WireMock com delay > timeout | Cliente recebe 502 |
| `healthCheckResponds` | GET `/estap/health` | 200 com body JSON contendo `status` e `version` |
| `telemetryLogsMetrics` | Qualquer requisição completa | Log contém `requestBytes`, `responseBytes`, `upstreamMs` |

### T.3.3 Testes de Integração — Fase 1

| Teste | Descrição | Validação |
|---|---|---|
| `compressionPipelineEndToEnd` | POST com prompt em PT, WireMock como Groq retorna comprimido | Upstream recebe payload com prompt comprimido, resposta streaming funciona |
| `failOpenOnGroqTimeout` | WireMock Groq com delay excessivo | Upstream recebe payload original intocado |
| `failOpenOnSanityFailure` | WireMock Groq retorna texto maior que o original | Upstream recebe payload original intocado |
| `dryRunMode` | `ESTAP_DRY_RUN=true`, POST com prompt | Nenhuma chamada ao upstream, resposta contém relatório |
| `codeBlocksPreservedEndToEnd` | Prompt com 3 blocos de código | Upstream recebe payload com blocos idênticos ao original |
| `noCompressionForUnknownPayload` | POST com JSON que não segue formato conhecido | Upstream recebe payload original, sem chamada ao Groq |
| `metricsSegregation` | Requisição com compressão + requisição com fail-open | Logs contêm métricas separadas para cada tipo |

---

## T.4 — Testes de Validação Semântica (Semi-Automatizados)

Estes testes não são unitários — são sessões de dry-run para validar a qualidade da compressão. Devem ser executados manualmente antes de habilitar a compressão em produção.

### T.4.1 Corpus de Teste

Criar um diretório `src/test/resources/prompts/` com pelo menos 20 prompts representativos:

```
prompts/
├── 01_simple_instruction.txt         # "Crie uma função que soma dois números"
├── 02_verbose_explanation.txt        # Parágrafo longo explicando uma feature
├── 03_with_single_code_block.txt     # Texto com um bloco Java
├── 04_with_multiple_code_blocks.txt  # Texto com 3+ blocos em linguagens distintas
├── 05_mixed_code_and_prose.txt       # Alternância entre prosa e código
├── 06_pure_code_no_prose.txt         # Apenas blocos de código (edge case)
├── 07_english_prompt.txt             # Prompt já em inglês
├── 08_short_prompt.txt               # Prompt de 1 linha
├── 09_long_architectural.txt         # Prompt longo sobre arquitetura
├── 10_with_file_paths.txt            # Referências a paths de arquivos
├── 11_with_env_variables.txt         # Referências a variáveis de ambiente
├── 12_with_error_logs.txt            # Prompt colando logs de erro
├── 13_refactoring_request.txt        # Pedido de refatoração com código
├── 14_debugging_request.txt          # Pedido de debug com stacktrace
├── 15_config_file_edit.txt           # Pedido de edição de YAML/JSON
├── 16_git_operations.txt             # Instruções envolvendo Git
├── 17_database_query.txt             # Prompt sobre SQL/queries
├── 18_api_design.txt                 # Design de API REST
├── 19_test_writing.txt               # Pedido para escrever testes
└── 20_multiline_complex.txt          # Cenário mais complexo possível
```

### T.4.2 Script de Validação Batch

Criar um script ou classe utilitária que:

1. Itera sobre todos os arquivos em `prompts/`.
2. Para cada prompt:
   a. Executa o pipeline de compressão (extração, Groq, sanity check).
   b. Imprime o relatório dry-run.
   c. Registra: nome do arquivo, tamanho original, tamanho comprimido, ratio, sanity check pass/fail.
3. Ao final, imprime a tabela consolidada:

```
╔═══════════════════════════════════════════════════════════════════════╗
║                    ESTAP BATCH DRY-RUN REPORT                        ║
╠══════════════════════════════╦═══════╦════════╦═══════╦══════════════╣
║ Prompt                       ║ Orig  ║ Comp   ║ Ratio ║ Sanity Check ║
╠══════════════════════════════╬═══════╬════════╬═══════╬══════════════╣
║ 01_simple_instruction.txt    ║   234 ║    112 ║ 52.1% ║ PASSED       ║
║ 02_verbose_explanation.txt   ║  1847 ║    723 ║ 60.9% ║ PASSED       ║
║ ...                          ║       ║        ║       ║              ║
╠══════════════════════════════╬═══════╬════════╬═══════╬══════════════╣
║ MÉDIA                        ║  1203 ║    498 ║ 58.6% ║ 19/20 PASS   ║
╚══════════════════════════════╩═══════╩════════╩═══════╩══════════════╝
```

### T.4.3 Critérios de Aprovação

| Critério | Limiar |
|---|---|
| Média de compressão | ≥ 30% |
| Sanity check pass rate | 100% (nenhum placeholder corrompido) |
| Blocos de código idênticos após reinserção | 100% |
| Prompts que pioram (ratio negativo) | Automaticamente excluídos via sanity check (fail-open) |

---

## T.5 — Estrutura de Diretórios de Teste

```
src/test/
├── java/
│   └── dev/estap/
│       ├── config/
│       │   └── EnvironmentConfigTest.java
│       ├── compression/
│       │   ├── CodeBlockExtractorTest.java
│       │   ├── GroqCompressorTest.java
│       │   ├── SanityCheckTest.java
│       │   └── CompressionOrchestratorTest.java
│       ├── circuitbreaker/
│       │   └── FailOpenCircuitBreakerTest.java
│       ├── proxy/
│       │   ├── ProxyControllerTest.java
│       │   ├── StreamingRelayTest.java
│       │   └── PromptExtractorTest.java
│       ├── telemetry/
│       │   └── MetricsLoggerTest.java
│       └── integration/
│           ├── Phase0IntegrationTest.java
│           ├── Phase1IntegrationTest.java
│           └── DryRunIntegrationTest.java
└── resources/
    └── prompts/
        ├── 01_simple_instruction.txt
        ├── ...
        └── 20_multiline_complex.txt
```

---

## T.6 — Comandos de Execução

```bash
# Executar todos os testes
./gradlew test

# Executar apenas testes unitários (excluir integração)
./gradlew test --tests "dev.estap.compression.*" --tests "dev.estap.circuitbreaker.*"

# Executar apenas testes de integração
./gradlew test --tests "dev.estap.integration.*"

# Executar com relatório de cobertura (requer plugin JaCoCo)
./gradlew test jacocoTestReport
```

---

## T.7 — Checklist de Testes (Ordem de Implementação)

### Fase 0
- [ ] **T.7.1** Testes unitários de `EnvironmentConfig`
- [ ] **T.7.2** Testes unitários de `MetricsLogger`
- [ ] **T.7.3** Testes de integração: proxy passthrough (request/response)
- [ ] **T.7.4** Testes de integração: SSE streaming
- [ ] **T.7.5** Testes de integração: erro upstream e timeout
- [ ] **T.7.6** Teste de health check

### Fase 1
- [ ] **T.7.7** Testes unitários de `CodeBlockExtractor`
- [ ] **T.7.8** Testes unitários de `PromptExtractor`
- [ ] **T.7.9** Testes unitários de `SanityCheck`
- [ ] **T.7.10** Testes unitários de `FailOpenCircuitBreaker`
- [ ] **T.7.11** Testes de integração: pipeline de compressão completo
- [ ] **T.7.12** Testes de integração: fail-open (timeout, sanity)
- [ ] **T.7.13** Testes de integração: dry-run
- [ ] **T.7.14** Criar corpus de prompts (20 arquivos)
- [ ] **T.7.15** Implementar script de validação batch
- [ ] **T.7.16** Sessão de calibração do system prompt (iterativa)

# ESTAP — Edge-Side Token Arbitrage Proxy

Um proxy HTTP transparente que intercepta requisições de IDEs agênticas para APIs de LLMs, comprime semanticamente os prompts do usuário usando um modelo leve e de baixo custo (Groq/Llama 3.3), e repassa o payload otimizado ao modelo principal na nuvem — reduzindo o consumo de tokens de entrada sem alterar a experiência do desenvolvedor.

---

## Lições Aprendidas / Trade-offs

*   **Compressão de Prosa vs. Redução Total:** A análise empírica revelou que o motor de compressão de linguagem natural opera com alta eficiência (média de **28,31%** de redução em tokens de prosa). Contudo, a taxa de redução total do payload (que inclui blocos de código protegidos e intocáveis por design) é naturalmente menor (**23,69%**), diluída pela proporção de código no prompt. Essa diluição é uma restrição de segurança inegociável, não uma falha de calibração. Consulte a [Seção 4.5 do RESULTS.md](docs/RESULTS.md) para a análise completa.
*   **A Realidade Financeira:** A infraestrutura roda a custo zero no Cloud Run e Groq (Free Tiers), mas o ROI financeiro para um desenvolvedor solo é irrelevante (frações de centavos). O valor da solução se prova em escala corporativa ou volumes massivos de requisições.
*   **A Troca de Latência:** O sistema troca ~554ms de tempo de rede por prompts mais limpos na nuvem principal.
*   **A Importância da Telemetria:** Os testes de campo mostraram que os limites de requisição por minuto (TPM) do Groq gratuito são o principal ponto de falha, tornando o mecanismo de log segregado e o fail-open as partes mais importantes do código backend.

---

## O Problema

IDEs agênticas modernas (Antigravity, Cursor, Windsurf) enviam grandes volumes de tokens para APIs de modelos como Claude, GPT-4 e Gemini. Boa parte desses tokens são prompts conversacionais redigidos em linguagem natural — frequentemente em Português ou outro idioma que não o Inglês — que poderiam ser comprimidos semanticamente sem perda de contexto.

O custo de tokens de entrada cresce linearmente com o volume de uso. Em equipes ou cenários de uso intensivo, essa conta pode atingir centenas de dólares por mês.

## A Solução

O ESTAP se posiciona como um middleware invisível entre a IDE e a API do modelo principal:

```
IDE (requisição original em PT-BR)
 │
 ▼
ESTAP Proxy
 ├── Extrai o prompt do usuário do payload JSON
 ├── Protege blocos de código com placeholders imutáveis (Camada 1)
 ├── Envia apenas o texto natural ao Groq/Llama 3.3
 ├── Recebe versão comprimida (traduzida para EN + compactada)
 ├── Valida integridade via jtokkit em tokens reais (Camada 2)
 ├── Recompõe o payload com o prompt otimizado
 │
 ▼
API do modelo principal (Claude, GPT, Gemini...)
 │
 ▼
IDE (resposta original, inalterada)
```

O desenvolvedor não percebe nenhuma mudança. A resposta retornada é idêntica à que seria gerada sem o proxy.

---

## Resultados Empíricos

Calibração executada com 20 prompts reais de desenvolvimento em Português Brasileiro, cobrindo Java, Node.js, Python, SQL, Docker, CI/CD e mais. Todas as medições utilizam contagem oficial de **tokens** via biblioteca `jtokkit` (codificação `CL100K_BASE`, padrão de GPT-4 e Claude):

| Métrica | Total (Prosa + Código) | Prosa (só linguagem natural) |
|---|---|---|
| **Taxa de compressão média** | **23,69%** | **28,31%** |
| Compressão máxima | 39,02% (Python Memory Leak) | **52,08%** (React Hooks State) |
| Compressão mínima | 7,32% (JWT Auth Flow) | 7,32% (JWT Auth Flow) |
| Compressões bem-sucedidas | **20/20 (100%)** | **20/20 (100%)** |
| Acionamentos de fail-open | **0/20 (0%)** | — |
| Latência adicional média (warm) | **554 ms** | — |
| Testes unitários e de integração | **47/47 passando** | — |

### Por que duas taxas?

Prompts que contêm blocos de código protegidos (extraídos como `{{CODE_BLOCK_N}}` antes da compressão) diluem a taxa total de redução, já que o código permanece 100% intacto por design. Exemplo: no prompt *React Hooks State*, o motor comprimiu a prosa com **52,08%** de eficiência, mas como 60% do payload era código JSX protegido, a redução total percebida na fatura da API foi de **20,83%**. A fórmula:

$$\text{Redução Total} = \text{Redução de Prosa} \times \left( \frac{\text{Tokens de Prosa}}{\text{Tokens Totais}} \right)$$

> Os resultados detalhados, incluindo dados por prompt, validação remota no Cloud Run, e análise do efeito de diluição, estão em [`docs/RESULTS.md`](docs/RESULTS.md).

---

## Arquitetura

```
src/main/java/dev/estap/
├── EstapApplication.java              # Entrypoint — servidor Javalin
├── config/
│   └── EnvironmentConfig.java         # Carregamento e validação de variáveis
├── proxy/
│   ├── ProxyController.java           # Handler principal — intercepta e delega
│   ├── PromptExtractor.java           # Localiza o prompt do usuário no payload
│   └── StreamingRelay.java            # Piping de SSE (streaming token a token)
├── compression/
│   ├── CodeBlockExtractor.java        # Extrai blocos de código → placeholders
│   ├── GroqCompressor.java            # Cliente síncrono da API do Groq
│   ├── SanityCheck.java               # Validação em 2 camadas (jtokkit tokens)
│   └── CompressionOrchestrator.java   # Coordena o pipeline completo
├── circuitbreaker/
│   └── FailOpenCircuitBreaker.java    # Timeout rígido com fallback transparente
└── telemetry/
    ├── RequestMetrics.java            # Dados de latência de rede (imutável)
    ├── CompressionMetrics.java        # Dados de compressão — total + prosa (imutável)
    ├── MetricsLogger.java             # Serialização JSON estruturada
    └── PayloadAnalyzer.java           # Anatomia do payload (sem expor segredos)
```

### Princípios de Design

- **Fail-open, nunca fail-closed.** Se o Groq estiver lento, fora do ar ou retornar lixo, o proxy simplesmente envia o prompt original intacto. O desenvolvedor nunca vê um erro causado pelo ESTAP.
- **Código é intocável.** Blocos de código Markdown são extraídos antes da compressão e recolocados depois, usando placeholders `{{CODE_BLOCK_N}}`. O Llama 3.3 nunca vê nem toca o código.
- **Validação em tokens reais.** O `SanityCheck` usa a biblioteca `jtokkit` com a codificação `CL100K_BASE` para comparar tokens reais (não bytes), capturando corretamente o ganho cross-lingual PT→EN que uma medição em bytes não enxerga. A compressão só é aplicada se o resultado for categoricamente menor em tokens.
- **Métricas segregadas.** A telemetria distingue explicitamente entre a compressão de prosa (eficiência do motor) e a redução total do payload (impacto financeiro na fatura). Fail-opens são contabilizados separadamente, permitindo diagnosticar se uma queda de eficiência é causada por lentidão de rede ou por qualidade do prompt — dois problemas com soluções completamente diferentes.
- **Zero logging de dados sensíveis.** Prompts, código e chaves de API nunca são registrados em logs. A telemetria contém apenas números: latência (ms), contagens de tokens e flags (bool).

---

## Prós e Contras

### Prós

| Vantagem | Detalhe |
|---|---|
| **Redução real de custos** | 23,69% de economia média total em tokens de input (28,31% na prosa), validado empiricamente com 20 prompts reais via jtokkit |
| **Transparente para o usuário** | A IDE e o modelo principal não sabem que o proxy existe; a resposta é idêntica |
| **Resiliência total** | Fail-open garante que qualquer falha do Groq (timeout, rate limit, erro de API) resulta na entrega do prompt original, sem impacto |
| **Custo de operação zero** | Groq free tier (6.000 req/dia) + Cloud Run free tier (scale-to-zero) = R$0/mês para uso pessoal |
| **Compressão + Tradução** | Prompts em Português são simultaneamente traduzidos para Inglês, o que por si só melhora a qualidade de resposta de modelos treinados predominantemente em inglês |
| **Preservação absoluta de código** | Blocos de código passam intactos pelo proxy — zero risco de adulteração de sintaxe |
| **Deploy serverless** | Scale-to-zero no Cloud Run: paga apenas pelo que usa, sem servidor ocioso |
| **Observabilidade granular** | Métricas segregadas (prosa vs. total vs. fail-open) permitem diagnosticar problemas com precisão cirúrgica |

### Contras

| Limitação | Detalhe |
|---|---|
| **Latência adicional** | +554 ms em média por requisição (warm). Requisições frias podem ter TLS handshake de 1-2s, mas o circuit breaker dispara fail-open antes disso afetar o usuário |
| **Diluição por código protegido** | Prompts com proporção alta de blocos de código (code-heavy) apresentam redução total menor. Ex.: um prompt 60% código atinge apenas ~20% de redução total, mesmo com 52% de compressão na prosa |
| **Compressão variável** | Prompts curtos ou muito técnicos (ex.: "configure JWT") comprimem pouco (7-13%). O ganho é expressivo em prompts conversacionais de 40+ tokens |
| **Rate limit do Groq (free tier)** | 12.000 tokens/min e 6.000 req/dia. Requisições em rajada podem estourar a cota — o fail-open trata graciosamente, mas perde a oportunidade de compressão |
| **Não atua em outputs** | Por design, o proxy comprime apenas os prompts de entrada. Respostas do modelo principal passam intactas |
| **Dependência de terceiro** | A compressão depende da disponibilidade da API do Groq. Se o Groq cair por horas, o proxy funciona normalmente mas sem economia de tokens durante o período |

---

## Pré-requisitos

- **Java 21+** (o projeto usa Gradle Toolchain com auto-provisionamento — veja a seção de instalação)
- **Conta no Groq** (gratuita) — obtenha sua API key em [console.groq.com](https://console.groq.com)
- **Chave de API do modelo upstream** (Anthropic, OpenAI, Google, etc.)
- **Google Cloud SDK** (apenas para deploy no Cloud Run — opcional para uso local)

---

## Instalação e Uso Local

### 1. Clonar o repositório

```bash
git clone https://github.com/opropriobertoni/llm-cost-optimizer-proxy.git
cd llm-cost-optimizer-proxy
```

### 2. Configurar variáveis de ambiente

```bash
cp .env.example .env
```

Edite o arquivo `.env` com suas credenciais reais:

```env
# Porta do proxy local
ESTAP_PORT=8080

# URL base da API upstream (ex.: Anthropic)
UPSTREAM_BASE_URL=https://api.anthropic.com

# Sua chave de API do modelo principal
UPSTREAM_API_KEY=sk-ant-xxx

# Sua chave de API do Groq (gratuita)
GROQ_API_KEY=gsk_xxx

# Modelo do Groq para compressão
GROQ_MODEL=llama-3.3-70b-versatile

# URL da API do Groq
GROQ_API_URL=https://api.groq.com/openai/v1/chat/completions

# Timeout do circuit breaker (ms)
GROQ_TIMEOUT_MS=1000

# Modo de desenvolvimento (logs detalhados de payload)
ESTAP_DEV_MODE=true

# Dry-run: imprime relatórios de compressão sem enviar ao upstream
ESTAP_DRY_RUN=false
```

### 3. Rodar os testes

O projeto possui **47 testes** automatizados (unitários + integração) cobrindo todas as camadas:

```bash
./gradlew test --no-daemon
```

Espere algo como:

```
> Task :test
BUILD SUCCESSFUL in 4m 33s
```

| Suíte | Testes | Cobertura |
|---|---|---|
| Fase 0 — Walking Skeleton | 18 | Proxy transparente, SSE streaming, telemetria de rede |
| Fase 1 — Motor de Compressão | 29 | CodeBlockExtractor, GroqCompressor, SanityCheck (jtokkit), CompressionOrchestrator, FailOpenCircuitBreaker, integração ponta a ponta |
| **Total** | **47** | **✅ 47/47 PASS** |

### 4. Iniciar o proxy

```bash
./gradlew run --no-daemon
```

O proxy estará ouvindo em `http://localhost:8080`. Configure sua IDE para apontar para esse endereço em vez de diretamente para a API do modelo.

### 5. Testar com dry-run (recomendado na primeira vez)

Defina `ESTAP_DRY_RUN=true` no `.env` e inicie o proxy. Todas as requisições serão interceptadas e comprimidas, mas o resultado será apenas registrado em log — a requisição original é enviada intacta ao upstream.

O relatório JSON de dry-run retornado para cada requisição inclui:

```json
{
  "dryRun": true,
  "compressionApplied": true,
  "originalTokens": 120,
  "compressedTokens": 95,
  "compressionRatio": 20.83,
  "proseOriginalTokens": 48,
  "proseCompressedTokens": 23,
  "proseCompressionRatio": 52.08,
  "groqLatencyMs": 491,
  "codeBlocksCount": 1,
  "failOpenTriggered": false,
  "failOpenReason": "NONE"
}
```

Confira nos logs os relatórios de compressão total vs. prosa para validar visualmente a qualidade da compressão e entender o efeito de diluição por código protegido.

---

## Deploy no Google Cloud Run

Para quem quer rodar o proxy na nuvem com custo zero (free tier), sem consumir recursos da máquina local.

### 1. Configurar o projeto GCP

```bash
gcloud config set project SEU_PROJETO_ID
```

### 2. Habilitar as APIs necessárias

```bash
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com
```

### 3. Criar repositório no Artifact Registry

```bash
gcloud artifacts repositories create estap-repo \
  --repository-format=docker \
  --location=southamerica-east1
```

### 4. Configurar segredos no Secret Manager

```bash
echo -n "SUA_CHAVE_GROQ" | \
  gcloud secrets create groq-api-key --data-file=-

echo -n "SUA_CHAVE_UPSTREAM" | \
  gcloud secrets create upstream-api-key --data-file=-
```

Conceda acesso à service account do Cloud Run:

```bash
PROJECT_NUMBER=$(gcloud projects describe $(gcloud config get-value project) --format="value(projectNumber)")

gcloud secrets add-iam-policy-binding groq-api-key \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

gcloud secrets add-iam-policy-binding upstream-api-key \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

### 5. Build e deploy

```bash
gcloud builds submit --tag \
  southamerica-east1-docker.pkg.dev/SEU_PROJETO_ID/estap-repo/estap:latest

gcloud run deploy estap \
  --image southamerica-east1-docker.pkg.dev/SEU_PROJETO_ID/estap-repo/estap:latest \
  --region southamerica-east1 \
  --port 8080 \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 3 \
  --timeout 300 \
  --concurrency 80 \
  --set-env-vars "ESTAP_PORT=8080,UPSTREAM_BASE_URL=https://api.anthropic.com,GROQ_MODEL=llama-3.3-70b-versatile,GROQ_API_URL=https://api.groq.com/openai/v1/chat/completions,GROQ_TIMEOUT_MS=1000,ESTAP_DEV_MODE=false,ESTAP_DRY_RUN=false" \
  --set-secrets "UPSTREAM_API_KEY=upstream-api-key:latest,GROQ_API_KEY=groq-api-key:latest"
```

### 6. Acessar via túnel seguro (se sua organização bloqueia acesso público)

```bash
gcloud run services proxy estap --region=southamerica-east1 --port=8080
```

O proxy estará disponível em `http://localhost:8080`, autenticado automaticamente com suas credenciais GCP.

---

## Configuração da IDE

Após iniciar o proxy (local ou via túnel), aponte sua IDE para o endereço do ESTAP em vez de diretamente para a API do modelo:

| Cenário | URL da API |
|---|---|
| Proxy local | `http://localhost:8080/v1/messages` |
| Cloud Run (via túnel) | `http://localhost:8080/v1/messages` |
| Cloud Run (direto, se público) | `https://SEU_SERVICO.run.app/v1/messages` |

O ESTAP é transparente: ele encaminha a requisição na mesma estrutura que a IDE espera. Headers de autenticação são reescritos automaticamente com a chave configurada em `UPSTREAM_API_KEY`.

---

## Monitoramento

O ESTAP emite métricas estruturadas em JSON via `stdout`, capturadas automaticamente pelo Cloud Logging no Cloud Run.

### Métricas de Compressão (por requisição)

```json
{
  "type": "COMPRESSION",
  "requestId": "abc-123",
  "compressionApplied": true,
  "failOpenTriggered": false,
  "failOpenReason": "NONE",
  "originalTokens": 92,
  "compressedTokens": 68,
  "compressionRatio": 26.09,
  "proseOriginalTokens": 32,
  "proseCompressedTokens": 19,
  "proseCompressionRatio": 40.63,
  "groqLatencyMs": 482,
  "codeBlocksCount": 1
}
```

| Campo | Descrição |
|---|---|
| `originalTokens` / `compressedTokens` | Tokens totais do payload (prosa + código) antes e depois |
| `compressionRatio` | Redução percentual total do payload em tokens |
| `proseOriginalTokens` / `proseCompressedTokens` | Tokens exclusivamente de linguagem natural (sem código protegido) |
| `proseCompressionRatio` | Eficiência real do motor de compressão, sem diluição por código |
| `failOpenTriggered` / `failOpenReason` | Se o circuit breaker foi acionado e o motivo (TIMEOUT, RATE_LIMIT, SANITY_CHECK_FAILED) |

### Dashboard no Cloud Monitoring

O arquivo [`scripts/monitoring_dashboard.json`](scripts/monitoring_dashboard.json) contém a configuração do dashboard operacional com 4 painéis: Request Count, Latência P95, Fail-Open Count e Successful Compressions.

Para importar:

```bash
gcloud monitoring dashboards create \
  --config-from-file=scripts/monitoring_dashboard.json
```

---

## Stack Tecnológica

| Componente | Tecnologia | Justificativa |
|---|---|---|
| Linguagem | Java 21 | Tipagem estrita, `HttpClient` nativo, performance de runtime |
| Framework Web | Javalin 6.6 | Overhead mínimo, inicialização sub-segundo, suporte nativo a SSE |
| Motor de Compressão | Groq API + Llama 3.3 70B | Inferência ultrarrápida (~500ms), free tier generoso |
| Tokenizador | jtokkit 1.1.0 (CL100K_BASE) | Contagem real de tokens compatível com GPT-4/Claude para validação e métricas |
| Serialização | Jackson 2.18 | Manipulação segura de payloads JSON |
| Container | Docker (Alpine JRE 21) | Imagem leve com ZGC para GC eficiente |
| Infraestrutura | Google Cloud Run | Scale-to-zero, free tier, deploy em segundos |
| Segredos | GCP Secret Manager | Injeção segura de credenciais sem variáveis em código |
| Testes | JUnit 5, WireMock, Mockito, OkHttp | 47 testes cobrindo proxy, compressão, circuit breaker e integração ponta a ponta |

---

## Documentação

| Documento | Descrição |
|---|---|
| [`ESTAP.md`](ESTAP.md) | Especificação arquitetural original do projeto |
| [`docs/INDEX.md`](docs/INDEX.md) | Índice da documentação técnica |
| [`docs/RESULTS.md`](docs/RESULTS.md) | Resultados experimentais detalhados (calibração com tokens, efeito de diluição por código, validação remota, análise econômica) |
| [`docs/PHASE_0_WALKING_SKELETON.md`](docs/PHASE_0_WALKING_SKELETON.md) | Especificação da Fase 0 |
| [`docs/PHASE_1_COMPRESSION_ENGINE.md`](docs/PHASE_1_COMPRESSION_ENGINE.md) | Especificação da Fase 1 |
| [`docs/DEPLOY_AND_OPERATIONS.md`](docs/DEPLOY_AND_OPERATIONS.md) | Guia de deploy e operações |
| [`docs/TESTING_STRATEGY.md`](docs/TESTING_STRATEGY.md) | Estratégia de testes |

---

## Licença

Este projeto é de uso pessoal e educacional. Consulte o autor antes de redistribuir ou usar comercialmente.

# ESTAP — Edge-Side Token Arbitrage Proxy

Um proxy HTTP transparente que intercepta requisições de IDEs agênticas para APIs de LLMs, comprime semanticamente os prompts do usuário usando um modelo leve e de baixo custo (Groq/Llama 3.3), e repassa o payload otimizado ao modelo principal na nuvem — reduzindo o consumo de tokens de entrada sem alterar a experiência do desenvolvedor.

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
 ├── Protege blocos de código com placeholders imutáveis
 ├── Envia apenas o texto natural ao Groq/Llama 3.3
 ├── Recebe versão comprimida (traduzida para EN + compactada)
 ├── Valida integridade (sanity check em 2 camadas)
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

Calibração executada com 20 prompts reais de desenvolvimento em Português Brasileiro, cobrindo Java, Node.js, Python, SQL, Docker, CI/CD e mais:

| Métrica | Valor |
|---|---|
| Taxa de compressão média | **22,26%** |
| Compressões bem-sucedidas | **20/20 (100%)** |
| Acionamentos de fail-open | **0/20 (0%)** |
| Compressão mínima | 5,49% |
| Compressão máxima | 37,50% |
| Latência adicional média (warm) | **554 ms** |
| Testes unitários e de integração | **47/47 passando** |

> Os resultados detalhados, incluindo dados de validação remota no Cloud Run, estão em [`docs/RESULTS.md`](docs/RESULTS.md).

---

## Arquitetura

```
src/main/java/dev/estap/
├── EstapApplication.java              # Entrypoint — servidor Javalin
├── config/
│   └── EnvironmentConfig.java         # Carregamento e validação de variáveis
├── proxy/
│   ├── ProxyController.java           # Handler principal — intercepta e delega
│   └── StreamingRelay.java            # Piping de SSE (streaming token a token)
├── compression/
│   ├── PromptExtractor.java           # Localiza o prompt do usuário no payload
│   ├── CodeBlockExtractor.java        # Extrai blocos de código → placeholders
│   ├── GroqCompressor.java            # Cliente síncrono da API do Groq
│   ├── SanityCheck.java               # Validação em 2 camadas (integridade + tamanho)
│   └── CompressionOrchestrator.java   # Coordena o pipeline completo
├── circuitbreaker/
│   └── FailOpenCircuitBreaker.java    # Timeout rígido com fallback transparente
└── telemetry/
    ├── RequestMetrics.java            # Dados de latência de rede (imutável)
    ├── CompressionMetrics.java        # Dados de compressão (imutável)
    ├── MetricsLogger.java             # Serialização JSON estruturada
    └── PayloadAnalyzer.java           # Anatomia do payload (sem expor segredos)
```

### Princípios de Design

- **Fail-open, nunca fail-closed.** Se o Groq estiver lento, fora do ar ou retornar lixo, o proxy simplesmente envia o prompt original intacto. O desenvolvedor nunca vê um erro causado pelo ESTAP.
- **Código é intocável.** Blocos de código Markdown são extraídos antes da compressão e recolocados depois, usando placeholders `{{CODE_BLOCK_N}}`. O Llama 3.3 nunca vê nem toca o código.
- **Validação dupla.** Mesmo que o Llama 3.3 retorne algo inesperado, o SanityCheck rejeita respostas que expandem o prompt ou corrompem placeholders. A compressão só é aplicada se for categoricamente menor.
- **Zero logging de dados sensíveis.** Prompts, código e chaves de API nunca são registrados em logs. A telemetria contém apenas números: latência (ms), tamanhos (bytes) e flags (bool).

---

## Prós e Contras

### Prós

| Vantagem | Detalhe |
|---|---|
| **Redução real de custos** | 22% de economia média em tokens de input, validado empiricamente com 20 prompts reais |
| **Transparente para o usuário** | A IDE e o modelo principal não sabem que o proxy existe; a resposta é idêntica |
| **Resiliência total** | Fail-open garante que qualquer falha do Groq (timeout, rate limit, erro de API) resulta na entrega do prompt original, sem impacto |
| **Custo de operação zero** | Groq free tier (6.000 req/dia) + Cloud Run free tier (scale-to-zero) = R$0/mês para uso pessoal |
| **Compressão + Tradução** | Prompts em Português são simultaneamente traduzidos para Inglês, o que por si só melhora a qualidade de resposta de modelos treinados predominantemente em inglês |
| **Preservação absoluta de código** | Blocos de código passam intactos pelo proxy — zero risco de adulteração de sintaxe |
| **Deploy serverless** | Scale-to-zero no Cloud Run: paga apenas pelo que usa, sem servidor ocioso |

### Contras

| Limitação | Detalhe |
|---|---|
| **Latência adicional** | +554 ms em média por requisição (warm). Requisições frias podem ter TLS handshake de 1-2s, mas o circuit breaker dispara fail-open antes disso afetar o usuário |
| **Compressão variável** | Prompts curtos ou muito técnicos (ex.: "configure JWT") comprimem pouco (5-13%). O ganho é mais expressivo em prompts conversacionais de 200+ bytes |
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

# Timeout do circuit breaker (ms)
GROQ_TIMEOUT_MS=1000

# Modo de desenvolvimento (logs detalhados de payload)
ESTAP_DEV_MODE=true

# Dry-run: imprime relatórios de compressão sem enviar ao upstream
ESTAP_DRY_RUN=false
```

### 3. Rodar os testes

```bash
./gradlew test --no-daemon
```

### 4. Iniciar o proxy

```bash
./gradlew run --no-daemon
```

O proxy estará ouvindo em `http://localhost:8080`. Configure sua IDE para apontar para esse endereço em vez de diretamente para a API do modelo.

### 5. Testar com dry-run (recomendado na primeira vez)

Defina `ESTAP_DRY_RUN=true` no `.env` e inicie o proxy. Todas as requisições serão interceptadas e comprimidas, mas o resultado será apenas registrado em log — a requisição original é enviada intacta ao upstream. Confira nos logs os relatórios de "Antes" e "Depois" para validar visualmente a qualidade da compressão.

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
  "originalSizeBytes": 304,
  "compressedSizeBytes": 205,
  "compressionRatio": 0.3257,
  "groqLatencyMs": 482,
  "codeBlocksCount": 0
}
```

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
| Serialização | Jackson 2.18 | Manipulação segura de payloads JSON |
| Container | Docker (Alpine JRE 21) | Imagem leve com ZGC para GC eficiente |
| Infraestrutura | Google Cloud Run | Scale-to-zero, free tier, deploy em segundos |
| Segredos | GCP Secret Manager | Injeção segura de credenciais sem variáveis em código |
| Testes | JUnit 5, WireMock, Mockito, OkHttp | Cobertura completa com mocks de APIs externas |

---

## Documentação

| Documento | Descrição |
|---|---|
| [`ESTAP.md`](ESTAP.md) | Especificação arquitetural original do projeto |
| [`docs/INDEX.md`](docs/INDEX.md) | Índice da documentação técnica |
| [`docs/RESULTS.md`](docs/RESULTS.md) | Resultados experimentais detalhados (calibração, validação remota, análise econômica) |
| [`docs/PHASE_0_WALKING_SKELETON.md`](docs/PHASE_0_WALKING_SKELETON.md) | Especificação da Fase 0 |
| [`docs/PHASE_1_COMPRESSION_ENGINE.md`](docs/PHASE_1_COMPRESSION_ENGINE.md) | Especificação da Fase 1 |
| [`docs/DEPLOY_AND_OPERATIONS.md`](docs/DEPLOY_AND_OPERATIONS.md) | Guia de deploy e operações |
| [`docs/TESTING_STRATEGY.md`](docs/TESTING_STRATEGY.md) | Estratégia de testes |

---

## Licença

Este projeto é de uso pessoal e educacional. Consulte o autor antes de redistribuir ou usar comercialmente.

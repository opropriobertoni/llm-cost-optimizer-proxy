# Fase 0: The Walking Skeleton

> **Referência:** [ESTAP.md — Seção 3, Fase 0](../ESTAP.md)
>
> **Objetivo:** Provar que o proxy consegue sequestrar a rota IDE → Nuvem de forma estável, fazendo piping assíncrono de SSE token a token, sem lógica de compressão. Todo o tráfego passa intocado.

---

## 0.1 — Bootstrap do Projeto

### 0.1.1 Inicialização com Gradle (Kotlin DSL)

Criar o projeto Gradle na raiz `/estap` com a seguinte estrutura de diretórios:

```
estap/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .env.example            # Template de variáveis (sem valores reais)
├── .gitignore
├── ESTAP.md
├── docs/
│   └── (documentação de fases)
└── src/
    ├── main/
    │   ├── java/
    │   │   └── dev/estap/
    │   │       ├── EstapApplication.java
    │   │       ├── config/
    │   │       │   └── EnvironmentConfig.java
    │   │       ├── proxy/
    │   │       │   ├── ProxyController.java
    │   │       │   └── StreamingRelay.java
    │   │       └── telemetry/
    │   │           ├── RequestMetrics.java
    │   │           └── MetricsLogger.java
    │   └── resources/
    │       └── logback.xml
    └── test/
        └── java/
            └── dev/estap/
                ├── proxy/
                │   ├── ProxyControllerTest.java
                │   └── StreamingRelayTest.java
                └── telemetry/
                    └── MetricsLoggerTest.java
```

### 0.1.2 Dependências (`build.gradle.kts`)

```kotlin
plugins {
    java
    application
    id("com.github.johnrgraham.shadow") version "8.1.1" // fat JAR para Docker
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.estap.EstapApplication"
}

repositories {
    mavenCentral()
}

dependencies {
    // --- Web Framework ---
    implementation("io.javalin:javalin:6.6.0")

    // --- JSON ---
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    // --- Environment ---
    implementation("io.github.cdimascio:dotenv-java:3.1.0")

    // --- Logging (exigido pelo Javalin/Jetty) ---
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // --- Testes ---
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.wiremock:wiremock:3.10.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

> **Nota sobre versões:** As versões listadas são referência para Julho/2026. Antes de implementar, verificar as últimas releases estáveis de cada dependência no Maven Central.

### 0.1.3 Configuração de Ambiente (`.env.example`)

```dotenv
# === ESTAP — Variáveis de Ambiente ===

# Porta do proxy local
ESTAP_PORT=8080

# URL base da API do modelo principal na nuvem
# Exemplos:
#   Anthropic: https://api.anthropic.com
#   Google:    https://generativelanguage.googleapis.com
UPSTREAM_BASE_URL=https://api.anthropic.com

# Chave de API do modelo principal
UPSTREAM_API_KEY=sk-ant-xxx

# Modo de desenvolvimento (habilita logs de payload anatomy)
ESTAP_DEV_MODE=true
```

### 0.1.4 `.gitignore`

```gitignore
# Build
build/
.gradle/

# IDE
.idea/
*.iml
.vscode/
.settings/
.project
.classpath

# Environment
.env

# OS
.DS_Store
Thumbs.db
```

---

## 0.2 — Servidor Javalin e Roteamento

### 0.2.1 `EstapApplication.java` — Entry Point

Responsabilidades:
- Carregar variáveis de ambiente via `dotenv-java`.
- Inicializar o `Javalin` na porta configurada.
- Registrar a rota catch-all que encaminha toda requisição ao `ProxyController`.

Rota a registrar:

```
ANY /*  →  ProxyController.handle(ctx)
```

O proxy deve ser **agnóstico ao path**. A IDE pode chamar `/v1/messages`, `/v1/chat/completions` ou qualquer outro endpoint. O proxy repassa tudo.

### 0.2.2 `EnvironmentConfig.java`

Record imutável que encapsula todas as variáveis de ambiente necessárias. Validação fail-fast no construtor: se qualquer variável obrigatória estiver ausente, lançar `IllegalStateException` com mensagem descritiva.

Campos obrigatórios:
| Campo | Env Var | Tipo |
|---|---|---|
| `port` | `ESTAP_PORT` | `int` |
| `upstreamBaseUrl` | `UPSTREAM_BASE_URL` | `String` |
| `upstreamApiKey` | `UPSTREAM_API_KEY` | `String` |
| `devMode` | `ESTAP_DEV_MODE` | `boolean` |

---

## 0.3 — Proxy Transparente (Passthrough)

### 0.3.1 `ProxyController.java`

Responsabilidade única: receber o `Context` do Javalin, construir a requisição espelho para o upstream, e delegar a execução ao `StreamingRelay`.

**Algoritmo:**

1. Capturar método HTTP, path, query string, headers e body do `ctx`.
2. Reconstruir a URL de destino: `UPSTREAM_BASE_URL + path + queryString`.
3. Clonar todos os headers da requisição original, substituindo:
   - `Host` → host do upstream.
   - `Authorization` → `Bearer {UPSTREAM_API_KEY}` (se o upstream exigir).
4. Construir o `HttpRequest` via `java.net.http.HttpClient`.
5. Delegar ao `StreamingRelay` para execução e piping da resposta.

**Headers a NÃO propagar:**
- `Host` (será reescrito)
- `Content-Length` (recalculado pelo HttpClient)
- `Transfer-Encoding` (gerenciado pelo Jetty/Javalin)

### 0.3.2 `StreamingRelay.java`

Responsabilidade única: executar a requisição HTTP upstream e fazer o piping da resposta de volta ao cliente (IDE), suportando dois modos:

**Modo 1 — Resposta Convencional (não-streaming):**
- `HttpClient.send()` síncrono com `BodyHandlers.ofByteArray()`.
- Copiar status code, headers de resposta relevantes, e body para o `ctx`.

**Modo 2 — Server-Sent Events (SSE Streaming):**
- Detectar streaming pela presença do header `Accept: text/event-stream` na requisição OU pelo campo `"stream": true` no body JSON.
- Usar `HttpClient.sendAsync()` com `BodyHandlers.ofLines()`.
- Para cada linha recebida do upstream, escrever imediatamente no `ctx.res().getOutputStream()` seguido de flush.
- Configurar o `Content-Type` da resposta como `text/event-stream`.
- Configurar `Cache-Control: no-cache` e `Connection: keep-alive`.

**Tratamento de Backpressure:**
- O `BodyHandlers.ofLines()` do JDK retorna um `Flow.Subscriber` com backpressure nativo via Reactive Streams.
- Cada chunk recebido do upstream deve ser escrito e flushed antes de solicitar o próximo (`subscription.request(1)`).

**Tratamento de Erros:**
- Se o upstream retornar erro HTTP (4xx/5xx), repassar o status e body de erro intactos para a IDE.
- Se a conexão com o upstream falhar (timeout, DNS, recusa), retornar `502 Bad Gateway` com body JSON descritivo.

---

## 0.4 — Telemetria de Rede

### 0.4.1 `RequestMetrics.java`

Record imutável para capturar as métricas de uma requisição:

| Campo | Tipo | Descrição |
|---|---|---|
| `requestId` | `String` (UUID) | Identificador único da requisição |
| `timestamp` | `Instant` | Momento de recebimento |
| `method` | `String` | Método HTTP |
| `path` | `String` | Path da requisição |
| `requestBodySizeBytes` | `long` | Tamanho do payload de entrada |
| `responseBodySizeBytes` | `long` | Tamanho do payload de saída |
| `upstreamLatencyMs` | `long` | Latência do round-trip (Proxy → Nuvem → Proxy) |
| `totalLatencyMs` | `long` | Latência total (IDE → Proxy → Nuvem → IDE) |
| `statusCode` | `int` | Status HTTP da resposta upstream |

### 0.4.2 `MetricsLogger.java`

Responsabilidades:
- Receber um `RequestMetrics` e registrá-lo via SLF4J em formato estruturado (JSON one-line).
- **Proibição absoluta:** nunca registrar conteúdo de prompts, respostas ou fragmentos de código.
- Formato de log:

```json
{"requestId":"uuid","timestamp":"ISO-8601","method":"POST","path":"/v1/messages","requestBytes":4523,"responseBytes":12847,"upstreamMs":342,"totalMs":348,"status":200}
```

### 0.4.3 Payload Anatomy (Modo Dev)

Quando `ESTAP_DEV_MODE=true`, o `ProxyController` deve:

1. Parsear o body JSON da requisição com Jackson.
2. Imprimir no console uma representação da **estrutura** do JSON (chaves e tipos, sem valores), para mapear onde o prompt do usuário reside.
3. Exemplo de output esperado:

```
[ESTAP:DEV] Payload Structure:
  model: STRING
  max_tokens: NUMBER
  messages: ARRAY[2]
    [0].role: STRING
    [0].content: STRING       ← candidato a prompt do usuário
    [1].role: STRING
    [1].content: ARRAY[3]
      [0].type: STRING
      [0].text: STRING        ← candidato a prompt do usuário
      [1].type: STRING
      [1].source: OBJECT
```

Este mapeamento é o **entregável técnico principal** da Fase 0 — ele determina a lógica de extração de prompt da Fase 1.

---

## 0.5 — Health Check

Registrar uma rota dedicada que **não** é encaminhada ao upstream:

```
GET /estap/health  →  200 OK  { "status": "healthy", "version": "0.1.0" }
```

Necessário para o Cloud Run verificar a saúde do container.

---

## 0.6 — Critérios de Aceite da Fase 0

| # | Critério | Validação |
|---|---|---|
| 1 | Proxy inicia em < 2 segundos | Timer no log de startup |
| 2 | Requisição POST com body JSON é encaminhada intacta ao upstream | Comparar body enviado vs body recebido no WireMock |
| 3 | Resposta não-streaming retorna status, headers e body corretos | Teste de integração com WireMock |
| 4 | SSE streaming funciona token a token sem buffering | Teste com mock SSE server + validação de chunks incrementais |
| 5 | Telemetria registra latência e tamanhos sem logar conteúdo | Inspeção dos logs após teste de integração |
| 6 | Payload anatomy imprime estrutura JSON correta em dev mode | Teste manual + teste unitário do parser de estrutura |
| 7 | Health check retorna 200 | Teste de integração |
| 8 | Erro de upstream (5xx) é repassado corretamente | Teste com WireMock retornando 500 |
| 9 | Timeout de upstream retorna 502 | Teste com WireMock com delay > timeout |

---

## 0.7 — Checklist de Implementação (Ordem de Execução)

- [ ] **0.7.1** Criar `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- [ ] **0.7.2** Criar `.gitignore` e `.env.example`
- [ ] **0.7.3** Implementar `EnvironmentConfig.java`
- [ ] **0.7.4** Implementar `EstapApplication.java` (servidor Javalin mínimo + health check)
- [ ] **0.7.5** Implementar `ProxyController.java` (forwarding sem streaming)
- [ ] **0.7.6** Implementar `StreamingRelay.java` (modo convencional)
- [ ] **0.7.7** Adicionar suporte a SSE no `StreamingRelay.java`
- [ ] **0.7.8** Implementar `RequestMetrics.java` e `MetricsLogger.java`
- [ ] **0.7.9** Integrar telemetria no `ProxyController`
- [ ] **0.7.10** Implementar Payload Anatomy (dev mode)
- [ ] **0.7.11** Escrever testes unitários
- [ ] **0.7.12** Escrever testes de integração com WireMock
- [ ] **0.7.13** Validação manual ponta a ponta (proxy real → API real)

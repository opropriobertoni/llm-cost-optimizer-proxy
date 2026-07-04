# Deploy e Operações

> **Referência:** [ESTAP.md — Seções 4 e 6](../ESTAP.md)
>
> **Objetivo:** Containerizar o ESTAP e implantá-lo no Google Cloud Run com scale-to-zero, gerenciamento de segredos e monitoramento operacional.

---

## D.1 — Dockerfile (Multi-Stage Build)

### D.1.1 Estrutura do Build

```dockerfile
# ===== STAGE 1: Build =====
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Cache de dependências Gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle/ gradle/
COPY gradlew ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Compilação do projeto
COPY src/ src/
RUN ./gradlew shadowJar --no-daemon

# ===== STAGE 2: Runtime =====
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN addgroup -S estap && adduser -S estap -G estap

WORKDIR /app

COPY --from=builder /app/build/libs/*-all.jar estap.jar

RUN chown -R estap:estap /app
USER estap

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/estap/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "estap.jar"]
```

### D.1.2 Decisões de Design

| Decisão | Justificativa |
|---|---|
| **Alpine** | Imagem mínima (~80MB vs ~300MB da `slim`) |
| **Multi-stage** | Build tools não entram na imagem final |
| **Shadow JAR** | Fat JAR único elimina problemas de classpath |
| **ZGC** | Garbage collector de baixa latência (crítico para proxy) |
| **MaxRAMPercentage=75%** | Deixa margem para o OS no container |
| **Usuário não-root** | Segurança: processo roda sem privilégios |

### D.1.3 `.dockerignore`

```dockerignore
.git
.idea
.vscode
.env
build/
docs/
*.md
```

---

## D.2 — Build e Teste Local do Container

```bash
# Build da imagem
docker build -t estap:latest .

# Executar localmente (injetando variáveis de ambiente)
docker run --rm -p 8080:8080 \
  -e ESTAP_PORT=8080 \
  -e UPSTREAM_BASE_URL=https://api.anthropic.com \
  -e UPSTREAM_API_KEY=sk-ant-xxx \
  -e GROQ_API_KEY=gsk_xxx \
  -e GROQ_MODEL=llama3-70b-8192 \
  -e GROQ_API_URL=https://api.groq.com/openai/v1/chat/completions \
  -e GROQ_TIMEOUT_MS=600 \
  -e ESTAP_DEV_MODE=false \
  -e ESTAP_DRY_RUN=false \
  estap:latest

# Testar health check
curl http://localhost:8080/estap/health
```

---

## D.3 — Google Cloud Run

### D.3.1 Pré-requisitos

1. Conta GCP com billing habilitado.
2. `gcloud` CLI instalado e autenticado.
3. APIs habilitadas:
   - Cloud Run
   - Artifact Registry
   - Cloud Logging

```bash
gcloud services enable run.googleapis.com artifactregistry.googleapis.com logging.googleapis.com
```

### D.3.2 Configuração do Artifact Registry

```bash
# Criar repositório Docker no Artifact Registry
gcloud artifacts repositories create estap-repo \
  --repository-format=docker \
  --location=southamerica-east1 \
  --description="ESTAP container images"

# Autenticar Docker com o registry
gcloud auth configure-docker southamerica-east1-docker.pkg.dev
```

### D.3.3 Build e Push da Imagem

```bash
# Tag da imagem para o Artifact Registry
IMAGE_URI="southamerica-east1-docker.pkg.dev/PROJECT_ID/estap-repo/estap"

# Build e push
docker build -t ${IMAGE_URI}:latest .
docker push ${IMAGE_URI}:latest

# Alternativa: Cloud Build (build remoto)
gcloud builds submit --tag ${IMAGE_URI}:latest
```

### D.3.4 Deploy no Cloud Run

```bash
gcloud run deploy estap \
  --image ${IMAGE_URI}:latest \
  --region southamerica-east1 \
  --platform managed \
  --port 8080 \
  --memory 512Mi \
  --cpu 1 \
  --min-instances 0 \
  --max-instances 3 \
  --timeout 300 \
  --concurrency 80 \
  --allow-unauthenticated \
  --set-env-vars "ESTAP_PORT=8080,GROQ_MODEL=llama3-70b-8192,GROQ_API_URL=https://api.groq.com/openai/v1/chat/completions,GROQ_TIMEOUT_MS=600,ESTAP_DEV_MODE=false,ESTAP_DRY_RUN=false"
```

> **Nota sobre `--allow-unauthenticated`:** Na v1, o acesso é aberto porque o proxy será acessado via URL pública pela IDE. Em versões futuras, considerar autenticação via API key própria ou IAM.

### D.3.5 Configuração de Segredos (Secret Manager)

> **Referência:** [ESTAP.md — Seção 6, Gerenciamento de Segredos](../ESTAP.md)
>
> Nenhuma chave de API deve existir hardcoded. No Cloud Run, segredos são injetados via Secret Manager.

```bash
# Criar segredos
echo -n "sk-ant-xxx" | gcloud secrets create upstream-api-key --data-file=-
echo -n "gsk_xxx" | gcloud secrets create groq-api-key --data-file=-

# Conceder acesso ao service account do Cloud Run
gcloud secrets add-iam-policy-binding upstream-api-key \
  --member="serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

gcloud secrets add-iam-policy-binding groq-api-key \
  --member="serviceAccount:PROJECT_NUMBER-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

Atualizar o deploy para montar segredos como variáveis de ambiente:

```bash
gcloud run deploy estap \
  --image ${IMAGE_URI}:latest \
  --region southamerica-east1 \
  # ... (demais flags iguais ao D.3.4) ...
  --set-secrets "UPSTREAM_API_KEY=upstream-api-key:latest,GROQ_API_KEY=groq-api-key:latest"
```

---

## D.4 — Configuração da IDE

Após o deploy, a IDE agêntica deve apontar para o proxy em vez de diretamente para a API do modelo:

### D.4.1 Desenvolvimento Local (proxy local)

```
URL da API: http://localhost:8080/v1/messages
```

### D.4.2 Produção (Cloud Run)

```
URL da API: https://estap-XXXXXX-rj.a.run.app/v1/messages
```

A IDE continuará enviando requisições no mesmo formato — o proxy é transparente.

---

## D.5 — Monitoramento e Observabilidade

### D.5.1 Cloud Logging

O ESTAP já produz logs estruturados em JSON via SLF4J/Logback. No Cloud Run, `stdout` e `stderr` são automaticamente capturados pelo Cloud Logging.

**Consultas úteis no Log Explorer:**

```
# Todas as requisições do ESTAP
resource.type="cloud_run_revision"
resource.labels.service_name="estap"

# Apenas métricas de compressão
jsonPayload.type="COMPRESSION"

# Apenas fail-opens
jsonPayload.type="COMPRESSION" AND jsonPayload.failOpen=true

# Requisições lentas (> 500ms)
jsonPayload.upstreamMs > 500
```

### D.5.2 Métricas de Dashboard (Cloud Monitoring)

Criar um dashboard com os seguintes painéis:

| Painel | Fonte | Tipo de Gráfico |
|---|---|---|
| **Request Count** | Cloud Run built-in | Linha temporal |
| **Latência P50/P95/P99** | Cloud Run built-in | Linha temporal |
| **Taxa de Compressão Média** | Log-based metric (`jsonPayload.ratio`) | Gauge |
| **Fail-Open Rate** | Log-based metric (`jsonPayload.failOpen=true`) | Percentual |
| **Fail-Open por Motivo** | Log-based metric (`jsonPayload.reason`) | Pizza |
| **Instâncias Ativas** | Cloud Run built-in | Linha temporal |
| **Cold Starts** | Cloud Run built-in | Contador |

### D.5.3 Alertas Recomendados

| Alerta | Condição | Ação |
|---|---|---|
| **Latência Alta** | P95 > 2 segundos por 5 minutos | Investigar upstream ou Groq |
| **Fail-Open Rate Alto** | > 50% por 10 minutos | Verificar disponibilidade do Groq |
| **Erros 5xx** | > 5 por minuto | Verificar logs de erro |
| **Cold Start Frequente** | > 10 por hora | Considerar `--min-instances 1` |

---

## D.6 — Logback Configuration (`logback.xml`)

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%msg%n</pattern>
        </encoder>
    </appender>

    <!-- Suprime logs verbosos do Jetty e Javalin -->
    <logger name="org.eclipse.jetty" level="WARN"/>
    <logger name="io.javalin" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

> O pattern `%msg%n` é proposital — como o ESTAP já produz JSON estruturado no `MetricsLogger`, não queremos que o Logback adicione prefixos de timestamp/level que quebrem o parsing do Cloud Logging.

---

## D.7 — Estratégia de Versionamento e Releases

### D.7.1 Convenção de Versão

Semantic Versioning: `MAJOR.MINOR.PATCH`

| Versão | Marco |
|---|---|
| `0.1.0` | Fase 0 completa (proxy transparente) |
| `0.2.0` | Fase 1 completa (compressão ativa) |
| `1.0.0` | Produção estável com métricas validadas |

### D.7.2 Fluxo de Deploy

```
Código local
    │
    ▼
Testes locais (./gradlew test)
    │ Passou?
    ▼
Docker build + teste local
    │ Funcional?
    ▼
Push para Artifact Registry
    │
    ▼
gcloud run deploy (novo revision)
    │
    ▼
Smoke test na URL do Cloud Run
    │ Healthy?
    ▼
Monitorar logs por 30 minutos
```

---

## D.8 — Checklist de Deploy

### Primeiro Deploy (Fase 0)
- [ ] **D.8.1** Criar projeto GCP (ou usar existente)
- [ ] **D.8.2** Habilitar APIs (Cloud Run, Artifact Registry, Logging)
- [ ] **D.8.3** Criar repositório no Artifact Registry
- [ ] **D.8.4** Criar `Dockerfile` e `.dockerignore`
- [ ] **D.8.5** Build e teste local do container
- [ ] **D.8.6** Push da imagem
- [ ] **D.8.7** Criar segredos no Secret Manager
- [ ] **D.8.8** Deploy no Cloud Run
- [ ] **D.8.9** Validar health check na URL pública
- [ ] **D.8.10** Configurar IDE para apontar ao proxy
- [ ] **D.8.11** Teste ponta a ponta (IDE → Proxy → Nuvem → IDE)

### Deploy da Fase 1
- [ ] **D.8.12** Atualizar segredos (adicionar `GROQ_API_KEY`)
- [ ] **D.8.13** Rebuild da imagem com código da Fase 1
- [ ] **D.8.14** Deploy com `ESTAP_DRY_RUN=true` para validação
- [ ] **D.8.15** Sessão de dry-run no Cloud Run (validar compressão remota)
- [ ] **D.8.16** Flip para `ESTAP_DRY_RUN=false`
- [ ] **D.8.17** Monitorar métricas de compressão e fail-open por 24h
- [ ] **D.8.18** Configurar dashboard e alertas no Cloud Monitoring

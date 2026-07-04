# ARQUITETURA DE SISTEMA: Edge-Side Token Arbitrage Proxy (ESTAP)

## 1. Objetivo do Projeto

Construir um middleware de interceptação de altíssima performance para atuar entre a IDE agentica e a nuvem. O objetivo central é reduzir drasticamente os custos operacionais (consumo de tokens de entrada) e otimizar a velocidade das requisições, realizando a tradução cross-lingual e a compressão algorítmica de prompts de forma invisível, antes que o pacote de dados alcance o modelo principal de alto custo da plataforma.

## 2. Escopo Operacional e Restrições de Fronteira (v1)

Para garantir a integridade da base de código e evitar a supressão acidental de variáveis de estado sistêmico (como mascaramento de segredos em arquivos .env ou adulteração de árvores de commit do Git), a primeira versão deste projeto possui restrições inegociáveis de atuação:

* **Foco Exclusivo no Prompt:** O proxy processará e comprimirá única e exclusivamente as entradas de texto natural redigidas pelo usuário.
* **Proibição de Atuação em Outputs:** O sistema está expressamente proibido de interceptar, resumir ou traduzir logs de erro de terminal, saídas de builds, listagens de diretório ou conteúdos integrais de arquivos lidos autonomamente pela IDE.

## 3. Fases de Desenvolvimento: A Abordagem Funcional

### Fase 0: The Walking Skeleton (Validação de Infraestrutura)

> **Nota de Governança Arquitetural:** Metodologias avançadas de codificação (Arquitetura Hexagonal, DDD Tático e SOLID estrito) foram deliberadamente adiadas para o ciclo pós-MVP. A prioridade da Fase 0 e 1 é a estabilidade de rede e a validação do ROI financeiro. A refatoração estrutural ocorrerá apenas após a consolidação das métricas base. Antes de implementar qualquer lógica de compressão, o sistema deve provar sua capacidade de sequestrar a rota de forma estável. O MVP será um proxy estrito e sem lógica de negócio densa.

* **API Hijacking e Validação de Streaming:** Receber a requisição local (localhost) e repassá-la intocada para o modelo principal utilizando o `java.net.http.HttpClient`. O critério de aceite desta etapa não é apenas a devolução de um *payload* estático, mas a orquestração bem-sucedida de *Server-Sent Events* (SSE). O proxy deve provar que consegue fazer o *piping* assíncrono token a token sem quebrar o *backpressure* ou a renderização fluida na IDE.
* **Telemetria de Rede:** Registrar e isolar a latência exata do round-trip (IDE -> Proxy -> Nuvem -> IDE) para estabelecer a linha de base de tempo.
* **Mapeamento de Payload:** Imprimir localmente a anatomia bruta do JSON enviado pela IDE para identificar com precisão o nó onde o prompt do usuário reside.

### Fase 1: Interceptação e Arbitragem Cross-Lingual

Após a estabilização empírica da Fase 0, ativa-se o motor de inteligência de borda.

* Integração com a API ultrarrápida (Groq/Llama 3) para tradução e compactação em blocos semânticos.
* Implementação das regras de Sanity Check e imutabilidade estrutural de código.

## 4. Stack de Desenvolvimento

* **Linguagem e Core:** Java 21+. Garante processamento assíncrono robusto e tipagem estrita para manipulação segura de payloads de rede e streams HTTP.
* **Framework Web:** Javalin. Selecionado pelo baixíssimo overhead, execução enxuta e inicialização sub-segundo, substituindo abstrações pesadas.
* **Infraestrutura Serverless:** Google Cloud Run (Container Docker com Scale-to-Zero).
* **Motor de Compressão (Edge AI):** Groq API rodando Llama 3 (8B/70B) focando em processamento massivo de tokens em frações de segundo.

## 5. Arquitetura Operacional (Fluxo de Rede)

| Fase de Rede | Componente | Responsabilidade Técnica |
| --- | --- | --- |
| **1. Origem** | IDE Agentica | Dispara o comando original em português apontando para o localhost/Proxy. |
| **2. Ingestão** | Proxy (Java) | Recebe a requisição, extrai o prompt do usuário e executa o isolamento de código (Allowlist). |
| **3. Compressão** | Motor Edge (Groq) | Traduz e comprime a prosa natural assincronamente. |
| **4. Roteamento** | Proxy (Java) | Reintegra os blocos de código extraídos, consolida o payload otimizado e repassa para a nuvem. |
| **5. Resolução** | Nuvem Principal | Processa a solicitação final e inicia a devolução dos dados e da arquitetura solicitada. |
| **6. Retorno** | Proxy -> IDE | Piping (streaming) contínuo da resposta de volta para a interface local. |

## 6. Governança Operacional e Validação Sistêmica

O rigor de execução deste middleware é pautado por mecanismos de defesa ativos contra degradação de contexto e indisponibilidade de rede:

* **Gerenciamento de Segredos:** Nenhuma chave de API (Groq, Anthropic, Google) deve existir *hardcoded* no código Java. Todas as credenciais devem ser injetadas exclusivamente via variáveis de ambiente no painel do Cloud Run (ou localmente via `.env` ignorado no Git).
* **Sanity Check em Duas Camadas:**
  * **Camada 1 (Allowlist de Imutabilidade):** Antes de comunicar com o Llama 3, o proxy aplicará extração pura e mecânica de todos os blocos de código formatados em Markdown (conteúdo entre \`\`\`). Estes blocos serão substituídos por placeholders temporários, garantindo que o LLM de borda traduza apenas a linguagem natural circundante, sem adulterar sintaxe estrita.
  * **Camada 2 (Validação Matemática):** Após a devolução pelo motor e reinserção dos blocos de código, o sistema comparará o volume final contra o prompt original. A compressão só é despachada para a nuvem principal se for categoricamente mais leve que o *payload* base. **Caso a Camada 2 falhe (o texto comprimido resulte maior ou igual ao original), o *payload* original é despachado sem alteração, seguindo o mesmo princípio de fallback do Circuit Breaker.**
* **Auditoria e Dry-Run:** Implementação de uma flag local de desenvolvimento que impede o envio do resultado para a nuvem, imprimindo no console o "Antes" e o "Depois" da instrução. Ação mandatória para aferir a preservação da fidelidade semântica visualmente antes da liberação em produção.
* **Circuit Breaker (Fail-Open):** Timeouts estritos na chamada do Groq, derivados do orçamento de latência. **O *timeout* será calculado como `800ms - overhead_estimado_de_rede` (uma variável empírica a ser validada na Fase 0).** Caso o motor de compressão estoure essa exata janela, a exceção é descartada e o proxy direciona o *payload* original de forma limpa para o modelo principal, garantindo zero bloqueio na IDE.
* **Segregação de Logs:** Proibição irrevogável da gravação permanente de payloads, prompts ou fragmentos de código-fonte. O sistema registra e acopla exclusivamente dados de telemetria base: tempo de resposta em milissegundos, tamanho do payload de entrada e tamanho do payload de saída.
* **Rastreamento Obrigatório de Fail-Open (Regra Não-Negociável):** Toda vez que o Circuit Breaker for acionado (o Groq não respondeu dentro da janela de tempo) ou a Camada 2 do Sanity Check rejeitar a compressão (resultado igual ou maior que o original), o sistema **deve registrar esse evento como uma métrica própria e separada**, distinta dos dados de uma compressão bem-sucedida. É proibido misturar essas duas contagens na mesma métrica. Sem essa separação, torna-se impossível diagnosticar se uma sessão com baixa economia de tokens falhou por lentidão de rede (problema de infraestrutura) ou por baixa qualidade de compressão do texto (problema de prompt engineering) — dois problemas com soluções completamente diferentes.

## 7. Métricas de Sucesso e ROI

O projeto exige prova de valor sistêmica, pautada nos seguintes gatilhos de validação:

* **Retenção Semântica Absoluta:** 100% de precisão nos blocos de código extraídos e zero falhas de sintaxe inseridas pelo modelo de borda, validadas pela flag de *Dry-Run*.
* **Eficiência de Compressão:** Redução mínima de **>30%** no volume final de *tokens* de entrada em *prompts* conversacionais extensos. **Nota de Cálculo (obrigatória):** este percentual deve ser calculado somente sobre os prompts em que a compressão foi de fato aplicada com sucesso — eventos de *fail-open* devem ser excluídos deste número e contabilizados à parte, conforme a regra da Seção 6.
* **Taxa de Fail-Open (métrica auxiliar):** percentual de requisições em que o sistema recorreu ao prompt original (por timeout do Groq ou reprovação no Sanity Check), monitorado de forma isolada da métrica de Eficiência de Compressão acima.
* **Tolerância de Latência (Overhead):** O hop extra de rede (Proxy -> Groq -> Proxy) não deve adicionar mais do que **800 milissegundos** ao tempo total de resposta (*Time to First Token*), garantindo que a redução do *payload* compense o atraso na conexão.

# forza-telemetry-service — instruções do serviço

> Complementa o [`CLAUDE.md` da raiz](../CLAUDE.md) (visão do monorepo, contrato, commits,
> infra) e as regras globais de `~/.claude/CLAUDE.md`. Aqui só o que é específico deste
> serviço. Detalhes de uso: [`README.md`](README.md).

## Papel e autoria
- Recebe a telemetria **Data Out (UDP)** do Forza Horizon 4/5/6 e do Forza Motorsport
  (2023), segmenta em sessões/voltas, grava a série temporal no Postgres e expõe uma API
  REST de leitura com um **resumo de tuning** por sessão (entrada pra skill
  `forza-tuning-engineer`).
- *Resource server*: valida o Bearer do `workbox-api` por introspecção remota. Sem login.
- Autoria: padrão do monorepo é o desenvolvedor implementar; aqui há **permissão total
  temporária** pro Claude ("Claude implementa tudo", 2026-10-03). Detalhes e limites em
  [raiz → Divisão de responsabilidade](../CLAUDE.md#divisão-de-responsabilidade).
- **Estado do repositório**: repo no GitLab (`sonar-group-oojuniiin/forza-telemetry-service`)
  e espelho no GitHub, registrado como submódulo do monorepo. CI com `test`,
  `contract-drift-check` e `build`; ainda **sem Sonar**.

## Stack e execução
- Java 25 LTS, Spring Boot 3.5.16, Gradle 9.7.1 (`./gradlew`), **JDBC** (`spring-boot-starter-jdbc`,
  não JPA), Liquibase, Spring Security 6 (OAuth2 resource server, opaque token), actuator,
  springdoc, virtual threads (`spring.threads.virtual.enabled=true`).
- API REST **7057** (container 8083); UDP **5310** (`TELEMETRY_UDP_PORT`/`FORZA_UDP_PORT`).
  Profiles `dev` (default) e `prod`; role Postgres `forza_service`, schema **`forza`**.
- Local: `DATABASE_URL="jdbc:postgresql://localhost:7050/workbox?reWriteBatchedInserts=true" ./gradlew bootRun`.
  Em banco já existente o `initdb/` não roda — criar role/schema à mão (README).
- O client `forza-telemetry-service` precisa existir em `workbox.api_clients` (seed
  `261003_0000_seed_api_clients_forza_telemetry_service.sql` no `workbox-api`).
- Gerar contrato: `./gradlew generateOpenApiDocs` — **sem Postgres e sem disputar portas**
  (Liquibase e conexão desligados, UDP off, porta 7099); servidor relativo `/` no contrato.
  O CI roda `contract-drift-check` com ele.

## Estrutura (`br.com.forza`)
`config/` (`SecurityConfig`, `TelemetryProperties`, `WorkboxTokenIntrospector`) ·
`controllers/` (`SessionController`, `LiveController`) · `exceptions/` ·
`models/{dto, entities}` (`SessionMeta`, `LapRecord`, `SampleRow`) · `repositories/`
(JDBC) · `services/SessionQueryService` · `telemetry/`:
`udp/UdpTelemetryListener` → fila → `ingest/IngestWorker` (+ `LiveSnapshot`) ·
`packet/{PacketFormat, PacketParser, TelemetryPacket}` · `summary/SummaryCalculator`.
Rotas: `GET /api/v1/sessions` (cursor), `/{id}`, `/{id}/laps`, `/{id}/summary`,
`/{id}/samples`, `GET /api/v1/live/snapshot`.

## Pipeline de ingestão — regras que não podem quebrar
- **Recepção desacoplada**: a thread UDP só carimba o horário e **enfileira** (fila
  limitada, descarta se encher); decodificação e I/O de banco ficam no `IngestWorker`.
  Nunca colocar I/O de banco, parsing pesado ou lock no caminho do `receive()`.
- **Formato do pacote detectado só pelo tamanho** (`PacketFormat.fromSize`): 232 (Sled),
  311 (FM7 Dash), 324 (Horizon), 331 (FM2023 Dash). Layout **little-endian**. Sled não
  traz inputs/pneus/posição e **não é gravado**. Formato novo = entrada nova em
  `PacketFormat` + teste com pacote sintético.
- **Sessão** = trecho contínuo com o mesmo carro (e mesma pista, no FM). Abre no primeiro
  pacote com `IsRaceOn=1`; fecha por inatividade (`telemetry.session-idle-timeout=30s`),
  troca de carro/pista ou shutdown. Sessões com < 100 amostras (`min-session-samples`)
  são descartadas (ruído de menu).
- **Downsample** 60 → ~20 Hz (`telemetry.sample-every=3`), gravação em lote a cada 1 s
  (`flush-interval`, `flush-batch-size=200`) com `reWriteBatchedInserts=true` na URL.
- **JDBC em vez de JPA** de propósito (ingestão em lote de série temporal; arrays `REAL[]`
  por roda mantêm a tabela estreita) — não migrar pra JPA/Hibernate no caminho quente.
- Unidades do jogo: velocidade em **m/s**, temperatura de pneu em **°F**, rodas na ordem
  **FL, FR, RL, RR**; `accel_x` = lateral, `accel_z` = longitudinal; `tire_wear` só no
  Forza Motorsport.
- Os limiares de `SummaryCalculator` (em movimento > 8 m/s, em curva |esterço| > 25,
  sub/sobresterço ±0.15 de slip angle) são **heurísticas iniciais** — calibrar com
  captura real; não tratá-los como verdade.
- O resumo é gravado no fim da sessão (`sessions.summary` JSONB) e calculado na hora se a
  sessão está ativa.

## Segurança e riscos conhecidos
- **UDP sem autenticação**: qualquer host da LAN que alcance a porta injeta pacotes.
  Mitigação é firewall restrito ao IP do Xbox (`ufw allow from <ip> to any port 5310
  proto udp`) — apontar sempre que mexer na exposição da porta.
- Sessões **não têm dono**: qualquer usuário autenticado vê todas (decisão atual,
  serviço de uso pessoal). Se virar multiusuário, é mudança de modelo — sinalizar.
- Paginação por **cursor opaco** (`nextCursor`, `size` máx. 100; `limit` de samples máx.
  10000) — nunca `OFFSET`. Cursor inválido → `InvalidCursorException` (RFC 9457).
- `/actuator/health` e Swagger são os únicos endpoints sem Bearer.

## Contrato e consumidores
- `openapi/openapi.yaml` versionado (exportado de `/v3/api-docs.yaml`). O `summary` é objeto
  **livre** no contrato — o front (`workbox-app/src/interfaces/forza`) espelha o
  `SummaryCalculator`; mudar o formato do resumo exige ajustar o front junto.
- Consumidor: módulo `/forza` do `workbox-app` (sessões, detalhe e ao vivo). CORS libera só
  `GET`/`OPTIONS` pra origem do front.

## Liquibase
`db/changelog/changelog.yaml`; changesets em `db/changelog/v0.0.1/create/` com
`preconditions onFail:MARK_RAN`, `rollback` e `-- comment`. Arquivo novo
`yymmdd_nnnn_<acao>_<alvo>.sql`; **nunca editar changeset aplicado**. Tabelas:
`forza.sessions`, `forza.laps`, `forza.samples` (PK composta por sessão + `t_ms`; `ON
DELETE CASCADE`). Índice `idx_sessions_started_at_id` sustenta a paginação por cursor.

## Testes (test-first)
- 153 testes (JUnit 5 + AssertJ + Mockito nas fronteiras de I/O), `./gradlew test`:
  `PacketParserTest`/`PacketFormatTest` (pacotes sintéticos de `support/PacketBuilder`),
  `SummaryCalculatorTest`, `SessionQueryServiceTest` (cursor, clamps, resumo),
  `IngestWorkerTest` (sessões, voltas, downsample, descarte, retentativas — pela thread
  real via `start/offer/stop`), controllers com o `SecurityConfig` **real** (só o
  `OpaqueTokenIntrospector` é mockado), `WorkboxTokenIntrospectorTest` (MockRestServiceServer).
- `PacketBuilder` fixa os offsets do Dash (244 Horizon / 232 FM) **de propósito, sem ler o
  enum** — senão o teste do parser seria circular. Mantenha assim.
- `RepositoriesIT` (Testcontainers, **exige Docker**): repositórios JDBC contra Postgres 18
  real com role/schema restritos e as migrations reais (arrays `REAL[]`, keyset, upsert,
  cascade).
- `TuningSummaryDTOTest` mantém o schema `TuningSummaryDTO` (OpenAPI) idêntico ao mapa do
  `SummaryCalculator` — mudou o cálculo, mude o DTO e o front.
- **Fechamento**: o flush final tem 3 tentativas; se falhar de vez, a sessão fecha com o que já
  foi gravado (ou é descartada abaixo do mínimo) — nunca fica aberta no banco; as amostras
  pendentes são perdidas e logadas.
- **Lacuna**: o parser ainda não foi validado com **captura real** do jogo (principalmente FH6).

## Convenção Java deste repo
- Seguir o estilo já adotado: `final` em parâmetros/locais, Javadoc em português.

## Commits
pt-BR, Conventional Commits, conforme o
[CLAUDE.md da raiz](../CLAUDE.md#convenção-de-mensagens-de-commit). Branch `develop`;
push só com confirmação.

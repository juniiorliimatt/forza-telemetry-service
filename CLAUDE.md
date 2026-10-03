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
- **Estado do repositório**: ainda sem repo no GitLab, **não registrado em
  `.gitmodules`**, repo git local **sem nenhum commit**; sem `.gitlab-ci.yml`, sem Sonar,
  sem testes, sem `openapi/openapi.yaml`. Não criar commit inicial, remote nem submódulo
  sem o desenvolvedor pedir.

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
- Gerar contrato: `./gradlew generateOpenApiDocs` (sobe em `dev` com
  `--telemetry.udp.enabled=false` pra não disputar a porta 5310 com o container; precisa do
  Postgres local).

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

## Liquibase
`db/changelog/changelog.yaml`; changesets em `db/changelog/v0.0.1/create/` com
`preconditions onFail:MARK_RAN`, `rollback` e `-- comment`. Arquivo novo
`yymmdd_nnnn_<acao>_<alvo>.sql`; **nunca editar changeset aplicado**. Tabelas:
`forza.sessions`, `forza.laps`, `forza.samples` (PK composta por sessão + `t_ms`; `ON
DELETE CASCADE`). Índice `idx_sessions_started_at_id` sustenta a paginação por cursor.

## Testes (test-first) — lacuna conhecida
- **Não há `src/test`** nem dependências de teste além de `spring-boot-starter-test`.
  Tudo que for alterado/criado daqui pra frente nasce com teste: parser com pacotes
  sintéticos (um por `PacketFormat`), `SummaryCalculator`, segmentação de sessão no
  `IngestWorker`, cursor de paginação.
- IT contra Postgres real exigiria Testcontainers (dependência nova → pedir confirmação,
  global §3). O parser ainda não foi validado com **captura real** do jogo (principalmente
  FH6) — dado sintético segue o layout da documentação oficial.

## Convenção Java deste repo
- Seguir o estilo já adotado: `final` em parâmetros/locais, Javadoc em português.

## Commits
pt-BR, Conventional Commits, conforme o
[CLAUDE.md da raiz](../CLAUDE.md#convenção-de-mensagens-de-commit). Branch `develop`;
push só com confirmação — e só depois que o repositório remoto existir.

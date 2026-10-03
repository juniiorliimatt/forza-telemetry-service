# forza-telemetry-service

Recebe a telemetria **Data Out (UDP)** do Forza Horizon 4/5/6 e do Forza Motorsport (2023),
segmenta em sessões/voltas, grava a série temporal no Postgres e expõe uma API REST de
leitura com um **resumo de tuning** por sessão (suspensão, balanço em curva, frenagem,
tração, câmbio, pneus) — a entrada pra ajustar o setup do carro.

*Resource server*: valida o token do `workbox-api` via introspecção remota
(`POST /api/v1/auth/introspect`), igual ao `budget-service`. Sem login próprio.

```
Xbox ──UDP ~60 Hz──▶ UdpTelemetryListener ─fila─▶ IngestWorker ──lote 1 s──▶ Postgres (schema forza)
                      (virtual thread)              ├─ decodifica por tamanho do pacote   ▲
                                                     ├─ sessões / voltas / downsample 60→20 Hz
                                                     └─ resumo de tuning no fim da sessão   │
                      API REST (/api/v1/**, Bearer) ◀────────────────────────────────────────┘
```

## Configuração do jogo

| Jogo | Onde | Formato |
|---|---|---|
| Forza Horizon 4/5/6 | `SETTINGS > HUD AND GAMEPLAY > Data Out` | fixo (324 bytes) |
| Forza Motorsport (2023) | `SETTINGS > GAMEPLAY & HUD > UDP RACE TELEMETRY` | **Dash** (331 bytes) — Sled não traz inputs, pneus nem posição e não é gravado |

Data Out = ON · **IP = IP deste PC na LAN** · **Porta = 5310** (`FORZA_UDP_PORT`). No
Forza Horizon, evite a faixa 5200–5300 (o jogo usa pros próprios sockets). O Xbox precisa
estar na mesma rede, e o firewall do PC tem que liberar UDP de entrada na porta — por
exemplo `sudo ufw allow from <ip-do-xbox> to any port 5310 proto udp`.

## Rodando

```bash
docker compose up --build -d forza-telemetry-service    # na raiz do monorepo — API em :7057, UDP em :5310
```

Local (Postgres do compose em `localhost:7050`, role/schema `forza_service`/`forza`):

```bash
DATABASE_URL="jdbc:postgresql://localhost:7050/workbox?reWriteBatchedInserts=true" ./gradlew bootRun
```

Banco já existente (o `initdb/` só roda em volume vazio) — crie role e schema uma vez:

```sql
CREATE ROLE forza_service WITH LOGIN PASSWORD 'forza_service';
GRANT CONNECT ON DATABASE workbox TO forza_service;
CREATE SCHEMA IF NOT EXISTS forza AUTHORIZATION forza_service;
```

O client `forza-telemetry-service` precisa existir em `workbox.api_clients` (seed no
changelog `261003_0000_seed_api_clients_forza_telemetry_service.sql` do `workbox-api`).

## API

Tudo exige `Authorization: Bearer <access_token do workbox-api>`, exceto `/actuator/health`
e o Swagger (`/swagger-ui/index.html`). Sessões não têm dono: qualquer usuário autenticado
vê todas.

| Endpoint | O quê |
|---|---|
| `GET /api/v1/sessions?cursor&size` | Sessões, mais recentes primeiro. Paginação por cursor opaco (`nextCursor`), `size` máx. 100 |
| `GET /api/v1/sessions/{id}` | Metadados (carro, PI, tração, formato, pista, `active`) |
| `GET /api/v1/sessions/{id}/laps` | Tempos de volta |
| `GET /api/v1/sessions/{id}/summary` | **Resumo de tuning** (gravado no fim da sessão; calculado na hora se a sessão está ativa) |
| `GET /api/v1/sessions/{id}/samples?fromMs&toMs&limit` | Série temporal (~20 Hz), `limit` máx. 10000 |
| `GET /api/v1/live/snapshot` | Último pacote recebido; 404 se nada chegou nos últimos 5 s |

Erros em RFC 9457 (`application/problem+json`).

Resumo → ajuste de setup: cole o JSON de `/summary` na skill `forza-tuning-engineer` (a
skill mapeia `suspension`, `cornerBalance`, `braking`, `traction`, `engine` e `tires` em
mudanças de mola, barra, diferencial, freio, câmbio e pressão).

## Como funciona

- **Formato do pacote** detectado só pelo tamanho: 232 (Sled), 311 (FM7 Dash), 324
  (Horizon), 331 (FM2023 Dash). Layout little-endian em `telemetry/packet/PacketParser`.
- **Recepção desacoplada**: a thread UDP só carimba o horário e enfileira (fila limitada,
  descarta se encher); o `IngestWorker` decodifica e grava — I/O de banco nunca atrasa o
  `receive()`.
- **Sessão** = trecho contínuo com o mesmo carro (e mesma pista, no FM). Abre no primeiro
  pacote com `IsRaceOn=1` e o carro andando; fecha por inatividade (30 s sem pacotes), troca de carro/pista ou
  shutdown. Como o jogo não avisa que o carro está na garagem (manda `IsRaceOn=1` com o carro parado), **carro parado
  não gera amostra** e só as amostras limitam a sessão: ela **fecha sozinha a cada 5000 amostras gravadas**
  (`telemetry.session-max-samples`) e a próxima abre na sequência; numa corrida/evento (`lapNumber > 0`) espera
  terminar. O tuning exige 10 sessões e 50000 amostras (10 sessões cheias); tempo não entra na regra.
  Sessões com menos de 100 amostras (~5 s) são descartadas.
- **Downsample** 60 → ~20 Hz (`telemetry.sample-every=3`), gravação em lote a cada 1 s.
  Unidades do jogo: velocidade em m/s, temperatura de pneu em °F, rodas na ordem FL, FR, RL, RR.
- **JDBC em vez de JPA**: caminho quente é ingestão em lote de série temporal; arrays
  `REAL[]` por roda mantêm a tabela estreita.
- Os limiares do resumo (em movimento > 8 m/s, em curva |esterço| > 25, sub/sobresterço ±0.15
  de slip angle) são heurísticas iniciais — calibrar com dados reais do jogo.

## Pendências conhecidas

- `openapi/openapi.yaml` versionado e protegido por `contract-drift-check` no CI; a geração
  (`./gradlew generateOpenApiDocs`) não precisa de Postgres. Regenerar ao mudar a API. O resumo de tuning tem schema `TuningSummaryDTO`,
  mantido em sincronia com o `SummaryCalculator` por teste.
- CI com `test` (dind, por causa dos ITs com Testcontainers), `contract-drift-check` e `build`; ainda sem Sonar.
- UDP não tem autenticação: qualquer host da LAN que alcance a porta consegue injetar pacotes.
  Restrinja no firewall ao IP do Xbox.
- O parser foi validado contra pacotes sintéticos montados com o layout da documentação
  oficial (FH, FM2023); falta confirmar com uma captura real do jogo, principalmente no FH6.

Convenção de commits: [CLAUDE.md](../CLAUDE.md#convenção-de-mensagens-de-commit).

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
  Exige o módulo **FORZA**: a introspecção devolve `modules` (ADMIN: todos), o introspector
  os converte em authorities `MODULE_<CODIGO>` e o `SecurityConfig` responde **403** a quem
  está autenticado sem `MODULE_FORZA`. Todo teste de controller que stuba o introspector
  precisa incluir `MODULE_FORZA` nas authorities.
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
Rotas: `GET /api/v1/live/info` (IP(s) e porta UDP anunciados — `TELEMETRY_ADVERTISED_HOST`/`_PORT`;
de dentro do container só se vê o IP docker, então o IP da LAN vem do `FORZA_HOST_IP` do compose,
detectado por `scripts/up-all.sh`), `GET /api/v1/sessions` (cursor), `/{id}`, `/{id}/laps`, `/{id}/summary`,
`/{id}/samples`, `GET /api/v1/live/snapshot`.

## Recomendação de tuning (`br.com.forza.tuning`)
- Escopo: família **Horizon** (FH4/FH5/FH6 usam o mesmo pacote de 324 bytes, então o jogo não é distinguido — o
  card do front diz "FH6"). **A coleta é por carro E classe de PI** (`PerformanceClass`, faixas do FH6 em
  forzahorizonhub.com: D 100–400, C 401–500, B 501–600, A 601–700, S1 701–800, S2 801–900, R 901–998): cada classe é
  uma build, então o mesmo carro em PI 416 (C) e 700 (A) são duas coletas e duas recomendações, e **mudar de classe
  fecha a sessão** (`IngestWorker.changesSession`); mudar o PI dentro da classe não. Rotas
  `/tuning/cars/{ordinal}/{classe}` e `.../{classe}/checkpoint` (o marco também é por classe; a migração 261006
  replicou os marcos antigos para as 7 classes). `TuningService` pega as sessões **encerradas com resumo** do carro
  naquela classe, só as
  iniciadas após o **marco de coleta** (`tuning_checkpoints`) e só as
  `tuning.max-sessions` (20) mais recentes; exige `tuning.min-sessions` (10) **e** `tuning.min-samples` (50000
  amostras = 10 sessões cheias de 5000) — contagem de sessões sozinha não basta, uma sessão pode ter poucas amostras.
- `TuningAggregator` combina os resumos ponderando por amostras; `TuningAdvisor` aplica as regras
  sintoma→ajuste da skill `forza-tuning-engineer` (matriz e limiares: bottoming > 3%, pneus 170–210 °F, eixos > 15 °F,
  limitador na última marcha, travamento > 5%, patinagem > 15%, balanço com diferença ≥ 20 pontos e ≥ 150 amostras).
- **Sempre as 9 guias do jogo** (Pneus, Câmbio, Alinhamento, Barras, Molas, Amortecimento, Aerodinâmica, Freios,
  Diferencial) com status `ADJUST`/`OK`/`NO_SIGNAL`; no máximo **3 ajustes por ciclo, um por guia** (`thisCycle`).
- **Só direções** (aumentar/reduzir): o Data Out não traz os valores do setup, peso nem distribuição. Valores
  absolutos exigem a skill com esses dados. Quem aplica ajustes no jogo deve **reiniciar a coleta**, senão sessões do
  setup antigo contaminam a média. Aerodinâmica não tem sinal na telemetria (`NO_SIGNAL`).
- **Matriz da skill coberta**: além dos limiares, o advisor cobre equilíbrio de freio (subesterço na entrada → para a
  traseira; sobresterço → para a dianteira, sem contradizer o travamento medido), diferencial central AWD (subesterço na
  saída), 1ª–3ª mais longas e pressão traseira menor (sobresterço na saída, sem duplicar a regra de temperatura).
  Equilíbrio de freio usa o **eixo** (`FRONT`/`REAR` = para onde mover), não `INCREASE/DECREASE`: no FH5 o slider era
  invertido, no FH6 foi corrigido. **Travamento de freio** = `|slip ratio| > 2.0` com freio > 150 (`BRAKE_LOCK_SLIP_RATIO`; era
  1.0, que dava 13–62% de "travamento" em qualquer carro — apertar todo o gatilho já passa de 1.0); resumos antigos usam o
  critério antigo e saem da janela conforme a coleta reinicia. **Pendente (cobrar):** a seleção do ciclo por categoria da skill
  — ver a memória `forza-tuning-pending-calibration`.
- **Quantidade do ajuste** (`StepCatalog`): o Data Out não traz o setup atual nem o curso dos sliders (e o forzahorizonhub
  confirma que não lista valores stock por carro), então cada sugestão traz `amount`/`unit`/`magnitude` = **tamanho do passo
  deste ciclo**, não o valor final: pneus 0,1 bar · cambagem 0,2° · convergência 0,1° · barras 2 pontos · molas 5% do curso do
  slider (varia por carro) · altura 0,5 cm · amortecimento 1 ponto · freio (pressão 5 / equilíbrio 1) e diferencial 5 pontos
  percentuais · relações 0,1. Cresce com a severidade (SMALL ×1 < 1,5 ≤ MEDIUM ×2 < 2,5 ≤ LARGE ×3). Parâmetro desconhecido
  → sem número (nunca inventado). Valores absolutos exigiriam peso/distribuição informados (modelo de frequência natural).
- **Marchas**: o jogo reporta ré como `0` e neutro/troca como `11` (comprovado em dados reais: 11 aparece em movimento
  com acelerador solto). `Gears.isForward` (1–10) filtra isso no `SummaryCalculator`, no `TuningAggregator` e no
  `TuningAdvisor` (resumos antigos já gravados trazem a chave "11"); sem o filtro a "última marcha" seria a 11 e a regra
  de relação final nunca dispararia.
- **Sessão em andamento no card**: `GET /tuning/cars` traz `activeSession` (amostras ao vivo + alvo `tuning.session-samples`, que é o
  mesmo `telemetry.session-max-samples`) por carro/classe; ela **não** entra no progresso até fechar, mas um carro só com
  sessão aberta também é listado (progresso zero). Sessão aberta antes do marco de coleta é ignorada.
- **Histórico de tunings** (`forza.tuning_history`, `GET /tuning/history[/{id}]`): ao **reiniciar a coleta** de um carro cuja
  recomendação já estava pronta, `TuningService.resetCollection` grava uma foto (JSONB da `TuningRecommendationDTO`)
  **antes** de mover o marco, na mesma transação (falha ao salvar = marco não se move). Sem dados suficientes nada é
  salvo. A foto independe das sessões (que podem ser apagadas) e das regras futuras do advisor: reflete o que foi
  recomendado na época. Só nasce de um reinício — não há botão "salvar" avulso.
- **Limpeza de amostras** (`SamplePurger`): `forza.samples` é a tabela que cresce (~5000 linhas/sessão). No mesmo
  `resetCollection`, **depois** de mover o marco, apaga fisicamente as amostras das sessões **fechadas** daquele
  (carro, classe de PI), **menos as `tuning.keep-sample-sessions` (2) mais recentes** — sempre sobra telemetria de amostra;
  essas saem no próximo reinício. Marca `sessions.samples_purged_at` (`SessionDTO.samplesPurged`); `summary`, voltas e
  `sample_count` ficam (o tuning e o histórico não dependem das amostras). **Sem retenção por tempo** (decisão do
  desenvolvedor): as amostras ficam até a coleta ser reiniciada. Nunca toca sessão aberta nem outro carro/classe.
- Regra nova = teste em `TuningAdvisorTest` primeiro (cada limiar tem mutação coberta).

## Nome do carro
O Data Out **não** traz o nome do carro, só `CarOrdinal`, classe, PI, tração e cilindros. `CarCatalog`
traduz o ordinal em nome com catálogos por família de jogo (`car-catalog/horizon.json` e
`motorsport.json`, gerados por `tools/build-car-catalog.py` — fontes/licenças em
`car-catalog/README.md`): o mesmo ordinal pode ter nome/ano diferente entre jogos, então nunca se usa o
catálogo de outra família (FM7 e Sled não têm). `carName` (nulo se desconhecido) vai em `SessionDTO` e
`LiveSnapshotDTO`. Cobertura parcial: o resto aparece como `#ordinal` no front.

## Pipeline de ingestão — regras que não podem quebrar
- **Recepção desacoplada**: a thread UDP só carimba o horário e **enfileira** (fila
  limitada, descarta se encher); decodificação e I/O de banco ficam no `IngestWorker`.
  Nunca colocar I/O de banco, parsing pesado ou lock no caminho do `receive()`.
- **Formato do pacote detectado só pelo tamanho** (`PacketFormat.fromSize`): 232 (Sled),
  311 (FM7 Dash), 324 (Horizon), 331 (FM2023 Dash). Layout **little-endian**. Sled não
  traz inputs/pneus/posição e **não é gravado**. Formato novo = entrada nova em
  `PacketFormat` + teste com pacote sintético.
- **Sessão** = trecho contínuo com o mesmo carro (e mesma pista, no FM). Abre no primeiro
  pacote com `IsRaceOn=1` **e o carro andando** (velocidade ≥ 0,5 m/s); fecha só por troca de carro/classe de PI/pista
  ou pelo **limite de amostras** (abaixo). **Não há NENHUMA regra de tempo** (decisão do desenvolvedor): pausar, ficar na
  garagem, sair do jogo e voltar só amanhã continuam na **mesma sessão**. A garagem (e menus de foto/loja) segue mandando
  `IsRaceOn=1` com o carro parado, então **carro parado (< 0,5 m/s) não gera amostra nem abre sessão** — só as amostras
  limitam a sessão e contam para o tuning. Sessões com < 100 amostras (`min-session-samples`) são descartadas ao fechar.
- **Retomada**: o desligamento do serviço grava o que falta mas **não fecha** a sessão (`pauseActive`); ao receber o primeiro
  pacote com o carro andando, `resumeOrOpen` consulta as sessões abertas no banco (`findActiveMeta`): a do mesmo carro e
  classe (e que ainda caiba amostras, ou esteja numa corrida) é **retomada** — o tempo (`t_ms`) continua logo após a última
  amostra e as amostras já gravadas contam para o limite; as outras abertas (outro carro/classe, ou cheias) são órfãs e
  fechadas ali, com resumo (ou descartadas se < 100 amostras). Efeito: uma sessão fica "Ativa" enquanto o jogo estiver
  fechado, e só passa a contar no tuning ao fechar (limite de amostras ou troca de carro/classe).
- **Rotação por amostras**: a sessão **fecha sozinha ao juntar `telemetry.session-max-samples` (5000) amostras
  gravadas** (já pós-downsample) e a próxima abre no pacote seguinte. **Em corrida/evento espera acabar**:
  `lapNumber > 0` (no mundo aberto é 0 — conferido em amostras reais: 0 = exploração a ~50 km/h, 1+ = voltas a
  130–170 km/h); `racePosition` não é usado (não validado no mundo aberto; falso positivo impediria a rotação).
  Sprints sem volta (`lapNumber` 0) rotacionam no meio — limitação conhecida. Corrida longa passa de 5000 sem teto.
  Uma sessão parada na garagem fica "Ativa" sem amostras até voltar a andar ou trocar de carro. 10 sessões cheias = 50000
  amostras = mínimo do tuning (tempo não entra na regra).
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

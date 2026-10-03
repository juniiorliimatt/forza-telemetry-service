-- Espelha initdb/*.sql da raiz do monorepo (só a parte de forza_service). Roda como o
-- superusuário do container, igual em produção.
CREATE ROLE forza_service WITH LOGIN PASSWORD 'forza_service';
GRANT CONNECT ON DATABASE workbox TO forza_service;
CREATE SCHEMA IF NOT EXISTS forza AUTHORIZATION forza_service;

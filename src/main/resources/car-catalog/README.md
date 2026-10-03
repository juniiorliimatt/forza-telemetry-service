# Catálogo de carros (CarOrdinal → nome)

O Data Out do Forza manda só o `CarOrdinal` (um id numérico). Estes arquivos (`{ "ordinal": "nome" }`)
traduzem o id em nome e são gerados por [`tools/build-car-catalog.py`](../../../../tools/build-car-catalog.py).

| Arquivo | Família | Origem |
|---|---|---|
| `horizon.json` | Forza Horizon 4/5/6 (pacote de 324 bytes) | gist de HDR, "Forza Horizon 6 Car Ordinals" (nomes limpos) |
| `motorsport.json` | Forza Motorsport 2023 (331 bytes) | [bluemanos/forza-motorsport-car-track-ordinal](https://github.com/bluemanos/forza-motorsport-car-track-ordinal) `fm8/cars.csv` (licença MIT) |

- **Um catálogo por família**: o mesmo ordinal pode ter ano/nome diferente entre jogos (249 é "1964 Ferrari
  250 GTO" no Horizon e "1962 Ferrari 250 GTO" no Motorsport).
- Só entram nomes no formato `AAAA Marca Modelo`; códigos internos do jogo ficam de fora (a UI mostra `#ordinal`).
- Cobertura parcial: carros novos/DLC ou de FH4/FH5 ausentes das listas aparecem como `#ordinal`. FM7 e o
  formato Sled não têm catálogo (nunca se usa o nome de outro jogo).
- Listas comunitárias, não oficiais; o gist do Horizon não declara licença — dados factuais (nomes de
  carros), mantidos aqui para uso pessoal/de estudo. Atualize rodando o script e commitando o resultado.

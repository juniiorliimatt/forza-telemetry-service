#!/usr/bin/env python3
"""
Gera src/main/resources/car-catalog/{horizon,motorsport}.json (ordinal -> nome) a partir de listas
comunitárias. O Data Out do jogo só manda o CarOrdinal (um id), não o nome, e o MESMO ordinal pode ter
nome/ano diferentes entre as famílias de jogos — por isso um catálogo por família:

  horizon     (FH4/FH5/FH6, pacote de 324 bytes)  <- gist HDR "Forza Horizon 6 Car Ordinals" (nomes limpos)
  motorsport  (Forza Motorsport 2023, 331 bytes)  <- bluemanos/forza-motorsport-car-track-ordinal (MIT), fm8/cars.csv

Só entram nomes limpos ("AAAA Marca Modelo"); códigos internos (ex.: POR_911SportClassic_10) ficam de
fora — a UI cai no "#ordinal". Não roda no build/CI: rode manualmente pra atualizar e commite o resultado.

Uso: python3 forza-telemetry-service/tools/build-car-catalog.py
"""
import csv
import io
import json
import re
import urllib.request
from pathlib import Path

HORIZON_URL = ("https://gist.github.com/HDR/0659d1717bc61504bf83750628963f4f/raw/"
               "3a1fd9e2fb51d9230176980d84ea9797447af2b0/Forza%20Horizon%206%20Car%20Ordinals.json")
MOTORSPORT_URL = "https://raw.githubusercontent.com/bluemanos/forza-motorsport-car-track-ordinal/master/fm8/cars.csv"

OUT = Path(__file__).resolve().parent.parent / "src/main/resources/car-catalog"
CLEAN_NAME = re.compile(r"^\d{4} \S")


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read().decode("utf-8")


def write(name: str, catalog: dict) -> None:
    ordered = {str(k): catalog[k] for k in sorted(catalog, key=int)}
    (OUT / f"{name}.json").write_text(json.dumps(ordered, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{name}.json: {len(ordered)} carros")


def horizon() -> dict:
    by_name = json.loads(fetch(HORIZON_URL))  # {"2021 Porsche 911 GT3": "3667"}
    return {int(ordinal): name.strip() for name, ordinal in by_name.items() if CLEAN_NAME.match(name.strip())}


def motorsport() -> dict:
    catalog = {}
    for row in csv.reader(io.StringIO(fetch(MOTORSPORT_URL))):  # ordinal,ano,marca,modelo
        if len(row) >= 4 and row[0].isdigit():
            name = f"{row[1]} {row[2]} {row[3]}".strip()
            if CLEAN_NAME.match(name):
                catalog[int(row[0])] = name
    return catalog


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    write("horizon", horizon())
    write("motorsport", motorsport())

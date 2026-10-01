#!/usr/bin/env python3
"""
Cruza los canales de Xuper TV (tools/canales_xuper.txt) con la base de iptv-org
y con la lista de canales que tienen guia (guides.json).

Salidas en out/:
  reporte_canales.csv   -> un renglon por canal de Xuper, con su estado y una nota
  mapa_canales.json     -> nombre de Xuper -> id de iptv-org (solo coincidencias seguras)
  channels.xml          -> lista para la herramienta iptv-org/epg (solo coincidencias seguras)
  resumen.txt           -> conteo por estado

Un canal solo queda "auto" si:
  - el puntaje llega a AUTO,
  - el pais del resultado coincide con la pista de pais del nombre (HN, GUA, NIC...),
  - el pais esta en PRIORIDAD (o el id esta en PERMITIDOS).
Si no, baja a "revisar". La tabla OVERRIDES permite corregir a mano.
"""
import csv
import json
import os
import re
import sys
import unicodedata
import urllib.request
from collections import Counter, defaultdict

API = "https://iptv-org.github.io/api/"
LOCAL = os.environ.get("LOCAL_DIR")  # solo para pruebas sin red

try:
    from rapidfuzz import fuzz, process
    HAVE_RF = True
except ImportError:  # respaldo lento, solo para pruebas
    import difflib
    HAVE_RF = False

AUTO = 90
REVISAR = 78

STOP = {"hd", "fhd", "uhd", "sd", "4k", "opc", "hd2"}
COUNTRY_TOKENS = {
    "mx": "mx", "mex": "mx", "mexico": "mx",
    "us": "us", "usa": "us",
    "col": "co", "colombia": "co",
    "peru": "pe", "pe": "pe",
    "chile": "cl", "cl": "cl",
    "py": "py", "bo": "bo", "uy": "uy", "uruguay": "uy",
    "sv": "sv", "hn": "hn",
    "gua": "gt", "guate": "gt", "guatemala": "gt",
    "nic": "ni", "ni": "ni", "rd": "do",
    "ecuador": "ec", "espana": "es",
}
PROPIOS = re.compile(
    r"cinema vip|alquiler|24/7|lgvip|eventos|cinema\+|^netflix|amazon prime", re.I)

# Paises donde es mas probable que este el canal que ve el usuario (orden de prioridad)
PRIORIDAD = ["mx", "us", "co", "ar", "pe", "cl", "uy", "py", "bo", "ec",
             "gt", "sv", "hn", "ni", "cr", "pa", "do", "pr", "es"]

# Ids de paises fuera de PRIORIDAD que si son aceptables (revisados a mano)
PERMITIDOS = {
    "AlJazeera.qa", "FightNetwork.ca", "Cubavision.cu",
    "Band.br", "BandNews.br",
    "HBOXtreme.br", "HBOPlus.br", "HBOPop.br", "HBOMundi.br",
    "TVCancaoNova.br",
}

# Sitios que en las pruebas respondieron sin ningun programa: se evitan al elegir fuente
SITIOS_SIN_DATOS = {"tvtv.us", "tvprofil.com", "meuguia.tv",
                    "epg.iptvx.one", "programacion-tv.elpais.com"}

# Nombre limpio de Xuper -> nombre limpio con el que buscar en iptv-org
ALIAS = {
    "azteca 1": "azteca uno",
    "chv": "chilevision",
    "canal de las estrella": "las estrellas",
    "cnn espanol": "cnn en espanol",
    "imagen": "imagen television",
    "a mas plus": "a plus",
    "warner": "warner channel",
}

# Correcciones manuales: nombre EXACTO en Xuper -> lista de ids candidatos de iptv-org
# (se usa el primero que tenga guia) o None si se sabe que no hay guia correcta.
# Si ningun candidato tiene guia, el canal queda como "sin_guia".
OVERRIDES = {
    # Canales genericos "Canal N": el cruce automatico los confunde entre paises
    "CANAL 5 HN HD": None,
    "CANAL 6 MX HD": None,
    "CANAL 8 NIC HD": None,
    "CANAL 9 HD": None,
    "CANAL 10 NI HD": None,
    "CANAL 11 GUA HD": None,
    "CANAL 13 GUATEMALA HD": None,
    "CANAL 41 RD HD": None,
    "CANAL UNO ECUADOR HD": None,
    "Canal Pro HD": None,
    # Pais equivocado
    "America TV Peru HD": ["AmericaTV.pe"],
    "America TV Peru FHD": ["AmericaTV.pe"],
    "ANIMAL PLANET HD": ["AnimalPlanet.us", "AnimalPlanet.mx", "AnimalPlanet.co"],
    "ANIMAL PLANET HD+": ["AnimalPlanet.us", "AnimalPlanet.mx", "AnimalPlanet.co"],
    "ANIMAL PLANET FHD": ["AnimalPlanet.us", "AnimalPlanet.mx", "AnimalPlanet.co"],
    "ABC NEWS": ["ABCNews.us"],
    "City TV": None,
    "HOLLYWOOD HD": None,
    "TNT PERU HD": ["TNT.pe", "TNT.mx", "TNT.ar"],
    "TNT CHILE HD": ["TNT.cl", "TNT.mx", "TNT.ar"],
    "Cartoon Network MX HD": ["CartoonNetwork.mx", "CartoonNetwork.us"],
    # Falsos "revisar" que conviene dejar sin guia
    "BALLY SPORTS OHIO HD": ["BallySportsOhio.us"],
    "BALLY SPORTS DETROIT HD": ["BallySportsDetroit.us"],
    "BALLY SPORTS MIDWEST HD": ["BallySportsMidwest.us"],
    "CHILE VISION HD": ["ChileVision.cl"],
    "CLAN TVE HD": ["ClanTVE.es", "Clan.es"],
    "TNT SERIES MX HD": None,
    "TNT SERIES COL HD": None,
    "TNT NOVELAS HD": None,
    "Imagen HD": ["ImagenTV.mx", "ImagenTelevision.mx", "ImagenTelevision.us"],
    "OPA TV": None,
    "Arte1": None,
    "A3S": None,
    "CHCH": None,
    "CADENA A BO HD": None,
    "PARAMOUNT+ HD": None,
    "ST☆R Central HD": None,
    "ST☆R Central FHD": None,
    "SHOWTIME EAST HD": None,
    "SHOWTIME WEST HD": None,
    "TL Novelas HD": ["TlnovelasMexico.mx"],
    "CBS NEWS USA HD": ["CBSNews.us", "CBSNewsNetwork.us"],
    # Revisados y aceptados
    "Baby TV": ["BabyTV.uk"],
    "BABY FIRTS HD": ["BabyFirst.us"],
    "BABY FIRTS FHD": ["BabyFirst.us"],
    "Cancao Nova": ["TVCancaoNova.br"],
    "World Fishing HD": ["WorldFishingNetwork.us"],
}


def get_json(name):
    if LOCAL:
        with open(os.path.join(LOCAL, name), encoding="utf-8") as f:
            return json.load(f)
    req = urllib.request.Request(API + name, headers={"User-Agent": "cruce-xuper/1.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def norm(s):
    """Devuelve (nombre_limpio, pais_pista)."""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\b(hd|fhd|uhd)\s*\+", " ", s)
    s = s.replace("&", " and ").replace("+", " plus ")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    hint = None
    toks = []
    for t in s.split():
        if t in STOP:
            continue
        if t in COUNTRY_TOKENS:
            hint = hint or COUNTRY_TOKENS[t]
            continue
        toks.append(t)
    return " ".join(toks), hint


def country_of(cid):
    return cid.rsplit(".", 1)[-1].split("@")[0].lower()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    xuper = []
    with open(os.path.join(here, "canales_xuper.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.strip():
                p = line.split("\t")
                xuper.append((p[0].strip(), p[1].strip() if len(p) > 1 else ""))

    print("Descargando channels.json y guides.json ...")
    channels = get_json("channels.json")
    guides = get_json("guides.json")

    # canal -> lista de fuentes con guia
    by_channel = defaultdict(list)
    site_count = Counter()
    for g in guides:
        cid = g.get("channel")
        if not cid:
            continue
        by_channel[cid].append(g)
        site_count[g.get("site")] += 1

    def pick(cid):
        """Fuente de guia para un canal: la que mas canales cubre, evitando sitios sin datos."""
        ok = [g for g in by_channel[cid] if g.get("site") not in SITIOS_SIN_DATOS]
        return max(ok or by_channel[cid], key=lambda x: site_count[x.get("site")])

    # candidatos: nombre y nombres alternos de canales que tienen guia
    choices, meta = [], []
    chan_name = {}
    for c in channels:
        cid = c.get("id")
        chan_name[cid] = c.get("name", "")
        if cid not in by_channel or c.get("is_nsfw"):
            continue
        names = [c.get("name", "")] + list(c.get("alt_names") or [])
        for n in names:
            core, _ = norm(n)
            if core:
                choices.append(core)
                meta.append((cid, c.get("name", "")))
    print(f"Canales con guia en iptv-org: {len(by_channel)}  |  nombres comparables: {len(choices)}")

    def sources_of(cid):
        return ";".join(sorted({g.get("site", "") for g in by_channel[cid]}))

    rows = []
    override_fallidos = []
    for name, num in xuper:
        if PROPIOS.search(name):
            rows.append([name, num, "propio", "", "", 0, "", ""])
            continue

        # 1) correcciones manuales
        if name in OVERRIDES:
            cands_ov = OVERRIDES[name]
            elegido = None
            for cid in (cands_ov or []):
                if cid in by_channel:
                    elegido = cid
                    break
            if elegido:
                rows.append([name, num, "auto", elegido, chan_name.get(elegido, ""),
                             100, sources_of(elegido), "correccion manual"])
            else:
                if cands_ov:
                    override_fallidos.append(name)
                rows.append([name, num, "sin_guia", "", "", 0, "",
                             "correccion manual: sin guia"])
            continue

        # 2) cruce automatico
        core, hint = norm(name)
        core = ALIAS.get(core, core)
        if not core:
            rows.append([name, num, "sin_guia", "", "", 0, "", ""])
            continue

        if HAVE_RF:
            cands = process.extract(core, choices, scorer=fuzz.token_sort_ratio, limit=10)
            cands = [(c[0], c[1], c[2]) for c in cands]  # texto, score, indice
        else:
            tmp = []
            for i, ch in enumerate(choices):
                tmp.append((ch, 100 * difflib.SequenceMatcher(None, core, ch).ratio(), i))
            cands = sorted(tmp, key=lambda x: -x[1])[:10]

        best = None
        for _, score, idx in cands:
            cid, oname = meta[idx]
            cc = country_of(cid)
            adj = score
            if cc in PRIORIDAD:
                adj += 3 - 0.25 * PRIORIDAD.index(cc)
            else:
                adj -= 10
            if hint:
                adj += 6 if cc == hint else -4
            if best is None or adj > best[0]:
                best = (adj, cid, oname)

        score, cid, oname = best
        cc = country_of(cid)
        nota = ""
        if score >= AUTO:
            status = "auto"
            if hint and cc != hint:
                status = "revisar"
                nota = f"pais del nombre ({hint}) no coincide con el resultado ({cc})"
            elif cc not in PRIORIDAD and cid not in PERMITIDOS:
                status = "revisar"
                nota = f"pais del resultado ({cc}) fuera de prioridad"
        elif score >= REVISAR:
            status = "revisar"
        else:
            status = "sin_guia"
        sites = sources_of(cid) if status != "sin_guia" else ""
        rows.append([name, num, status, cid if status != "sin_guia" else "",
                     oname if status != "sin_guia" else "", round(min(score, 100), 1),
                     sites, nota])

    os.makedirs("out", exist_ok=True)
    with open("out/reporte_canales.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["canal_xuper", "numero", "estado", "id_iptv_org",
                    "nombre_iptv_org", "puntaje", "fuentes_de_guia", "nota"])
        w.writerows(rows)

    mapa, xml = {}, ['<?xml version="1.0" encoding="UTF-8"?>', "<channels>"]
    usados = set()
    for name, num, status, cid, oname, score, sites, nota in rows:
        if status != "auto":
            continue
        mapa[name] = cid
        if cid in usados:
            continue
        usados.add(cid)
        # una sola fuente por canal: la que mas canales cubre
        g = pick(cid)
        lang = g.get("lang") or "es"
        esc = lambda t: (t or "").replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")
        xml.append(
            f'  <channel site="{esc(g.get("site"))}" lang="{esc(lang)}" '
            f'xmltv_id="{esc(cid)}" site_id="{esc(g.get("site_id"))}">{esc(oname)}</channel>'
        )
    xml.append("</channels>")
    with open("out/mapa_canales.json", "w", encoding="utf-8") as f:
        json.dump(mapa, f, ensure_ascii=False, indent=1)
    with open("out/channels.xml", "w", encoding="utf-8") as f:
        f.write("\n".join(xml) + "\n")

    cnt = Counter(r[2] for r in rows)
    total = len(rows)
    resumen = [f"Canales de Xuper analizados: {total}"]
    for k in ("auto", "revisar", "sin_guia", "propio"):
        resumen.append(f"  {k}: {cnt.get(k, 0)}")
    resumen.append(f"Canales distintos en channels.xml: {len(usados)}")
    resumen.append("Fuentes usadas: " + ", ".join(
        f"{s} ({n})" for s, n in Counter(
            pick(c).get('site') for c in usados
        ).most_common()))
    solo_malas = sorted(c for c in usados if pick(c).get("site") in SITIOS_SIN_DATOS)
    if solo_malas:
        resumen.append("Canales cuya unica fuente es un sitio sin datos: " + "; ".join(solo_malas))
    if override_fallidos:
        resumen.append("Correcciones manuales sin id valido (quedaron sin guia): "
                       + "; ".join(override_fallidos))
    txt = "\n".join(resumen)
    with open("out/resumen.txt", "w", encoding="utf-8") as f:
        f.write(txt + "\n")
    print(txt)
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    if summ:
        with open(summ, "a", encoding="utf-8") as f:
            f.write("```\n" + txt + "\n```\n")


if __name__ == "__main__":
    sys.exit(main())

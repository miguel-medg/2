#!/usr/bin/env python3
"""
Toma la guia Latino/Mexico de EPGTalk (Schedules Direct, lineup Izzi) y la liga a los canales
de Xuper TV (tools/canales_xuper.txt).

Hace:
  1. Descarga Latino_guide.xml.gz (o usa LATINO_FILE para pruebas locales).
  2. Cruza el nombre de cada canal de Xuper con las estaciones de la guia.
  3. Muchas estaciones vienen en varias copias con horarios distintos; descarta las que
     dicen "Canal no disponible" y elige una por canal (tools/latino_feeds.json permite
     fijar cual; si no, la copia mas comun).
  4. Escribe out/guides/latino.xml (solo las estaciones elegidas) y agrega los canales
     a out/mapa_canales.json (tienen prioridad sobre el cruce de iptv-org).
  5. Escribe out/reporte_latino.csv; en los canales con copias distintas muestra que
     programa pasa AHORA en cada copia, para que elijas la que coincide con tu tele.

Debe correr DESPUES de tools/cruce.py. Si falla la descarga, no rompe el workflow.
"""
import csv
import difflib
import gzip
import io
import json
import os
import re
import sys
import unicodedata
import urllib.request
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
URL = "https://raw.githubusercontent.com/acidjesuz/EPGTalk/master/Latino_guide.xml.gz"
LOCAL = os.environ.get("LATINO_FILE")  # solo para pruebas
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"

AUTO, REVISAR = 0.90, 0.78
STOP = {"hd", "fhd", "sd", "uhd", "opc", "mx", "latin", "america", "latinoamerica", "latam"}
PROPIOS = re.compile(
    r"cinema vip|alquiler|24/7|lgvip|eventos|cinema\+|^netflix|amazon prime", re.I)
PLACEHOLDER = re.compile(
    r"canal no disponible|no disponible|programaci[oó]n no disponible|sign off|^\s*$", re.I)

# nombre normalizado de Xuper -> nombre normalizado de la guia
ALIAS = {
    "azteca 1": "azteca uno",
    "canal de las estrella": "las estrellas",
    "las estrellas mex": "las estrellas",
    "a mas plus": "a mas",
    "tnt series": "tnt series",
    "warner": "warner channel",
    "sony": "canal sony",
    "cnn espanol": "cnn en espanol",
    "tlnovelas": "tlnovelas",
    "tl novelas": "tlnovelas",
}

# Nombre EXACTO en Xuper -> nombre de la estacion en la guia (o None = no usar esta fuente).
# Revisa estos con tu tele: son deducciones por nombre o por siglas de la estacion.
FORCE = {
      "ST☆R Central HD": "Star Channel",
    "ST☆R Central FHD": "Star Channel",
    "HBO2 Central HD": "HBO 2",
    "Cartoon Network MX HD": None,
    "Latina HD": None,          # es el canal de Peru, no "ALATINA"
    "Latina HD+": None,
    # --- corregidos con lo que se vio en la tele (1 oct, tarde) ---
    "AXN HD": "AMC",                    # el canal "AXN" de Xuper en realidad es AMC
    "FX Central HD": "FX",
    "Sony Central HD": "Canal Sony",
    "ST☆R HD": "Star Channel",
    "ST☆R HD+": "Star Channel",
    "TNT SERIES MX HD": "TNT Series",
    "TNT SERIES COL HD": "TNT Series",
    "TNT FHD": "TNT Series",            # el FHD es TNT Series, no TNT
    "De Pelicula HD": "De Película",
    "HBO 2 HD": "HBO 2",
    "E! HD": "E! Entertainment TV",
    # No son la senal de Mexico de la guia: mejor sin guia que una guia equivocada
        "STAR CHANNEL HD": "Star Channel",          
    "Sony HD": None,                    # senal en ingles, no esta en la guia
    "TNT PERU HD": None,
    "TNT CHILE HD": None,
    "UNIVERSAL PREMIER HD": None,
    "UNIVERSAL CINEMA HD": None,
    "UNIVERSAL COMEDY HD": None,
    "UNIVERSAL CRIME HD": None,
    "HOLLYWOOD HD": None,               # senal de Espana
    "TL Novelas HD": None,
    "TLNOVELAS HD": None,
    "De Pelicula Plus FHD": None,
    "NEOX HD": None,
    "CANAL 5 HD": "XHGC",       # Canal 5 de Mexico (siglas XHGC)
    "Azteca 7 HD": "XHIMT",     # Canal 7 de TV Azteca (siglas XHIMT)
    "CANAL ONCE MX HD": "Once",
    "Imagen HD": "Imagen TV HD",
    "BABY FIRTS HD": "BabyFirst",
    "BABY FIRTS FHD": "BabyFirst",
    "A3S Series HD": "A3S Atreseries",
    "AZ CINEMA FHD": "Azteca Cinema",
    "Pasiones": "Pasiones TV Latin America",
}


def abrir(data):
    return gzip.GzipFile(fileobj=io.BytesIO(data)) if data[:2] == b"\x1f\x8b" else io.BytesIO(data)


def cargar_bytes():
    if LOCAL:
        with open(LOCAL, "rb") as f:
            return f.read()
    req = urllib.request.Request(URL, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def norm(s):
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    s = re.sub(r"\b(hd|fhd|uhd)\s*\+", " ", s)
    s = s.replace("&", " and ").replace("+", " plus ")
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return " ".join(t for t in s.split() if t not in STOP)


def lineup_num(cid):
    m = re.match(r"I(\d+)\.", cid)
    return int(m.group(1)) if m else 10 ** 9


def leer_xuper():
    out = []
    with open(os.path.join(HERE, "canales_xuper.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if line.strip():
                out.append(line.split("\t")[0].strip())
    return out


def leer_feeds():
    p = os.path.join(HERE, "latino_feeds.json")
    if os.path.exists(p):
        with open(p, encoding="utf-8") as f:
            return {k: v for k, v in json.load(f).items() if not k.startswith("_")}
    return {}


def main():
    try:
        data = cargar_bytes()
    except Exception as e:
        print(f"! No se pudo descargar la guia Latino: {e}")
        return 0
    print(f"Guia Latino: {len(data) / 1e6:.1f} MB")

    # ---- pasada 1: estaciones ----
    nombres = {}
    for ev, el in ET.iterparse(abrir(data), events=("end",)):
        if el.tag == "channel":
            dn = [d.text.strip() for d in el.findall("display-name") if d.text]
            if dn and not dn[0].lower().startswith("duplicate"):
                nombres[el.get("id")] = dn[0]
            el.clear()
        elif el.tag == "programme":
            break
    por_clave = defaultdict(list)
    for cid, n in nombres.items():
        por_clave[norm(n)].append(cid)
    claves = list(por_clave)
    print(f"Estaciones: {len(nombres)}  nombres distintos: {len(claves)}")

    # ---- cruce de nombres ----
    xuper = leer_xuper()
    asign = {}   # canal xuper -> (clave, estado, nota)
    for x in xuper:
        if PROPIOS.search(x):
            asign[x] = (None, "propio", "")
            continue
        if x in FORCE:
            if FORCE[x] is None:
                asign[x] = (None, "sin_guia", "descartado a mano")
                continue
            k = norm(FORCE[x])
            if k in por_clave:
                asign[x] = (k, "auto", "forzado a mano")
            else:
                asign[x] = (None, "sin_guia", f"forzado a '{FORCE[x]}' pero no existe en la guia")
            continue
        n = norm(x)
        n = ALIAS.get(n, n)
        if not n:
            asign[x] = (None, "sin_guia", "")
            continue
        if n in por_clave:
            asign[x] = (n, "auto", "")
            continue
        best = max(claves, key=lambda k: difflib.SequenceMatcher(None, n, k).ratio())
        r = difflib.SequenceMatcher(None, n, best).ratio()
        if r >= AUTO:
            asign[x] = (best, "auto", f"parecido {r:.2f}")
        elif r >= REVISAR:
            asign[x] = (best, "revisar", f"parecido {r:.2f} con '{best}'")
        else:
            asign[x] = (None, "sin_guia", "")

    candidatas = set()
    for k, est, _ in asign.values():
        if k and est in ("auto", "revisar"):
            candidatas.update(por_clave[k])

    # ---- pasada 2: programas de las estaciones candidatas ----
    ahora = datetime.now(timezone.utc)
    if os.environ.get("LATINO_NOW"):   # solo para pruebas: AAAAMMDDHHMMSS en UTC
        ahora = datetime.strptime(os.environ["LATINO_NOW"], "%Y%m%d%H%M%S").replace(tzinfo=timezone.utc)
    corte = (ahora - timedelta(hours=3)).strftime("%Y%m%d%H%M%S")
    now_s = ahora.strftime("%Y%m%d%H%M%S")
    fin_huella = (ahora + timedelta(hours=36)).strftime("%Y%m%d%H%M%S")
    progs = defaultdict(list)
    for ev, el in ET.iterparse(abrir(data), events=("end",)):
        if el.tag == "programme":
            cid = el.get("channel")
            if cid in candidatas:
                t = el.find("title")
                titulo = (t.text or "").strip() if t is not None else ""
                progs[cid].append((el.get("start")[:14], el.get("stop")[:14], titulo,
                                   el.get("start"), el.get("stop")))
            el.clear()
        elif el.tag == "channel":
            el.clear()

    def buena(cid):
        l = progs.get(cid, [])
        if len(l) < 15:
            return False
        reales = sum(1 for p in l if not PLACEHOLDER.search(p[2]))
        return reales / len(l) >= 0.8

    def huella(cid):
        return tuple((s, t) for s, e, t, _, _ in sorted(progs[cid]) if now_s <= e and s <= fin_huella)

    def titulo_ahora(cid):
        for s, e, t, _, _ in sorted(progs[cid]):
            if s <= now_s < e:
                return t
        return "(sin dato)"

    feeds_fijos = leer_feeds()
    elegidas = {}   # canal xuper -> id de estacion
    filas = []
    for x in xuper:
        k, estado, nota = asign[x]
        if not k or estado in ("propio", "sin_guia"):
            filas.append([x, estado, "", "", 0, 0, "", nota])
            continue
        todas = sorted(por_clave[k], key=lineup_num)
        buenas = [c for c in todas if buena(c)]
        if not buenas:
            filas.append([x, "sin_guia", "", nombres[todas[0]], 0, 0, "",
                          "todas las copias vienen sin programacion"])
            continue
        grupos = defaultdict(list)
        for c in buenas:
            grupos[huella(c)].append(c)
        ahora_txt = " | ".join(
            f"{'/'.join(g[0].split('.')[0] for g in [ids])}: {titulo_ahora(ids[0])}"
            for ids in sorted(grupos.values(), key=lambda v: lineup_num(v[0])))
        fijo = feeds_fijos.get(x)
        if fijo:
            cand = [c for c in buenas if c.split(".")[0] == fijo]
            elegido = cand[0] if cand else None
            if not elegido:
                nota = (nota + " " if nota else "") + f"la copia fijada {fijo} no existe o no sirve"
        else:
            elegido = None
        if not elegido:
            mayor = max(grupos.values(), key=lambda v: (len(v), -lineup_num(v[0])))
            elegido = sorted(mayor, key=lineup_num)[0]
            if len(grupos) > 1 and not fijo:
                nota = (nota + " " if nota else "") + "VARIAS COPIAS DISTINTAS: elige en latino_feeds.json"
        if estado == "auto":
            elegidas[x] = elegido
        filas.append([x, estado, elegido.split(".")[0], nombres[elegido], len(buenas),
                      len(grupos), ahora_txt if len(grupos) > 1 else titulo_ahora(elegido), nota])

    # ---- salida: guia filtrada ----
    usadas = sorted(set(elegidas.values()), key=lineup_num)
    tv = ET.Element("tv", {"generator-info-name": "epg-xuper-latino"})
    for cid in usadas:
        c = ET.SubElement(tv, "channel", {"id": cid})
        ET.SubElement(c, "display-name").text = nombres[cid]
    n_prog = 0
    for cid in usadas:
        for s, e, t, s_raw, e_raw in sorted(progs[cid]):
            if e < corte or PLACEHOLDER.search(t):
                continue
            p = ET.SubElement(tv, "programme", {"start": s_raw, "stop": e_raw, "channel": cid})
            ET.SubElement(p, "title", {"lang": "es"}).text = t
            n_prog += 1
    os.makedirs("out/guides", exist_ok=True)
    ET.ElementTree(tv).write("out/guides/latino.xml", encoding="utf-8", xml_declaration=True)

    # ---- mapa ----
    mapa_path = "out/mapa_canales.json"
    mapa = {}
    if os.path.exists(mapa_path):
        with open(mapa_path, encoding="utf-8") as f:
            mapa = json.load(f)
    # Latino tiene prioridad sobre el cruce de iptv-org
    reemplazados = set()
    # Los canales descartados a mano quedan SIN guia (no se deja la de iptv-org, que suele ser erronea)
    for x, v in FORCE.items():
        if v is None and x in mapa:
            reemplazados.add(mapa.pop(x))
    for x, cid in elegidas.items():
        if x in mapa and mapa[x] != cid:
            reemplazados.add(mapa[x])
        mapa[x] = cid
    with open(mapa_path, "w", encoding="utf-8") as f:
        json.dump(mapa, f, ensure_ascii=False, indent=1)

    # Los canales de iptv-org que ya nadie usa se quitan de channels.xml (menos descargas)
    cx = "out/channels.xml"
    if reemplazados and os.path.exists(cx):
        sobran = reemplazados - set(mapa.values())
        try:
            tree = ET.parse(cx)
            root = tree.getroot()
            quitados = 0
            for ch in list(root.findall("channel")):
                if ch.get("xmltv_id") in sobran:
                    root.remove(ch)
                    quitados += 1
            tree.write(cx, encoding="utf-8", xml_declaration=True)
            print(f"channels.xml: se quitaron {quitados} canales que ahora cubre Latino")
        except Exception as e:
            print(f"aviso: no se pudo ajustar channels.xml ({e})")

    with open("out/reporte_latino.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["canal_xuper", "estado", "estacion", "nombre_guia", "copias_buenas",
                    "copias_distintas", "programa_ahora", "nota"])
        w.writerows(filas)

    cnt = Counter(r[1] for r in filas)
    ambiguos = sum(1 for r in filas if r[1] != "sin_guia" and r[5] > 1)
    print(f"Xuper: {len(xuper)} | auto {cnt['auto']} | revisar {cnt['revisar']} | "
          f"sin_guia {cnt['sin_guia']} | propios {cnt['propio']}")
    print(f"latino.xml: {len(usadas)} estaciones, {n_prog} programas | "
          f"canales con copias distintas: {ambiguos}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

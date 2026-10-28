#!/usr/bin/env python3
"""Aggiorna lo store: legge fonti.json, controlla l'ultima versione di ogni app
nei repository GitHub e copia gli APK nuovi in apk/<id>/, poi riscrive apps.json.
Se un'app da' errore resta la versione precedente e si passa alla successiva."""

import glob, hashlib, json, os, re, subprocess, sys, tempfile, urllib.request, urllib.error

API = "https://api.github.com"
TOKEN = os.environ.get("GITHUB_TOKEN", "")
RADICE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def richiesta(url, accept="application/vnd.github+json"):
    h = {"Accept": accept, "User-Agent": "store-aggiorna"}
    if TOKEN:
        h["Authorization"] = "Bearer " + TOKEN
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=120) as r:
        return r.read()


def api_json(percorso):
    return json.loads(richiesta(API + percorso))


def contenuto(repo, percorso):
    return richiesta(f"{API}/repos/{repo}/contents/{percorso}", "application/vnd.github.raw")


def chiave_versione(v):
    return [int(p) if p.isdigit() else p for p in re.split(r"(\d+)", v)]


def note_brevi(t):
    t = re.sub(r"\s+", " ", (t or "")).strip()
    return t[:600]


def trova_aapt2():
    for base in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), "/usr/local/lib/android/sdk"):
        if base:
            c = sorted(glob.glob(os.path.join(base, "build-tools", "*", "aapt2")), key=chiave_versione)
            if c:
                return c[-1]
    return os.environ.get("AAPT2")


AAPT2 = trova_aapt2()


def pacchetto(file_apk):
    """Nome del pacchetto e versione interna dell'APK (servono all'app per sapere cosa e' installato)."""
    if not AAPT2:
        return {}
    try:
        out = subprocess.run([AAPT2, "dump", "badging", file_apk], capture_output=True, text=True, timeout=60).stdout
        m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", out)
        return {"pacchetto": m.group(1), "codice_versione": int(m.group(2))} if m else {}
    except Exception:
        return {}


# ---------- le tre fonti ----------
def da_json(a):
    d = json.loads(contenuto(a["repo"], "releases/latest.json"))
    if a.get("chiave"):
        d = d[a["chiave"]]
    apk = d["apk"]
    return {"versione": str(d["version"]), "nome_file": apk.split("/")[-1],
            "note": note_brevi(d.get("notes")),
            "scarica": lambda: contenuto(a["repo"], apk)}


def da_cartella(a):
    rx = re.compile(a["file"])
    trovati = []
    for f in api_json(f"/repos/{a['repo']}/contents/releases"):
        m = rx.search(f["name"]) if f.get("type") == "file" else None
        if m:
            trovati.append((m.group(1) if m.groups() else f["name"], f))
    if not trovati:
        raise RuntimeError("nessun APK trovato in releases/")
    versione, f = max(trovati, key=lambda x: chiave_versione(x[0]))
    return {"versione": versione, "nome_file": f["name"], "note": "",
            "scarica": lambda: contenuto(a["repo"], f["path"])}


def da_release(a):
    rx = re.compile(a["file"])
    for rel in api_json(f"/repos/{a['repo']}/releases?per_page=20"):
        if rel.get("draft"):
            continue
        for asset in rel.get("assets", []):
            if rx.search(asset["name"]):
                m = re.search(r"\d[\w.\-]*", rel["tag_name"])
                return {"versione": m.group(0) if m else rel["tag_name"], "nome_file": asset["name"],
                        "note": note_brevi(rel.get("body")),
                        "scarica": lambda u=asset["url"]: richiesta(u, "application/octet-stream")}
    raise RuntimeError("nessuna release con il file cercato")


FONTI = {"json": da_json, "cartella": da_cartella, "release": da_release}


def main():
    fonti = json.load(open(os.path.join(RADICE, "fonti.json"), encoding="utf-8"))["app"]
    percorso_cat = os.path.join(RADICE, "apps.json")
    try:
        vecchio = {x["id"]: x for x in json.load(open(percorso_cat, encoding="utf-8"))["apps"]}
    except (OSError, ValueError, KeyError):
        vecchio = {}

    nuovo, errori = [], 0
    for a in fonti:
        prima = vecchio.get(a["id"])
        try:
            info = FONTI[a["tipo"]](a)
            cartella = os.path.join(RADICE, "apk", a["id"])
            file_rel = f"apk/{a['id']}/{info['nome_file']}"
            file_abs = os.path.join(RADICE, file_rel)

            if prima and prima.get("versione") == info["versione"] and prima.get("file") == file_rel \
                    and os.path.exists(file_abs):
                voce = dict(prima)
            else:
                dati = info["scarica"]()
                if not dati.startswith(b"PK"):
                    raise RuntimeError("il file scaricato non e' un APK")
                os.makedirs(cartella, exist_ok=True)
                for vecchio_file in os.listdir(cartella):   # tiene solo l'ultima versione
                    os.remove(os.path.join(cartella, vecchio_file))
                open(file_abs, "wb").write(dati)
                voce = {"id": a["id"], "versione": info["versione"], "file": file_rel,
                        "dimensione": len(dati), "sha256": hashlib.sha256(dati).hexdigest(),
                        "note": info["note"]}
                voce.update(pacchetto(file_abs))
                print(f"NUOVA  {a['id']} {info['versione']}")
            if "pacchetto" not in voce:
                voce.update(pacchetto(file_abs))
            voce.update({"nome": a["nome"], "per": a.get("per", []), "repo": a["repo"]})
            if a.get("richiede"):
                voce["richiede"] = a["richiede"]
            else:
                voce.pop("richiede", None)
            nuovo.append(voce)
        except Exception as e:
            errori += 1
            print(f"ERRORE {a['id']}: {e}", file=sys.stderr)
            if prima:
                nuovo.append(prima)

    # toglie le cartelle delle app eliminate da fonti.json
    ids = {a["id"] for a in fonti}
    base = os.path.join(RADICE, "apk")
    for d in (os.listdir(base) if os.path.isdir(base) else []):
        if d not in ids:
            for f in os.listdir(os.path.join(base, d)):
                os.remove(os.path.join(base, d, f))
            os.rmdir(os.path.join(base, d))

    testo = json.dumps({"versione_catalogo": 1, "apps": nuovo}, ensure_ascii=False, indent=2) + "\n"
    open(percorso_cat, "w", encoding="utf-8").write(testo)
    print(f"Fatto: {len(nuovo)} app, {errori} errori")


if __name__ == "__main__":
    main()

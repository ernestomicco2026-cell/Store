#!/usr/bin/env python3
"""Icone dello store: per ogni app produce icone/<id>.png (192x192).

Ordine di ricerca:
  1. file store-icon.png nella radice del repo dell'app (se l'autore vuole un'icona apposta);
  2. icona dentro l'APK: se e' un'immagine (png/webp) la copia, se e' un disegno vettoriale
     (VectorDrawable, anche dentro un'icona "adaptive") lo converte in SVG e poi in PNG.
Se non si trova niente l'app mostra il suo simbolo predefinito.
"""
import os, re, shutil, subprocess, tempfile, zipfile

SIZE = 192
ANDROID = "http://schemas.android.com/apk/res/android:"


# ---------------------------------------------------------------- lettura con aapt2

def _run(aapt2, *args):
    return subprocess.run([aapt2, *args], capture_output=True, text=True, timeout=120).stdout


def _icon_paths(aapt2, apk):
    """Percorsi dell'icona nell'APK, dal piu' grande al piu' piccolo."""
    out = _run(aapt2, "dump", "badging", apk)
    found = []
    for dens, path in re.findall(r"application-icon-(\d+):'([^']+)'", out):
        found.append((int(dens), path))
    m = re.search(r"application: label='[^']*' icon='([^']+)'", out)
    if m:
        found.append((0, m.group(1)))
    found.sort(key=lambda x: -x[0])
    seen, res = set(), []
    for _, p in found:
        if p not in seen:
            seen.add(p)
            res.append(p)
    return res


class _Res:
    """Risolve i riferimenti @0x7f... (colori e drawable) leggendo la tabella risorse."""

    def __init__(self, aapt2, apk):
        self.ids = {}
        cur = None
        for line in _run(aapt2, "dump", "resources", apk).splitlines():
            m = re.match(r"\s*resource (0x[0-9a-f]+) (\S+)", line)
            if m:
                cur = int(m.group(1), 16)
                continue
            if cur is None:
                continue
            m = re.match(r"\s*\([^)]*\) (.+)$", line)
            if m and cur not in self.ids:
                self.ids[cur] = m.group(1).strip()

    def get(self, ref):
        try:
            return self.ids.get(int(ref.lstrip("@"), 16))
        except ValueError:
            return None


def _tree(aapt2, apk, path):
    """Albero XML (tag, attributi, figli) dal testo di 'aapt2 dump xmltree'."""
    out = _run(aapt2, "dump", "xmltree", "--file", path, apk)
    root, stack = None, []
    for line in out.splitlines():
        ind = len(line) - len(line.lstrip())
        s = line.strip()
        if s.startswith("E: "):
            node = {"tag": s[3:].split(" ")[0], "a": {}, "c": [], "ind": ind}
            while stack and stack[-1]["ind"] >= ind:
                stack.pop()
            if stack:
                stack[-1]["c"].append(node)
            elif root is None:
                root = node
            stack.append(node)
        elif s.startswith("A: ") and stack:
            m = re.match(r"A: (?:\S+?:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$", s)
            if not m:
                continue
            val = m.group(2)
            raw = re.search(r'\(Raw: "(.*)"\)$', val)
            if raw:
                val = raw.group(1)
            elif val.startswith('"') and val.endswith('"'):
                val = val[1:-1]
            stack[-1]["a"][m.group(1)] = val
    return root


# ---------------------------------------------------------------- vettoriale -> SVG

def _num(v, d=0.0):
    try:
        return float(re.sub(r"(dp|dip|px|sp)$", "", str(v)))
    except (TypeError, ValueError):
        return d


def _color(v, res):
    """Restituisce (colore #rrggbb, opacita') oppure None."""
    if v is None:
        return None
    if v.startswith("@") and res:
        v = res.get(v) or ""
    m = re.match(r"#([0-9a-fA-F]{3,8})$", v.strip())
    if not m:
        return None
    h = m.group(1)
    if len(h) in (3, 4):
        h = "".join(c * 2 for c in h)
    a = 1.0
    if len(h) == 8:
        a, h = int(h[:2], 16) / 255.0, h[2:]
    return "#" + h, a


def _esc(s):
    return s.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;")


def _vector_svg(node, res):
    a = node["a"]
    vw, vh = _num(a.get("viewportWidth"), 24), _num(a.get("viewportHeight"), 24)
    body = []

    def walk(n):
        for c in n["c"]:
            ca = c["a"]
            if c["tag"] == "group":
                px, py = _num(ca.get("pivotX")), _num(ca.get("pivotY"))
                t = "translate(%g %g) translate(%g %g) rotate(%g) scale(%g %g) translate(%g %g)" % (
                    _num(ca.get("translateX")), _num(ca.get("translateY")), px, py,
                    _num(ca.get("rotation")), _num(ca.get("scaleX"), 1), _num(ca.get("scaleY"), 1), -px, -py)
                body.append('<g transform="%s">' % t)
                walk(c)
                body.append("</g>")
            elif c["tag"] == "path" and ca.get("pathData"):
                fill = _color(ca.get("fillColor"), res)
                stroke = _color(ca.get("strokeColor"), res)
                at = ['d="%s"' % _esc(ca["pathData"])]
                if fill:
                    at.append('fill="%s" fill-opacity="%g"' % (fill[0], fill[1] * _num(ca.get("fillAlpha"), 1)))
                else:
                    at.append('fill="none"')
                if ca.get("fillType") in ("evenOdd", "1"):
                    at.append('fill-rule="evenodd"')
                if stroke and _num(ca.get("strokeWidth")) > 0:
                    cap = {"0": "butt", "1": "round", "2": "square"}.get(ca.get("strokeLineCap"), ca.get("strokeLineCap", "butt"))
                    join = {"0": "miter", "1": "round", "2": "bevel"}.get(ca.get("strokeLineJoin"), ca.get("strokeLineJoin", "miter"))
                    at.append('stroke="%s" stroke-opacity="%g" stroke-width="%g" stroke-linecap="%s" stroke-linejoin="%s"' % (
                        stroke[0], stroke[1] * _num(ca.get("strokeAlpha"), 1), _num(ca.get("strokeWidth")), cap, join))
                body.append("<path %s/>" % " ".join(at))

    walk(node)
    return vw, vh, body


def _drawable_svg(aapt2, apk, path, res, depth=0):
    """SVG di un drawable XML (vector o adaptive-icon), oppure None."""
    if depth > 3:
        return None
    root = _tree(aapt2, apk, path)
    if not root:
        return None
    if root["tag"] == "vector":
        vw, vh, body = _vector_svg(root, res)
        return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %g %g">%s</svg>' % (vw, vh, "".join(body))
    if root["tag"] == "adaptive-icon":
        layers = []
        for c in root["c"]:
            if c["tag"] not in ("background", "foreground"):
                continue
            d = c["a"].get("drawable", "")
            col = _color(d, res)
            if col:
                layers.append('<rect width="108" height="108" fill="%s" fill-opacity="%g"/>' % col)
                continue
            target = res.get(d) if d.startswith("@") else None
            if target and target.endswith(".xml"):
                inner = _drawable_svg(aapt2, apk, target, res, depth + 1)
                if inner:
                    layers.append(re.sub(r"^<svg ", '<svg width="108" height="108" ', inner))
        if not layers:
            return None
        # le icone adaptive si vedono ritagliate: mostro la zona centrale con angoli arrotondati
        return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72"><defs><clipPath id="c">'
                '<rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>'
                '<g clip-path="url(#c)">%s</g></svg>' % "".join(layers))
    return None


def _svg_to_png(svg, dest):
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "i.svg")
        open(src, "w", encoding="utf-8").write(svg)
        if shutil.which("rsvg-convert"):
            subprocess.run(["rsvg-convert", "-w", str(SIZE), "-h", str(SIZE), "-o", dest, src], capture_output=True, timeout=60)
        else:
            try:   # senza rsvg-convert (prove in locale): disegna con Chromium
                from playwright.sync_api import sync_playwright
                with sync_playwright() as p:
                    b = p.chromium.launch()
                    pg = b.new_page(viewport={"width": SIZE, "height": SIZE})
                    pg.set_content('<html><body style="margin:0;background:transparent">%s</body></html>'
                                   % re.sub(r"^<svg ", '<svg width="%d" height="%d" ' % (SIZE, SIZE), svg))
                    pg.screenshot(path=dest, omit_background=True)
                    b.close()
            except Exception:
                subprocess.run(["convert", "-background", "none", "-density", "600", src, "-resize",
                                "%dx%d" % (SIZE, SIZE), dest], capture_output=True, timeout=60)
    return os.path.exists(dest) and os.path.getsize(dest) > 0


def _raster_to_png(data, dest):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        # PNG: va bene cosi' (l'app la ridimensiona da sola)
        open(dest, "wb").write(data)
        return True
    if shutil.which("rsvg-convert"):
        # altri formati (webp, jpg): li disegna rsvg-convert dentro un SVG
        import base64
        mime = "image/webp" if data[8:12] == b"WEBP" else "image/jpeg"
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="%d" height="%d">'
               '<image width="%d" height="%d" xlink:href="data:%s;base64,%s"/></svg>'
               % (SIZE, SIZE, SIZE, SIZE, mime, base64.b64encode(data).decode()))
        if _svg_to_png(svg, dest):
            return True
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, "i.img")
        open(src, "wb").write(data)
        subprocess.run(["convert", src, "-resize", "%dx%d" % (SIZE, SIZE), dest], capture_output=True, timeout=60)
    return os.path.exists(dest) and os.path.getsize(dest) > 0


# ---------------------------------------------------------------- funzione principale

def make(aapt2, apk, dest, repo_png=None):
    """Crea dest (PNG). repo_png = byte di store-icon.png del repo, se esiste. Ritorna True se ci e' riuscito."""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if repo_png and _raster_to_png(repo_png, dest):
        return True
    if not aapt2 or not apk:
        return False
    try:
        res = None
        for p in _icon_paths(aapt2, apk):
            if p.endswith(".xml"):
                res = res or _Res(aapt2, apk)
                svg = _drawable_svg(aapt2, apk, p, res)
                if svg and _svg_to_png(svg, dest):
                    return True
            else:
                with zipfile.ZipFile(apk) as z:
                    if p in z.namelist() and _raster_to_png(z.read(p), dest):
                        return True
    except Exception:
        pass
    return False

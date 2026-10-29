#!/usr/bin/env python3
"""
The "DLNA app icon" icons: YouTube (a screen with a play button) and YouTube Music (a record
with a play button), each with the cast waves in the corner, in two styles (night, day).

Writes Android vector drawables (adaptive icon layers, 108 x 108 dp) to the patch's resources,
and with --preview <dir> also SVG previews of each (masked to a circle, like a launcher).

    python3 scripts/icons/generate.py [--preview DIR]
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "patches/src/main/resources/ytdlna/branding")

# The cast waves, top right: inside the launcher's safe zone (a 66 dp circle around 54, 54).
ORIGIN = (68.0, 39.0)
RADII = (6.5, 12.0)

STYLES = {
    "night": dict(
        background=("linear", (0, 0, 108, 108), ["#2A1052", "#0C0A20"]),
        glow=("#FF2D55", 0.16),
        shadow=0.40,
        waves="#FFFFFF",
        arm="#E9ECF2",
    ),
    "day": dict(
        background=("linear", (0, 0, 108, 108), ["#FFFFFF", "#FFE4EA"]),
        glow=("#FF2D55", 0.10),
        shadow=0.16,
        waves="#3A3F4C",
        arm="#7D8392",
    ),
}

RED = ("linear", (26, 42, 72, 80), ["#FF5C70", "#E6002E"])


def rrect(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} A{r},{r} 0 0 1 {x + w},{y + r} V{y + h - r} "
            f"A{r},{r} 0 0 1 {x + w - r},{y + h} H{x + r} A{r},{r} 0 0 1 {x},{y + h - r} "
            f"V{y + r} A{r},{r} 0 0 1 {x + r},{y} Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy} Z"


def triangle(points):
    (a, b, c) = points
    return f"M{a[0]},{a[1]} L{b[0]},{b[1]} L{c[0]},{c[1]} Z"


def arc(r):
    x, y = ORIGIN
    return f"M{x},{y - r} A{r},{r} 0 0 1 {x + r},{y}"


# A shape: dict(d, fill | stroke, width, alpha, evenodd).
def shapes(app, style):
    s = STYLES[style]
    out = []
    if app == "youtube":
        body = rrect(26, 44, 46, 34, 9)
        shadow = rrect(26, 47, 46, 34, 9)
        play = triangle([(43.5, 53), (43.5, 69), (57, 61)])
        gloss = rrect(28, 45.5, 42, 13, 7.5)
        extra = []
    else:
        return turntable(s) + waves(s)
    out.append(dict(d=shadow, fill="#000000", alpha=s["shadow"]))
    out.append(dict(d=body, fill=RED))
    out += extra
    if gloss:
        out.append(dict(d=gloss, fill="#FFFFFF", alpha=0.13))
    out.append(dict(d=play, fill="#FFFFFF"))
    return out + waves(s)


# The record (YouTube Music): a red record with grooves and a play button, and the tonearm.
RECORD = (45, 60, 20)
ARM = "M70.5,52 L69,70 L60,75"
ARM_SHORT = "M70.5,52 L69,70 L65.2,72.1"
HEAD = "M62.4,73.7 L57.6,76.3"
PIVOT = (70.5, 52)
PLAY = [(40.5, 52.5), (40.5, 67.5), (53, 60)]


def turntable(s):
    cx, cy, rr = RECORD
    out = [
        dict(d=circle(cx, cy + 3, rr), fill="#000000", alpha=s["shadow"]),
        dict(d=circle(cx, cy, rr), fill=RED),
    ]
    out += [dict(d=circle(cx, cy, g), stroke="#FFFFFF", width=0.7, alpha=0.20) for g in (16.8, 14.0, 11.3)]
    # A sheen across the grooves.
    out.append(dict(d=f"M{cx - 12},{cy - 9.5} A15.3,15.3 0 0 1 {cx + 3},{cy - 15}", stroke="#FFFFFF", width=2.0, alpha=0.30))
    out += [
        dict(d=triangle(PLAY), fill="#FFFFFF"),
        dict(d=ARM, stroke="#000000", width=3.0, alpha=0.18),
        dict(d=ARM, stroke=s["arm"], width=1.8),
        dict(d=HEAD, stroke=s["arm"], width=3.2),
        dict(d=circle(PIVOT[0], PIVOT[1], 2.8), fill=s["arm"]),
    ]
    return out


def waves(s):
    out = [dict(d=arc(r), stroke=s["waves"], width=3.4) for r in RADII]
    out.append(dict(d=circle(ORIGIN[0], ORIGIN[1], 3.0), fill=s["waves"]))
    return out


def background(style):
    s = STYLES[style]
    color, alpha = s["glow"]
    return [
        dict(d="M0,0 H108 V108 H0 Z", fill=s["background"]),
        dict(d=circle(49, 60, 36), fill=color, alpha=alpha * 0.5),
        dict(d=circle(49, 60, 27), fill=color, alpha=alpha),
    ]


def monochrome(app):
    if app == "youtube":
        body = rrect(26, 44, 46, 34, 9) + " " + triangle([(43.5, 53), (43.5, 69), (57, 61)])
    else:
        cx, cy, rr = RECORD
        out = [
            dict(d=circle(cx, cy, rr) + " " + triangle(PLAY), fill="#FFFFFF", evenodd=True),
            # Off the record: white on white wouldn't show.
            dict(d=ARM_SHORT, stroke="#FFFFFF", width=1.8),
            dict(d=circle(PIVOT[0], PIVOT[1], 2.8), fill="#FFFFFF"),
        ]
        out += [dict(d=arc(r), stroke="#FFFFFF", width=3.4) for r in RADII]
        out.append(dict(d=circle(ORIGIN[0], ORIGIN[1], 3.0), fill="#FFFFFF"))
        return out
    out = [dict(d=body, fill="#FFFFFF", evenodd=True)]
    out += [dict(d=arc(r), stroke="#FFFFFF", width=3.4) for r in RADII]
    out.append(dict(d=circle(ORIGIN[0], ORIGIN[1], 3.0), fill="#FFFFFF"))
    return out


# region Android vector drawable

def vd_gradient(attr, g):
    kind, (x1, y1, x2, y2), colors = g
    items = "".join(
        f'\n                <item android:offset="{i / (len(colors) - 1):.2f}" android:color="{c}" />'
        for i, c in enumerate(colors))
    return (f'\n        <aapt:attr name="android:{attr}">'
            f'\n            <gradient android:type="{kind}" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}">{items}'
            f'\n            </gradient>\n        </aapt:attr>')


def vd(shape_list):
    paths = []
    for s in shape_list:
        attrs = [f'android:pathData="{s["d"]}"']
        children = ""
        if "fill" in s:
            if isinstance(s["fill"], tuple):
                children = vd_gradient("fillColor", s["fill"])
            else:
                attrs.append(f'android:fillColor="{s["fill"]}"')
            if "alpha" in s:
                attrs.append(f'android:fillAlpha="{s["alpha"]}"')
            if s.get("evenodd"):
                attrs.append('android:fillType="evenOdd"')
        else:
            attrs += [f'android:strokeColor="{s["stroke"]}"', f'android:strokeWidth="{s["width"]}"',
                      'android:strokeLineCap="round"']
            if "alpha" in s:
                attrs.append(f'android:strokeAlpha="{s["alpha"]}"')
        attr_text = "\n        ".join(attrs)
        if children:
            paths.append(f"    <path\n        {attr_text}>{children}\n    </path>")
        else:
            paths.append(f"    <path\n        {attr_text} />")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by scripts/icons/generate.py. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + "\n".join(paths) + "\n</vector>\n")

# endregion

# region SVG preview

def svg_shapes(shape_list, prefix):
    defs, body = [], []
    for i, s in enumerate(shape_list):
        if "fill" in s:
            if isinstance(s["fill"], tuple):
                _, (x1, y1, x2, y2), colors = s["fill"]
                gid = f"{prefix}{i}"
                stops = "".join(f'<stop offset="{j / (len(colors) - 1)}" stop-color="{c}"/>' for j, c in enumerate(colors))
                defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" '
                            f'x2="{x2}" y2="{y2}">{stops}</linearGradient>')
                fill = f"url(#{gid})"
            else:
                fill = s["fill"]
            rule = ' fill-rule="evenodd"' if s.get("evenodd") else ""
            body.append(f'<path d="{s["d"]}" fill="{fill}" fill-opacity="{s.get("alpha", 1)}"{rule}/>')
        else:
            body.append(f'<path d="{s["d"]}" fill="none" stroke="{s["stroke"]}" stroke-width="{s["width"]}" '
                        f'stroke-linecap="round" stroke-opacity="{s.get("alpha", 1)}"/>')
    return defs, body


def svg(layers, size=432):
    defs, body = [], []
    for n, layer in enumerate(layers):
        d, b = svg_shapes(layer, f"g{n}_")
        defs += d
        body += b
    # The launcher shows the middle 72 of 108 dp, masked here to a circle.
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
            f'<defs>{"".join(defs)}<clipPath id="m"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
            f'<g clip-path="url(#m)">{"".join(body)}</g></svg>')

# endregion


def main():
    preview = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for app in ("youtube", "music"):
        for style in STYLES:
            folder = os.path.join(OUT, app, style)
            os.makedirs(folder, exist_ok=True)
            with open(os.path.join(folder, "background.xml"), "w") as f:
                f.write(vd(background(style)))
            with open(os.path.join(folder, "foreground.xml"), "w") as f:
                f.write(vd(shapes(app, style)))
            if preview:
                os.makedirs(preview, exist_ok=True)
                with open(os.path.join(preview, f"{app}_{style}.svg"), "w") as f:
                    f.write(svg([background(style), shapes(app, style)]))
        with open(os.path.join(OUT, app, "monochrome.xml"), "w") as f:
            f.write(vd(monochrome(app)))
        if preview:
            with open(os.path.join(preview, f"{app}_mono.svg"), "w") as f:
                f.write(svg([[dict(d="M0,0 H108 V108 H0 Z", fill="#3B3F4A")], monochrome(app)]))


if __name__ == "__main__":
    main()

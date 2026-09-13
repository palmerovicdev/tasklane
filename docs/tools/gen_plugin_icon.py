#!/usr/bin/env python3
"""Genera el icono del plugin —el de la ficha del Marketplace— claro y oscuro.

    python3 docs/tools/gen_plugin_icon.py

El icono **es el producto**: dos tarjetas con su casilla, su franja de prioridad y
su linea de texto, que es exactamente lo que se ve al abrir la tool window. Se
descarto el pictograma abstracto de la tool window (dos vistos y dos rayas) porque a
40 px, y al lado de cualquier otra ficha del Marketplace, no distingue a este plugin
de los otros treinta gestores de TODOs.

Las franjas llevan los colores REALES de las prioridades por defecto, los que estan
en `TasklaneConfig.DEFAULT`: alta y normal. Si alli cambian, aqui tambien.

Un solo cuerpo para las dos variantes, por lo mismo que en `gen_format_icons.py`:
mantenerlas a mano es la forma segura de que dejen de dibujar lo mismo.
"""
import os

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "src", "main", "resources", "META-INF",
)

# Claro y oscuro. Los de prioridad salen de TaskPriority en Config.kt.
THEMES = {
    "": dict(CARD="#EBECF0", EDGE="#C9CCD6", FG="#6C707E", HIGH="#B3392C", NORMAL="#B0700F"),
    "_dark": dict(CARD="#3C3F41", EDGE="#4E5157", FG="#CED0D6", HIGH="#E2705F", NORMAL="#DBA646"),
}

BODY = """
  <g>
    <rect x="3" y="7" width="34" height="12" rx="3.5" fill="{CARD}" stroke="{EDGE}"/>
    <path d="M3 10.5A3.5 3.5 0 0 1 6.5 7H7v12h-.5A3.5 3.5 0 0 1 3 15.5z" fill="{HIGH}"/>
    <rect x="10.5" y="10.5" width="5" height="5" rx="1.3" stroke="{FG}" fill="none"/>
    <path d="M19 11.5H32" stroke="{FG}" stroke-width="1.6" stroke-linecap="round"/>
    <path d="M19 15H27" stroke="{FG}" stroke-width="1.6" stroke-linecap="round" opacity="0.55"/>
  </g>
  <g>
    <rect x="3" y="21" width="34" height="12" rx="3.5" fill="{CARD}" stroke="{EDGE}"/>
    <path d="M3 24.5A3.5 3.5 0 0 1 6.5 21H7v12h-.5A3.5 3.5 0 0 1 3 29.5z" fill="{NORMAL}"/>
    <rect x="10.5" y="24.5" width="5" height="5" rx="1.3" stroke="{FG}" fill="none"/>
    <path d="M11.6 27L12.8 28.2L15 25.8" stroke="{FG}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
    <path d="M19 25.5H29" stroke="{FG}" stroke-width="1.6" stroke-linecap="round"/>
    <path d="M19 29H24" stroke="{FG}" stroke-width="1.6" stroke-linecap="round" opacity="0.55"/>
  </g>
"""

HEAD = '<svg width="40" height="40" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">\n'
TAIL = '</svg>\n'

for suffix, colors in THEMES.items():
    body = BODY.strip("\n")
    for key, value in colors.items():
        body = body.replace("{" + key + "}", value)
    path = os.path.normpath(os.path.join(OUT, f"pluginIcon{suffix}.svg"))
    with open(path, "w", encoding="utf-8") as f:
        f.write(HEAD + body + "\n" + TAIL)
    print(path)

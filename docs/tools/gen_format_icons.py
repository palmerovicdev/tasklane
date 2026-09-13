#!/usr/bin/env python3
"""Genera los 8 iconos de la barra de formato del dialogo, claro y oscuro.

    python3 docs/tools/gen_format_icons.py

Un solo cuerpo por icono, con {C} donde va el color: las dos variantes tienen que
dibujar exactamente lo mismo, y mantenerlas a mano es la forma segura de que dejen
de hacerlo. `IconLoader` elige la variante `_dark` sola, asi que el codigo Kotlin
referencia solo el fichero base.

Los dos grises son los de la interfaz nueva de la plataforma y los mismos que usan
`calendar.svg` y `tasklane.svg`. Rejilla de 16, trazo de 1 salvo en las letras, que
a 1 quedan enclenques al lado de los pictogramas.
"""
import os

LIGHT = "#6C707E"
DARK = "#CED0D6"
# Relativo a la raiz del repositorio, no al directorio desde el que se lance.
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "icons")

HEAD = '<svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">\n'
TAIL = '</svg>\n'

ICONS = {
    # «B». Trazo grueso: es lo que hace que se lea negrita y no una letra cualquiera.
    "format_bold": """
  <path d="M5.5 3.5V12.5" stroke="{C}" stroke-width="1.6" stroke-linecap="round"/>
  <path d="M5.5 3.5H8.25C9.49 3.5 10.25 4.51 10.25 5.75C10.25 6.99 9.49 8 8.25 8H5.5" stroke="{C}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M5.5 8H8.75C9.99 8 10.75 9.01 10.75 10.25C10.75 11.49 9.99 12.5 8.75 12.5H5.5" stroke="{C}" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
""",
    # «I» inclinada, con las dos barras: sin ellas una diagonal suelta no es una letra.
    "format_italic": """
  <path d="M6.75 3.5H12" stroke="{C}" stroke-width="1.3" stroke-linecap="round"/>
  <path d="M4 12.5H9.25" stroke="{C}" stroke-width="1.3" stroke-linecap="round"/>
  <path d="M9.5 3.5L6.5 12.5" stroke="{C}" stroke-width="1.3" stroke-linecap="round"/>
""",
    # «</>».
    "format_code": """
  <path d="M5.5 5L2.5 8L5.5 11" stroke="{C}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M10.5 5L13.5 8L10.5 11" stroke="{C}" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M9 4L7 12" stroke="{C}" stroke-width="1.3" stroke-linecap="round"/>
""",
    # Dos ganchos entrelazados y la barra que los cruza. La barra va sobre el eje y
    # las patas de cada gancho a lado y lado: puestas las tres sobre la misma linea
    # —que fue el primer intento— el conjunto se lee como una ese, no como una cadena.
    "format_link": """
  <path d="M9.9 6.1L6.1 9.9" stroke="{C}" stroke-linecap="round"/>
  <path d="M10.4 8.4L12.9 5.9A2 2 0 0 0 10.1 3.1L7.6 5.6" stroke="{C}" stroke-linecap="round"/>
  <path d="M5.6 7.6L3.1 10.1A2 2 0 0 0 5.9 12.9L8.4 10.4" stroke="{C}" stroke-linecap="round"/>
""",
    # Marco, sol y dos montanas. Monocromo a proposito: `AllIcons.FileTypes.Image` es
    # azul y en una fila de botones de formato cantaba como si fuera otra cosa.
    "format_image": """
  <rect x="2.5" y="3.5" width="11" height="9" rx="1.5" stroke="{C}"/>
  <circle cx="5.75" cy="6.5" r="1" stroke="{C}"/>
  <path d="M2.75 12.25L6.5 8.5L10.25 12.25" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M8.75 10.75L10.75 8.75L13.25 11.25" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
""",
    # Tres puntos y tres lineas.
    "format_bullet": """
  <circle cx="3.25" cy="3.5" r="1.1" fill="{C}"/>
  <circle cx="3.25" cy="8" r="1.1" fill="{C}"/>
  <circle cx="3.25" cy="12.5" r="1.1" fill="{C}"/>
  <path d="M6.5 3.5H13.5" stroke="{C}" stroke-linecap="round"/>
  <path d="M6.5 8H13.5" stroke="{C}" stroke-linecap="round"/>
  <path d="M6.5 12.5H13.5" stroke="{C}" stroke-linecap="round"/>
""",
    # Lo mismo con «1 2 3» en lugar de los puntos. A 16 px las cifras son mas una
    # silueta que una lectura, y es suficiente: lo que distingue este icono del de
    # puntos es que la columna izquierda cambia de fila en fila.
    "format_numbered": """
  <path d="M2.9 2.4L3.9 1.8V5.2" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M2.9 5.2H4.9" stroke="{C}" stroke-linecap="round"/>
  <path d="M2.6 6.85C2.75 6.15 4.7 6.1 4.7 7.25C4.7 8.3 2.6 8.8 2.6 9.65H4.85" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M2.7 11.2C3.2 10.55 4.85 10.9 4.85 11.75C4.85 12.4 4.15 12.55 3.5 12.55C4.15 12.55 4.95 12.75 4.95 13.5C4.95 14.4 3.2 14.5 2.7 13.85" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M7 3.5H13.5" stroke="{C}" stroke-linecap="round"/>
  <path d="M7 8H13.5" stroke="{C}" stroke-linecap="round"/>
  <path d="M7 12.5H13.5" stroke="{C}" stroke-linecap="round"/>
""",
    # Dos casillas —una marcada, otra no— y su linea. Es literalmente lo que inserta:
    # `- [x]` encima de `- [ ]`. Un visto suelto, que es lo que habia antes, no dice
    # que la marca vaya dentro de una lista.
    "format_checklist": """
  <rect x="1.75" y="2.5" width="4.5" height="4.5" rx="1" stroke="{C}"/>
  <path d="M2.9 4.75L3.85 5.7L5.5 3.9" stroke="{C}" stroke-linecap="round" stroke-linejoin="round"/>
  <rect x="1.75" y="9" width="4.5" height="4.5" rx="1" stroke="{C}"/>
  <path d="M8.25 4.75H14" stroke="{C}" stroke-linecap="round"/>
  <path d="M8.25 11.25H14" stroke="{C}" stroke-linecap="round"/>
""",
}

for name, body in ICONS.items():
    for suffix, color in (("", LIGHT), ("_dark", DARK)):
        path = os.path.normpath(os.path.join(OUT, f"{name}{suffix}.svg"))
        with open(path, "w", encoding="utf-8") as f:
            f.write(HEAD + body.strip("\n").replace("{C}", color) + "\n" + TAIL)
        print(path)

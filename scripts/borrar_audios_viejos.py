"""
Borra de Firebase los audios de la sala por internet que tengan más de
HORAS_A_GUARDAR horas, y las salas abandonadas. Lo ejecuta GitHub Actions
solo, cada hora (ver .github/workflows/borrar-audios-viejos.yml).

Truco para no gastar los 10 GB de descarga del plan gratis: no se descarga
ningún audio. Se piden solo los NOMBRES de los mensajes ("shallow"), y como
Firebase guarda dentro de cada nombre (push id) la hora exacta en que se
creó, con eso basta para saber cuáles son viejos.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

import google.auth.transport.requests
from google.oauth2 import service_account

URL_BASE = os.environ.get("FIREBASE_URL", "https://motointercom-1235a-default-rtdb.firebaseio.com").rstrip("/")
HORAS_A_GUARDAR = float(os.environ.get("HORAS_A_GUARDAR", "24"))

# Caracteres que usa Firebase para los push id; los primeros 8 son la hora en milisegundos.
CARACTERES_PUSH = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"


def hora_del_push_id(clave):
    """Milisegundos en que se creó un mensaje, sacados de su push id (o None si no es uno)."""
    if len(clave) != 20:
        return None
    ms = 0
    for c in clave[:8]:
        i = CARACTERES_PUSH.find(c)
        if i < 0:
            return None
        ms = ms * 64 + i
    return ms


def token():
    info = json.loads(os.environ["FIREBASE_CUENTA_DE_SERVICIO"])
    cred = service_account.Credentials.from_service_account_info(
        info,
        scopes=[
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/firebase.database",
        ],
    )
    cred.refresh(google.auth.transport.requests.Request())
    return cred.token


def pedir(metodo, ruta, tok, cuerpo=None, consulta=None):
    url = URL_BASE + "/" + "/".join(urllib.parse.quote(p, safe="") for p in ruta) + ".json"
    if consulta:
        url += "?" + urllib.parse.urlencode(consulta)
    datos = json.dumps(cuerpo).encode() if cuerpo is not None else None
    req = urllib.request.Request(url, data=datos, method=metodo)
    req.add_header("Authorization", "Bearer " + tok)
    if datos is not None:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.loads(r.read().decode() or "null")


def main():
    tok = token()
    ahora = int(time.time() * 1000)
    limite = ahora - int(HORAS_A_GUARDAR * 3600 * 1000)

    salas = pedir("GET", ["salas"], tok, consulta={"shallow": "true"}) or {}
    total_audios = 0
    total_salas = 0

    for codigo in salas:
        claves = pedir("GET", ["salas", codigo, "mensajes"], tok, consulta={"shallow": "true"}) or {}
        viejos = [k for k in claves if (hora_del_push_id(k) or ahora) < limite]
        # Se borran de a 500 en una sola petición (poner null = borrar).
        for i in range(0, len(viejos), 500):
            pedir("PATCH", ["salas", codigo, "mensajes"], tok, cuerpo={k: None for k in viejos[i:i + 500]})
        total_audios += len(viejos)

        # Sala abandonada: sin audios recientes, sin nadie adentro y creada hace más del límite.
        quedan = len(claves) - len(viejos)
        if quedan == 0:
            gente = pedir("GET", ["salas", codigo, "participantes"], tok, consulta={"shallow": "true"}) or {}
            creada = pedir("GET", ["salas", codigo, "creada"], tok)
            if not gente and (not isinstance(creada, (int, float)) or creada < limite):
                pedir("DELETE", ["salas", codigo], tok)
                total_salas += 1

    print(f"Listo: {total_audios} audios viejos borrados, {total_salas} salas abandonadas borradas, "
          f"{len(salas)} salas revisadas (se guardan las últimas {HORAS_A_GUARDAR:g} horas).")


if __name__ == "__main__":
    try:
        main()
    except KeyError:
        print("Falta el secreto FIREBASE_CUENTA_DE_SERVICIO en GitHub (Settings > Secrets and variables > Actions).")
        sys.exit(1)

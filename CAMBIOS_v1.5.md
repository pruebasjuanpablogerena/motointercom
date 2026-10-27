# MotoIntercom 1.5: burbuja con doble toque y audios más rápidos

## Burbuja flotante
- **Mantener presionada** = grabar y hablar (igual que antes). Al soltar, se envía.
- **Dos toques rápidos** = abre MotoIntercom en la pantalla de la sala.
- **Un toque rápido solo** = no hace nada (se descarta, no envía audio vacío).
- **Arrastrar** = mover la burbuja (igual que antes).

## Menos demora al enviar audios
- Al soltar el botón (en la app, en la burbuja y en la versión web del iPhone) se esperaba **1 segundo** por si fue un toque accidental. Ahora son **0,3 segundos**: cada audio llega unos 0,7 s antes.
- La versión web (iPhone) ya no descarga todos los audios de las últimas 24 horas al entrar, y usa la hora de Firebase (antes, si el reloj del iPhone estaba adelantado, se perdían audios).
- **Medidor de demora:** cuando llega un audio, el estado dice por ejemplo *"Juan está hablando... (llegó en 1,4 s)"*. Ese número es lo que tardó por internet desde que el otro lo envió. Sirve para saber si la demora es de la señal.

Archivos que cambiaron respecto a la 1.4:
- `app/src/main/java/com/motointercom/app/SalaInternetService.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `sala-web/index.html`
- `app/build.gradle` (versión 1.4 → 1.5)

## Borrado automático de audios viejos (gratis)
- `.github/workflows/borrar-audios-viejos.yml` + `scripts/borrar_audios_viejos.py`: cada hora GitHub borra de Firebase los audios de más de 24 horas y las salas abandonadas, **sin descargar los audios** (no gasta los 10 GB).
- Necesita una sola vez el secreto `FIREBASE_CUENTA_DE_SERVICIO` en GitHub (ver instrucciones en el chat / README).

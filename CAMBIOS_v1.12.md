# MotoIntercom 1.12: emisoras favoritas, contador y motivo de error

Todo es **agregado**; no se quitó ni se cambió nada de lo que ya funcionaba.

## Favoritas ⭐
- Cada emisora tiene una **estrella ☆** a la derecha. Al tocarla queda **★ dorada** y se guarda en "Mis favoritas" (se guarda en el celular, no se pierde al cerrar la app).
- En el desplegable de departamentos hay una opción nueva, **"⭐ Mis favoritas"** (justo debajo de "Todos los departamentos"), que muestra solo las favoritas.
- Tocar el resto de la fila sigue reproduciendo la emisora, como siempre.

## Contador de emisoras 📻
- Debajo del desplegable dice cuántas emisoras salen, por ejemplo:
  - "📻 1.948 emisoras"
  - "📻 12 emisoras con "mega""
  - "📻 60 emisoras en HUILA"
  - "📻 3 emisoras con "pita" en HUILA"

## Por qué no suena una emisora
- Si una emisora no se puede reproducir, ahora dice **el motivo**, por ejemplo: *"el servidor de la emisora no responde (puede estar apagada o haber cambiado de link)"* o *"la emisora ya no está en ese link (error 404)"*, con un código técnico al final.
- A las emisoras lentas se les da más tiempo para responder (15 segundos).

Archivos que cambiaron respecto a la 1.11:
- `app/src/main/java/com/motointercom/app/RadioActivity.kt`
- `app/src/main/java/com/motointercom/app/ReproductorRadio.kt`
- `app/src/main/res/layout/item_emisora.xml`
- `app/src/main/res/layout/activity_radio.xml`
- `app/src/main/res/values/colors.xml`
- `app/build.gradle` (versión 1.11 → 1.12)

## La radio sigue sonando fuera de su pantalla
- Al salir de **Radio Online** (con ← o con atrás) la radio **sigue sonando**: en el menú principal, en la sala por internet o en otra app. También sigue al salir de la sala.
- Para eso Android exige una **notificación fija** (como Spotify): dice qué emisora suena y trae el botón **"Apagar radio"**. Tocar la notificación abre Radio Online.
- **Botón flotante "📻 Radio OFF"** dentro de la app (menú principal y sala): **solo aparece mientras suena la radio**. Al tocarlo se apaga. Se puede **arrastrar** si tapa algo.
- La radio también se apaga con **"Detener"** en Radio Online, como antes.

Archivos adicionales que cambiaron:
- `app/src/main/java/com/motointercom/app/RadioService.kt` (nuevo)
- `app/src/main/java/com/motointercom/app/BotonRadioOff.kt` (nuevo)
- `app/src/main/java/com/motointercom/app/MainActivity.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `app/src/main/AndroidManifest.xml`

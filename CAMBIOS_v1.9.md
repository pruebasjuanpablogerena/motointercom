# MotoIntercom 1.9: radio con ExoPlayer (La Mega y demás emisoras de RCN)

## Por qué La Mega no sonaba
La Mega transmite en HLS "por pedacitos de AAC" (archivos `.aac` de pocos segundos, cada uno con su token de sesión). El reproductor básico de Android que usaba la app **no entiende ese formato**, por eso en el navegador sonaba y en la app no.
No era solo La Mega: **72 emisoras** de la lista usan ese mismo sistema (las de RCN: La Mega, La FM, Alerta, El Sol, Radio 1… en varias ciudades).

**Arreglo:** la radio ahora usa **ExoPlayer**, el reproductor de Google, que sí entiende ese formato y pide solo los tokens de cada pedacito. El link de La Mega queda igual (`mdstrm.com/audio/632c9ae6660fef03fe3855fe/live.m3u8`): ese link ES el que entrega los tokens.

## Otras 245 emisoras que tampoco sonaban
**245 emisoras** usan links `http://` (sin candado). Android 9 o más nuevo los bloquea si la app no lo permite expresamente. Ahora la app los permite (`usesCleartextTraffic`).

## Además
- Si una emisora se corta, la app reintenta sola (hasta 3 veces).
- Si la transmisión en vivo se queda atrasada, salta al presente.

Archivos que cambiaron respecto a la 1.8:
- `app/src/main/java/com/motointercom/app/ReproductorRadio.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle` (librería ExoPlayer, versión 1.8 → 1.9)

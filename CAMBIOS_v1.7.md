# MotoIntercom 1.7: compartir la sala con la imagen del QR

- El botón **Compartir** de la sala ahora envía la **imagen del código QR** (con el código de la sala debajo) junto con el texto y el link. En WhatsApp llega como foto con el mensaje.
- Si por algo la imagen no se puede preparar, se comparte solo el texto, como antes.

Archivos que cambiaron respecto a la 1.6:
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `app/src/main/AndroidManifest.xml` (permiso para compartir la imagen)
- `app/src/main/res/xml/rutas_compartir.xml` (nuevo)
- `app/build.gradle` (versión 1.6 → 1.7)

## Importante: el link y el QR necesitan GitHub Pages activado
El link y el QR abren `https://pruebasjuanpablogerena.github.io/motointercom/`. Si sale **404 "There isn't a GitHub Pages site here"**, es porque GitHub Pages no está activado:
**Settings → Pages → Source: "Deploy from a branch" → Branch: main, carpeta / (root) → Save.**

## Versión web para iPhone (`sala-web/index.html`)
- **Micrófono:** antes se abría una sola vez al entrar y quedaba abierto; el iPhone lo silenciaba al rato y los audios salían vacíos. Ahora se abre al presionar "hablar" y se suelta al enviar.
- **Altavoz:** como el micrófono ya no queda abierto, lo que llega suena por el altavoz (y en iOS 17+ aunque el iPhone esté en silencio), no bajito por el auricular.
- **Volumen:** barra nueva "Volumen de lo que llega", de 1x a 3x, igual que en la app.
- **Pantalla encendida** mientras se está en la sala (iOS 16.4+), porque Safari deja de recibir audios si la pantalla se apaga.
- Si el micrófono no graba, ahora lo dice (antes no avisaba nada) y explica cómo dar el permiso en Safari.

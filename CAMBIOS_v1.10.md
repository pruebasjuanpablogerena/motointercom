# MotoIntercom 1.10: permisos solo cuando se usan, explicando para qué

Antes la app pedía casi todo de golpe al abrirla (micrófono, Bluetooth, ubicación, notificaciones y batería) sin decir para qué. Ahora cada permiso se pide **en el momento en que se usa** y **siempre con una explicación antes**.

| Permiso | Cuándo se pide ahora |
|---|---|
| Bluetooth | Al tocar *Soy el conductor* o *Soy el pasajero* |
| Micrófono | Al conectar por Bluetooth, al entrar a una sala o al tocar *hablar* en la sala |
| Notificaciones | La primera vez que se conecta o se entra a una sala (una sola vez) |
| Batería sin restricciones | La primera vez que se conecta o se entra a una sala (una sola vez) |
| Mostrar sobre otras apps (burbuja) | Al **entrar** a una sala (antes: al abrir la pantalla) |
| Cámara | Al tocar *Leer código QR* |
| Compartir música/GPS | Igual que antes (ya tenía explicación) |

- La **radio** ya no pide ningún permiso.
- Si dices que no: lo que no necesita ese permiso sigue funcionando. Por ejemplo, sin micrófono se puede estar en la sala **solo escuchando**.
- Si Android ya no deja volver a preguntar (porque se negó varias veces), la app lo explica y ofrece **"Abrir ajustes"** para activarlo a mano.
- La app **ya no pide la ubicación** en ningún celular (no la necesitaba: se conecta a celulares ya emparejados).

Archivos que cambiaron respecto a la 1.9:
- `app/src/main/java/com/motointercom/app/PedidorDePermisos.kt` (nuevo)
- `app/src/main/java/com/motointercom/app/MainActivity.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/build.gradle` (versión 1.9 → 1.10)

# MotoIntercom 1.4: leer el código QR de la sala

En la pantalla **Sala por internet** hay un botón nuevo: **📷 Leer código QR**.

- Abre la cámara (la primera vez Android pide el permiso de cámara).
- Al leer el QR de una sala, **entra directo**, sin escribir el código.
- Sirve con el QR que genera la app, con el link `motointercom://sala/CODIGO` y con un QR que tenga solo el código.
- Si el QR no es de una sala, avisa y no hace nada.

Archivos que cambiaron respecto a la 1.3:
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`: el botón y la lectura del QR.
- `app/src/main/res/layout/activity_sala_internet.xml`: el botón nuevo en la pantalla.
- `app/src/main/AndroidManifest.xml`: permiso de cámara y pantalla de la cámara en vertical.
- `app/build.gradle`: librería `zxing-android-embedded` para la cámara, versión 1.3 → 1.4.

Los cambios de la 1.3 (Bluetooth con Waze y burbuja) están en `CAMBIOS_v1.3.md`.

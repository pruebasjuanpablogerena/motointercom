# MotoIntercom 1.14: link nuevo de Musicalia Stereo

- **Musicalia Stereo** (Planadas, FM 106.0) cambió de servidor y de puerto:
  - Antes: `https://stream.vocesparatumarca.net/8008/stream` (ya no responde)
  - Ahora: `https://streamhdglobal.com/8108/stream` (sacado de su página musicaliastereo.com)
- No se cambió nada más.

Archivos que cambiaron respecto a la 1.13:
- `app/src/main/assets/emisoras.json` (solo el link de Musicalia)
- `app/build.gradle` (versión 1.13 → 1.14)

## Arreglo de la compilación en GitHub
- La compilación fallaba con "Could not find kotlin-stdlib-jdk8 / jsr305 / javawriter": **Maven Central estaba limitando las descargas (error 429)**.
- Se agregó en `settings.gradle` la **copia de Maven Central que mantiene Google** como depósito principal; Maven Central y el portal de Gradle quedan de respaldo.
- Archivo que cambió: `settings.gradle` (está en la raíz, junto a `build.gradle`).

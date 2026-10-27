# MotoIntercom 1.8: flecha de atrás, radio desde la sala y versión correcta

- **Versión correcta:** la pantalla principal decía siempre "Versión 1.2" (estaba escrito a mano). Ahora muestra la versión instalada de verdad.
- **Flecha ← de atrás** dentro de la app en *Sala por internet* y en *Radio Online* (además del botón atrás del celular).
- **Sala por internet:** el título bajó un poco.
- **Radio desde la sala:** botón nuevo *"📻 Escuchar radio (solo para mí)"* dentro de la sala.
  - Es solo para escucharla uno mismo: no se comparte con nadie (no aparece el interruptor de compartir).
  - La radio **sigue sonando** al volver a la sala. El botón muestra qué emisora está sonando.
  - Cuando llega un audio de la sala, **la radio baja el volumen** y vuelve a subir al terminar.
  - La radio se detiene con "Detener" o al salir de la sala.
  - Desde la pantalla principal la radio funciona igual que antes (se detiene al salir de esa pantalla).

Archivos que cambiaron respecto a la 1.7:
- `app/src/main/java/com/motointercom/app/ReproductorRadio.kt` (nuevo)
- `app/src/main/java/com/motointercom/app/RadioActivity.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetService.kt`
- `app/src/main/java/com/motointercom/app/MainActivity.kt`
- `app/src/main/res/layout/activity_sala_internet.xml`
- `app/src/main/res/layout/activity_radio.xml`
- `app/build.gradle` (versión 1.7 → 1.8)
- `sala-web/index.html` (los arreglos del iPhone de la versión anterior, por si no se habían subido)

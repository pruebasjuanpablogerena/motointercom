# MotoIntercom 1.13: avisos que explican, botón de radio claro e iPhone entra directo

## Pantalla principal: "Silenciar micrófono" y "Altavoz"
- Antes, sin conexión, esos botones no hacían nada al tocarlos.
- Ahora **siempre responden**: si no hay conexión por Bluetooth, muestran un **aviso que se alcanza a leer y desaparece solo**, explicando para qué sirven y que primero hay que conectarse.
- Mientras no sirven se ven **más tenues**, para que se note.
- En modo walkie-talkie, "Silenciar micrófono" explica que el micrófono se maneja con "Hablar".

## Radio Online: el botón de abajo ya no confunde
- **Si suena una emisora:** "⏹ Detener radio" (rojo), como antes.
- **Si no suena nada y tienes favoritas:** "▶ Escuchar: <tu primera favorita>" (azul). Un toque y empieza a sonar.
- **Si no suena nada y no tienes favoritas:** se ve tenue y explica que toques una emisora (y que con la ☆ la guardas).
- El texto de arriba dice "Ninguna emisora sonando. Toca una emisora de la lista para escucharla." en vez de "Selecciona una emisora".

## iPhone (versión web): entrar directo con el link o el QR
- Antes, al abrir el link o leer el QR, el iPhone quedaba en la pantalla con el código escrito y había que tocar "Unirse".
- Ahora **entra directo a la sala**. Abajo sale "🔊 Toca aquí para activar el sonido de la sala": Safari solo deja activar el sonido después de un toque.

Archivos que cambiaron respecto a la 1.12:
- `app/src/main/java/com/motointercom/app/MainActivity.kt`
- `app/src/main/java/com/motointercom/app/RadioActivity.kt`
- `sala-web/index.html`
- `app/build.gradle` (versión 1.12 → 1.13)

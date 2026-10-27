# MotoIntercom — Todo desde GitHub (sin Android Studio)

Este .zip ya trae todo listo: la app, la landing page y la automatización
que compila el APK solo. Tu trabajo es solo subirlo y, cada vez que quieras
un cambio, volver a subir el archivo que cambiaste.

## PASO 1: Crear el repositorio

1. Entra a https://github.com y crea un repositorio nuevo (botón "New").
   - Ponle un nombre, por ejemplo `motointercom`.
   - Debe ser **público** (para que GitHub Pages y las descargas funcionen gratis).
   - No marques "Add a README" (ya tienes uno en el zip).

## PASO 2: Subir todos los archivos

**Recomendado para principiantes: GitHub Desktop**
1. Descarga GitHub Desktop: https://desktop.github.com
2. Instálalo e inicia sesión con tu cuenta de GitHub.
3. Extrae este .zip en una carpeta de tu computadora.
4. En GitHub Desktop: **File → Add Local Repository** → selecciona esa carpeta.
5. Te va a preguntar si quieres inicializar un repositorio Git ahí — di que sí.
6. Abajo a la izquierda, en "Current repository", verifica que apunte a tu carpeta. Escribe un mensaje corto como "Primera versión" y presiona **"Commit to main"**.
7. Arriba, presiona **"Publish repository"**. Elige el mismo nombre que creaste en GitHub (`motointercom`) y publícalo.

**Cada vez que quieras actualizar algo más adelante:** edita el archivo que quieras (por ejemplo con el Bloc de notas), guarda, abre GitHub Desktop, vas a ver el cambio detectado automáticamente, escribe un mensaje y presiona "Commit" y luego "Push origin". Eso ya dispara todo lo demás solo.

## PASO 3: Editar el link de descarga

Antes o después de subir, abre `index.html` con cualquier editor de texto y busca esta línea:

```
https://github.com/TU-USUARIO/TU-REPOSITORIO/releases/latest/download/app-debug.apk
```

Reemplaza `TU-USUARIO` por tu usuario de GitHub y `TU-REPOSITORIO` por el nombre que le pusiste (ej. `motointercom`). Guarda y vuelve a subir el archivo (commit + push).

Este link es especial: **siempre apunta a la versión más reciente**, aunque subas actualizaciones después. No hay que tocarlo de nuevo.

## PASO 4: Ver el APK compilarse solo

1. En tu repositorio de GitHub, entra a la pestaña **"Actions"**.
2. Vas a ver un flujo llamado "Compilar APK" corriendo (círculo amarillo girando). Tarda unos 3-5 minutos la primera vez.
3. Cuando termine (círculo verde ✓), ve a la pestaña **"Releases"** (a la derecha de "Code", puede estar bajo "About"). Ahí va a estar el archivo `app-debug.apk` publicado, listo para descargar.

Si el círculo queda en rojo ✗, entra y revisa el mensaje de error — usualmente es algo pequeño como un archivo mal ubicado. Puedes pegarme el mensaje de error y te ayudo a corregirlo.

## PASO 5: Activar la landing page (GitHub Pages)

1. En tu repositorio: **Settings → Pages** (menú de la izquierda).
2. En "Source" elige la rama **main** y carpeta **/ (root)**. Guarda.
3. En un par de minutos tu página va a estar en:
   `https://tu-usuario.github.io/motointercom/`

## PASO 6: Instalar el APK en el celular

1. Desde el celular, abre esa página y toca "Descargar APK".
2. Android va a pedir permiso para "instalar apps de fuentes desconocidas" — actívalo solo para el navegador que estás usando.
3. Instala y listo.

---

## Cómo actualizar la app más adelante

Cualquier cambio que hagas a los archivos `.kt` (el código) y lo subas con GitHub Desktop (commit + push) dispara automáticamente:
código nuevo → Actions compila → Release "latest" se actualiza → el link de la landing page ya sirve la versión nueva, sin que tengas que tocar nada más.

## Función nueva: compartir música/GPS de un solo celular

Además de hablar entre los dos (eso ya funciona siempre), ahora hay un botón
**"Compartir mi música/GPS"** que solo debes activar en el celular que quieres
que transmita (por ejemplo, el conductor). El otro celular nunca lo toca —
así se queda solo escuchando y hablando, sin transmitir su propio audio.

Cosas a tener en cuenta con esta función:
- Solo funciona en **Android 10 o superior**, en el celular que la activa.
- La primera vez que la actives, Android va a mostrar un cuadro de permiso
  ("permitir que MotoIntercom capture lo que se reproduce"). Hay que aceptarlo.
- Mientras está activa, va a aparecer una notificación fija en ese celular
  ("MotoIntercom compartiendo música") — es obligatorio por Android, no se
  puede quitar mientras la función esté encendida.
- Apps con contenido protegido (Netflix, a veces Spotify Premium) pueden
  bloquear la captura. Waze, Google Maps y archivos de música locales sí
  funcionan.
- La voz siempre se escucha más fuerte que la música transmitida, para que
  se entienda bien la conversación.

## Función nueva: Sala por Internet (walkie-talkie sin Bluetooth)

Esta función deja hablar con otras personas **sin estar cerca ni emparejados
por Bluetooth** — cualquiera con el código de la sala se conecta desde
cualquier parte, usando WiFi o datos móviles. Funciona por turnos, como un
radioteléfono real: mantienes presionado el botón para hablar, sueltas y el
mensaje le llega al otro.

### Paso obligatorio antes de compilar: crear tu proyecto de Firebase

Esta función usa Firebase (de Google), gratis, pero necesita que **tú mismo**
crees el proyecto — es un paso que no puedo hacer por ti porque requiere tu
propia cuenta de Google.

1. Entra a https://console.firebase.google.com y crea un proyecto nuevo
   (el nombre no importa, puede ser "MotoIntercom").
2. Dentro del proyecto, toca el ícono de Android para "agregar una app".
   - **Nombre del paquete**: escribe exactamente `com.motointercom.app`
   - No hace falta certificado SHA-1 para esta función, puedes dejarlo vacío.
3. Descarga el archivo `google-services.json` que te ofrece al final.
4. Copia ese archivo dentro de la carpeta `app/` de tu proyecto (al mismo
   nivel que `build.gradle`, no dentro de `src`). Súbelo a tu repositorio de
   GitHub igual que los demás archivos (commit + push).
5. En el menú izquierdo de Firebase, entra a **Realtime Database** → "Crear
   base de datos" → elige cualquier región → empieza en **modo de prueba**.
6. Entra también a **Storage** → "Comenzar" → modo de prueba igual.

**Importante sobre el "modo de prueba":** deja la base de datos abierta para
que cualquiera con el link pueda leer y escribir, durante 30 días (luego hay
que renovarlo desde la consola de Firebase). Para un proyecto personal está
bien, pero significa que técnicamente cualquiera que conozca tu proyecto
podría ver los códigos de sala. No hay datos sensibles guardados (solo
mensajes de voz cortos), así que el riesgo real es bajo para este uso.

Si no subes el `google-services.json`, el proyecto **no va a compilar** —
Actions va a fallar con un error mencionando ese archivo. Si ves ese error,
es justamente porque falta este paso.



- El APK que se genera es una compilación "debug": se instala y funciona perfecto, pero no está optimizada para tamaño/velocidad al máximo. Es la forma más simple de tener descargas automáticas sin manejar contraseñas de firma en GitHub.
- **Alcance real:** Bluetooth clásico entre dos celulares da unos 10-30 metros en la práctica.
- **El ducking (bajar volumen de Waze/música) es automático** una vez conectados.
- **No hay cancelación de eco** todavía: usa audífonos o parlantes del casco para evitar realimentación.
- Prueba primero en corto antes de confiar en esto en carretera.

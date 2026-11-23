# MotoIntercom 1.3: cambios respecto a la 1.2

## 1. Botón flotante (burbuja) en la sala por internet

**La app ya lo tiene.** Está en `SalaInternetService.kt`, en la parte "Burbuja flotante (estilo Didi/Uber)".

- Aparece **solo cuando se está en una sala por internet** y se quita al tocar "Salir de la sala". ✔
- Se mantiene presionada para hablar y al soltar envía el audio (con 1 segundo de colchón). Si se arrastra, se mueve de lugar sin enviar nada. ✔
- Para que aparezca, Android exige activar a mano el permiso **"Mostrar sobre otras apps"**. La app lo pide **una sola vez**. Si la persona toca "Ahora no", la burbuja no vuelve a aparecer hasta que lo active en *Ajustes > Apps > MotoIntercom > Mostrar sobre otras apps*.

**Lo que se corrigió:**

| Fallo | Qué pasaba | Arreglo |
|---|---|---|
| La burbuja salía encima de la propia pantalla de la sala | Había dos botones de hablar al mismo tiempo | Ahora, igual que en Didi, se esconde mientras la pantalla de la sala está abierta y aparece al pasar a otra app (Waze, WhatsApp...) |
| Se perdía el audio al abrir la app justo después de soltar la burbuja | El audio que estaba en el segundo de colchón se borraba | Ahora se envía en vez de perderse |
| **La app se podía cerrar sola a los ~10 segundos** | La pantalla arranca el servicio con `startForegroundService()`, y Android exige que el servicio llame a `startForeground()` rápido. Si la entrada a la sala no ocurría (pantalla aún no conectada, o volver a entrar a la misma sala), Android cerraba la app | El servicio ahora cumple ese requisito siempre (`onStartCommand`). Si se pide entrar antes de tiempo, la entrada se guarda y se hace apenas el servicio esté listo |
| Audios nuevos que no sonaban | La hora del celular se comparaba con la hora de Firebase. Si el reloj del celular estaba adelantado, los audios nuevos se tomaban por viejos y se ignoraban | Se usa la hora de Firebase (`.info/serverTimeOffset`) |
| Entrar a la sala era lento en datos móviles | Al entrar se descargaban **todos los audios de las últimas 24 horas** solo para ignorarlos | Ahora solo se piden los audios nuevos. **Importante:** en las reglas de Firebase hay que agregar `".indexOn": ["timestamp"]` en `salas/$codigo/mensajes` para que el filtro se haga en el servidor |
| Dos audios podían cruzarse | La fila de reproducción se manejaba desde varios hilos a la vez | Ahora todo pasa por el hilo principal |

> La sala por internet funciona como WhatsApp: graba, envía y luego se reproduce. Por eso siempre tendrá **2–3 segundos de demora** (1 s de colchón + subir + bajar). No es una llamada en vivo.

## 2. Bluetooth + Waze: se desconecta, se traba, ~4 s de retraso y no llega completo

Encontré **cuatro causas** en el código.

### Causa 1: se desconecta al abrir Waze (la más grave)
`IntercomService` (el servicio que mantiene la llamada Bluetooth) solo estaba **conectado** a la pantalla principal (`bindService`), nunca **arrancado** por su cuenta. Waze gasta bastante memoria, así que Android suele cerrar la pantalla de MotoIntercom, y el servicio moría con ella: se cortaba la llamada.
La sala por internet ya lo hacía bien. El Bluetooth no.
**Arreglo:** el servicio se arranca por su cuenta y sigue vivo hasta que se toque "Desconectar".

### Causa 2: los ~4 segundos de retraso y el audio que no llega completo
Mientras Waze o la música usan el Bluetooth, el enlace con el otro celular se pone lento. El código limitaba su propia fila de envío a 200 ms, pero **Android, por dentro, sigue guardando varios segundos de audio** en el Bluetooth del celular que envía. Ahí se acumulan los ~4 s.
Además, el que recibe, al ver tanto audio atrasado de golpe, **descarta** casi todo para volver al tiempo real, y por eso "no llega completo".
**Arreglo (control de flujo):** el celular que recibe le confirma al otro cada 100 ms cuánto audio le ha llegado, por el canal de avisos que ya existía. Si hay más de ~400 ms "en camino", el que envía descarta ese pedacito en vez de acumular. Resultado: como mucho se pierde algún pedacito de 20 ms cuando el enlace se ahoga, pero **ya no se acumulan segundos de retraso**.
Funciona aunque uno de los dos celulares tenga la versión vieja: en ese caso el control simplemente no se activa.

### Causa 3: Waze pelea con la llamada por el audio
La llamada pedía el audio con `AUDIOFOCUS_GAIN` (como si fuera una app de música), así que Spotify y Waze perdían el audio por completo y luego intentaban recuperarlo.
**Arreglo:** ahora se pide con `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, que significa "baja el volumen de las demás mientras hablamos", que es lo que el código decía querer hacer.

### Causa 4: el audio se sale de los audífonos o se queda mudo después de que Waze habla
Algunas apps de GPS cambian el "modo de audio" del celular para sus indicaciones y al terminar lo dejan en modo normal. La app nunca lo volvía a poner.
**Arreglo:** un vigilante revisa cada 2 segundos y, si alguien cambió el modo, lo restaura y vuelve a poner la salida que el usuario eligió (altavoz o audífonos).

### Arreglo extra: hilos que se cruzaban al reconectar
Al desconectar y volver a conectar rápido, los hilos de la conexión anterior podían seguir vivos y leer el micrófono al mismo tiempo que los nuevos (audio cortado), o incluso cerrar la conexión nueva. Ahora cada conexión tiene su número y los hilos viejos se salen solos.

## 3. Otras cosas que conviene saber (no las cambié)

- **Sensor de proximidad:** durante la llamada Bluetooth, la app apaga la pantalla si algo tapa el sensor de arriba del celular. En un soporte de moto con Waze abierto, eso puede **apagar la pantalla del GPS**. Recomiendo dejarlo solo cuando el altavoz está apagado, o quitarlo.
- **Bluetooth solo uno a uno:** son dos celulares (conductor y pasajero). Para grupos está la sala por internet.
- **iPhone:** no puede conectarse por Bluetooth a esta app. Solo entra a la sala por internet desde la página web (`sala-web/index.html`), y en iPhone **la página no recibe audios con la pantalla bloqueada ni en segundo plano**, porque Safari no lo permite.
- **Seguridad de Firebase:** la base de datos se usa sin iniciar sesión. Conviene revisar que las reglas no dejen a cualquiera borrar o leer todas las salas.
- **Reconexión automática:** si el Bluetooth se cae, hay que volver a conectar a mano. Se podría agregar reconexión automática más adelante.

## 4. Qué archivos cambiaron

- `app/src/main/java/com/motointercom/app/BluetoothAudioService.kt`
- `app/src/main/java/com/motointercom/app/IntercomService.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetService.kt`
- `app/src/main/java/com/motointercom/app/SalaInternetActivity.kt`
- `app/build.gradle`: versión 1.2 → 1.3 (versionCode 3 → 4), para que se instale encima de la anterior.

Hay que probarlo en dos celulares reales: conectar por Bluetooth, abrir Waze con una ruta, hablar varios minutos y revisar que no se corte ni se atrase.

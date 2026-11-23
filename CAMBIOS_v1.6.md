# MotoIntercom 1.6: emisoras con token (Esperanza Colombia)

- Nuevas emisoras **Esperanza Colombia** (buscando "esperanza" salen todas): Amazonas (AMAZONAS), Bogotá (BOGOTÁ), Eje Cafetero (CALDAS, RISARALDA y QUINDÍO), Tolima (TOLIMA), Valle del Cauca y Palmira (VALLE DEL CAUCA).
- Algunas emisoras no tienen un link fijo: al tocarlas, la app **primero le pregunta a su servidor** el link del momento (con su token) y **después** reproduce con ese link.
- Si el token se vence y la emisora se corta, la app pide un link nuevo sola (hasta 3 veces seguidas).
- Al compartir la emisora por Bluetooth, el otro celular pide **su propio** link, porque el token de uno no le sirve al otro.

## Cómo agregar otra emisora con token
En `app/src/main/assets/emisoras.json`, la emisora lleva `"t": "token"` y en `"s"` va la dirección del servidor que entrega el link, por ejemplo:

```json
{"d": "BOGOTÁ", "n": "Esperanza Colombia", "i": "Bogotá - Radio en línea", "s": "https://esperanzacolombia.com/api/public/radio-stream?station=bogota", "w": "https://esperanzacolombia.com/", "t": "token"}
```

Archivos que cambiaron respecto a la 1.5:
- `app/src/main/java/com/motointercom/app/EnlaceConToken.kt` (nuevo)
- `app/src/main/java/com/motointercom/app/RadioActivity.kt`
- `app/src/main/assets/emisoras.json`
- `app/build.gradle` (versión 1.5 → 1.6)

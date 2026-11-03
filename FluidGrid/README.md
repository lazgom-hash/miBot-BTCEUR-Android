# FluidGrid Android 1.0 — BTC spot, cartera virtual

Aplicación Android **nativa (Java)** para simular compras y ventas BTC basadas en las zonas del indicador **FluidTrades SMC Lite** en velas de 1 hora. No envía órdenes al exchange ni utiliza API keys. El código de la aplicación está en `FluidGrid/`.

## Reglas
- Datos públicos de OKX, BTC-USDC si existe; en su defecto BTC-USD **etiquetado como aproximación**.
- Zonas: pivotes 10/10, ATR Wilder de 50 periodos, ancho 0,25 ATR, rechazo por solapamiento 2 ATR, invalidación por cierre horario al cruzar la zona.
- Compra simulada: 10 % de la base del bot por zona verde distinta. Sin efectivo suficiente, no compra ni inventa una aportación.
- Venta simulada: 10 % de los BTC restantes, una vez por zona roja distinta, y únicamente si tras comisiones alcanza beneficio neto >=1 % respecto al coste proporcional de esos BTC.
- Comisiones supuestas: 0,10 % por lado. Se descuentan tanto al comprar como al vender.
- Aportaciones manuales autorizadas: nunca automáticas. El asesor calcula reserva, liquidez y posible efecto en TP sin hacer previsiones.
- Estado persistido localmente en SharedPreferences: efectivo, BTC, coste pendiente, operaciones, zonas utilizadas, último minuto procesado y aportaciones.

## Compilación
Android Studio: abre `FluidGrid/`, espera la sincronización Gradle, compila `app` (JDK 17, SDK Android 35).
GitHub Actions: workflow **FluidGrid Android APK** crea un artifact `FluidGrid-debug-APK` con `app-debug.apk` cuando se actualiza la rama del proyecto.

## Funcionamiento y límites
La app usa un servicio en primer plano con notificación persistente y consulta velas cerradas de 1 minuto. **No es una ejecución tick a tick**: los contactos y precios de ejecución se estiman con OHLC y pueden no ser posibles en una operativa real. La situación de una zona se evalúa solo después de haber sido confirmada, sin aplicar zonas retroactivamente.

Android puede detener o limitar el servicio (batería, conectividad o límites de duración de servicios `dataSync`). La app intenta recuperar velas pendientes hasta un máximo de 7 días al reiniciarse y **se detiene si hay un hueco o no se pueden verificar datos**. No se garantiza vigilancia 24/7. Para eso se requiere un backend siempre activo.

No se debe utilizar para decisiones de inversión sin verificar los datos y resultados; no incluye deslizamiento, spread variable, latencia, fiscalidad ni la disponibilidad efectiva de liquidez en el libro de órdenes.

El motor es una adaptación independiente del indicador SMC Lite publicado por **Pmgjiv** bajo licencia MPL 2.0. Se conserva el reconocimiento del autor. Las zonas se aproximan a las originales; faltan pruebas comparativas de paridad vela a vela con TradingView.

Repositorio: no añadir credenciales ni claves reales de OKX. La app no necesita ninguna.

## 1.1 — Avisos de operaciones
- Canal Android de compras simuladas y otro de ventas, ambos de prioridad alta; permisos Android 13+.
- Detalle de precio, USDC, BTC, saldos después de la operación y beneficio neto realizado al vender.
- Cada evento se identifica mediante secuencia persistente para no duplicar avisos al reiniciar; las operaciones recuperadas se señalan como históricas.
- Se pueden silenciar las notificaciones por tipo desde Ajustes de Android. La app no envía órdenes reales.
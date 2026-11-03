# FluidGrid · Actualizaciones sin perder datos

La aplicación 1.7.0 adopta el identificador **es.fluidgrid.simulator**. No debe cambiarse en versiones posteriores.

Para instalar futuras versiones sobre la anterior sin perder los datos locales, Android exige que las APK tengan también **exactamente el mismo certificado de firma**. En GitHub Actions cada compilación debug utiliza normalmente una clave efímera; por eso se necesita configurar una clave privada estable.

## Configuración una única vez (administrador del repositorio)

1. Genera en tu ordenador un almacén de claves Android con el comando:

   ```bash
   keytool -genkeypair -v -keystore fluidgrid-upload.jks -alias fluidgrid -keyalg RSA -keysize 3072 -validity 20000
   ```

   Elige contraseñas robustas y conserva una copia de seguridad **privada** del archivo `.jks` y de las contraseñas. No publiques el archivo ni compartas las contraseñas por chat.

2. Abre GitHub > tu repositorio > **Settings** > **Secrets and variables** > **Actions** > **New repository secret**. Crea estos cuatro secretos:
   - `FLUIDGRID_KEYSTORE_B64`: contenido de `fluidgrid-upload.jks` codificado en Base64 en una sola línea.
   - `FLUIDGRID_STORE_PASS`: contraseña del almacén.
   - `FLUIDGRID_KEY_ALIAS`: alias `fluidgrid`.
   - `FLUIDGRID_KEY_PASS`: contraseña de la clave.
3. Vuelve a compilar la aplicación desde la rama `fluidgrid-android`, usando el flujo **FluidGrid Android APK**.
4. Instala esa APK firmada como **versión de referencia**. Las compilaciones futuras firmadas con los mismos secretos y con un `versionCode` superior podrán instalarse como actualizaciones normales.

**Importante:** la primera APK firmada con esta clave puede no ser compatible con una APK de pruebas anterior firmada con otra clave. En ese primer cambio puede ser necesario exportar los datos de la aplicación anterior y reinstalar. El sistema de actualizaciones de la app solo descarga desde GitHub y solicita instalación al usuario; nunca puede ignorar la validación de firmas de Android.

## BIN admitido por el testador

- Longitud exacta múltiplo de **20 bytes**.
- Cada registro, en orden cronológico, contiene: Unix timestamp en segundos (`uint32 little-endian`), seguido de 4 números `float32 little-endian`: `Open`, `High`, `Low`, `Close`.
- Periodicidad continua de **un minuto**, sin huecos.
- El simulador no cambia el capital de la cartera virtual en tiempo real y no opera en OKX. Solo calcula una estimación histórica de BTC/USD que puede diferir de BTC/USDC.

## Limitaciones

El testador hace una simulación OHLC: el toque de una zona se aproxima al precio de su borde y no incluye deslizamiento, liquidez real o impacto de mercado. Usa comisión estimada del 0,10 % por lado y no reproduce aportaciones manuales. La máxima caída patrimonial se mide con cierres de cada minuto.
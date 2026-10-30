# miBot · Comparador de ejecución BTC/EUR vs BTC/USD (OKX)

Herramienta independiente de miBot 3.5. Consulta libros de órdenes públicos OKX y compara **spreads y profundidad ejecutable para el mismo nominal en euros**, sin operaciones, credenciales ni claves API.

## Qué mide

- BTC-EUR frente a BTC-USD en el libro unificado de OKX Europa (liquidable en USDC). Si el primero no está disponible devuelve un error, no inventa sustitutos. Si BTC-USD no se ofrece, intenta BTC-USDC y **registra explícitamente el símbolo real**.
- Niveles: 50 bids y 50 asks por par cada captura, con validación del timestamp de OKX, ambos libros en un máximo de 30 segundos y antigüedad máxima de 60 segundos.
- Spread bid/ask en **puntos básicos (pb)** y coste de **compra taker + venta inmediata taker del mismo BTC**, con nominal equivalente 200, 300, 1.000, 5.000 y 15.000 EUR.
- Comisión supuesta: 0,10 % de compra y 0,10 % de venta. **No** es una comprobación de comisiones reales de la cuenta. Se ejecuta precio-volume walk con las 50 posiciones por lado del libro; si no hay profundidad suficiente, el dato se deja vacío y no se promedia.
- Cada ejecución de GitHub registra dos filas emparejadas en `comparador-okx/data/observaciones.csv`. Actualiza `comparador-okx/data/estado.json`, incluidos errores y antigüedad de la última muestra.

## Ver el panel

Abre el fichero **comparador-okx/index.html** desde el móvil o el ordenador. Descargable también en el ZIP de la entrega. Tiene un selector de tamaño de orden y de ventana temporal, gráfico de costes, spreads medianos, calidad y exportación del CSV.

## Automatización en GitHub

Workflow `.github/workflows/okx-comparador.yml`: cada cinco minutos programados (`cron`) y manual `workflow_dispatch`. **GitHub Actions puede demorar u omitir ejecuciones programadas** y las desactiva en algunos supuestos de inactividad. Los datos son públicos y se publican en el propio repositorio. Se comprueban fallos de API; no se inventan muestras.

El repositorio público `lazgom-hash/miBot-BTCEUR-Android` contiene el histórico de observaciones. No se modifica el bot ni se realizan órdenes. El workflow Android se configura para no compilar una APK cada vez que se añade un CSV.

## Interpretación

El coste ida/vuelta corresponde a un **test de mercado instantáneo taker-taker**, no a la comisión de un TP maker, no considera cola de órdenes y no permite asegurar ejecuciones del bot ni inferir ingresos mensuales. Para validar rentabilidad real hará falta otro backtest sobre **las velas de OKX de ambos mercados**, con reglas exactas de miBot y costes de sus órdenes.

## Reproducción / tests

```bash
python -m unittest discover -s tests -v
python comparador-okx/collector.py
```

El segundo comando requiere Internet y consulta OKX, sin credenciales.
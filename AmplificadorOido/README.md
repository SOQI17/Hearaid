# Amplificador Oído a Oído (Android)

App nativa que toma el sonido del micrófono del teléfono, lo amplifica y lo envía a tus audífonos (por ejemplo, los HS1 de conducción ósea por Bluetooth), con volumen independiente para cada oído.

## Funciones

- Volumen general de 0 a +30 dB.
- Oído izquierdo y derecho por separado (−20 a +12 dB), con botones rápidos para dar más volumen a un lado o centrar.
- Realce de voz a 2,5 kHz y filtro para quitar el retumbe de los graves.
- Reducción de ruido opcional, que usa la del propio teléfono.
- Elección de micrófono: del teléfono (abajo o atrás), con cable o USB-C.
- Un limitador de seguridad evita que el volumen de salida suba de golpe.
- Sigue funcionando con la pantalla apagada; se apaga desde la notificación.
- Guarda tus ajustes.
- Requiere Android 8.0 o superior.

## Opción A: compilar con GitHub (sin instalar nada)

1. Crea una cuenta gratis en github.com y un repositorio nuevo (puede ser privado).
2. Sube todo el contenido de esta carpeta con "Add file > Upload files". Incluye la carpeta oculta `.github`; si el navegador no la sube, créala a mano con "Add file > Create new file" y el nombre `.github/workflows/compilar-apk.yml`, y pega su contenido.
3. Ve a la pestaña **Actions**. La compilación arranca sola; si no, entra a "Compilar APK" y pulsa "Run workflow".
4. Cuando termine (unos 5 minutos, con la marca verde), abre la ejecución y descarga **amplificador-apk** en la sección *Artifacts*. Es un .zip con el archivo `app-debug.apk` adentro.

## Opción B: Android Studio

1. Instala Android Studio (developer.android.com/studio).
2. Elige **Open** y abre esta carpeta. Espera a que termine la sincronización.
3. Conecta el teléfono con la depuración USB activada y pulsa ▶ Run, o usa *Build > Build APK(s)*.

## Instalar el APK en el teléfono

1. Copia `app-debug.apk` al teléfono (por WhatsApp, Drive o cable) y ábrelo.
2. Android pedirá permitir "instalar apps desconocidas" para esa app de origen. Acepta.
3. Abre **Amplificador**, conecta tus audífonos y pulsa **Encender**. Acepta el permiso de micrófono.

## Consejos

- Empieza con el volumen bajo y súbelo poco a poco. Si escuchas un pitido (acople), baja el volumen o aleja el teléfono de los audífonos.
- La app usa el micrófono del teléfono, no el de los audífonos, porque el de Bluetooth tiene baja calidad.
- El retraso que muestra la app es solo el del teléfono. El Bluetooth agrega unos 100–250 ms, según los audífonos.
- Para el menor retraso posible, usa audífonos con cable o USB-C.
- Esto no reemplaza un audífono médico. Si notas pérdida auditiva, consulta con un audiólogo.

# Odin Input Mirror

Aplicacion Android nativa (Kotlin + Jetpack Compose) para clonar eventos de entrada desde un control externo Bluetooth hacia el dispositivo de entrada del joystick integrado en Android con root.

Stack: Kotlin 2.0.21, Jetpack Compose + Material 3 (BOM 2024.10.01), Gradle 8.11.1, AGP 8.7.3, JVM 17, root via topjohnwu libsu 5.2.2 (JitPack). minSdk 28, target/compileSdk 35.

> Requiere root. Escribe directamente en `/dev/input/eventX`, por lo que debes verificar bien origen y destino antes de activar el espejo.

## Estructura

```text
.
├── android/
│   ├── settings.gradle
│   ├── gradle/libs.versions.toml
│   ├── app/        # Compose UI + MainActivity/MainApplication + supervisor service
│   │   └── src/main/
│   │       ├── java/com/odininputmirror/
│   │       │   ├── MainActivity.kt
│   │       │   ├── MainApplication.kt
│   │       │   ├── InputMirrorSupervisorService.kt
│   │       │   └── ui/   # MirrorScreen, MirrorViewModel, theme
│   │       ├── assets/input_mirror/input_mirror
│   │       └── native/input_mirror.c
│   ├── data/       # InputMirrorGraph, repos, Shell (libsu)
│   └── domain/     # use cases + interfaces + models (Kotlin puro)
└── scripts/build-input-mirror.ps1
```

## Compilar e instalar

```powershell
.\android\gradlew.bat -p .\android :app:assembleDebug
adb install -r .\android\app\build\outputs\apk\debug\app-debug.apk
```

## Compilar el binario C con Android NDK

1. Instala Android Studio, Android SDK y NDK.
2. Define `ANDROID_NDK_HOME`, por ejemplo:

```powershell
$env:ANDROID_NDK_HOME="$env:LOCALAPPDATA\Android\Sdk\ndk\26.3.11579264"
```

3. Compila el binario:

```powershell
.\scripts\build-input-mirror.ps1
```

El script compila `android/app/src/main/native/input_mirror.c` para `arm64-v8a` y copia el resultado a:

```text
android/app/src/main/assets/input_mirror/input_mirror
```

En tiempo de ejecucion, el modulo Kotlin copia ese asset a `filesDir/bin/input_mirror`, le aplica `chmod 755` y lo ejecuta vía la sesion root de libsu:

```sh
nice -n -20 /data/data/<paquete>/files/bin/input_mirror <source> <target> &
```

## Notas operativas

- Usa `getConnectedDevices()` para revisar nombres y rutas reales. En Android, `/dev/input/eventX` puede cambiar tras reinicios o reconexiones.
- El origen debe abrirse como lectura y el destino como escritura. Si el driver destino no acepta escrituras directas, el binario fallara con `EACCES`, `EINVAL` u otro error del kernel.
- Algunos kernels Android restringen escritura sobre event nodes aun con root. En ese caso puede ser necesario usar `uinput` como destino virtual, pero este proyecto implementa el espejo directo pedido.

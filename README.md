# Odin Input Mirror

Aplicacion React Native para clonar eventos de entrada desde un control externo Bluetooth hacia el dispositivo de entrada del joystick integrado en Android con root.

> Requiere root. Escribe directamente en `/dev/input/eventX`, por lo que debes verificar bien origen y destino antes de activar el espejo.

## Estructura

```text
.
├── App.tsx
├── package.json
├── tsconfig.json
├── android/
│   └── app/
│       ├── build.gradle
│       ├── src/main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/input_mirror/README.txt
│       │   ├── java/com/odininputmirror/
│       │   │   ├── MainApplication.kt
│       │   │   ├── InputMirrorModule.kt
│       │   │   ├── InputMirrorPackage.kt
│       │   │   └── InputMirrorTileService.kt
│       │   └── native/input_mirror.c
│       └── src/main/res/values/strings.xml
└── scripts/build-input-mirror.ps1
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

En tiempo de ejecucion, el modulo Kotlin copia ese asset a `filesDir/bin/input_mirror`, le aplica `chmod 755` y lo ejecuta con:

```sh
su -c 'nice -n -20 /data/data/<paquete>/files/bin/input_mirror <source> <target> &'
```

## Instalar dependencias RN

```powershell
npm install
npm run android
```

## Notas operativas

- Usa `getConnectedDevices()` para revisar nombres y rutas reales. En Android, `/dev/input/eventX` puede cambiar tras reinicios o reconexiones.
- El origen debe abrirse como lectura y el destino como escritura. Si el driver destino no acepta escrituras directas, el binario fallara con `EACCES`, `EINVAL` u otro error del kernel.
- Algunos kernels Android restringen escritura sobre event nodes aun con root. En ese caso puede ser necesario usar `uinput` como destino virtual, pero este proyecto implementa el espejo directo pedido.

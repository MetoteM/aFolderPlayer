@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
call gradlew.bat :app:assembleRelease :translator:assembleRelease --console=plain
if errorlevel 1 (
 echo Build failed. See the output above.
 pause
 exit /b 1
)
echo Player APK: app\build\outputs\apk\release\app-release.apk
echo Optional engine APK: translator\build\outputs\apk\release\translator-release.apk
pause

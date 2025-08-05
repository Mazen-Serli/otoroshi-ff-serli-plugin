@echo off
setlocal enabledelayedexpansion

echo ========================================
echo    FF-SERLI Plugin Build and Test
echo ========================================
echo.

:: Check Java
echo [1/6] Verification de Java...
java -version >nul 2>&1
if errorlevel 1 (
    echo ERREUR: Java n'est pas installe ou non disponible dans PATH
    pause
    exit /b 1
)
echo - Java detecte avec succes

:: Check SBT
echo.
echo [2/6] Verification de SBT...
echo Tentative de detection de SBT...
where sbt >nul 2>&1
if errorlevel 1 (
    echo ERREUR: SBT n'est pas installe ou non disponible dans PATH
    pause
    exit /b 1
)
echo - SBT detecte avec succes

:: Check and download Otoroshi JAR
echo.
echo [3/6] Verification d'Otoroshi...
set "otoroshiJarPath=..\app\otoroshi.jar"
if not exist "%otoroshiJarPath%" (
    echo Otoroshi JAR non trouve dans le dossier app
    echo Tentative de telechargement depuis GitHub...

    :: Check if curl is available
    where curl >nul 2>&1
    if errorlevel 1 (
        echo ERREUR: curl n'est pas disponible pour le telechargement
        echo.
        echo SOLUTION MANUELLE REQUISE:
        echo 1. Allez sur: https://github.com/MAIF/otoroshi/releases
        echo 2. Telechargez la derniere version d'otoroshi.jar
        echo 3. Placez le fichier dans: otoroshi-test\app\otoroshi.jar
        echo.
        pause
        exit /b 1
    )

    :: Create app directory if it doesn't exist
    if not exist "..\app" mkdir ..\app

    :: Download latest Otoroshi JAR
    echo Telechargement de la derniere version d'Otoroshi...
    curl -L -o "%otoroshiJarPath%" "https://github.com/MAIF/otoroshi/releases/latest/download/otoroshi.jar"

    if errorlevel 1 (
        echo ERREUR: Echec du telechargement automatique
        echo.
        echo SOLUTION MANUELLE REQUISE:
        echo 1. Allez sur: https://github.com/MAIF/otoroshi/releases
        echo 2. Telechargez la derniere version d'otoroshi.jar
        echo 3. Placez le fichier dans: otoroshi-test\app\otoroshi.jar
        echo.
        pause
        exit /b 1
    )

    echo - Otoroshi JAR telecharge avec succes
) else (
    echo - Otoroshi JAR trouve dans le dossier app
)

:: Build project
echo.
echo [4/6] Compilation du plugin...
cd ..\..
call sbt clean package
if errorlevel 1 (
    echo ERREUR: Echec de la compilation
    pause
    exit /b 1
)
echo - Plugin compile avec succes

:: Copy plugin JAR
echo.
echo [5/6] Copie du plugin vers otoroshi-test...
if not exist "otoroshi-test\plugins" mkdir otoroshi-test\plugins
for /r target %%i in (*otoroshi-ff-serli-plugin*.jar) do (
    copy "%%i" otoroshi-test\plugins\
    echo - Plugin copie: %%~nxi
)

:: Start Otoroshi
echo.
echo [6/6] Demarrage d'Otoroshi avec le plugin...
cd otoroshi-test

set "classPath=plugins\*;app\otoroshi.jar"
set "javaArgs=-cp "%classPath%" -Dotoroshi.http.port=9000 -Dotoroshi.adminLogin=admin -Dotoroshi.adminPassword=password -Dotoroshi.storage=file play.core.server.ProdServerStart"

echo.
echo Configuration Otoroshi:
echo - Port: 9000
echo - Admin: admin/password
echo - Interface: http://localhost:9000
echo.
echo Demarrage en cours...

java %javaArgs%
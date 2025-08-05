#!/bin/bash

echo "========================================"
echo "    FF-SERLI Plugin Build and Test"
echo "========================================"
echo

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Check Java
echo -e "${BLUE}[1/6] Verification de Java...${NC}"
if ! java -version &> /dev/null; then
    echo -e "${RED}ERREUR: Java n'est pas installe ou non disponible dans PATH${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Java detecte${NC}"

# Check SBT
echo
echo -e "${BLUE}[2/6] Verification de SBT...${NC}"
echo "Tentative de detection de SBT..."
if ! command -v sbt &> /dev/null; then
    echo -e "${RED}ERREUR: SBT n'est pas installe ou non disponible dans PATH${NC}"
    exit 1
fi
echo -e "${GREEN}✓ SBT detecte${NC}"

# Check and download Otoroshi JAR
echo
echo -e "${BLUE}[3/6] Verification d'Otoroshi...${NC}"
otoroshi_jar_path="../app/otoroshi.jar"
if [ ! -f "$otoroshi_jar_path" ]; then
    echo -e "${YELLOW}Otoroshi JAR non trouve dans le dossier app${NC}"
    echo "Tentative de telechargement depuis GitHub..."

    # Check if curl is available
    if ! command -v curl &> /dev/null; then
        echo -e "${RED}ERREUR: curl n'est pas disponible pour le telechargement${NC}"
        echo
        echo -e "${YELLOW}SOLUTION MANUELLE REQUISE:${NC}"
        echo "1. Allez sur: https://github.com/MAIF/otoroshi/releases"
        echo "2. Telechargez la derniere version d'otoroshi.jar"
        echo "3. Placez le fichier dans: otoroshi-test/app/otoroshi.jar"
        echo
        exit 1
    fi

    # Create app directory if it doesn't exist
    mkdir -p "../app"

    # Download latest Otoroshi JAR
    echo "Telechargement de la derniere version d'Otoroshi..."
    if curl -L -o "$otoroshi_jar_path" "https://github.com/MAIF/otoroshi/releases/latest/download/otoroshi.jar"; then
        echo -e "${GREEN}✓ Otoroshi JAR telecharge avec succes${NC}"
    else
        echo -e "${RED}ERREUR: Echec du telechargement automatique${NC}"
        echo
        echo -e "${YELLOW}SOLUTION MANUELLE REQUISE:${NC}"
        echo "1. Allez sur: https://github.com/MAIF/otoroshi/releases"
        echo "2. Telechargez la derniere version d'otoroshi.jar"
        echo "3. Placez le fichier dans: otoroshi-test/app/otoroshi.jar"
        echo
        exit 1
    fi
else
    echo -e "${GREEN}✓ Otoroshi JAR trouve dans le dossier app${NC}"
fi

# Build project
echo
echo -e "${BLUE}[4/6] Compilation du plugin...${NC}"
cd ../..
sbt clean package
if [ $? -ne 0 ]; then
    echo -e "${RED}ERREUR: Echec de la compilation${NC}"
    exit 1
fi
echo -e "${GREEN}✓ Plugin compile avec succes${NC}"

# Copy plugin JAR
echo
echo -e "${BLUE}[5/6] Copie du plugin vers otoroshi-test...${NC}"
mkdir -p otoroshi-test/plugins
find target -name "*otoroshi-ff-serli-plugin*.jar" -exec cp {} otoroshi-test/plugins/ \;
if [ $? -eq 0 ]; then
    plugin_name=$(find otoroshi-test/plugins -name "*otoroshi-ff-serli-plugin*.jar" | head -1 | xargs basename)
    echo -e "${GREEN}✓ Plugin copie: $plugin_name${NC}"
else
    echo -e "${RED}ERREUR: Impossible de copier le plugin${NC}"
    exit 1
fi

# Start Otoroshi
echo
echo -e "${BLUE}[6/6] Demarrage d'Otoroshi avec le plugin...${NC}"
cd otoroshi-test

classPath="plugins/*:app/otoroshi.jar"
javaArgs=(
    "-cp" "$classPath"
    "-Dotoroshi.http.port=9000"
    "-Dotoroshi.adminLogin=admin"
    "-Dotoroshi.adminPassword=password"
    "-Dotoroshi.storage=file"
    "play.core.server.ProdServerStart"
)

echo
echo
echo "Demarrage en cours..."

java "${javaArgs[@]}"
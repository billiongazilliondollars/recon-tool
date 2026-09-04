#!/usr/bin/env bash
# Compiles Block3D and starts the localhost launcher.
#   ./run.sh          -> http://localhost:8080
#   ./run.sh 9000     -> http://localhost:9000
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -nowarn -d out Block3D.java Block3DServer.java
exec java -cp out Block3DServer "$@"

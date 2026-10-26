#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
javac -encoding UTF-8 -d "$T" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/InstallerProtocol.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/InstallerAnswers.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/GptTable.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/MakeRoomProtocol.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/MagiskVersion.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/RemoteJobState.java" \
  "$ROOT/app/src/main/java/org/linote/mu300controller/TargetStageCleanup.java" \
  "$ROOT/tests/SyntheticSelfTest.java" \
  "$ROOT/tests/ProtocolSelfTest.java" \
  "$ROOT/tests/TargetStageCleanupSelfTest.java"
java -cp "$T" org.linote.mu300controller.SyntheticSelfTest
java -cp "$T" org.linote.mu300controller.ProtocolSelfTest
java -cp "$T" org.linote.mu300controller.TargetStageCleanupSelfTest
bash "$ROOT/tests/ui-check.sh"

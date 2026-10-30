#!/bin/sh
# Synthetic audio only. macOS voices are used locally; no recordings leave the computer.
set -eu
cd "$(dirname "$0")/.."
mkdir -p app/src/androidTest/assets/voice-fixtures
say -v Samantha -o /private/tmp/sofia-en.aiff 'Sofia. Check receipt number one two three four five.'
say -v Milena -o /private/tmp/sofia-ru.aiff 'София. Проверь квитанцию номер один два три четыре пять.'
for lang in en ru; do
  afconvert -f WAVE -d LEI16@16000 -c 1 "/private/tmp/sofia-$lang.aiff" "app/src/androidTest/assets/voice-fixtures/$lang.wav"
done

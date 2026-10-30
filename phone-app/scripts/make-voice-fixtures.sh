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
say -v Samantha -o /private/tmp/sofia-en-negative.aiff 'I have been working in the store all afternoon. The rain is falling outside. This is a receipt for the customer.'
say -v Milena -o /private/tmp/sofia-ru-negative.aiff 'Сегодня хорошая погода. Мы работаем в магазине. Пожалуйста посмотри эту квитанцию. Всё уже готово.'
for lang in en ru; do
  afconvert -f WAVE -d LEI16@16000 -c 1 "/private/tmp/sofia-$lang-negative.aiff" "app/src/androidTest/assets/voice-fixtures/$lang-negative.wav"
done
python3 - <<'PY'
from pathlib import Path
import wave,array,random,math
root=Path('app/src/androidTest/assets/voice-fixtures')
for lang in ('en','ru'):
    with wave.open(str(root/f'{lang}.wav'),'rb') as w: src=array.array('h',w.readframes(w.getnframes()))
    rms=math.sqrt(sum(v*v for v in src)/len(src));rng=random.Random(17)
    noisy=array.array('h',(max(-32768,min(32767,round(v+rng.gauss(0,rms/(10**(15/20)))))) for v in src))
    idle=array.array('h',[0]*(16000*30));idle.extend(src)
    for label,data in [('noisy',noisy),('idle',idle)]:
        with wave.open(str(root/f'{lang}-{label}.wav'),'wb') as out:
            out.setnchannels(1);out.setsampwidth(2);out.setframerate(16000);out.writeframes(data.tobytes())
PY

#!/bin/sh

set -e

rm -rf ./kyo-core-opt*
mkdir -p ./kyo-core-opt1/src/
mkdir -p ./kyo-core-opt2/src/
mkdir -p ./kyo-core-opt3/src/

cp -r ./kyo-core/src/ ./kyo-core-opt1/src/
cp -r ./kyo-core/src/ ./kyo-core-opt2/src/
cp -r ./kyo-core/src/ ./kyo-core-opt3/src/

find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(1\)\\\*\\//inline/g {} +
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(2\)\\\*\\//inline/g {} +
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +

find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(2\)\\\*\\//inline/g {} +
find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +

find ./kyo-core-opt1/src -type f -name '*.scala' -exec sed -i ''  s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +

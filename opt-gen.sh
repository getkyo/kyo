#!/bin/sh

set -e

echo "gen kyo-core-optN"

echo "creating dirs"
mkdir -p ./kyo-core-opt1/src/
mkdir -p ./kyo-core-opt2/src/
mkdir -p ./kyo-core-opt3/src/

echo "copying"
cp -r ./kyo-core/src/ ./kyo-core-opt1/
cp -r ./kyo-core/src/ ./kyo-core-opt2/
cp -r ./kyo-core/src/ ./kyo-core-opt3/

echo "ls ."
find .

echo "opt3 inlining"
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(1\)\*\//inline/g' {} \;
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(2\)\*\//inline/g' {} \;
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;

echo "opt2 inlining"
find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(2\)\*\//inline/g' {} \;
find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;

echo "opt1 inlining"
find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;
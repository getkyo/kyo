#!/bin/bash

echo "gen kyo-core-optN"

echo "creating dirs"
mkdir -p ./kyo-core-opt1/src/
mkdir -p ./kyo-core-opt2/src/
mkdir -p ./kyo-core-opt3/src/

echo "copying"
cp -r ./kyo-core/src/ ./kyo-core-opt1/
cp -r ./kyo-core/src/ ./kyo-core-opt2/
cp -r ./kyo-core/src/ ./kyo-core-opt3/

echo "opt3 inlining"
if [[ ! -z "$GITHUB_BASE_REF" ]]
then
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(1\)\\\*\\//inline/g {} +
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(2\)\\\*\\//inline/g {} +
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +
else
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(1\)\*\//inline/g' {} \;
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(2\)\*\//inline/g' {} \;
    find ./kyo-core-opt3/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;
fi

echo "opt2 inlining"
if [[ ! -z "$GITHUB_BASE_REF" ]]
then
    find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(2\)\\\*\\//inline/g {} +
    find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +
else
    find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(2\)\*\//inline/g' {} \;
    find ./kyo-core-opt2/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;
fi

echo "opt1 inlining"
if [[ ! -z "$GITHUB_BASE_REF" ]]
then
    find ./kyo-core-opt1/src -type f -name '*.scala' -exec sed -i '' s/\\/\\\*inline\(3\)\\\*\\//inline/g {} +
else
    find ./kyo-core-opt1/src -type f -name '*.scala' -exec sed -i 's/\/\*inline\(3\)\*\//inline/g' {} \;
fi
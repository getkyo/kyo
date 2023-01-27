#!/usr/bin/env bash

set -e # Any subsequent(*) commands which fail will cause the shell script to exit immediately

SBT_CMD="sbt clean test checkUnformattedFiles"   
SBT_PUBLISH=" +publish"

./build/opt-gen.sh

# if [[ -z "$GITHUB_BASE_REF" ]]
if [[ true ]]
then
    SBT_CMD+=$SBT_PUBLISH
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/secring.gpg.enc -out local.secring.gpg -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/pubring.gpg.enc -out local.pubring.gpg -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/credentials.sbt.enc -out local.credentials.sbt -d
    openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in ./build/deploy_key.pem.enc -out local.deploy_key.pem -d

    if [[ $GITHUB_REF_NAME == "main" && $(cat version.sbt) != *"SNAPSHOT"* ]]
    then
        eval "$(ssh-agent -s)"
        chmod 600 local.deploy_key.pem
        ssh-add local.deploy_key.pem
        git config --global user.name "Quill CI"
        git config --global user.email "quillci@getquill.io"
        git remote set-url origin git@github.com:traneio/arrows.git
        git checkout master || git checkout -b master
        git reset --hard origin/master
        git push --delete origin website || true

        echo 'Performing a release'
        sbt 'release cross with-defaults'
    elif [[ $GITHUB_REF == "master" ]]
    then
        echo 'Master build'
        $SBT_CMD
    else
        echo 'Branch build'
        echo "version in ThisBuild := \"$GITHUB_REF-SNAPSHOT\"" > version.sbt
        $SBT_CMD
    fi
else
    echo 'PR build'
    $SBT_CMD
fi

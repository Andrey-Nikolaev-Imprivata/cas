#!/bin/bash

echo "Current Nodejs version: ${NODE_CURRENT}"
export NODE_REQUIRED=${NODE_CURRENT}

CI_CONFIG="$PWD/ci/tests/puppeteer/scenarios/${{matrix.scenario}}/ci.json"
echo "Checking scenario CI configuration file: ${CI_CONFIG}"

if [[ -f $CI_CONFIG ]]; then
  echo "Found $CI_CONFIG file"
  cat "$CI_CONFIG"
  export NODE_REQUIRED=$(cat "$CI_CONFIG" | jq -j '."nodejs.version"')
fi
echo "Final Nodejs version: ${NODE_REQUIRED}"
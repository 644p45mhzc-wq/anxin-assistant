#!/bin/sh
set -eu
cd "$(dirname "$0")/voice-chat-backend 3"
if [ -z "${DEEPSEEK_API_KEY:-}" ] && [ -s ../.env.deepseek-key ]; then
  DEEPSEEK_API_KEY=$(cat ../.env.deepseek-key)
  export DEEPSEEK_API_KEY
fi
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
echo "启动后，请在浏览器打开 http://localhost:${SERVER_PORT:-8080}"
exec sh mvnw spring-boot:run

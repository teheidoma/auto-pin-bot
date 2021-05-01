#!/usr/bin/env sh

version=$(gradle -q getVersion)

gradle bootBuildImage

docker push ghcr.io/teheidoma/auto-pin-bot:"$version"

docker tag ghcr.io/teheidoma/auto-pin-bot:"$version" ghcr.io/teheidoma/auto-pin-bot:latest

docker push ghcr.io/teheidoma/auto-pin-bot:latest

echo "done!"

#!/usr/bin/env sh

version=$(gradle -q getVersion)

gradle bootBuildImage

docker push docker.pkg.github.com/teheidoma/auto-pin-bot/auto-pin-bot:"$version"

docker tag docker.pkg.github.com/teheidoma/auto-pin-bot/auto-pin-bot:"$version" docker.pkg.github.com/teheidoma/auto-pin-bot/auto-pin-bot:latest

docker push docker.pkg.github.com/teheidoma/auto-pin-bot/auto-pin-bot:latest

echo "done!"

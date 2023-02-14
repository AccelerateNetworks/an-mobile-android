#!/bin/sh
tag="$(git describe --abbrev=0)"
commits="$(git rev-list ${tag}..HEAD --count)"
if [ "${commits}" = "0" ]; then
  echo "${tag}"
else
  echo "${tag}.${commits}+$(git rev-parse --short HEAD)"
fi
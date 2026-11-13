#!/usr/bin/env bash
set -euo pipefail
target=${1:?Usage: print-reference.sh NEW-DIRECTORY [PLA|PETG]}
material=${2:-PLA}
mkdir "$target"
root=$(cd "$(dirname "$0")/.." && pwd)
cli="$root/cli/build/install/cli/bin/cli"
file="$target/print-reference.vxb"
"$cli" document new "$file"
"$cli" designer sketch rectangle "$file" --id plate --width 60 --height 40
"$cli" designer solid extrude "$file" --id thickness --sketch plate --distance 8
"$cli" designer sketch circle "$file" --id hole --center-x 10 --center-y 10 --diameter 4
"$cli" designer solid cut "$file" --id cut --sketch hole --body-feature thickness
"$cli" slicer placement add "$file" --instance reference --body thickness.body --x 20 --y 30
"$cli" slicer process set "$file" --material "$material"
"$cli" slicer validate "$file"
"$cli" document export "$file" --format prusa-project --space BUILD_PLATE --output "$target/reference-project.3mf"
"$cli" slicer job slice "$file" --output "$target/reference.gcode"
"$cli" slicer job inspect "$target/reference.gcode"

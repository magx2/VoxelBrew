#!/usr/bin/env bash
set -euo pipefail
target=${1:?Usage: print-reference.sh NEW-DIRECTORY [PLA|PETG]}
material=${2:-PLA}
mkdir "$target"
root=$(cd "$(dirname "$0")/.." && pwd)
cli="$root/cli/build/install/cli/bin/cli"
file="$target/print-reference.jcad"
"$cli" new "$file"
"$cli" rectangle "$file" --id plate --width 60 --height 40
"$cli" extrude "$file" --id thickness --sketch plate --distance 8
"$cli" circle "$file" --id hole --center-x 10 --center-y 10 --diameter 4
"$cli" cut "$file" --id cut --sketch hole --body-feature thickness
"$cli" place "$file" --instance reference --body thickness.body --x 20 --y 30
"$cli" print-settings "$file" --material "$material"
"$cli" validate "$file"
"$cli" export "$file" --format prusa-project --space BUILD_PLATE --output "$target/reference-project.3mf"
"$cli" slice "$file" --output "$target/reference.gcode"
"$cli" inspect-job "$target/reference.gcode"

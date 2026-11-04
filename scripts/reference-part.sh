#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
destination="${1:-$repo_root/build/demo/reference.jcad}"
mkdir -p "$(dirname "$destination")"
cli="$repo_root/cli/build/install/cli/bin/cli"
if [[ ! -x "$cli" ]]; then echo 'Run ./gradlew :cli:installDist first' >&2; exit 1; fi
"$cli" new "$destination"
"$cli" rectangle "$destination" --id plate --width 60 --height 40
"$cli" extrude "$destination" --id thickness --sketch plate --distance 5
"$cli" circle "$destination" --id hole --center-x 10 --center-y 10 --diameter 4
"$cli" cut "$destination" --id cut --sketch hole --body-feature thickness
"$cli" inspect "$destination" --point 10,10,2.5
# Each invocation reopens the definitions in another process.
"$cli" edit "$destination" --feature thickness --field distance --value 8
"$cli" inspect "$destination" --point 10,10,4
"$cli" cursor "$destination" --position 3
"$cli" inspect "$destination"
"$cli" cursor "$destination" --position 4
"$cli" history "$destination"
echo "Saved editable reference part: $destination"

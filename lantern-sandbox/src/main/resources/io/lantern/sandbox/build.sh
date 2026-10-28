#!/bin/sh
# Runs inside a build container as its command. The arguments are passed to Maven unchanged.
# Afterwards it copies what may leave the sandbox into /out: for each module, its compiled classes,
# generated sources and the metadata Lantern asked Maven for (target/lantern), and every jar on a
# module's classpath, in the local repository's layout. The sandbox copies /out back to the host.
set -u
repository=/var/lantern/m2
out=/out
mkdir -p "$out/lib" "$out/work"

mvn --version
cd /work || exit 1
mvn "$@"
code=$?
echo "$code" > "$out/maven-exit-code"

find . -path '*/target/lantern/dependency-tree.txt' -type f | while IFS= read -r tree; do
  module=${tree%/target/lantern/dependency-tree.txt}
  mkdir -p "$out/work/$module/target"
  for part in classes generated-sources lantern; do
    if [ -d "$module/target/$part" ]; then
      cp -R "$module/target/$part" "$out/work/$module/target/"
    fi
  done
  classpath="$module/target/lantern/classpath.txt"
  if [ -f "$classpath" ]; then
    # The file has no final newline, so the last entry is read even when read fails.
    tr ':' '\n' < "$classpath" | while IFS= read -r entry || [ -n "$entry" ]; do
      case "$entry" in
        "$repository"/*)
          relative=${entry#"$repository"/}
          if [ -f "$entry" ] && [ ! -e "$out/lib/$relative" ]; then
            mkdir -p "$out/lib/$(dirname "$relative")"
            cp "$entry" "$out/lib/$relative"
          fi
          ;;
      esac
    done
  fi
done

# The effective POM is written before dependencies are resolved, so a build that fails to resolve
# them still has one, though no module has a dependency tree.
pom=target/lantern/effective-pom.xml
if [ -f "$pom" ] && [ ! -e "$out/work/$pom" ]; then
  mkdir -p "$out/work/target/lantern"
  cp "$pom" "$out/work/$pom"
fi

exit "$code"

#!/bin/bash -eu
#
# Builds the wagon, then compiles the fuzz targets against it and emits one
# launcher per target, which is the contract ClusterFuzzLite expects in $OUT.

cd "$SRC/s3-wagon"

# Everything that reports on the build rather than producing it is noise here.
mvn -B --no-transfer-progress package \
  -DskipTests -Dspotbugs.skip=true -Dcyclonedx.skip=true \
  -Djacoco.skip=true -Dmaven.javadoc.skip=true -Dmaven.source.skip=true

cp "$(ls target/s3-wagon-*.jar | grep -vE -- '-(sources|javadoc)\.jar$' | head -1)" "$OUT/s3-wagon.jar"

# Test scope, because the fuzz targets need wagon-provider-api, which the wagon
# itself only needs at compile time - Maven supplies it at runtime.
mvn -B --no-transfer-progress dependency:copy-dependencies \
  -DincludeScope=test -DoutputDirectory="$OUT/deps"

CLASSPATH_JARS="$OUT/s3-wagon.jar:$(echo "$OUT"/deps/*.jar | tr ' ' ':')"

mkdir -p "$OUT/fuzz-classes"
javac -cp "$CLASSPATH_JARS:$JAZZER_API_PATH" -d "$OUT/fuzz-classes" \
  src/fuzz/java/io/github/michalmela/*.java

for target in BaseDirectoryFuzzer ProxyEndpointFuzzer NonProxyHostsFuzzer; do
  cat > "$OUT/$target" <<LAUNCHER
#!/bin/bash
# LLVMFuzzerTestOneInput -- ClusterFuzzLite decides what in \$OUT is a fuzz target by
# looking for a name ending in _fuzzer or for this string in the file. Ours are named
# *Fuzzer, so without this line the build succeeds and then nothing is found to run.
this_dir=\$(dirname "\$0")
LD_LIBRARY_PATH="\$JVM_LD_LIBRARY_PATH":\$this_dir \\
\$this_dir/jazzer_driver \\
  --agent_path=\$this_dir/jazzer_agent_deploy.jar \\
  --cp=\$this_dir/s3-wagon.jar:\$(echo \$this_dir/deps/*.jar | tr ' ' ':'):\$this_dir/fuzz-classes \\
  --target_class=io.github.michalmela.$target \\
  --jvm_args="-Xmx2048m" \\
  "\$@"
LAUNCHER
  chmod +x "$OUT/$target"
done

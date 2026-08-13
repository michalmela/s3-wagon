# ClusterFuzzLite builds fuzz targets inside this image and runs them with Jazzer.
# The base image brings JDK 17 and the Jazzer driver; it does not bring Maven.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cc3c3da52c2f48d7a6caf1d5736ab8ec8cb213f4345329b33cb6e497780b1ed0

# Same Maven this project pins in mise.toml, so the fuzz build and the ordinary
# build are not quietly different.
ARG MAVEN_VERSION=3.9.16
RUN curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

COPY . $SRC/s3-wagon
COPY .clusterfuzzlite/build.sh $SRC/build.sh
WORKDIR $SRC/s3-wagon

# ClusterFuzzLite builds fuzz targets inside this image and runs them with Jazzer.
# The base image brings JDK 17 and the Jazzer driver; it does not bring Maven.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:cc3c3da52c2f48d7a6caf1d5736ab8ec8cb213f4345329b33cb6e497780b1ed0

# Same Maven this project pins in mise.toml, so the fuzz build and the ordinary
# build are not quietly different. The checksum is Apache's published SHA-512 for
# this exact tarball; extracting only after it matches means a compromised mirror
# cannot slip a different Maven into the build. Bumping MAVEN_VERSION means
# bumping MAVEN_SHA512 alongside it, from
# https://archive.apache.org/dist/maven/maven-3/<version>/binaries/apache-maven-<version>-bin.tar.gz.sha512
ARG MAVEN_VERSION=3.9.16
ARG MAVEN_SHA512=831a8591fe20c8243b1dbe7d71e3244f31d1665b0804b2e825e38cbbe5ce0cafb8338851f90780735568773e0a6cd07bbec107cda0b896b008b861075358b6f6
RUN curl -fsSL -o /tmp/maven.tar.gz \
      "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    && echo "${MAVEN_SHA512}  /tmp/maven.tar.gz" | sha512sum -c - \
    && tar -xz -C /opt -f /tmp/maven.tar.gz \
    && rm /tmp/maven.tar.gz \
    && ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn

COPY . $SRC/s3-wagon
COPY .clusterfuzzlite/build.sh $SRC/build.sh
WORKDIR $SRC/s3-wagon

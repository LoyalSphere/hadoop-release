#!/usr/bin/env bash
#
# Build hadoop native libraries.
#
# On Mac OS X, use Homebrew to install dependencies.
#
#    brew install \
#        cmake \
#        protobuf@2.5 \
#        maven \
#        openssl \
#        snappy
#    export OPENSSL_ROOT_DIR=`brew --prefix openssl`
#    export HADOOP_PROTOC_PATH="$(brew --prefix protobuf@2.5)/bin/protoc"
# 
# On Linux, it's probably easier to copy from HDP distribution. If you really
# want to, the following instructions work for Fedora 28.
#
#    sudo dnf install -y \
#        autoconf \
#        automake \
#        cmake \
#        compat-openssl10-devel \
#        gcc-c++ \
#        libtool \
#        libtirpc-devel \
#        maven \
#        snappy-devel
#
#    # Hadoop 2.7.1 build is not compatible with latest protobuf compiler
#    # so we build protobuf 2.5.0 from source.
#    export HADOOP_PROTOC_PATH="$HOME/.local/bin/protoc"
#    if [ ! -f "$HADOOP_PROTOC_PATH" ]; then
#        cd "$HOME/.local/var"
#        curl -LO https://github.com/google/protobuf/releases/download/v2.5.0/protobuf-2.5.0.tar.gz
#        tar xf protobuf-2.5.0.tar.gz
#        cd protobuf-2.5.0
#        ./autogen.sh
#        ./configure --prefix="$HOME/.local"
#        make
#        make install
#    fi
#
#    # patch hadoop build to use libtirpc
#    # - fatal error: rpc/types.h: No such file or directory
#    cd "$work_dir"
#    sed -ri 's#^(include_directories.*)#\1\n    /usr/include/tirpc#' hadoop-tools/hadoop-pipes/src/CMakeLists.txt
#    sed -ri 's/^( *pthread)/\1\n    tirpc/g' hadoop-tools/hadoop-pipes/src/CMakeLists.txt
#
# Once prerequisites are in place, run this script to produce native libs.

# Function to probe the exit code of the script commands, 
# and stop in the case of failure with an contextual error 
# message.
run() {
  echo "\$ ${@}"
  "${@}"
  exitCode=$?
  if [[ $exitCode != 0 ]]; then
    echo
    echo "Failed! running ${@} in `pwd`"
    echo
    exit $exitCode
  fi
}

# Extract Hadoop version from POM
HADOOP_VERSION=`cat pom.xml | grep "<version>" | head -1 | sed 's|^ *<version>||' | sed 's|</version>.*$||'`

# Setup git
GIT=${GIT:-git}

echo
echo "*****************************************************************"
echo
echo "Hadoop version to create native libraries: ${HADOOP_VERSION}"
echo
echo "*****************************************************************"
echo

# Get Maven command
if [ -z "$MAVEN_HOME" ]; then
  MVN=mvn
else
  MVN=$MAVEN_HOME/bin/mvn
fi

# git clean to clear any remnants from previous build
run ${GIT} clean -xdf

# mvn clean for sanity
run ${MVN} clean

OS_NAME=$(java -XshowSettings:properties -version 2>&1 \
    | grep "os.name" \
    | cut -d = -f 2 \
    | xargs \
    | sed -e "s/ /_/g"
)

if [ "$OS_NAME" == "Mac_OS_X" ]; then
    echo "patching for macOS"
    patch -p1 < dev-support/YARN-8622.patch
fi

# Create SRC and BIN tarballs for release,
# Using 'install’ goal instead of 'package' so artifacts are available 
# in the Maven local cache for the site generation
run ${MVN} package -Drequire.snappy -Pdist,native -DskipTests -Dtar -Dmaven.javadoc.skip

if [ "$OS_NAME" == "Mac_OS_X" ]; then
    echo "patching for macOS"
    patch -R -p1 < dev-support/YARN-8622.patch
fi

OS_ARCH=$(java -XshowSettings:properties -version 2>&1 \
    | grep "os.arch" \
    | cut -d = -f 2 \
    | xargs \
    | sed -e "s/ /_/g"
)

PACKAGE_NAME="libhadoop-${HADOOP_VERSION}-${OS_NAME}-${OS_ARCH}.tar.gz"
NATIVE_LIB_DIR="hadoop-dist/target/hadoop-${HADOOP_VERSION}/lib/native"

run pushd "$NATIVE_LIB_DIR"
run tar -czf "$PACKAGE_NAME" ./*
run popd

run mv "$NATIVE_LIB_DIR/$PACKAGE_NAME" hadoop-dist/target
echo
echo "Congratulations, you have successfully built the native"
echo "libraries for Apache Hadoop ${HADOOP_VERSION}"
echo
echo "The package for this run are available as ${PACKAGE_NAME}:"
run ls -1 "hadoop-dist/target/${PACKAGE_NAME}"
echo 
echo "Enjoy."
echo
